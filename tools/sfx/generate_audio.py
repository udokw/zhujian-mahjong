#!/usr/bin/env python3
"""
麻将音效资源生成器（一次性离线生成，运行时不联网）。

产物全部写入 app/src/androidMain/res/raw/：
  - sfx_start.wav / sfx_draw.wav / sfx_discard_1..6.wav / sfx_chi.wav /
    sfx_peng.wav / sfx_gang.wav / sfx_hu.wav / sfx_liuju.wav / sfx_settle.wav
  - voice_tile_00..33.wav（34 种牌名报牌）
  - voice_chi / voice_peng / voice_gang / voice_hu / voice_zimo / voice_liuju

两类来源：
  1. 敲桌、摸牌、吃碰杠、胡牌、开局洗牌、结算等音效：本脚本用 numpy 程序化合成，
     不含任何第三方采样，无版权风险。
  2. 34 种牌名与动作报牌：用 edge-tts 的 9 套中文神经网络音色
     （5 女 4 男，见 VOICE_SETS）一次性合成，生成后打包为本地 WAV；
     每局开始时给四家随机分配互不相同的一整套音色，运行期完全离线。

依赖：numpy、soundfile、edge-tts（生成时需要联网，仅生成阶段）。
用法：python tools/sfx/generate_audio.py
"""

from __future__ import annotations

import asyncio
import io
import math
import random
import wave
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 44100
ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / "app" / "src" / "androidMain" / "res" / "raw"

# 9 套明显不同的人声：5 女 4 男，覆盖不同年龄与口音，靠换真人音色区分，
# 不做变调。运行期不再按座位固定，而是每局开始时给四家随机分配互不相同的一整套。
# 每个音色单独调速，把各套语音时长压到同一量级，避免某一家拖慢报牌节奏。
VOICE_SETS = [
    ("1", "zh-CN-XiaoxiaoNeural", "+55%"),    # 女 · 青年 · 温和标准
    ("2", "zh-CN-XiaoyiNeural", "+50%"),      # 女 · 青年 · 活泼俏皮
    ("3", "zh-CN-liaoning-XiaobeiNeural", "+52%"),  # 女 · 中年 · 东北口音
    ("4", "zh-CN-shaanxi-XiaoniNeural", "+48%"),    # 女 · 中年 · 陕西口音，偏沉
    ("5", "zh-CN-YunxiNeural", "+70%"),       # 男 · 青年 · 清亮
    ("6", "zh-CN-YunjianNeural", "+60%"),     # 男 · 青年偏成熟 · 浑厚
    ("7", "zh-CN-YunyangNeural", "+58%"),     # 男 · 中年 · 播音腔，端正
    ("8", "zh-CN-YunxiaNeural", "+72%"),      # 男 · 少年 · 年龄感最小
    ("9", "zh-TW-YunJheNeural", "+56%"),      # 男 · 青年 · 台湾腔
]

# 引擎牌序号：0..8 万，9..17 筒，18..26 条，27 东 28 南 29 西 30 北 31 中 32 发 33 白
TILE_WORDS = [
    "一万", "二万", "三万", "四万", "五万", "六万", "七万", "八万", "九万",
    "一筒", "二筒", "三筒", "四筒", "五筒", "六筒", "七筒", "八筒", "九筒",
    "一条", "二条", "三条", "四条", "五条", "六条", "七条", "八条", "九条",
    "东风", "南风", "西风", "北风", "红中", "发财", "白板",
]
ACTION_WORDS = {
    "chi": "吃",
    "peng": "碰",
    "gang": "杠",
    "hu": "胡",
    "zimo": "自摸",
    "liuju": "流局",
}


# ---------------------------------------------------------------- 基础工具

def t_axis(dur: float) -> np.ndarray:
    return np.arange(int(round(SR * dur)), dtype=np.float64) / SR


def fade_edges(x: np.ndarray, ms_in: float = 0.4, ms_out: float = 4.0) -> None:
    n = len(x)
    a = min(int(SR * ms_in / 1000), n // 2)
    b = min(int(SR * ms_out / 1000), n // 2)
    if a > 0:
        x[:a] *= np.linspace(0.0, 1.0, a) ** 1.4
    if b > 0:
        x[-b:] *= np.linspace(1.0, 0.0, b) ** 1.4


def normalize(x: np.ndarray, peak: float = 0.9) -> np.ndarray:
    m = float(np.max(np.abs(x))) or 1.0
    return x * (peak / m)


def band_noise(dur: float, lo: float, hi: float, seed: int, slope: float = 0.0) -> np.ndarray:
    """FFT 带通白噪声，边缘用 tanh 平滑，避免滤波振铃。"""
    n = int(round(SR * dur))
    rng = np.random.default_rng(seed)
    x = rng.normal(0.0, 1.0, n)
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(n, 1.0 / SR)
    lo_w = max(lo * 0.35, 25.0)
    hi_w = max(hi * 0.18, 300.0)
    mask = 0.5 * (1.0 + np.tanh((freqs - lo) / lo_w))
    mask *= 0.5 * (1.0 - np.tanh((freqs - hi) / hi_w))
    if slope:
        mask *= np.clip(freqs / max(hi, 1.0), 1e-6, 1.0) ** slope
    y = np.fft.irfft(spec * mask, n)
    m = float(np.max(np.abs(y))) or 1.0
    return y / m


def short_ir(dur: float = 0.045, seed: int = 7, decay: float = 0.010) -> np.ndarray:
    n = int(SR * dur)
    rng = np.random.default_rng(seed)
    ir = rng.normal(0.0, 1.0, n) * np.exp(-np.arange(n) / (SR * decay))
    ir[0] = 1.0
    return ir / np.max(np.abs(ir))


def room_touch(x: np.ndarray, seed: int, mix: float = 0.16) -> np.ndarray:
    y = np.convolve(x, short_ir(seed=seed), mode="full")[: len(x)]
    return x + mix * y


def modal(freqs, taus, amps, dur: float, seed: int = 0, pitch_drop: float = 0.0) -> np.ndarray:
    t = t_axis(dur)
    rng = np.random.default_rng(seed)
    out = np.zeros_like(t)
    for f, tau, a in zip(freqs, taus, amps):
        ph = rng.uniform(0.0, 2.0 * math.pi)
        if pitch_drop > 0:
            # 近似下坠音高，短促敲击足够自然
            phase = 2.0 * math.pi * (f * t - f * pitch_drop * tau * (1.0 - np.exp(-t / tau)))
        else:
            phase = 2.0 * math.pi * f * t + ph
        out += a * np.sin(phase) * np.exp(-t / tau)
    return out


def clack(
    seed: int,
    base: float = 1500.0,
    bright: float = 0.5,
    dur: float = 0.15,
    body: float = 0.5,
    second: float = 0.0,
    noise_mix: float = 0.55,
    total_gain: float = 0.9,
) -> np.ndarray:
    """麻将牌磕桌：高频瞬态 + 牌体共振模态 + 低频桌面体感。"""
    t = t_axis(dur)
    grain = band_noise(dur, base * 0.7, min(base * 4.8, 15000.0), seed, slope=-0.15)
    grain *= np.exp(-t / 0.0065)
    click = band_noise(dur, 2600.0, 14500.0, seed + 1)
    click *= np.exp(-t / 0.0032)
    modes = modal(
        [base, base * 1.62, base * 2.55, base * 3.72],
        [0.032, 0.024, 0.017, 0.011],
        [0.55, 0.34, 0.22, 0.12],
        dur,
        seed=seed + 2,
        pitch_drop=0.10,
    )
    low = modal(
        [base * 0.085, base * 0.135],
        [0.055, 0.040],
        [0.55, 0.32],
        dur,
        seed=seed + 3,
        pitch_drop=0.18,
    )
    x = noise_mix * 0.62 * grain + bright * 0.40 * click + 0.52 * modes + body * 0.55 * low
    if second > 0:
        shift = int(SR * second)
        echo = np.zeros_like(x)
        echo[shift:] = x[: len(x) - shift]
        x = x + 0.34 * echo
    x = room_touch(x, seed + 9, 0.16)
    x -= float(np.mean(x))
    fade_edges(x, 0.25, 3.0)
    return normalize(x, total_gain)


def bell(freq: float, dur: float, amp: float, attack: float = 0.004) -> np.ndarray:
    t = t_axis(dur)
    env = (1.0 - np.exp(-t / attack)) * np.exp(-t / (dur * 0.35))
    out = np.zeros_like(t)
    partials = [(1.00, 1.00), (2.01, 0.42), (3.01, 0.24), (4.18, 0.13), (5.43, 0.07)]
    for ratio, a in partials:
        out += a * np.sin(2.0 * math.pi * freq * ratio * t + ratio * 0.7)
    return amp * out * env


# ---------------------------------------------------------------- 音效

def make_start() -> np.ndarray:
    """开局洗牌：密集但轻盈的牌面碰撞，前后有渐入渐出。"""
    dur = 1.20
    n = int(SR * dur)
    out = np.zeros(n)
    rng = np.random.default_rng(9001)
    kernels = [
        clack(seed=9100 + i, base=900.0 + i * 220.0, bright=0.35 + i * 0.1,
              dur=0.075, body=0.22, second=0.0, noise_mix=0.5, total_gain=0.55)
        for i in range(6)
    ]
    count = 64
    for i in range(count):
        pos = rng.beta(2.4, 2.4) * (dur - 0.09) + 0.02
        at = int(pos * SR)
        k = kernels[rng.integers(0, len(kernels))]
        gain = 0.20 + 0.42 * rng.random()
        # 中间密度高、两端轻
        edge = min(1.0, min(pos, dur - pos) / 0.14)
        chunk = k * gain * edge
        end = min(n, at + len(chunk))
        out[at:end] += chunk[: end - at]
    out = room_touch(out, 9301, 0.10)
    fade_edges(out, 2.0, 20.0)
    return normalize(out, 0.82)


def make_draw() -> np.ndarray:
    """摸牌：比出牌更轻、更闷的一声，带一点牌面摩擦。"""
    dur = 0.16
    t = t_axis(dur)
    brush = band_noise(dur, 320.0, 2800.0, 1201, slope=-0.4) * np.exp(-t / 0.045) * 0.35
    tap = clack(seed=1202, base=1050.0, bright=0.18, dur=0.10, body=0.30,
                second=0.0, noise_mix=0.32, total_gain=0.52)
    out = np.zeros(len(t))
    out += brush
    out[: len(tap)] += tap
    fade_edges(out, 0.5, 5.0)
    return normalize(out, 0.72)


def make_discards() -> list[np.ndarray]:
    specs = [
        dict(seed=2101, base=1680.0, bright=0.85, dur=0.135, body=0.45, second=0.030, noise_mix=0.60),
        dict(seed=2202, base=1260.0, bright=0.52, dur=0.160, body=0.58, second=0.000, noise_mix=0.50),
        dict(seed=2303, base=980.0, bright=0.30, dur=0.190, body=0.82, second=0.045, noise_mix=0.46),
        dict(seed=2404, base=2150.0, bright=1.00, dur=0.120, body=0.34, second=0.022, noise_mix=0.66),
        dict(seed=2505, base=820.0, bright=0.10, dur=0.205, body=0.38, second=0.000, noise_mix=0.34),
        dict(seed=2606, base=1450.0, bright=0.95, dur=0.150, body=0.50, second=0.036, noise_mix=0.56),
    ]
    return [clack(total_gain=0.88, **spec) for spec in specs]


def make_chi() -> np.ndarray:
    """吃：两声轻快的上行木牌敲击。"""
    dur = 0.24
    n = int(SR * dur)
    out = np.zeros(n)
    a = clack(seed=3101, base=1080.0, bright=0.42, dur=0.11, body=0.30,
              second=0.0, noise_mix=0.40, total_gain=0.58)
    b = clack(seed=3102, base=1380.0, bright=0.48, dur=0.11, body=0.30,
              second=0.0, noise_mix=0.42, total_gain=0.62)
    at_b = int(0.070 * SR)
    out[: len(a)] += a
    out[at_b: at_b + len(b)] += b
    fade_edges(out, 0.4, 6.0)
    return normalize(out, 0.86)


def make_peng() -> np.ndarray:
    """碰：一记更实、更重的碰牌声。"""
    dur = 0.34
    t = t_axis(dur)
    out = np.zeros(len(t))
    hit = clack(seed=3201, base=760.0, bright=0.28, dur=0.26, body=0.92,
                second=0.034, noise_mix=0.52, total_gain=0.94)
    boom = modal([118.0, 176.0], [0.085, 0.060], [0.42, 0.22], dur,
                 seed=3202, pitch_drop=0.22)
    out[: len(hit)] += hit
    out += boom
    fade_edges(out, 0.3, 8.0)
    return normalize(out, 0.95)


def make_gang() -> np.ndarray:
    """杠：双记重敲 + 低频轰鸣 + 一点金属余韵。"""
    dur = 0.50
    t = t_axis(dur)
    out = np.zeros(len(t))
    first = clack(seed=3301, base=700.0, bright=0.34, dur=0.30, body=1.00,
                  second=0.0, noise_mix=0.58, total_gain=0.98)
    second = clack(seed=3302, base=560.0, bright=0.26, dur=0.30, body=1.00,
                   second=0.0, noise_mix=0.55, total_gain=0.92)
    out[: len(first)] += first
    at = int(0.060 * SR)
    out[at: at + len(second)] += second
    boom = modal([78.0, 112.0, 168.0], [0.16, 0.12, 0.08],
                 [0.55, 0.34, 0.18], dur, seed=3303, pitch_drop=0.30)
    out += boom
    ring = modal([2600.0, 3900.0], [0.10, 0.08], [0.10, 0.06], dur, seed=3304)
    out += ring
    fade_edges(out, 0.3, 12.0)
    return normalize(out, 0.96)


def make_hu() -> np.ndarray:
    """胡牌：明亮上行钟声和弦，带一点金光尾音。"""
    dur = 1.35
    n = int(SR * dur)
    out = np.zeros(n)
    notes = [(523.25, 0.00, 0.60), (659.25, 0.055, 0.55),
             (783.99, 0.110, 0.52), (1046.50, 0.170, 0.48)]
    for freq, at_s, amp in notes:
        tail = bell(freq, dur - at_s, amp)
        at = int(at_s * SR)
        out[at: at + len(tail)] += tail
    warm = bell(261.63, dur, 0.30, attack=0.012)
    out += warm
    t = t_axis(dur)
    shimmer = band_noise(dur, 4500.0, 12000.0, 4401) * np.exp(-t / 0.35) * 0.035
    out += shimmer
    fade_edges(out, 1.0, 60.0)
    return normalize(out, 0.92)


def make_liuju() -> np.ndarray:
    """荒庄：低沉的闷响 + 下行钟声，情绪收束。"""
    dur = 0.95
    n = int(SR * dur)
    out = np.zeros(n)
    boom = modal([196.0, 261.6, 392.0], [0.30, 0.22, 0.14],
                 [0.55, 0.30, 0.16], dur, seed=4501, pitch_drop=0.32)
    out += boom
    hit = clack(seed=4502, base=620.0, bright=0.20, dur=0.24, body=0.85,
                second=0.0, noise_mix=0.40, total_gain=0.70)
    out[: len(hit)] += hit
    fade_edges(out, 1.0, 80.0)
    return normalize(out, 0.80)


def make_settle() -> np.ndarray:
    """结算：温暖的三音和弦 + 轻微金币般的高频泛音。"""
    dur = 1.25
    n = int(SR * dur)
    out = np.zeros(n)
    for freq, at_s, amp in [(261.63, 0.00, 0.42), (329.63, 0.035, 0.36), (392.00, 0.070, 0.34)]:
        tail = bell(freq, dur - at_s, amp, attack=0.012)
        at = int(at_s * SR)
        out[at: at + len(tail)] += tail
    t = t_axis(dur)
    sparkle = band_noise(dur, 5200.0, 13500.0, 4601) * np.exp(-t / 0.28) * 0.05
    out += sparkle
    fade_edges(out, 2.0, 70.0)
    return normalize(out, 0.84)


# ---------------------------------------------------------------- WAV 输出

def write_wav(path: Path, x: np.ndarray, sr: int = SR, peak: float = 0.95) -> None:
    x = np.asarray(x, dtype=np.float64)
    if np.max(np.abs(x)) > 0:
        x = x / np.max(np.abs(x)) * peak
    pcm = np.clip(x * 32767.0, -32768, 32767).astype("<i2")
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(sr)
        f.writeframes(pcm.tobytes())


# ---------------------------------------------------------------- 语音

def trim_speech(x: np.ndarray, sr: int, lead_ms: float = 12.0, tail_ms: float = 28.0) -> np.ndarray:
    env = np.abs(x)
    peak = float(env.max()) or 1.0
    idx = np.where(env > max(peak * 0.025, 2e-4))[0]
    if len(idx) == 0:
        return x
    a = max(0, idx[0] - int(sr * lead_ms / 1000))
    b = min(len(x), idx[-1] + int(sr * tail_ms / 1000))
    y = x[a:b].copy()
    fade_edges(y, 3.0, 12.0)
    return y


async def synth_voice(
    text: str,
    voice: str,
    rate: str,
    path: Path,
    semaphore: asyncio.Semaphore,
) -> tuple[str, float]:
    import edge_tts

    async with semaphore:
        last_error: Exception | None = None
        for attempt in range(4):
            try:
                communicate = edge_tts.Communicate(text, voice, rate=rate)
                buf = bytearray()
                async for chunk in communicate.stream():
                    if chunk["type"] == "audio":
                        buf.extend(chunk["data"])
                if not buf:
                    raise RuntimeError("empty audio")
                x, sr = sf.read(io.BytesIO(bytes(buf)), dtype="float32")
                if x.ndim > 1:
                    x = x.mean(axis=1)
                y = trim_speech(x, sr)
                write_wav(path, y, sr, peak=0.92)
                return text, len(y) / float(sr)
            except Exception as exc:  # noqa: BLE001
                last_error = exc
                await asyncio.sleep(0.8 + attempt * 0.7)
        raise RuntimeError(f"TTS failed for {text!r}: {last_error}")


async def build_voice() -> None:
    import edge_tts  # noqa: F401  确保依赖存在

    jobs: list[tuple[str, str, str, Path]] = []
    for set_id, voice, rate in VOICE_SETS:
        for i, word in enumerate(TILE_WORDS):
            jobs.append((word, voice, rate, RAW / f"voice{set_id}_tile_{i:02d}.wav"))
        for key, word in ACTION_WORDS.items():
            jobs.append((word, voice, rate, RAW / f"voice{set_id}_{key}.wav"))

    semaphore = asyncio.Semaphore(4)
    results = await asyncio.gather(
        *(synth_voice(text, voice, rate, path, semaphore) for text, voice, rate, path in jobs)
    )
    durations = [dur for _, dur in results]
    print("语音生成完成：")
    print(f"  {len(VOICE_SETS)} 套人声 × {len(TILE_WORDS)} 种牌 + {len(ACTION_WORDS)} 个动作 = {len(jobs)} 条")
    print(f"  最长 {max(durations) * 1000:.0f} ms，平均 {sum(durations) / len(durations) * 1000:.0f} ms")

    # 清掉早期单人声版本，避免 APK 里同时存在两套报牌资源。
    for legacy in list(RAW.glob("voice_tile_*.wav")) + [
        RAW / f"voice_{key}.wav" for key in ACTION_WORDS
    ]:
        if legacy.exists():
            legacy.unlink()


# ---------------------------------------------------------------- 入口

def build_effects() -> None:
    RAW.mkdir(parents=True, exist_ok=True)
    assets = {
        "sfx_start": make_start(),
        "sfx_draw": make_draw(),
        "sfx_chi": make_chi(),
        "sfx_peng": make_peng(),
        "sfx_gang": make_gang(),
        "sfx_hu": make_hu(),
        "sfx_liuju": make_liuju(),
        "sfx_settle": make_settle(),
    }
    for i, sound in enumerate(make_discards(), start=1):
        assets[f"sfx_discard_{i}"] = sound
    for name, sound in assets.items():
        write_wav(RAW / f"{name}.wav", sound, peak=0.94)
    print(f"敲桌/动作音效生成完成：{len(assets)} 个（含 6 套出牌敲桌声）")


def main() -> None:
    build_effects()
    asyncio.run(build_voice())
    files = sorted(RAW.glob("*.wav"))
    print(f"\n输出目录：{RAW}")
    print(f"WAV 总数：{len(files)}")
    total = sum(p.stat().st_size for p in files)
    print(f"总体积：{total / 1024 / 1024:.2f} MB")


if __name__ == "__main__":
    main()
