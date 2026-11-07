#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
麻将游戏原创音效生成器（纯程序化合成，不使用任何外部采样或版权音频库）

输出：44.1kHz / 16-bit / 单声道 WAV，峰值 <= -1 dBFS。
依赖：仅 numpy（不依赖 scipy / soundfile）。

用法：
    python assets/audio/tools/generate_audio.py
    python assets/audio/tools/generate_audio.py --out "g:\\AI\\麻将\\assets\\audio"
    python assets/audio/tools/generate_audio.py --only hu,win_sting

设计要点：
- 牌声采用“模态合成”：几组非谐阻尼正弦模拟塑料/竹牌撞击，叠加短噪声脉冲与低频桌面共鸣。
- 全程干声，不做长混响；结尾仅做极短淡出，避免爆音。
- 统一以 A 加权 50ms 最大 RMS 作为响度基准，再以 -1.2 dBFS 为峰值上限做安全限制。
"""

from __future__ import annotations

import argparse
import wave
from pathlib import Path

import numpy as np

SR = 44100
CEILING_DB = -1.2          # 峰值上限，留 0.2dB 余量，量化后仍 <= -1.0
MASTER_SEED = 20261108


# --------------------------------------------------------------------------
# 基础工具
# --------------------------------------------------------------------------
def sec(dur: float) -> np.ndarray:
    """返回从 0 开始的时间轴（秒）。"""
    return np.arange(int(round(dur * SR)), dtype=np.float64) / SR


def db2a(db: float) -> float:
    return 10.0 ** (db / 20.0)


def a2db(x: float) -> float:
    return 20.0 * np.log10(max(float(x), 1e-12))


def white(dur: float, rng: np.random.Generator) -> np.ndarray:
    return rng.standard_normal(int(round(dur * SR)))


def fft_filter(x: np.ndarray, gain) -> np.ndarray:
    """零相位频域滤波（补零到 >= 2N，减少循环卷积泄漏）。"""
    n = len(x)
    if n == 0:
        return x
    nfft = 1 << (2 * n - 1).bit_length()
    spec = np.fft.rfft(x, nfft)
    freqs = np.fft.rfftfreq(nfft, 1.0 / SR)
    spec *= gain(freqs)
    return np.fft.irfft(spec, nfft)[:n]


def lp(fc: float, order: int = 2):
    def gain(f):
        return 1.0 / np.sqrt(1.0 + (f / fc) ** (2 * order))

    return gain


def hp(fc: float, order: int = 2):
    def gain(f):
        fr = np.maximum(f, 1e-6)
        return 1.0 / np.sqrt(1.0 + (fc / fr) ** (2 * order))

    return gain


def bp(lo: float, hi: float, order: int = 2):
    g_hi, g_lo = hp(lo, order), lp(hi, order)
    return lambda f: g_hi(f) * g_lo(f)


def fade(y: np.ndarray, fade_in: float = 0.0, fade_out: float = 0.0) -> None:
    """原地做升余弦淡入淡出。"""
    n = len(y)
    if fade_in > 0:
        k = max(1, min(n, int(round(fade_in * SR))))
        y[:k] *= np.sin(0.5 * np.pi * np.linspace(0.0, 1.0, k)) ** 2
    if fade_out > 0:
        k = max(1, min(n, int(round(fade_out * SR))))
        y[-k:] *= np.cos(0.5 * np.pi * np.linspace(0.0, 1.0, k)) ** 2


def add_at(buf: np.ndarray, sig: np.ndarray, t0: float) -> None:
    i = int(round(t0 * SR))
    if i >= len(buf):
        return
    n = min(len(sig), len(buf) - i)
    if n > 0:
        buf[i:i + n] += sig[:n]


# --------------------------------------------------------------------------
# 合成音色
# --------------------------------------------------------------------------
def wood_hit(
    dur: float,
    rng: np.random.Generator,
    f_scale: float = 1.0,
    thump: float = 0.6,
    bright: float = 1.0,
    noise_amt: float = 0.35,
    attack: float = 0.0004,
) -> np.ndarray:
    """牌体撞击：非谐模态 + 宽带瞬态 + 低频桌面共鸣。"""
    t = sec(dur)
    y = np.zeros_like(t)

    # 非谐模态（比例 1 / 1.82 / 3.14 / 4.63，接近塑料牌的非整数倍振动）
    base = 720.0 * f_scale
    modes = (
        (1.00, 0.060, 0.46, 1.00),
        (1.82, 0.036, 0.32, 0.90),
        (3.14, 0.020, 0.24, 1.10),
        (4.63, 0.011, 0.13, 1.20),
    )
    for ratio, tau, amp, _high in modes:
        amp_eff = amp * (bright if ratio >= 3.0 else 1.0)
        y += amp_eff * np.cos(2.0 * np.pi * base * ratio * t) * np.exp(-t / tau)

    # 撞击瞬间的宽带噪声
    n = white(dur, rng) * np.exp(-t / 0.0022)
    n = fft_filter(n, bp(1600.0, 9000.0))
    y += noise_amt * n

    # 桌面/牌体低频“咚”
    if thump > 0:
        f_inst = 150.0 + 70.0 * np.exp(-t / 0.016)
        phase = 2.0 * np.pi * np.cumsum(f_inst) / SR
        y += thump * np.sin(phase) * np.exp(-t / 0.045)

    # 少量中频木质body
    bn = white(dur, rng) * np.exp(-t / 0.008)
    bn = fft_filter(bn, bp(250.0, 1400.0))
    y += 0.22 * bn

    k = max(1, int(attack * SR))
    y[:k] *= np.linspace(0.0, 1.0, k)
    return y


def bell_note(freq: float, dur: float, tau: float = 0.30, amp: float = 1.0) -> np.ndarray:
    """带非谐泛音的短钟/铃音，用于胡牌收束。"""
    t = sec(dur)
    y = np.zeros_like(t)
    partials = (
        (1.00, 1.00, 1.00),
        (2.00, 0.42, 0.62),
        (2.76, 0.22, 0.44),
        (4.07, 0.10, 0.28),
        (5.43, 0.05, 0.18),
    )
    for ratio, a, tau_scale in partials:
        detune = 1.0 + 0.0006 * (ratio - 1.0)
        y += amp * a * np.sin(
            2.0 * np.pi * freq * ratio * detune * t + 0.35 * ratio
        ) * np.exp(-t / (tau * tau_scale))

    k = max(1, int(0.0015 * SR))
    y[:k] *= np.linspace(0.0, 1.0, k) ** 0.7
    return y


def mallet_note(freq: float, dur: float, tau: float = 0.5, amp: float = 1.0) -> np.ndarray:
    """柔和木琴/马林巴音色，用于结算短乐句。"""
    t = sec(dur)
    y = (
        np.sin(2.0 * np.pi * freq * t)
        + 0.32 * np.sin(2.0 * np.pi * 2.0 * freq * t + 0.4)
        + 0.12 * np.sin(2.0 * np.pi * 3.0 * freq * t + 1.1)
    ) * np.exp(-t / tau)
    y += 0.10 * np.sin(2.0 * np.pi * 0.5 * freq * t) * np.exp(-t / (tau * 1.2))

    k = max(1, int(0.003 * SR))
    y[:k] *= np.sin(0.5 * np.pi * np.linspace(0.0, 1.0, k)) ** 2
    return amp * y


def friction(
    dur: float,
    rng: np.random.Generator,
    lo: float = 1100.0,
    hi: float = 6500.0,
    amp: float = 1.0,
) -> np.ndarray:
    t = sec(dur)
    n = fft_filter(white(dur, rng), bp(lo, hi))
    env = np.sin(np.pi * np.linspace(0.0, 1.0, len(t))) ** 0.8
    return amp * n * env


def finish(y: np.ndarray, fade_out: float = 0.012) -> np.ndarray:
    """去直流 + 极短淡入/淡出。"""
    y = np.asarray(y, dtype=np.float64).copy()
    y -= float(np.mean(y))
    fade(y, 0.0004, fade_out)
    return y


# --------------------------------------------------------------------------
# 单个音效
# --------------------------------------------------------------------------
def build_tile_pick(rng):
    dur = 0.07
    y = wood_hit(dur, rng, f_scale=1.16, thump=0.14, bright=1.20, noise_amt=0.55)
    t = sec(dur)
    y += 0.16 * fft_filter(white(dur, rng) * np.exp(-t / 0.0015), bp(4000.0, 10000.0))
    return finish(y, 0.010)


def build_tile_discard(rng):
    dur = 0.16
    y = wood_hit(dur, rng, f_scale=0.92, thump=0.90, bright=0.95, noise_amt=0.45)
    t = sec(dur)
    y += 0.22 * np.sin(2.0 * np.pi * 118.0 * t) * np.exp(-t / 0.030)
    return finish(y, 0.020)


def build_tile_draw(rng):
    dur = 0.12
    buf = np.zeros(int(round(dur * SR)))
    add_at(buf, friction(0.06, rng, 900.0, 4500.0, 0.09), 0.0)
    add_at(buf, wood_hit(0.08, rng, f_scale=1.06, thump=0.30, bright=1.00, noise_amt=0.35), 0.035)
    return finish(buf, 0.015)


def build_tile_rub(rng):
    dur = 0.17
    t = sec(dur)
    n = fft_filter(white(dur, rng), bp(700.0, 3800.0))
    x = np.linspace(0.0, 1.0, len(t))
    env = np.sin(np.pi * x) ** 0.7 * (0.75 + 0.25 * np.sin(2.0 * np.pi * 2.2 * x - 0.6))
    y = 0.10 * n * env
    for t0 in (0.030, 0.085, 0.130):
        add_at(y, 0.06 * wood_hit(0.03, rng, f_scale=1.5, thump=0.0,
                                  bright=0.6, noise_amt=0.2), t0)
    return finish(y, 0.020)


def build_shuffle(rng):
    dur = 1.20
    buf = np.zeros(int(round(dur * SR)))
    x = np.linspace(0.0, 1.0, len(buf))

    # 底层摩擦噪声
    bed = fft_filter(white(dur, rng), bp(600.0, 6000.0))
    buf += 0.045 * bed * np.sin(np.pi * x) ** 0.7

    # 成组牌粒碰撞
    count = 85
    for _ in range(count):
        tt = float(rng.beta(2.0, 2.0)) * 1.12 + 0.035
        gd = float(rng.uniform(0.025, 0.055))
        grain = wood_hit(
            gd,
            rng,
            f_scale=float(rng.uniform(0.72, 1.50)),
            thump=float(rng.uniform(0.0, 0.30)),
            bright=float(rng.uniform(0.60, 1.25)),
            noise_amt=float(rng.uniform(0.20, 0.50)),
        )
        dens = np.sin(np.pi * tt / dur) ** 0.55
        add_at(buf, float(rng.uniform(0.10, 0.50)) * dens * grain, tt)

    return finish(buf, 0.030)


def build_deal(rng):
    dur = 0.09
    y = wood_hit(dur, rng, f_scale=1.28, thump=0.28, bright=1.10, noise_amt=0.50)
    return finish(y, 0.012)


def build_chi(rng):
    dur = 0.22
    buf = np.zeros(int(round(dur * SR)))
    add_at(buf, 0.90 * wood_hit(0.09, rng, f_scale=1.20, thump=0.22,
                                bright=1.20, noise_amt=0.40), 0.000)
    add_at(buf, 0.75 * wood_hit(0.09, rng, f_scale=1.48, thump=0.18,
                                bright=1.25, noise_amt=0.40), 0.072)
    add_at(buf, 0.16 * bell_note(1046.50, 0.12, tau=0.060), 0.074)
    return finish(buf, 0.020)


def build_peng(rng):
    dur = 0.30
    buf = np.zeros(int(round(dur * SR)))
    add_at(buf, 1.00 * wood_hit(0.12, rng, f_scale=0.90, thump=0.75,
                                bright=0.95, noise_amt=0.45), 0.000)
    add_at(buf, 0.85 * wood_hit(0.12, rng, f_scale=0.96, thump=0.70,
                                bright=0.90, noise_amt=0.45), 0.055)
    t = sec(dur)
    buf += 0.10 * np.sin(2.0 * np.pi * 147.0 * t) * np.exp(-t / 0.100)
    return finish(buf, 0.030)


def build_gang(rng):
    dur = 0.55
    buf = np.zeros(int(round(dur * SR)))
    hits = ((0.000, 1.00, 1.00), (0.050, 0.94, 0.92),
            (0.100, 0.90, 0.85), (0.150, 0.86, 0.78))
    for t0, fs, amp in hits:
        add_at(buf, amp * wood_hit(0.14, rng, f_scale=fs, thump=0.80,
                                   bright=0.90, noise_amt=0.45), t0)
    t = sec(dur)
    buf += 0.22 * np.sin(2.0 * np.pi * 82.0 * t) * np.exp(-t / 0.120)
    buf += 0.10 * np.sin(2.0 * np.pi * 164.0 * t) * np.exp(-t / 0.100)
    return finish(buf, 0.040)


def build_hu(rng):
    dur = 0.64
    buf = np.zeros(int(round(dur * SR)))
    add_at(buf, 1.45 * wood_hit(0.30, rng, f_scale=0.80, thump=1.00,
                                bright=1.00, noise_amt=0.50), 0.000)
    # G 大三和弦收束，短衰减，不做长旋律
    for freq, amp in ((392.00, 0.55), (493.88, 0.45), (587.33, 0.38), (783.99, 0.34)):
        add_at(buf, amp * bell_note(freq, dur, tau=0.240), 0.018)
    add_at(buf, 0.10 * bell_note(1567.98, 0.25, tau=0.100), 0.020)
    return finish(buf, 0.050)


def build_hu_multi(rng):
    dur = 0.66
    buf = np.zeros(int(round(dur * SR)))
    add_at(buf, 1.10 * wood_hit(0.35, rng, f_scale=0.72, thump=1.15,
                                bright=0.90, noise_amt=0.50), 0.000)
    add_at(buf, 0.75 * wood_hit(0.35, rng, f_scale=0.66, thump=1.00,
                                bright=0.80, noise_amt=0.45), 0.065)
    for freq, amp in ((196.00, 0.50), (293.66, 0.42), (392.00, 0.40),
                      (493.88, 0.34), (587.33, 0.30)):
        add_at(buf, amp * bell_note(freq, dur, tau=0.260), 0.015)
    t = sec(dur)
    buf += 0.16 * np.sin(2.0 * np.pi * 98.0 * t) * np.exp(-t / 0.250)
    return finish(buf, 0.050)


def build_score_tick(rng):
    dur = 0.03
    t = sec(dur)
    y = np.sin(2.0 * np.pi * 1700.0 * t) * np.exp(-t / 0.0055)
    y += 0.14 * np.sin(2.0 * np.pi * 3400.0 * t + 0.4) * np.exp(-t / 0.0030)
    y += 0.15 * fft_filter(white(dur, rng) * np.exp(-t / 0.0012), bp(2200.0, 6500.0))
    return finish(y, 0.006)


def build_win_sting(rng):
    dur = 1.50
    buf = np.zeros(int(round(dur * SR)))
    # G 大调五声音阶上行短句
    melody = ((0.000, 392.00, 0.72), (0.105, 493.88, 0.68),
              (0.210, 587.33, 0.65), (0.315, 783.99, 0.62))
    for t0, freq, amp in melody:
        add_at(buf, amp * mallet_note(freq, min(0.90, dur - t0), tau=0.320), t0)

    # 落点和弦
    for freq, amp in ((392.00, 0.26), (587.33, 0.22),
                      (783.99, 0.20), (987.77, 0.16)):
        add_at(buf, amp * mallet_note(freq, dur - 0.44, tau=0.600), 0.440)
    add_at(buf, 0.10 * mallet_note(196.00, dur - 0.44, tau=0.700), 0.440)
    return finish(buf, 0.080)


def build_button_tap(rng):
    dur = 0.045
    y = wood_hit(dur, rng, f_scale=1.15, thump=0.0, bright=0.65, noise_amt=0.35)
    y = fft_filter(y, lp(7000.0, 2))
    return finish(y, 0.008)


def build_invalid(rng):
    dur = 0.19
    buf = np.zeros(int(round(dur * SR)))
    # 两个下行软音 + 一记闷响，否定但不刺耳
    for t0, freq, amp in ((0.000, 196.00, 1.00), (0.075, 164.81, 0.90)):
        tt = sec(0.10)
        tone = (np.sin(2.0 * np.pi * freq * tt)
                + 0.22 * np.sin(2.0 * np.pi * 2.0 * freq * tt + 0.5))
        tone *= np.exp(-tt / 0.055) * np.minimum(1.0, tt / 0.004)
        tone = fft_filter(tone, lp(1100.0, 2))
        add_at(buf, 0.50 * amp * tone, t0)
    add_at(buf, 0.25 * wood_hit(0.08, rng, f_scale=0.70, thump=0.50,
                                bright=0.30, noise_amt=0.20), 0.000)
    return finish(buf, 0.020)


def build_countdown(rng):
    dur = 0.09
    t = sec(dur)
    env = np.minimum(1.0, t / 0.0025) * np.exp(-t / 0.030)
    y = (np.sin(2.0 * np.pi * 1046.50 * t)
         + 0.25 * np.sin(2.0 * np.pi * 2093.00 * t + 0.3)) * env
    y += 0.12 * fft_filter(white(dur, rng) * np.exp(-t / 0.0015), bp(2500.0, 9000.0))
    return finish(y, 0.010)


# name, builder, 目标响度（A 加权 50ms 最大 RMS, dBFS）
SPECS = (
    ("tile_pick", build_tile_pick, -25.0),
    ("tile_discard", build_tile_discard, -20.0),
    ("tile_draw", build_tile_draw, -23.0),
    ("tile_rub", build_tile_rub, -27.0),
    ("shuffle", build_shuffle, -21.0),
    ("deal", build_deal, -23.0),
    ("chi", build_chi, -21.0),
    ("peng", build_peng, -19.0),
    ("gang", build_gang, -18.0),
    ("hu", build_hu, -16.5),
    ("hu_multi", build_hu_multi, -15.5),
    ("score_tick", build_score_tick, -28.0),
    ("win_sting", build_win_sting, -17.5),
    ("button_tap", build_button_tap, -26.0),
    ("invalid", build_invalid, -23.0),
    ("countdown", build_countdown, -21.0),
)


# --------------------------------------------------------------------------
# 响度与输出
# --------------------------------------------------------------------------
def a_weight(x: np.ndarray) -> np.ndarray:
    """A 计权（频域施加），用于让响度更接近人耳感受。"""
    n = len(x)
    spec = np.fft.rfft(x)
    f = np.maximum(np.fft.rfftfreq(n, 1.0 / SR), 1e-6)
    num = (12194.0 ** 2) * (f ** 4)
    den = ((f ** 2 + 20.6 ** 2)
           * np.sqrt((f ** 2 + 107.7 ** 2) * (f ** 2 + 737.9 ** 2))
           * (f ** 2 + 12194.0 ** 2))
    gain = num / np.maximum(den, 1e-20)
    gain_db = 20.0 * np.log10(np.maximum(gain, 1e-12)) + 2.0
    return np.fft.irfft(spec * (10.0 ** (gain_db / 20.0)), n)


def loudness_db(y: np.ndarray) -> float:
    """A 加权 50ms 滑动窗口最大 RMS。"""
    a = a_weight(y)
    win = max(1, int(0.050 * SR))
    if len(a) <= win:
        return a2db(np.sqrt(np.mean(a * a)))
    c = np.concatenate(([0.0], np.cumsum(a * a)))
    sums = c[win:] - c[:-win]
    return a2db(np.sqrt(float(np.max(sums)) / win))


def peak_db(y: np.ndarray) -> float:
    return a2db(float(np.max(np.abs(y))))


def normalize(y: np.ndarray, target_db: float):
    """先按目标响度增益，再受峰值上限约束。"""
    before = loudness_db(y)
    gain = db2a(target_db - before)
    gain = min(gain, db2a(CEILING_DB - peak_db(y)))
    y = y * gain
    return y, loudness_db(y), peak_db(y)


def write_wav(path: Path, y: np.ndarray) -> None:
    pcm = np.clip(np.round(y * 32767.0), -32768, 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main() -> None:
    parser = argparse.ArgumentParser(description="生成麻将游戏原创音效")
    parser.add_argument("--out", default=str(Path(__file__).resolve().parent.parent),
                        help="输出目录（默认 assets/audio）")
    parser.add_argument("--only", default="", help="只生成指定音效，逗号分隔")
    args = parser.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    only = {s.strip() for s in args.only.split(",") if s.strip()}

    print(f"输出目录: {out_dir}")
    print(f"{'文件':<14} {'时长(s)':>8} {'大小(KB)':>9} {'峰值(dBFS)':>11} {'响度(dBFS)':>11}")
    print("-" * 62)

    total = 0.0
    for idx, (name, builder, target) in enumerate(SPECS):
        if only and name not in only:
            continue
        rng = np.random.default_rng(MASTER_SEED + idx * 7919)
        y = builder(rng)
        y, loud, peak = normalize(y, target)
        path = out_dir / f"{name}.wav"
        write_wav(path, y)

        dur = len(y) / SR
        size_kb = path.stat().st_size / 1024.0
        total += size_kb
        print(f"{name:<14} {dur:>8.3f} {size_kb:>9.1f} {peak:>11.2f} {loud:>11.2f}")

    print("-" * 62)
    print(f"合计约 {total:.1f} KB")


if __name__ == "__main__":
    main()
