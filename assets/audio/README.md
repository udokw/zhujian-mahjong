# 麻将游戏音效

本目录的音效分两类：

1. **真实录音采样**：落牌 5 套、洗牌、摸牌、理牌、发牌，由 `tools/generate_real_sfx.py` 从两段可免费使用的真实麻将录音中切段、去低频轰鸣、截断拖尾、等响归一而成，**没有变调、没有同一段复制**。素材来源与授权详见 [SOURCES.md](SOURCES.md)。
2. **原创程序化合成**：选牌、吃碰杠、胡牌、结算等其余音效，由 `tools/generate_audio.py` 用 numpy 合成，不含第三方采样。

可用范围：国标 / 广东推倒胡 / 广东鸡平胡。

## 规格

- 采样率：44 100 Hz
- 位深：16-bit PCM
- 声道：单声道
- 峰值：全部 ≤ -1.0 dBFS（生成时以 -1.2 dBFS 为上限，量化后仍有余量）
- 响度：以 A 加权 50 ms 最大 RMS 为统一基准，按“轻提示 / 操作 / 胡牌 / 结算”分级，避免个别音效过炸
- 全部为**一次性触发**，没有循环素材，因此本批未生成 OGG；后续若增加环境底噪循环，再单独提供 OGG 并标注 loop start/end
- 本目录共 22 个 WAV，合计约 657 KB（另有一套历史遗留的 `tile_discard.wav` 已不被播放层引用）

## 文件清单

| 文件 | 用途 | 时长 | 大小 | 峰值 | 响度* | 播放方式 |
|---|---|---:|---:|---:|---:|---|
| `tile_pick.wav` | 选牌 / 抬起手牌 | 0.070 s | 6.1 KB | -13.1 dBFS | -25.0 dBFS | 单次 |
| `tile_discard_1.wav` | 出牌落桌 · 厚实（真实录音） | 0.132 s | 11.4 KB | -1.7 dBFS | -21.8 dBFS | 单次 |
| `tile_discard_2.wav` | 出牌落桌 · 干净脆响（真实录音） | 0.102 s | 8.8 KB | -2.5 dBFS | -22.1 dBFS | 单次 |
| `tile_discard_3.wav` | 出牌落桌 · 明亮（真实录音） | 0.107 s | 9.3 KB | -1.2 dBFS | -26.6 dBFS | 单次 |
| `tile_discard_4.wav` | 出牌落桌 · 高频颗粒（真实录音） | 0.100 s | 8.7 KB | -1.2 dBFS | -28.6 dBFS | 单次 |
| `tile_discard_5.wav` | 出牌落桌 · 低沉叠牌（真实录音） | 0.120 s | 10.4 KB | -5.0 dBFS | -22.6 dBFS | 单次 |
| `tile_draw.wav` | 从牌墙摸牌（真实录音） | 0.170 s | 14.7 KB | -4.6 dBFS | -23.4 dBFS | 单次 |
| `tile_rub.wav` | 摸牌后摩牌（可选） | 0.170 s | 14.7 KB | -16.6 dBFS | -27.0 dBFS | 单次 |
| `tile_sort.wav` | 开局理牌，细碎碰撞（真实录音） | 0.950 s | 81.9 KB | -7.5 dBFS | -24.6 dBFS | 单次 |
| `shuffle.wav` | 开局洗牌，起手→加速→收尾（真实录音） | 1.240 s | 106.8 KB | -2.2 dBFS | -15.0 dBFS | 单次 |
| `deal.wav` | 发牌单张（真实录音） | 0.066 s | 5.7 KB | -8.0 dBFS | -31.0 dBFS | 单次（可连续触发） |
| `chi.wav` | 吃 | 0.220 s | 19.0 KB | -8.5 dBFS | -21.0 dBFS | 单次 |
| `peng.wav` | 碰 | 0.300 s | 25.9 KB | -4.0 dBFS | -19.0 dBFS | 单次 |
| `gang.wav` | 杠 | 0.550 s | 47.4 KB | -1.8 dBFS | -18.0 dBFS | 单次 |
| `hu.wav` | 胡牌成立瞬间，短收束和弦 | 0.640 s | 55.2 KB | -3.0 dBFS | -16.5 dBFS | 单次 |
| `hu_multi.wav` | 一炮多响 / 连续胡，更厚重 | 0.660 s | 56.9 KB | -1.5 dBFS | -15.5 dBFS | 单次 |
| `score_tick.wav` | 分数滚动计数颗粒音 | 0.030 s | 2.6 KB | -14.8 dBFS | -28.0 dBFS | 连续触发 |
| `win_sting.wav` | 结算页短乐句（G 大调上行） | 1.500 s | 129.2 KB | -7.2 dBFS | -17.5 dBFS | 单次 |
| `button_tap.wav` | 通用按钮 | 0.045 s | 3.9 KB | -14.8 dBFS | -26.0 dBFS | 单次 |
| `invalid.wav` | 非法 / 不可用操作 | 0.190 s | 16.4 KB | -5.3 dBFS | -23.0 dBFS | 单次 |
| `countdown.wav` | 响应倒计时预警 | 0.090 s | 7.8 KB | -12.2 dBFS | -21.0 dBFS | 每秒一次 |

\* 响度 = A 加权 50 ms 滑动窗口最大 RMS，用于横向比较，不等同于 LUFS。

### 关于 60 KB

44.1 kHz / 16-bit / 单声道 WAV 约为 **88.2 KB/s**，因此 60 KB 上限对应约 **0.68 s**。除 `shuffle`（1.2 s）和 `win_sting`（1.5 s）因内容时长必须超出外，其余 14 个文件均小于 60 KB。若后续必须严格 ≤60 KB，可缩短这两条或改为 OGG，但会偏离本批“全程 WAV”的约定，故本次保留 WAV 并在此注明。

## 建议播放时机

- `tile_discard_1..5`：牌落到牌河、动画接触桌面的那一帧；**每次随机取一套，且不与上一张相同**（`GameAudio` 已内置轮换），不要变调。
- `tile_draw`：从牌墙取牌动作开始后约 30–40 ms 触发，贴合“先摩擦、后落手”的层次。
- `tile_rub`：摸牌后摩牌动画开始时；只在开启摩牌表现时播放。
- `shuffle`：进入对局、洗牌动画开始时**只播一次**，不要循环；1.24 s 内含起手、加速与收尾落牌（已加一道轻降噪，最大衰减 8 dB，保留牌面摩擦）。
- `deal`：洗牌收尾后每发一张播一次，间隔 55 ms 左右。
- `tile_sort`：发牌结束后播一次（0.95 s），代表四家把牌立在手里理顺。
- `chi` / `peng` / `gang`：对应动作成立、副露落到桌面时触发；`gang` 比 `peng` 更重，不要同时叠加 `peng`。
- `hu`：胡牌判定成立、手牌定格时触发一次；随后再进结算页，不要与 `win_sting` 同时起播。
- `hu_multi`：一炮多响或连续胡时**替代** `hu` 播放；若已播过 `hu`，可延迟 80–120 ms 再补 `hu_multi`。
- `score_tick`：结算分数滚动期间每 30–50 ms 触发一次，建议播放音量再降 3–6 dB；滚动结束后立即停止。
- `win_sting`：结算页主体出现时播放一次，覆盖番型清单展开的入场动画。
- `button_tap`：所有普通按钮 / 页签切换；设置里的开关也可复用。
- `invalid`：点击不可用按钮、非法出牌、手牌不满足条件时；不要与错误弹窗叠加多次。
- `countdown`：响应倒计时每秒一次，最后 5 秒可保持不变或逐秒提高 1–2 dB。

## 生成脚本

- 真实采样类：`assets/audio/tools/generate_real_sfx.py`，读取 `assets/audio/sources/` 下的原始录音，依赖 numpy + soundfile。
- 合成类：`assets/audio/tools/generate_audio.py`，仅依赖 Python 3 + numpy，不依赖 scipy。

```bash
# 在项目根目录执行
python assets/audio/tools/generate_real_sfx.py   # 落牌 / 洗牌 / 摸牌 / 理牌 / 发牌
python assets/audio/tools/generate_audio.py     # 其余合成音效
```

真实采样脚本会把同名 WAV 同时写入 `assets/audio/` 和 `app/src/androidMain/res/raw/`，
两处内容保持一致；iOS 通过 Xcode 的 `raw` 文件夹引用直接复用 Android 的资源。

## 授权

- **合成音效**（选牌、吃碰杠、胡牌、结算等）：本项目原创，波形由正弦模态、噪声脉冲与包络合成，不含第三方采样。
- **真实录音采样**（落牌、洗牌、摸牌、理牌、发牌）：来自 Freesound 上分别以 CC BY 4.0 与 CC0 发布的真实麻将录音，本项目做了切段与整形。必须保留的署名与来源见 [SOURCES.md](SOURCES.md)。
