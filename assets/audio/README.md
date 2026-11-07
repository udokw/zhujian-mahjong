# 麻将游戏原创音效

本目录所有音效均由 `tools/generate_audio.py` 程序化合成，**未使用任何现有游戏采样或版权音频库**，可安全用于本项目（国标 / 广东推倒胡 / 广东鸡平胡）。

## 规格

- 采样率：44 100 Hz
- 位深：16-bit PCM
- 声道：单声道
- 峰值：全部 ≤ -1.0 dBFS（生成时以 -1.2 dBFS 为上限，量化后仍有余量）
- 响度：以 A 加权 50 ms 最大 RMS 为统一基准，按“轻提示 / 操作 / 胡牌 / 结算”分级，避免个别音效过炸
- 全部为**一次性触发**，没有循环素材，因此本批未生成 OGG；后续若增加环境底噪循环，再单独提供 OGG 并标注 loop start/end
- 总大小约 520 KB

## 文件清单

| 文件 | 用途 | 时长 | 大小 | 峰值 | 响度* | 播放方式 |
|---|---|---:|---:|---:|---:|---|
| `tile_pick.wav` | 选牌 / 抬起手牌 | 0.070 s | 6.1 KB | -13.1 dBFS | -25.0 dBFS | 单次 |
| `tile_discard.wav` | 出牌落桌，牌与桌面撞击 | 0.160 s | 13.8 KB | -3.6 dBFS | -20.0 dBFS | 单次 |
| `tile_draw.wav` | 从牌墙摸牌 | 0.120 s | 10.4 KB | -10.1 dBFS | -23.0 dBFS | 单次 |
| `tile_rub.wav` | 摸牌后摩牌（可选） | 0.170 s | 14.7 KB | -16.6 dBFS | -27.0 dBFS | 单次 |
| `shuffle.wav` | 开局洗牌，成组摩擦 | 1.200 s | 103.4 KB | -8.9 dBFS | -21.0 dBFS | 单次 |
| `deal.wav` | 发牌单张 | 0.090 s | 7.8 KB | -10.7 dBFS | -23.0 dBFS | 单次（可连续触发） |
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

- `tile_pick`：手指按下 / 手牌抬起时，紧跟一次极短点击。
- `tile_discard`：牌落到牌河、动画接触桌面的那一帧；建议每次随机 ±1 半音、±1 dB，避免连续出牌时机械重复。
- `tile_draw`：从牌墙取牌动作开始后约 30–40 ms 触发，贴合“先摩擦、后落手”的层次。
- `tile_rub`：摸牌后摩牌动画开始时；只在开启摩牌表现时播放。
- `shuffle`：进入对局、洗牌动画开始时**只播一次**，不要循环。
- `deal`：每发一张播一次；连续发牌时间隔 40–60 ms，避免叠加成噪声。
- `chi` / `peng` / `gang`：对应动作成立、副露落到桌面时触发；`gang` 比 `peng` 更重，不要同时叠加 `peng`。
- `hu`：胡牌判定成立、手牌定格时触发一次；随后再进结算页，不要与 `win_sting` 同时起播。
- `hu_multi`：一炮多响或连续胡时**替代** `hu` 播放；若已播过 `hu`，可延迟 80–120 ms 再补 `hu_multi`。
- `score_tick`：结算分数滚动期间每 30–50 ms 触发一次，建议播放音量再降 3–6 dB；滚动结束后立即停止。
- `win_sting`：结算页主体出现时播放一次，覆盖番型清单展开的入场动画。
- `button_tap`：所有普通按钮 / 页签切换；设置里的开关也可复用。
- `invalid`：点击不可用按钮、非法出牌、手牌不满足条件时；不要与错误弹窗叠加多次。
- `countdown`：响应倒计时每秒一次，最后 5 秒可保持不变或逐秒提高 1–2 dB。

## 生成脚本

脚本位置：`assets/audio/tools/generate_audio.py`，仅依赖 Python 3 + numpy，不依赖 scipy。

```bash
# 在项目根目录执行，重新生成全部音效
python assets/audio/tools/generate_audio.py

# 指定输出目录
python assets/audio/tools/generate_audio.py --out "g:\AI\麻将\assets\audio"

# 只重生成某几个
python assets/audio/tools/generate_audio.py --only hu,win_sting
```

可调参数集中在脚本顶部与 `SPECS` 表：

- `SR`：采样率（默认 44100）
- `CEILING_DB`：峰值上限（默认 -1.2 dBFS）
- `MASTER_SEED`：随机种子，保证每次生成结果可复现
- `SPECS` 中每个音效的第三个值：目标响度（A 加权 50 ms 最大 RMS，dBFS）

修改后重新运行脚本即可覆盖同名 WAV，无需其他构建步骤。

## 原创与授权

全部波形由正弦模态、噪声脉冲、包络与频域滤波实时合成，不含任何第三方录音采样；生成脚本与音频文件均属于本项目原创资产。
