#!/usr/bin/env python3
"""对已生成的 9 套报牌人声做二次响度收敛与自检（纯增益，不改波形/采样率/时长）。

背景：单次增益后，部分静音帧会跨过 -45 dBFS 门限被计入有声段，导致实测响度
比目标低 3～5 dB。这里迭代 3～4 次，只调幅度，直到接近 -18.0 dB(A)；
若峰值会超过 -0.2 dBFS 则停止增益（宁可不达标也不削波）。

自检输出 _check.json：
  - missing / zero_bytes / outliers：缺条、空文件、时长离群
  - abrupt_tail：结尾 10ms 电平接近峰值，疑似被硬切（需试听确认）
  - no_tail_silence：模型自然收尾、没有尾部静音（不一定是问题，单列）
"""
from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parent))
from voice_bank_common import STAGE_DIR, VOICES, clip_name, jobs  # noqa: E402

TARGET_A_DB = -18.0
PEAK_CEILING_DB = -0.2
SIL_DB = -45.0
MIN_MS = 120.0
MAX_MS = 3000.0


def a_weighted_db(x: np.ndarray, sr: int) -> float:
    x = np.asarray(x, dtype=np.float64).reshape(-1)
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1.0 / sr)
    f2 = np.maximum(freqs, 1e-6) ** 2
    num = (12194.0 ** 2) * f2 * f2
    den = (f2 + 20.6 ** 2) * np.sqrt((f2 + 107.7 ** 2) * (f2 + 737.9 ** 2)) * (f2 + 12194.0 ** 2)
    weight = (num / den) * (10 ** (2.0 / 20))
    y = np.fft.irfft(spec * weight, len(x))
    return 20.0 * math.log10(float(np.sqrt(np.mean(y ** 2))) + 1e-12)


def active_level(x: np.ndarray, sr: int) -> float:
    w = max(1, int(0.01 * sr))
    n = len(x) // w
    if n == 0:
        return a_weighted_db(x, sr)
    rms = np.array([np.sqrt(np.mean(x[i * w:(i + 1) * w] ** 2)) for i in range(n)])
    keep = rms > 10 ** (SIL_DB / 20.0)
    if not keep.any():
        return a_weighted_db(x, sr)
    idx = np.concatenate([np.arange(i * w, (i + 1) * w) for i in range(n) if keep[i]])
    return a_weighted_db(x[idx], sr)


def tail_silence_ms(x: np.ndarray, sr: int) -> float:
    w = max(1, int(0.01 * sr))
    n = len(x) // w
    count = 0
    for i in range(n - 1, -1, -1):
        seg = x[i * w:(i + 1) * w]
        if np.sqrt(np.mean(seg ** 2)) < 10 ** (SIL_DB / 20.0):
            count += 1
        else:
            break
    return round(count * 10.0, 1)


def main() -> None:
    stats_path = STAGE_DIR / "_stats.json"
    meta = json.loads(stats_path.read_text(encoding="utf-8"))
    rows = {r["file"]: r for r in meta["rows"]}
    ceiling = 10 ** (PEAK_CEILING_DB / 20.0)

    for p in sorted(STAGE_DIR.glob("*.wav")):
        y, sr = sf.read(str(p), dtype="float32")
        y = np.asarray(y, dtype=np.float64).reshape(-1)
        for _ in range(4):
            a = active_level(y, sr)
            diff = TARGET_A_DB - a
            if abs(diff) < 0.08:
                break
            peak = float(np.max(np.abs(y))) if y.size else 0.0
            if peak <= 1e-9:
                break
            gain = min(10 ** (diff / 20.0), ceiling / peak)
            if abs(20.0 * math.log10(gain)) < 0.02:
                break
            y = y * gain
        sf.write(str(p), y.astype(np.float32), sr, subtype="PCM_16")

        a2 = active_level(y, sr)
        pk2 = 20.0 * math.log10(float(np.max(np.abs(y))) + 1e-12)
        row = rows.get(p.name, {})
        row.update(a_db=round(a2, 2), peak_db=round(pk2, 2),
                   tail_silence_ms=tail_silence_ms(y, sr), bytes=p.stat().st_size)
        rows[p.name] = row

    meta["rows"] = list(rows.values())
    meta["norm"] = dict(target_a_db=TARGET_A_DB, peak_ceiling_db=PEAK_CEILING_DB,
                        method="iterative pure gain, no resample/speed/pitch/trim")
    stats_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

    expected = {clip_name(v["set_id"], clip) for v in VOICES for clip, _ in jobs()}
    missing = sorted(expected - set(rows))
    zero = sorted(name for name in expected
                  if not (STAGE_DIR / name).exists() or (STAGE_DIR / name).stat().st_size == 0)
    outliers = [dict(file=r["file"], ms=r["ms"]) for r in rows.values()
                if r.get("ms") is not None and not (MIN_MS <= r["ms"] <= MAX_MS)]
    low = [dict(file=r["file"], a_db=r["a_db"], peak_db=r["peak_db"])
           for r in rows.values() if r.get("a_db", 0) < TARGET_A_DB - 0.5]
    no_tail = [r["file"] for r in rows.values() if r.get("tail_silence_ms", 0) <= 0]
    abrupt = []
    for name in no_tail:
        y, sr = sf.read(str(STAGE_DIR / name), dtype="float32")
        y = np.asarray(y, dtype=np.float32).reshape(-1)
        w = max(1, int(0.01 * sr))
        last_db = 20.0 * math.log10(float(np.sqrt(np.mean(y[-w:] ** 2))) + 1e-9)
        peak_db = 20.0 * math.log10(float(np.max(np.abs(y))) + 1e-9)
        if last_db > peak_db - 6.0:
            abrupt.append(dict(file=name, last10ms_db=round(last_db, 1),
                               peak_db=round(peak_db, 1)))
    check = dict(expected=len(expected), present=len(rows), missing=missing,
                 zero_bytes=zero, outliers=outliers, low_loudness=low,
                 no_tail_silence=no_tail, abrupt_tail=abrupt,
                 passed=not (missing or zero or outliers or abrupt))
    (STAGE_DIR / "_check.json").write_text(json.dumps(check, ensure_ascii=False, indent=2),
                                           encoding="utf-8")
    print(f"[renorm] {len(rows)} clips | low={len(low)} no_tail={len(no_tail)} "
          f"abrupt={len(abrupt)} missing={len(missing)} zero={len(zero)} "
          f"outlier={len(outliers)}", flush=True)
    if low:
        print("[renorm] worst loudness:", sorted((r["a_db"], r["file"]) for r in low)[:5], flush=True)
    if abrupt:
        for r in abrupt:
            print("[abrupt]", r, flush=True)


if __name__ == "__main__":
    main()
