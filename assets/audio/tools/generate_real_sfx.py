#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
真实麻将录音采样 + 轻量物理整形，生成落牌 / 洗牌 / 摸牌 / 理牌 / 发牌音效。

素材（见同目录 SOURCES.md）：
  1. Freesound #156668 "Mahjong.wav" by RTB45 — CC BY 4.0
     真实牌局现场，取 6 段孤立的单张牌落桌瞬态。
  2. Freesound #149041 "Playing Mahjong at Apartment" by al.barbosa — CC0
     近场牌桌录音，取连续牌面摩擦（洗牌）与抽牌摩擦。

处理原则：
- 只做“切段 + 去低频轰鸣 + 截断房间拖尾 + 等响归一”，不做变调、不做波形复制，
  每一套落牌都是不同的真实录音片段。
- 洗牌用真实连续摩擦段做底，再叠加真实的单张落牌做“起手/收尾”，
  用增益包络做出“起手 → 加速 → 收尾”，而不是循环或一声闷响。
- 摸牌 = 真实摩擦段（做短促包络）+ 真实轻磕；理牌 = 若干真实细碎敲击随机排布。

依赖：numpy、soundfile（仅生成阶段）。
用法：
    python assets/audio/tools/generate_real_sfx.py
    python assets/audio/tools/generate_real_sfx.py --out "g:\\AI\\麻将\\assets\\audio"
"""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 44100
TOOLS = Path(__file__).resolve().parent
ASSETS = TOOLS.parent
SOURCES = ASSETS / "sources"
RAW = ASSETS.parents[1] / "app" / "src" / "androidMain" / "res" / "raw"


# ------------------------------------------------------------------ 基础工具

def load_mono(path: Path) -> np.ndarray:
    data, sr = sf.read(str(path), dtype="float32", always_2d=True)
    if sr != SR:
        raise SystemExit(f"{path.name} 采样率 {sr}，本脚本按 {SR} 处理，请先重采样")
    return data.mean(axis=1).astype(np.float64)


def fft_filter(x: np.ndarray, lo: float | None, hi: float | None, order: int = 2) -> np.ndarray:
    n = len(x)
    nfft = 1 << (2 * n - 1).bit_length()
    spec = np.fft.rfft(x, nfft)
    f = np.fft.rfftfreq(nfft, 1.0 / SR)
    if lo:
        spec *= 1.0 / np.sqrt(1.0 + (lo / np.maximum(f, 1e-6)) ** (2 * order))
    if hi:
        spec *= 1.0 / np.sqrt(1.0 + (f / hi) ** (2 * order))
    return np.fft.irfft(spec, nfft)[:n]


def fade(x: np.ndarray, ms_in: float, ms_out: float) -> np.ndarray:
    y = x.copy()
    a = min(int(SR * ms_in / 1000), len(y) // 2)
    b = min(int(SR * ms_out / 1000), len(y) // 2)
    if a > 0:
        y[:a] *= np.sin(np.linspace(0, np.pi / 2, a)) ** 2
    if b > 0:
        y[-b:] *= np.cos(np.linspace(0, np.pi / 2, b)) ** 2
    return y


def peak_normalize(x: np.ndarray, peak_db: float) -> np.ndarray:
    m = float(np.max(np.abs(x)))
    if m <= 0:
        return x
    return x * (10 ** (peak_db / 20.0) / m)


def cut_at(src: np.ndarray, t: float, pre: float, post: float) -> tuple[np.ndarray, int]:
    """按时间点切一段，返回 (片段, 峰值在片段中的位置)。"""
    a = max(0, int((t - pre) * SR))
    b = min(len(src), a + int((pre + post) * SR))
    return src[a:b].copy(), int(pre * SR)


def shorten_tail(x: np.ndarray, onset: int, hold_ms: float, tau_ms: float) -> np.ndarray:
    """保留真实瞬态与牌体，随后快速收住，去掉房间拖尾（落地即收）。"""
    y = x.copy()
    hold = int(SR * hold_ms / 1000)
    tau = SR * tau_ms / 1000
    idx = np.arange(len(y)) - onset
    env = np.ones(len(y))
    after = idx > hold
    env[after] = np.exp(-(idx[after] - hold) / tau)
    y *= env
    return y


def prep_clack(x: np.ndarray, onset: int, hold_ms: float = 42.0, tau_ms: float = 13.0) -> np.ndarray:
    y = x - float(np.mean(x))
    y = fft_filter(y, lo=110.0, hi=16500.0, order=2)
    y = shorten_tail(y, onset, hold_ms, tau_ms)
    y = fade(y, 0.15, 6.0)
    # 裁掉衰减后的静音尾巴，控制资源体积
    keep = min(len(y), onset + int(SR * (hold_ms + 5.5 * tau_ms) / 1000) + int(0.006 * SR))
    return y[:keep]


# ------------------------------------------------------------------ 落牌 5 套

# 每项：音源、时间点、截取长度、收尾保持时长（ms）、衰减时间常数（ms）、目标 RMS
DISCARDS = [
    ("rtb", 133.566, 0.200, 46.0, 14.0, -25.0),  # 厚实、带桌面低频，最像“拍”在桌上
    ("rtb", 40.566, 0.160, 38.0, 10.0, -26.0),   # 极干净的一记脆响，信噪比最高
    ("rtb", 108.256, 0.150, 38.0, 11.0, -27.0),  # 明亮、颗粒清楚
    ("rtb", 97.059, 0.150, 36.0, 10.0, -26.5),   # 高频突出，颗粒感
    ("rtb", 13.731, 0.200, 40.0, 13.0, -26.0),   # 偏低沉，像叠在牌堆上
]

# 理牌用的真实细碎敲击（RTB45 中较孤立的小声）
SORT_TICKS = [109.108, 87.744, 131.726, 132.726, 144.817, 98.743, 147.381, 107.990]


def align_peak(x: np.ndarray, t: float) -> float:
    """把检测到的时刻对齐到局部样本峰值，避免切歪导致起音被削。"""
    a = max(0, int((t - 0.012) * SR))
    b = min(len(x), int((t + 0.030) * SR))
    if b <= a:
        return t
    return (a + int(np.argmax(np.abs(x[a:b])))) / SR


def match_loudness(x: np.ndarray, target_rms_db: float, peak_ceiling_db: float = -1.2) -> np.ndarray:
    """按 RMS 对齐响度，同时保证峰值不削顶。"""
    rms = float(np.sqrt((x ** 2).mean())) + 1e-12
    gain = 10 ** (target_rms_db / 20.0) / rms
    peak = float(np.max(np.abs(x))) * gain
    ceiling = 10 ** (peak_ceiling_db / 20.0)
    if peak > ceiling:
        gain *= ceiling / peak
    return x * gain


def make_discards(rec: dict[str, np.ndarray]) -> list[np.ndarray]:
    out = []
    for key, t, dur, hold, tau, rms_db in DISCARDS:
        src = rec[key]
        t = align_peak(src, t)
        seg, onset = cut_at(src, t, 0.003, dur)
        y = prep_clack(seg, onset, hold, tau)
        out.append(match_loudness(y, rms_db))
    return out


# ------------------------------------------------------------------ 洗牌

def make_shuffle(src: np.ndarray, rtb: np.ndarray, discards: list[np.ndarray]) -> np.ndarray:
    """1.9s：真实连续摩擦为底，增益做出起手→加速→收尾，末尾补一记真实落牌。"""
    total = 1.95
    bed_dur = 1.72
    a = int(25.05 * SR)
    bed = src[a: a + int(bed_dur * SR)].copy()
    bed -= float(np.mean(bed))
    bed = fft_filter(bed, lo=180.0, hi=16000.0, order=2)

    t = np.arange(len(bed)) / SR
    # 起手 0.0-0.30 渐入；0.30-0.95 加速（增益抬升）；0.95-1.45 维持；1.45-1.72 收尾
    env = np.ones(len(bed))
    ramp_in = int(0.30 * SR)
    env[:ramp_in] = np.linspace(0.0, 0.62, ramp_in) ** 1.3
    acc = (t >= 0.30) & (t < 0.95)
    env[acc] = 0.62 + 0.38 * (t[acc] - 0.30) / 0.65
    env[(t >= 0.95) & (t < 1.45)] = 1.0
    tail = t >= 1.45
    env[tail] = np.linspace(1.0, 0.0, int(tail.sum())) ** 1.5
    bed *= env

    out = np.zeros(int(total * SR))
    out[: len(bed)] += bed

    # 起手：两下真实的单张牌被推入牌堆
    for offset, gain, idx in ((0.045, 0.42, 4), (0.145, 0.30, 2)):
        seg = discards[idx][: int(0.11 * SR)]
        at = int(offset * SR)
        out[at: at + len(seg)] += seg[: max(0, len(out) - at)] * gain

    # 收尾：最后一张牌落下，声音清晰可辨
    seg = discards[0][: int(0.16 * SR)]
    at = int(1.74 * SR)
    out[at: at + len(seg)] += seg[: max(0, len(out) - at)] * 0.72

    out = fade(out, 0.5, 8.0)
    return peak_normalize(out, -2.2)


# ------------------------------------------------------------------ 摸牌

def make_draw(src: np.ndarray, discards: list[np.ndarray]) -> np.ndarray:
    """0.17s：牌从牌墙抽出时与邻牌擦过的摩擦 + 落定的轻磕。"""
    total = 0.17
    a = int(24.36 * SR)
    rub = src[a: a + int(0.105 * SR)].copy()
    rub -= float(np.mean(rub))
    rub = fft_filter(rub, lo=650.0, hi=8500.0, order=2)
    n = len(rub)
    t = np.arange(n) / SR
    rub *= np.minimum(1.0, t / 0.016) * np.exp(-t / 0.042)
    rub *= 0.55 / (np.max(np.abs(rub)) + 1e-9)
    out = np.zeros(int(total * SR))
    out[:n] += rub

    tap = discards[3][: int(0.075 * SR)].copy()
    tap = fft_filter(tap, lo=320.0, hi=15000.0, order=2)
    tap *= 0.30 / (np.max(np.abs(tap)) + 1e-9)
    at = int(0.052 * SR)
    out[at: at + len(tap)] += tap[: max(0, len(out) - at)]

    out = fade(out, 0.4, 9.0)
    return peak_normalize(out, -4.6)


# ------------------------------------------------------------------ 理牌

def make_sort(rtb: np.ndarray) -> np.ndarray:
    """0.95s：手里挪牌、牌与牌轻碰的细碎声，间隔与力度随机。"""
    total = 0.95
    rng = np.random.default_rng(20261108)
    out = np.zeros(int(total * SR))
    t = 0.03
    i = 0
    while t < total - 0.09:
        source = SORT_TICKS[i % len(SORT_TICKS)]
        seg, onset = cut_at(rtb, align_peak(rtb, source), 0.002, 0.075)
        seg = prep_clack(seg, onset, hold_ms=26.0, tau_ms=7.5)
        seg = shorten_tail(seg, onset, 14.0, 6.0)
        gain = 0.16 + 0.30 * rng.random()
        # 偶尔两下几乎同时，像手指把牌推进去
        if rng.random() < 0.16:
            gain *= 0.6
        seg *= gain / (np.max(np.abs(seg)) + 1e-9)
        at = int(t * SR)
        out[at: at + len(seg)] += seg[: max(0, len(out) - at)]
        i += 1
        t += 0.038 + rng.random() * 0.072
    out = fade(out, 0.3, 14.0)
    return peak_normalize(out, -7.5)


# ------------------------------------------------------------------ 发牌

def make_deal(rtb: np.ndarray) -> np.ndarray:
    seg, onset = cut_at(rtb, 87.744, 0.002, 0.075)
    seg = prep_clack(seg, onset, hold_ms=22.0, tau_ms=6.5)
    seg = shorten_tail(seg, onset, 12.0, 5.5)
    seg = fade(seg, 0.1, 7.0)
    return peak_normalize(seg, -8.0)


# ------------------------------------------------------------------ 输出

def write(path: Path, x: np.ndarray) -> None:
    y = np.clip(x, -1.0, 1.0)
    sf.write(str(path), y.astype(np.float32), SR, subtype="PCM_16")
    print(f"  {path.name:20s} {len(y)/SR:5.2f}s  {path.stat().st_size/1024:6.1f}KB")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=str(ASSETS))
    ap.add_argument("--raw", default=str(RAW))
    args = ap.parse_args()
    out_dirs = [Path(args.out), Path(args.raw)]

    rtb = load_mono(SOURCES / "mahjong_rtb45.mp3")
    apt = load_mono(SOURCES / "fs_149041.mp3")
    rec = {"rtb": rtb}

    print("生成落牌 5 套…")
    discards = make_discards(rec)
    print("生成洗牌 / 摸牌 / 理牌 / 发牌…")
    shuffle = make_shuffle(apt, rtb, discards)
    draw = make_draw(apt, discards)
    sort = make_sort(rtb)
    deal = make_deal(rtb)

    for d in out_dirs:
        d.mkdir(parents=True, exist_ok=True)
        print(f"写入 {d}")
        for i, x in enumerate(discards, 1):
            write(d / f"tile_discard_{i}.wav", x)
        write(d / "shuffle.wav", shuffle)
        write(d / "tile_draw.wav", draw)
        write(d / "tile_sort.wav", sort)
        write(d / "deal.wav", deal)


if __name__ == "__main__":
    main()
