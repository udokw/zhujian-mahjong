#!/usr/bin/env python3
"""9 套普通话报牌人声 · Fun-CosyVoice3-0.5B 正式生成。

用法（必须在 CosyVoice 仓库根目录下跑）：
  G:\\AI\\cosyvoice\\venv\\Scripts\\python.exe tools\\sfx\\voice_bank_cosyvoice3.py
  可选：--install  自检通过后把 360 条复制进 app/src/androidMain/res/raw

处理约定（与需求一致）：
  - 不压缩、不变速、不变调、不重采样：保留模型原生 24 kHz；
  - 只做统一响度归一：按有声段 A 加权 RMS 推到 -18.0 dB(A)，纯增益；
  - 峰值上限 -0.2 dBFS，宁可不达标也不限幅/软削波；
  - 不裁剪首尾，保留模型输出的自然静音；
  - 交付位深用 16-bit PCM（SoundPool/AVAudioPlayer 的通用格式），不引入有损编码。

输出：
  assets/audio/voice_bank_mandarin/voice<套>_<clip>.wav
  assets/audio/voice_bank_mandarin/_stats.json / _check.json
"""
from __future__ import annotations

import argparse
import json
import math
import shutil
import sys
import time
from pathlib import Path

import numpy as np
import soundfile as sf
import torch

REPO = Path(r"G:\AI\cosyvoice\repo\CosyVoice")
sys.path.insert(0, str(REPO))
sys.path.insert(0, str(REPO / "third_party" / "Matcha-TTS"))
sys.path.insert(0, str(Path(__file__).resolve().parent))

from voice_bank_common import (  # noqa: E402
    CACHE_DIR, LICENSE, RAW_DIR, STAGE_DIR, VOICES, clip_name, jobs, ref_path,
)

MODEL_DIR = Path(r"G:\AI\cosyvoice\models\Fun-CosyVoice3-0.5B")
INSTRUCT = "You are a helpful assistant. 请用标准普通话表达。<|endofprompt|>"
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
    """只统计有声段（10ms 帧，-45 dBFS 门限）的 A 加权响度，避免静音拉低读数。"""
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


def normalize_gain(x: np.ndarray, sr: int) -> tuple[np.ndarray, float]:
    y = np.asarray(x, dtype=np.float64).reshape(-1).copy()
    peak = float(np.max(np.abs(y))) if y.size else 0.0
    if peak <= 1e-9:
        return y, 1.0
    ceiling = 10 ** (PEAK_CEILING_DB / 20.0)
    gain = min(10 ** ((TARGET_A_DB - active_level(y, sr)) / 20.0), ceiling / peak)
    return y * gain, gain


def tail_silence_ms(x: np.ndarray, sr: int) -> float:
    """从结尾往前数，连续低于 -45 dBFS 的时长（用于确认没有硬切掉尾音）。"""
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


def generate_one(cv, text: str, prompt: str) -> np.ndarray:
    chunks = []
    for out in cv.inference_instruct2(text, INSTRUCT, prompt, stream=False, text_frontend=False):
        chunks.append(out["tts_speech"].detach().cpu().numpy().reshape(-1))
    return np.concatenate(chunks).astype(np.float32) if chunks else np.zeros(0, np.float32)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--install", action="store_true", help="自检通过后复制到 res/raw")
    ap.add_argument("--force", action="store_true", help="忽略原始输出缓存")
    args = ap.parse_args()

    from cosyvoice.cli.cosyvoice import AutoModel

    STAGE_DIR.mkdir(parents=True, exist_ok=True)
    CACHE_DIR.mkdir(parents=True, exist_ok=True)

    t0 = time.time()
    cv = AutoModel(model_dir=str(MODEL_DIR))
    load_s = round(time.time() - t0, 2)
    sr = int(cv.sample_rate)
    print(f"[load] {load_s}s sr={sr}", flush=True)

    tasks = jobs()
    total = len(VOICES) * len(tasks)
    rows: list[dict] = []
    start = time.time()
    done = 0

    for voice in VOICES:
        prompt = str(ref_path(voice))
        if not Path(prompt).exists():
            raise SystemExit(f"缺少参考音频：{prompt}")
        for clip, text in tasks:
            name = clip_name(voice["set_id"], clip)
            raw_path = CACHE_DIR / name
            t1 = time.time()
            if raw_path.exists() and not args.force:
                raw, raw_sr = sf.read(str(raw_path), dtype="float32")
                raw = np.asarray(raw, dtype=np.float32).reshape(-1)
                infer_s = 0.0
            else:
                raw = np.zeros(0, np.float32)
                for _ in range(3):
                    raw = generate_one(cv, text, prompt)
                    if raw.size > 0 and len(raw) / sr * 1000.0 >= MIN_MS:
                        break
                raw_sr = sr
                sf.write(str(raw_path), raw, raw_sr, subtype="FLOAT")
                infer_s = round(time.time() - t1, 2)

            if raw.size == 0:
                rows.append(dict(file=name, set_id=voice["set_id"], voice=voice["desc"],
                                 clip=clip, text=text, ok=False, reason="empty"))
                print(f"[FAIL] {name} empty", flush=True)
                continue

            y, gain = normalize_gain(raw.astype(np.float64), raw_sr)
            out_path = STAGE_DIR / name
            sf.write(str(out_path), y.astype(np.float32), raw_sr, subtype="PCM_16")

            ms = len(y) / raw_sr * 1000.0
            a_db = active_level(y, raw_sr)
            peak_db = 20.0 * math.log10(float(np.max(np.abs(y))) + 1e-12)
            rows.append(dict(
                file=name, set_id=voice["set_id"], voice=voice["desc"],
                clip=clip, text=text, ok=True,
                sr=raw_sr, ms=round(ms, 1),
                a_db=round(a_db, 2), peak_db=round(peak_db, 2),
                gain_db=round(20.0 * math.log10(gain + 1e-12), 2),
                tail_silence_ms=tail_silence_ms(y, raw_sr),
                bytes=out_path.stat().st_size,
                infer_s=infer_s,
            ))
            done += 1
            if done % 10 == 0 or done == total:
                print(f"[gen] {done}/{total} {name} {ms:.0f}ms A={a_db:.1f} "
                      f"peak={peak_db:.1f} {infer_s:.1f}s", flush=True)

    vram = round(torch.cuda.max_memory_allocated() / 1024 ** 3, 2)
    meta = dict(
        model="Fun-CosyVoice3-0.5B", license=LICENSE, instruct=INSTRUCT,
        sample_rate=sr, subtype="PCM_16", load_s=load_s,
        total_gen_s=round(time.time() - start, 1), peak_vram_gb=vram,
        target_a_db=TARGET_A_DB, peak_ceiling_db=PEAK_CEILING_DB,
        loudness="active-only A-weighted RMS, pure gain, no resample/speed/pitch",
        clips=len(rows), voices=[dict(set_id=v["set_id"], key=v["key"], desc=v["desc"]) for v in VOICES],
        rows=rows,
    )
    (STAGE_DIR / "_stats.json").write_text(
        json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")

    check = run_check(rows)
    (STAGE_DIR / "_check.json").write_text(
        json.dumps(check, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"[check] missing={len(check['missing'])} zero={len(check['zero_bytes'])} "
          f"outlier={len(check['outliers'])} tail_cut={len(check['tail_suspect'])} "
          f"low_loudness={len(check['low_loudness'])}", flush=True)

    if args.install:
        if not check["passed"]:
            raise SystemExit("自检未通过，拒绝安装到 res/raw")
        RAW_DIR.mkdir(parents=True, exist_ok=True)
        for row in rows:
            shutil.copy2(STAGE_DIR / row["file"], RAW_DIR / row["file"])
        print(f"[install] copied {len(rows)} wav -> {RAW_DIR}", flush=True)

    print(f"[done] {done}/{total} clips, peak VRAM {vram} GB, "
          f"{time.time() - start:.0f}s total", flush=True)


def run_check(rows: list[dict]) -> dict:
    expected = {clip_name(v["set_id"], clip) for v in VOICES for clip, _ in jobs()}
    present = {r["file"] for r in rows if r.get("ok")}
    missing = sorted(expected - present)
    zero = sorted(r["file"] for r in rows if not (STAGE_DIR / r["file"]).exists()
                  or (STAGE_DIR / r["file"]).stat().st_size == 0)
    outliers = [dict(file=r["file"], ms=r["ms"]) for r in rows
                if r.get("ok") and not (MIN_MS <= r["ms"] <= MAX_MS)]
    # 结尾 10ms 仍有明显能量 → 可能被硬切；自然静音的 tail_silence_ms 应大于 0。
    tail_suspect = [dict(file=r["file"], tail_silence_ms=r["tail_silence_ms"])
                    for r in rows if r.get("ok") and r["tail_silence_ms"] <= 0]
    low = [dict(file=r["file"], a_db=r["a_db"], peak_db=r["peak_db"])
           for r in rows if r.get("ok") and r["a_db"] < TARGET_A_DB - 0.5]
    passed = not (missing or zero or outliers or tail_suspect)
    return dict(expected=len(expected), present=len(present), missing=missing,
                zero_bytes=zero, outliers=outliers, tail_suspect=tail_suspect,
                low_loudness=low, passed=passed)


if __name__ == "__main__":
    main()
