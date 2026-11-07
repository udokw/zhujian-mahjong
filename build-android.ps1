# 安卓构建入口（Windows）
#
# 背景：本机用户目录含中文（狂客），JDK 17 启动器按系统 GBK 解码 @argfile，
# 会把 worker classpath 弄成乱码，导致 :core:jvmTest 之类的任务失败。
# 这里自动做两件事，不需要手工准备：
#   1. 把项目映射到一个纯 ASCII 的 junction 路径上，再通过该路径调用 gradlew；
#   2. 把 GRADLE_USER_HOME 指到 ASCII 目录，避免缓存路径里出现中文。
#
# 用法：
#   .\build-android.ps1                 # 打 debug APK
#   .\build-android.ps1 test            # 跑 :core:jvmTest
#   .\build-android.ps1 clean           # 清理
#   .\build-android.ps1 -- :app:assembleRelease   # 透传任意 Gradle 任务
#
# 如果用 .bat 包装（build-android.bat），参数同样透传。

param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs
)

$ErrorActionPreference = 'Stop'

# 控制台按 UTF-8 输出，避免中文提示在 GBK 终端里显示成乱码
try {
    [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $OutputEncoding = [System.Text.UTF8Encoding]::new($false)
} catch { }

$projectRoot = $PSScriptRoot
if (-not $projectRoot) { $projectRoot = (Get-Location).Path }
$projectRoot = (Resolve-Path -LiteralPath $projectRoot).Path

# ---------------------------------------------------------------- ASCII 路径

function Test-Ascii([string] $text) {
    return ($text -match '^[\x20-\x7E]*$')
}

$asciiRoot = $projectRoot
if (-not (Test-Ascii $projectRoot)) {
    $linkPath = 'C:\Users\Public\mahjong-app'
    $existing = Get-Item -LiteralPath $linkPath -ErrorAction SilentlyContinue
    if ($existing) {
        $target = $null
        if ($existing.LinkType -eq 'Junction' -or $existing.LinkType -eq 'SymbolicLink') {
            $target = $existing.Target
            if ($target -is [array]) { $target = $target[0] }
        }
        if ($target -and ((Resolve-Path -LiteralPath $target).Path -eq $projectRoot)) {
            Write-Host "[build] 复用既有 ASCII 映射: $linkPath -> $projectRoot"
        } elseif (-not $target -and $existing.PSIsContainer) {
            # 已存在普通目录且不是映射，不能覆盖
            throw "$linkPath 已被占用（不是指向本项目的映射），请先删除后再运行。"
        } else {
            Remove-Item -LiteralPath $linkPath -Force -Recurse -ErrorAction SilentlyContinue
            New-Item -ItemType Junction -Path $linkPath -Target $projectRoot | Out-Null
            Write-Host "[build] 重建 ASCII 映射: $linkPath -> $projectRoot"
        }
    } else {
        New-Item -ItemType Junction -Path $linkPath -Target $projectRoot | Out-Null
        Write-Host "[build] 创建 ASCII 映射: $linkPath -> $projectRoot"
    }
    $asciiRoot = $linkPath
}

# ---------------------------------------------------------------- 环境变量

$gradleHome = 'C:\Users\Public\gradlehome'
if (-not (Test-Path -LiteralPath $gradleHome)) {
    New-Item -ItemType Directory -Path $gradleHome -Force | Out-Null
}
$env:GRADLE_USER_HOME = $gradleHome

if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\java.exe'))) {
    $candidates = @(
        'C:\Program Files\Java\jdk-17.0.2',
        'C:\Program Files\Java\jdk-17',
        'C:\Program Files\Eclipse Adoptium\jdk-17*'
    )
    foreach ($candidate in $candidates) {
        $found = Get-Item -LiteralPath $candidate -ErrorAction SilentlyContinue
        if ($found -and (Test-Path -LiteralPath (Join-Path $found.FullName 'bin\java.exe'))) {
            $env:JAVA_HOME = $found.FullName
            break
        }
    }
}
if ($env:JAVA_HOME) { Write-Host "[build] JAVA_HOME=$env:JAVA_HOME" }
Write-Host "[build] GRADLE_USER_HOME=$env:GRADLE_USER_HOME"
Write-Host "[build] 工作路径=$asciiRoot"

# ---------------------------------------------------------------- 组装任务

$mapped = @()
foreach ($arg in $GradleArgs) {
    switch ($arg) {
        'test'   { $mapped += ':core:jvmTest' }
        'app'    { $mapped += ':app:assembleDebug' }
        default  { $mapped += $arg }
    }
}
if ($mapped.Count -eq 0 -or $mapped[0] -eq '') { $mapped = @(':app:assembleDebug') }
if ($mapped -contains '--') { $mapped = $mapped | Where-Object { $_ -ne '--' } }

$gradlew = Join-Path $asciiRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlew)) { throw "找不到 $gradlew" }

Write-Host "[build] gradlew $($mapped -join ' ')"

# 用 -p 显式指定工程目录：Windows 会把子进程的工作目录解析成 junction 的真实路径（中文），
# 只切目录不够，必须显式告诉 Gradle 用 ASCII 路径，否则 worker 参数里又出现中文。
Push-Location -LiteralPath $asciiRoot
try {
    & $gradlew -p $asciiRoot @mapped --console=plain --no-daemon
    $code = $LASTEXITCODE
} finally {
    Pop-Location
}

# ---------------------------------------------------------------- 结果

$apk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
if ($code -eq 0 -and (Test-Path -LiteralPath $apk)) {
    $info = Get-Item -LiteralPath $apk
    $mb = [math]::Round($info.Length / 1MB, 2)
    Write-Host ''
    Write-Host "[build] 构建成功"
    Write-Host "[build] APK: $($info.FullName)"
    Write-Host "[build] 大小: $mb MB    生成时间: $($info.LastWriteTime)"
    Write-Host "[build] 安装: adb install -r `"$($info.FullName)`""
}

exit $code
