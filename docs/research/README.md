# 麻将项目前期调研索引

> 目录：`g:\AI\麻将\docs\research`
> 状态：仅调研文档，不含代码工程。
> 更新：2026-11-07

| 文档 | 内容 | 关键结论 |
|---|---|---|
| [01 清弈联机方案](computer://g:\AI\麻将\docs\research\01-qingyi-online-solution.md) | 清弈 Android/iOS 联机实现、协议、房间、重连、鉴权、战绩；麻将联机方案对比 | 清弈**没有服务端**，是局域网双人 Host 权威 TCP 同步；协议思想可复用，部署不可复用；推荐**自建权威服务端 + WebSocket** |
| [02 规则与默认规则集](computer://g:\AI\麻将\docs\research\02-mahjong-rules-mcr-guangdong.md) | 国标 81 番种、五条计分原则、广东推倒胡/鸡平胡/花牌/买马/包牌；30 条高发边界 | 默认推荐**广东推倒胡**（休闲）+ **国标标准场**（排位）+ **鸡平胡**（好友房）；花猪属四川、天听/地听属台湾/大众，默认关闭 |
| [03 竞品与 AI](computer://g:\AI\麻将\docs\research\03-competitor-features-and-ai.md) | 欢乐麻将/麻将来了/雀魂/天凤功能拆解、MVP 优先级、新手引导、四级 AI | AI 分级：L1 合法+随机 → L2 向听/牌效 → L3 打点/防守/场况 → L4 搜索/建模/学习；托管固定用 L2 |
| [04 技术选型](computer://g:\AI\麻将\docs\research\04-tech-stack-kmp-cmp.md) | KMP + Compose Multiplatform、Flutter、Unity、双原生对比；iOS 约束与 CI | **推荐 KMP 共享核心 + CMP 共享 UI**；iOS 无法在 Windows 编译/验证，必须 Mac + macOS CI；iOS 同步首发则考虑 KMP 核心 + SwiftUI |

---

## 三个最重要的结论

### 1. 联机不要照搬清弈

清弈是「手机当 Host + 局域网 TCP + 双人」的方案，没有账号、匹配、持久化、托管、观战、反作弊。麻将需要：

- 服务端权威（发牌、牌墙、动作校验、计分复算）
- 私有信息裁剪（每个玩家只能看到自己的手牌）
- 4 人房间 + 响应窗口 + 优先级
- 断线重连 + 30 秒后 AI 托管
- 事件日志回放（种子 + 动作流）

推荐：**WebSocket + 自建权威服务端（Ktor 或 Go）+ Redis 房间状态 + PostgreSQL 战绩/牌谱**。

### 2. 规则先做「两套预设」，不要做「万能配置」

| 预设 | 用途 | 关键参数 |
|---|---|---|
| `mcr_1998` | 排位/竞技 | 144 张、8 分起和、花不计起和、不连庄、一炮单响 |
| `gd_tuidaohu` | 默认休闲 | 136 张、不吃、只自摸、3 番封顶、连庄、买马 |
| `gd_jipinghu` | 好友房 | 144 张、可吃、可点炮、3 番封顶、一炮多响、包牌 |

最容易写错的 5 个边界：**番种不重复/就高不就低、抢杠只抢加杠、杠上开花不含补花、国标一炮单响、广东荒庄连庄**。

### 3. 技术栈选 KMP，但先解决 Mac

- 规则引擎、AI、协议、状态机用纯 Kotlin 共享，收益最大、风险最低。
- UI 用 Compose Multiplatform 可行（iOS 已 Stable），但必须在 Mac 真机做一次 Spike。
- **Windows 无法编译/运行 iOS，这是 Apple 限制，换 Flutter/Unity 也一样。**
- 没有 Mac 就没有 iOS 交付；建议 Mac mini + macOS CI（GitHub Actions/Codemagic/Xcode Cloud）。

---

## 建议的下一步

1. 确认三套规则预设的默认值与命名（尤其广东默认用推倒胡还是鸡平胡）。
2. 确认联机首版范围：先好友房，还是直接匹配。
3. 确认 iOS 是否与 Android 同步首发，决定 CMP UI 还是 SwiftUI 壳。
4. 规则引擎先做「纯 Kotlin + JVM 单测」，再谈 UI/网络。
