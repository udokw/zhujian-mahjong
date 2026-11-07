# 技术选型评估：KMP + Compose Multiplatform 是否适合双规则麻将

> 约束前提：
> - 目标平台 Android + iOS；
> - 当前主力开发环境是 **Windows**；
> - 需要同时支持国标（MCR）与广东麻将两套规则；
> - 需要单机 AI、联机对战、回放、战绩。
>
> 结论日期：2026-11-07

---

## 0. 结论摘要

| 问题 | 结论 |
|---|---|
| KMP 共用规则引擎/AI/协议？ | **强烈推荐**。这是收益最大、风险最低的部分。 |
| Compose Multiplatform 共用 UI？ | **可行，推荐**；但 iOS 端必须在 Mac 上做一次真实设备验证。 |
| 在 Windows 上能开发 iOS 吗？ | **不能编译、不能跑模拟器、不能真机调试**。这是 Apple 的限制，换任何跨平台框架都一样。 |
| 有没有「Windows 就能出 iOS 包」的方案？ | **没有**。Flutter、Unity、React Native 同样需要 macOS/Xcode 才能出 iOS 包。 |
| 最终建议 | **KMP 共享核心 + Compose Multiplatform UI，Android 先行；iOS 用 Mac/CI 验证。** 若 iOS 必须与 Android 同步首发，改为 **KMP 核心 + SwiftUI 壳**，风险更低。 |
| 是否推荐 Unity？ | **不推荐**。2D 麻将用 Unity 过重，规则引擎无法与 KMP 共享，包体和迭代成本更高。 |

一句话：**KMP + CMP 适合这个项目；真正的风险不是 KMP，而是「没有 Mac 就无法验证 iOS」。先解决 Mac/CI，再谈框架。**

---

## 1. 项目对技术栈的真实要求

| 要求 | 权重 | 说明 |
|---|---|---|
| 规则引擎可测试 | 极高 | 国标 81 番、五条计番原则、广东买马/包牌，必须有大量单测 |
| 双端逻辑一致 | 极高 | 同一手牌在 Android/iOS 必须算出同样番数 |
| 服务端可复算 | 极高 | 反作弊要求服务端跑同一套规则 |
| 2D 牌桌渲染 | 高 | 136/144 张牌、四人手牌、牌河、副露、动画 |
| 60 fps | 高 | 摸打、碰杠、结算动画 |
| 触摸/手势 | 高 | 点牌、拖牌、滑动、长按 |
| 音频/语音 | 中 | 麻将音效、方言语音、背景音 |
| 推送/登录/支付 | 中 | Apple/Google 登录、内购（若做商业化） |
| 包体 | 中 | 麻将素材多，但可控 |
| 热更新 | 低 | 棋牌类通常不需要 |
| 3D/重度特效 | 低 | 除非要做 3D 牌桌 |

结论：**这是「重逻辑、轻 3D」的 2D 游戏**，最适合「共享核心 + 跨平台 UI」，而不是重度游戏引擎。

---

## 2. 候选方案对比

评分：5 = 最优，1 = 最差。权重按上表。

| 维度 | KMP + CMP | KMP 核心 + 原生 UI | Flutter | Unity | React Native | 双原生 |
|---|---|---|---|---|---|---|
| 规则引擎复用 | 5 | 5 | 3 | 2 | 2 | 1 |
| 双端逻辑一致 | 5 | 5 | 4 | 4 | 3 | 1 |
| 服务端复用（JVM） | 5 | 5 | 2 | 1 | 1 | 1 |
| 2D 渲染/动画 | 4 | 5 | 4 | 5 | 3 | 5 |
| iOS 原生体验 | 3 | 5 | 3 | 3 | 3 | 5 |
| Android 体验 | 5 | 5 | 4 | 3 | 4 | 5 |
| 开发速度（单团队） | 5 | 3 | 5 | 3 | 4 | 1 |
| 学习成本 | 4（Kotlin 背景） | 3 | 3 | 3 | 3 | 2 |
| 包体 | 4 | 4 | 4 | 2 | 4 | 5 |
| 生态/招聘 | 4 | 4 | 5 | 5 | 4 | 5 |
| iOS 盲开发风险 | 中 | 中 | 中 | 中 | 中 | 中 |
| **综合** | **4.4** | **4.1** | **3.8** | **3.1** | **3.1** | **2.9** |

### 2.1 结论

- **KMP + CMP 综合最高**，尤其适合「一套规则引擎 + 一套 UI + JVM 服务端复用」。
- **KMP 核心 + 原生 UI** 是保守方案：iOS 体验最好，但 UI 要写两套。
- **Flutter** 是合理备选：如果团队更熟 Dart，UI 一致性和动画生态很好；但规则引擎不能与 JVM 服务端共享。
- **Unity** 只在需要 3D 牌桌/重度特效时考虑；对 2D 麻将是过度设计。
- **React Native** 不适合高频动画和复杂手势。
- **双原生** 只在团队规模足够、iOS 体验优先时考虑。

---

## 3. KMP + CMP 详细评估

### 3.1 适合共享的部分

| 模块 | 共享 | 说明 |
|---|---|---|
| 规则引擎 | ✅ | 纯 Kotlin，无平台依赖，JVM 单测覆盖 |
| AI（L1–L3） | ✅ | 纯计算，端上运行 |
| 网络协议 | ✅ | Ktor Client + kotlinx.serialization |
| 对局状态机 | ✅ | 客户端预测、服务端权威共用 |
| 数据模型 | ✅ | Tile/Hand/Meld/Action/GameState |
| 本地存储 | ✅（接口） | expect/actual：DataStore/SQLDelight |
| 日志/埋点 | ✅（接口） | 平台实现 |
| 回放播放 | ✅ | 基于动作流 |
| 设置/规则配置 | ✅ | 同一份 JSON |
| 服务端 | ✅ | Ktor Server 直接复用规则引擎 |

### 3.2 不应共享的部分

| 模块 | 原因 |
|---|---|
| 音频会话 | iOS AVAudioSession 与 Android AudioManager 差异大 |
| 触觉反馈 | Haptic API 不同 |
| 支付/内购 | StoreKit / Google Play Billing |
| 推送 | APNs / FCM |
| 登录 | Sign in with Apple / Google Sign-In |
| 文件选择/分享 | 平台 UI |
| 生命周期/后台 | 平台差异 |
| 无障碍 | VoiceOver / TalkBack |
| 字体渲染 | 中文排版、字重差异 |
| 安全区/刘海 | 平台 UI |
| 应用评分/更新 | 平台商店 |

> 原则：**能用纯 Kotlin 表达的业务逻辑全部共享；一旦碰到系统能力，用 `expect/actual` 薄封装，不在 shared 里直接调平台 API。**

### 3.3 iOS 端的现实约束（重点）

| 约束 | 事实 | 影响 |
|---|---|---|
| 编译 | Kotlin/Native iOS 目标需要 macOS + Xcode | Windows 无法编译 iOS framework |
| 模拟器 | iOS Simulator 只能跑在 macOS | Windows 无法预览 iOS UI |
| 真机 | 需要 Mac + Xcode + 签名 | Windows 无法真机调试 |
| 签名 | 需要 Apple Developer 账号 | 上架/TestFlight 必需 |
| 调试 | Xcode/LLDB | Windows 只能盲写 |
| CI | 需要 macOS runner | GitHub Actions/Codemagic/Xcode Cloud |
| 版本 | Kotlin/Native 与 Xcode 版本需匹配 | 升级需回归 |

**这不是 KMP 的缺点，是所有跨平台方案的共同约束。** Flutter/Unity/RN 在 Windows 上同样不能出 iOS 包。

### 3.4 CMP iOS 的成熟度

- Compose Multiplatform 的 iOS 支持已于 2025 年 5 月进入 **Stable**；Android/iOS/Desktop 均为 Stable。
- 但「框架 Stable」不等于「你的场景零风险」。麻将需要重点验证：
  1. 大量图片/图集渲染（136 张牌 + 牌河 + 动画）；
  2. 中文文本渲染与字体；
  3. 手势（点、拖、长按、滑动）；
  4. 低端机 60 fps；
  5. 音频延迟；
  6. 无障碍；
  7. 输入法（聊天/昵称）；
  8. 内存/包体。
- 建议做一个 **2 周 iOS Spike**：4 人牌桌 + 摸打动画 + 音效 + 结算页，在真机跑一遍，再决定 UI 是否全量 CMP。

### 3.5 麻将 UI 的渲染建议

- 牌面用 **矢量/图集**，不要为每张牌单独 PNG。
- 牌桌用 `Canvas` 绘制，手牌用可复用组件；避免每帧创建对象。
- 动画用 `animateFloatAsState`/`Animatable`，不要手写每帧循环。
- 牌河用固定布局 + 增量更新，避免整树重组。
- 结算页独立路由，避免和牌桌抢资源。
- 低端 Android 提供「简化动画」开关。

---

## 4. 推荐架构

### 4.1 工程结构

```text
mahjong/
├── shared/
│   ├── core-model/        # Tile, Hand, Meld, Action, GameState
│   ├── core-rules/        # MCR / Guangdong RuleSet, Scorer
│   ├── core-ai/           # L1-L4 AI
│   ├── core-net/          # 协议、Ktor client、重连
│   └── core-storage/      # expect/actual 存储接口
├── composeApp/            # CMP UI（Android + iOS）
│   ├── commonMain/
│   ├── androidMain/
│   └── iosMain/
├── iosApp/                # Xcode 壳工程（SwiftUI 入口）
├── androidApp/            # 可选，若不用 CMP 的 Android 入口
└── server/                # Ktor Server，复用 core-rules
```

### 4.2 依赖方向

```text
core-model ← core-rules ← core-ai
     ↑           ↑
     └── core-net ┘
              ↑
        composeApp / server
```

- `core-rules` 不依赖 UI、网络、平台。
- `core-ai` 只依赖 `core-rules`。
- `server` 依赖 `core-rules`，保证客户端/服务端同一套判定。
- `composeApp` 依赖所有 core，不反向依赖。

### 4.3 构建与验证

| 环境 | 能做什么 | 不能做什么 |
|---|---|---|
| Windows | Android 构建、JVM 单测、共享代码编译（非 iOS target） | iOS 编译/模拟器/真机 |
| macOS | iOS framework、Xcode 构建、模拟器、真机 | — |
| CI（macOS runner） | iOS 编译、单测、TestFlight 上传 | 真机交互测试 |

推荐 CI：

| 方案 | 适用 |
|---|---|
| GitHub Actions `macos-latest` | 开源/小团队，PR 编译检查 |
| Codemagic | 移动端专用，签名/发布方便 |
| Xcode Cloud | 苹果生态，TestFlight 集成好 |
| Bitrise | 成熟移动 CI |

建议流水线：

```text
PR:
  - Windows runner: shared JVM tests + Android assembleDebug
  - macOS runner: iOS simulator build + shared tests

Tag:
  - macOS runner: archive + sign + TestFlight
  - Android: bundleRelease + Play Console
```

### 4.4 iOS 开发的最低配置

| 项 | 建议 |
|---|---|
| 机器 | Mac mini M4（16GB+）或 MacBook Air M3 |
| Xcode | 最新稳定版，固定版本避免漂移 |
| 账号 | Apple Developer Program（$99/年） |
| 设备 | 至少一台真机（iPhone） |
| 工具 | KDoctor、XcodeGen（可选） |

> 如果没有 Mac，最现实的替代是 **云 Mac**（MacStadium、AWS EC2 Mac、Codemagic 的 Mac 构建）。但 UI 调试仍需远程桌面，体验较差。

---

## 5. 备选方案：什么情况下不选 KMP + CMP

| 情况 | 建议 |
|---|---|
| 团队只有 Web/TS 背景 | Flutter 或 React Native（但麻将动画用 Flutter 更合适） |
| 要做 3D 牌桌/重度特效 | Unity |
| iOS 体验必须极致、团队有 iOS 工程师 | KMP 核心 + SwiftUI UI |
| 只做 Android，iOS 以后再说 | 原生 Android + 纯 Kotlin 核心，保留 KMP 迁移空间 |
| 需要一套代码同时出 Web 版 | Flutter 或 KMP + Compose Web（Wasm 仍 Beta） |

### 5.1 为什么不用 Unity

- 2D 麻将不需要物理/3D/场景图。
- C# 规则引擎无法与 Kotlin/JVM 服务端共享，双端一致性和反作弊成本高。
- 包体大、启动慢、UI 迭代重。
- 只有在「3D 牌桌 + 强演出」是核心卖点时，Unity 才值得。

### 5.2 为什么 Flutter 不是首选

- 规则引擎要用 Dart 重写，服务端（Kotlin/Go）无法复用；或者规则引擎放服务端，客户端只做展示，但离线单机又需要本地引擎。
- 如果团队已是 Dart/Flutter 栈，Flutter 完全可行；否则 KMP 的「Kotlin 全栈」收益更大。

---

## 6. 风险清单与缓解

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| Windows 无法验证 iOS | 高 | 高 | 买 Mac mini + macOS CI；iOS 先行 Spike |
| CMP iOS 渲染/手势问题 | 中 | 中 | 2 周 Spike；关键页面保留原生降级方案 |
| Kotlin/Native 包体偏大 | 中 | 低 | Release 构建、strip、按需链接 |
| 低端 Android 掉帧 | 中 | 中 | 牌桌 Canvas、对象池、简化动画 |
| 规则引擎性能（国标拆分枚举） | 中 | 高 | 剪枝、缓存、位运算；基准测试 |
| 服务端/客户端规则不一致 | 中 | 高 | 共享 Kotlin 核心，或 JSON 测试向量双向校验 |
| 团队不熟 Kotlin | 中 | 中 | 先做 Android；CMP 学习曲线可控 |
| App Store 审核（棋牌类） | 中 | 高 | 避免真钱赌博；实名/防沉迷；隐私合规 |
| 版号/合规（中国大陆） | 高 | 高 | 提前确认发行资质；无版号先做海外/TestFlight |

> 合规提醒：如果涉及真钱兑换、抽奖/开箱，审核和法规风险显著上升。建议首版纯休闲、无真钱、无兑换。

---

## 7. 最终建议

### 7.1 推荐路线（按优先级）

1. **先做纯 Kotlin 规则引擎 + JVM 单测**（不依赖 UI/网络）。这是最高优先级，也是所有方案都需要的。
2. **Android 先行**：Compose（CMP 的 Android 部分）+ 本地单机 + L2 AI。
3. **并行做 iOS Spike**：在 Mac 上用 CMP 跑通「4 人牌桌 + 摸打 + 结算」，验证渲染/手势/音频。
4. **Spike 通过 → CMP 全量共用 UI**；不通过 → 保留 KMP 核心，iOS 用 SwiftUI 重写 UI。
5. **服务端用 Ktor**，直接复用规则引擎；若用 Go，则用同一套 JSON 测试向量保证一致。
6. **CI 双跑**：Windows 跑 Android/共享测试，macOS 跑 iOS 编译/模拟器。

### 7.2 一句话回答

**KMP + Compose Multiplatform 适合这个项目，推荐「共享核心 + 共享 UI」；但 iOS 不能在 Windows 上验证，必须准备 Mac + macOS CI。若 iOS 必须与 Android 同步首发且团队有 iOS 能力，改用「KMP 核心 + SwiftUI UI」更稳。**

---

## 8. 待决策

1. **是否有 Mac / 云 Mac 预算？** 没有的话，iOS 上线时间无法承诺。
2. **iOS 是否与 Android 同步首发？** 同步 → 建议 SwiftUI 壳；可延后 → CMP 全量。
3. **服务端语言？** Kotlin/Ktor（复用规则）vs Go（性能/运维熟悉度）。
4. **是否做 3D 牌桌？** 不做 → KMP/CMP；做 → 重新评估 Unity。
5. **是否中国大陆发行？** 涉及版号/实名/防沉迷，需提前规划。

---

## 9. 参考

- Kotlin Multiplatform 官方文档：支持平台与稳定性
- Compose Multiplatform 官方文档：Android/iOS/Desktop 均为 Stable（iOS 自 2025-05 起）
- Kotlin/Native 官方文档：iOS 目标需要 macOS + Xcode
- JetBrains KMP FAQ：非 macOS 系统无法运行 iOS 模拟器/真机
