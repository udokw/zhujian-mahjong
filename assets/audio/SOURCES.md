# 真实录音素材来源与授权

本目录下 `tile_discard_1..5.wav`、`shuffle.wav`、`tile_draw.wav`、`tile_sort.wav`、`deal.wav`
由 `tools/generate_real_sfx.py` 从下面两段公开发布的真实麻将录音切段、整形后生成。

两段录音均来自 [Freesound](https://freesound.org)，可免费用于商业项目，使用条件如下。
原始 MP3 存放在 `sources/`，仅供重新生成音效时使用，不会打进 App 包。

---

## 1. Freesound #156668 — "Mahjong.wav"

- 作者：RTB45
- 页面：<https://freesound.org/people/RTB45/sounds/156668/>
- 授权：**Creative Commons Attribution 4.0 International (CC BY 4.0)**
  授权全文：<https://creativecommons.org/licenses/by/4.0/>
- 原始文件：`sources/mahjong_rtb45.mp3`（约 4 分 13 秒，44.1 kHz）
- 内容：真实牌局现场录音，包含大量单张牌落桌的孤立瞬态，背景有轻微房间噪声与人声。

### 本项目使用的片段

| 用途 | 原录音时间点 | 说明 |
|---|---:|---|
| `tile_discard_1` | 133.566 s | 厚实、带桌面低频 |
| `tile_discard_2` | 40.566 s | 最干净的一记脆响，信噪比最高 |
| `tile_discard_3` | 108.256 s | 明亮、颗粒清楚 |
| `tile_discard_4` | 97.059 s | 高频突出，颗粒感强 |
| `tile_discard_5` | 13.731 s | 偏低沉，像叠在牌堆上的落牌 |
| `tile_sort` 底料 | 109.108 / 87.744 / 131.726 / 132.726 / 144.817 / 98.743 / 147.381 / 107.990 s | 8 记细小的孤立敲击，作为理牌的“骨料” |
| `deal` | 87.744 s | 单张发牌，力度更轻 |
| `shuffle` 的起手 / 收尾落牌 | 复用上面 5 套落牌中的片段 | 见 `generate_real_sfx.py` 的 `make_shuffle` |

### 处理方式

- 按瞬态起点切段，前置 2 ms、后置 75–200 ms，保留完整的撞击瞬态；
- 去直流 + 110 Hz 高通（去掉桌面轰鸣与房间低频）+ 16.5 kHz 低通（去掉 MP3 高频毛刺）；
- `shorten_tail` 把房间反射的长拖尾按指数收住，让每记落牌“落地即收”；
- 片段峰值在 ±12/+30 ms 内对齐到样本峰，避免切到半个瞬态；
- 按目标 RMS 做等响归一（-25 ~ -27 dBFS），峰值上限 -1.2 dBFS。

**未做变调、未做时间伸缩、未复制同一段波形。** 5 套落牌来自 5 个不同的真实时间点。

---

## 2. Freesound #149041 — "Playing Mahjong at Apartment"

- 作者：al.barbosa
- 页面：<https://freesound.org/people/al.barbosa/sounds/149041/>
- 授权：**CC0 1.0 Universal (Public Domain Dedication)**
  授权全文：<https://creativecommons.org/publicdomain/zero/1.0/>
- 原始文件：`sources/fs_149041.mp3`（约 31 秒，44.1 kHz）
- 内容：近场牌桌录音。约 17.4 s 之后背景人声明显减少，牌面相互摩擦的沙沙声清晰、连续。

### 本项目使用的片段

| 用途 | 原录音时间点 | 时长 | 说明 |
|---|---:|---:|---|
| `shuffle` 主体（bed） | 25.05 s 起 | 1.72 s | 连续洗牌的牌面摩擦底噪 |
| `tile_draw` 摩擦 | 24.36 s 起 | 0.105 s | 从牌墙抽牌时与邻牌擦过的摩擦 |

### 处理方式

- 洗牌：以 1.72 s 真实连续摩擦为底，用增益包络做出“起手渐入（0–0.30 s）→ 加速（0.30–0.95 s）→ 维持（0.95–1.45 s）→ 收尾渐出（1.45–1.72 s）”，
  并在 0.045 s / 0.145 s 叠两记真实落牌、1.74 s 补一记收尾落牌，总长 1.95 s；
  180 Hz 高通 + 16 kHz 低通，去掉房间低频与嘶声。
- 摸牌：取 105 ms 摩擦，做 16 ms 起振 + 42 ms 指数衰减的短包络，再在 52 ms 处叠一记真实的轻磕（来自 #156668），
  总长 0.17 s；650 Hz 高通把低频房间声压掉，只留下牌面之间的高频摩擦。
- 理牌：以 #156668 的 8 记细小孤立敲击为骨料，按 38–110 ms 的随机间隔排布，单记力度 0.16–0.46 随机，
  偶尔两记几乎同时（模拟手指把牌推进去），总长 0.95 s。**不是循环，也不是一段录音的重复。**

---

## 署名（CC BY 4.0 要求）

按 CC BY 4.0 的要求，使用 RTB45 的录音需保留署名。建议在 App 的“关于 / 致谢”或发布页中保留以下文字：

> Mahjong tile sounds sampled from "Mahjong.wav" by RTB45
> (<https://freesound.org/people/RTB45/sounds/156668/>), licensed under CC BY 4.0.
> Modified: cut into short one-shots, high-pass/low-pass filtered, tail trimmed, loudness normalized.

al.barbosa 的录音以 CC0 发布，不强制署名，此处一并致谢：

> Additional shuffle/riffle friction sampled from "Playing Mahjong at Apartment" by al.barbosa
> (<https://freesound.org/people/al.barbosa/sounds/149041/>), CC0 1.0.

---

## 局限说明

- 两段素材都是现场录音，不是专业棚录：RTB45 的片段带轻微房间噪声，5 套落牌的信噪比不完全一致。
- 洗牌段的连续摩擦底噪来自公寓现场，夹着极少量远处人声。已先用 180 Hz 高通削弱，再加一道
  **分帧谱减轻降噪**（`denoise_light`，按每个频点 25% 分位估计稳定底噪，单频点最大衰减封顶 8 dB）：
  实测 200–600 Hz 轰鸣约 -2.1 dB、1–3 kHz 摩擦约 -1.2~-1.6 dB、3–8 kHz 约 -1.5 dB，
  属于“轻”，牌面摩擦的脆感基本保留。落牌/摸牌/理牌是短瞬态，降噪收益小且容易削掉起音，故未加。
  安静环境下细听仍可能残留一丝底噪，这是“保留脆响”与“绝对干净”之间的取舍。
- 原始素材是 MP3 预览版，高频在 16 kHz 以上经过压缩，已用低通截掉，避免毛刺，代价是极高频的空气感略少。
- 洗牌、理牌是“真实片段 + 时间轴重组”，不是一条完整实录；听感上比纯合成自然，但节奏仍由脚本决定，
  不会像真人那样每次洗牌都不同。若要做到“每次洗牌都不重样”，需要多段实录随机拼接，素材量会成倍增加。
