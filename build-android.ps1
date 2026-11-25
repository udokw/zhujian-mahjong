# 安卓构建入口（Windows）
#
# 背景：项目路径或 Windows 用户目录含中文时，JDK 17 启动器按系统 GBK 解码
# @argfile，会把 worker classpath 弄成乱码，导致 :core:jvmTest 之类的任务失败。
# 这里自动做两件事，不需要手工准备，也不依赖任何固定用户名：
#   1. 把项目映射到一个纯 ASCII 的 junction 路径上（运行时不失效就复用，失效则重建），
#      再通过该路径调用 gradlew；
#   2. GRADLE_USER_HOME 默认取当前用户 ~/.gradle（已设置同名环境变量时优先沿用），
#      启动时校验并打印最终使用的路径。
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
    # 映射位置不写死用户名：优先 $env:PUBLIC，其次 $env:LOCALAPPDATA。
    $candidates = @(
        (Join-Path $env:PUBLIC 'mahjong-app'),
        (Join-Path $env:LOCALAPPDATA 'mahjong-app')
    ) | Where-Object { $_ }

    $asciiRoot = $null
    foreach ($linkPath in $candidates) {
        $existing = Get-Item -LiteralPath $linkPath -Force -ErrorAction SilentlyContinue
        if ($existing -and ($existing.LinkType -eq 'Junction' -or $existing.LinkType -eq 'SymbolicLink')) {
            $target = $existing.Target
            if ($target -is [array]) { $target = $target[0] }
            $resolved = $null
            if ($target) {
                try { $resolved = (Resolve-Path -LiteralPath $target -ErrorAction Stop).Path } catch { }
            }
            if ($resolved -and $resolved -eq $projectRoot) {
                Write-Host "[build] 复用既有 ASCII 映射: $linkPath -> $projectRoot"
                $asciiRoot = $linkPath
                break
            }
            # 映射失效（目标已不存在）或指向别处：只删链接本身，不动目标目录内容。
            Write-Host "[build] ASCII 映射失效或指向别处，重建: $linkPath"
            try { [System.IO.Directory]::Delete($linkPath, $false) } catch { }
        } elseif ($existing) {
            # 普通目录：不覆盖用户数据，换下一个候选路径。
            Write-Host "[build] 路径已被普通目录占用，跳过: $linkPath"
            continue
        }

        try {
            New-Item -ItemType Junction -Path $linkPath -Target $projectRoot -ErrorAction Stop | Out-Null
            Write-Host "[build] 创建 ASCII 映射: $linkPath -> $projectRoot"
            $asciiRoot = $linkPath
            break
        } catch {
            Write-Host "[build] 无法在此创建映射，换下一个候选: $linkPath ($($_.Exception.Message))"
        }
    }
    if (-not $asciiRoot) {
        throw '无法创建可用的 ASCII 路径映射，请检查 $env:PUBLIC / $env:LOCALAPPDATA 的写权限。'
    }
}

# ---------------------------------------------------------------- 环境变量

# 不写死用户目录：显式设置的 GRADLE_USER_HOME 可用则沿用；
# 未设置或目录不存在时，回退到当前用户 ~/.gradle。
$userGradleHome = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle'
$gradleHome = $env:GRADLE_USER_HOME
if (-not $gradleHome -or -not (Test-Path -LiteralPath $gradleHome -PathType Container)) {
    if ($gradleHome) {
        Write-Host "[build] GRADLE_USER_HOME 不可用，回退到当前用户目录: $gradleHome"
    }
    $gradleHome = $userGradleHome
}
if (-not (Test-Path -LiteralPath $gradleHome -PathType Container)) {
    New-Item -ItemType Directory -Path $gradleHome -Force | Out-Null
    Write-Host "[build] 已创建 GRADLE_USER_HOME: $gradleHome"
}
$gradleHome = (Resolve-Path -LiteralPath $gradleHome).Path
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
