#!/usr/bin/env python3
"""补齐 9 套普通话报牌音色的参考音频（VoxCPM2 Voice Design）。

已有 4 条（男青年/女青年/男中年/女中年）直接复用，只生成缺少的 5 条：
男老年、女老年、少年男孩、少女、男播音。

注意：标签必须用半角括号，全角“（）”会被 VoxCPM2 当作正文念出来。
输出：G:\\AI\\tts_refs_mandarin\\ref_<音色>.wav（48 kHz float32）
"""
from __future__ import annotations

import sys
import time
from pathlib import Path

import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).resolve().parent))
from voice_bank_common import REF_DIR, VOICES, ref_path  # noqa: E402

MODEL_DIR = Path(r"G:\AI\voxcpm\models\VoxCPM2")


def main() -> None:
    from voxcpm import VoxCPM

    REF_DIR.mkdir(parents=True, exist_ok=True)
    model = VoxCPM.from_pretrained(
        str(MODEL_DIR), load_denoiser=False, local_files_only=True,
        optimize=False, device="cuda",
    )
    sr = int(model.tts_model.sample_rate)
    print(f"[load] sr={sr}", flush=True)

    for voice in VOICES:
        path = ref_path(voice)
        if path.exists() and path.stat().st_size > 0:
            print(f"[skip] {voice['key']}", flush=True)
            continue
        t0 = time.time()
        wav = model.generate(
            text=f"({voice['design']}){voice['ref_text']}",
            cfg_value=2.0, inference_timesteps=10, retry_badcase=False,
        )
        wav = np.asarray(wav, dtype=np.float32).reshape(-1)
        sf.write(str(path), wav, sr, subtype="FLOAT")
        print(f"[ref] {voice['key']} {len(wav) / sr:.2f}s {time.time() - t0:.1f}s", flush=True)


if __name__ == "__main__":
    main()
