#!/usr/bin/env python3
"""麻将报牌语音生成器（VoxCPM2 本地推理，9 套中国方言）。

与 generate_audio.py 的分工：
  - generate_audio.py：用 numpy 合成敲桌/洗牌/结算等音效（不需要 GPU）。
  - 本脚本：用 VoxCPM2 在本地 GPU 生成 voice1..voice9 的 34 张牌名 + 6 个动作，
    写入 app/src/androidMain/res/raw/voiceN_*.wav，命名与播放层
    （AndroidAudio.kt / GameAudio.kt）完全一致。

方言与 voiceN 的对应（VoxCPM2 官方支持的 9 种中文方言）：
  voice1 四川话   voice2 广东话   voice3 吴语
  voice4 东北话   voice5 河南话   voice6 陕西话
  voice7 山东话   voice8 天津话   voice9 闽南话

音色一致性靠“参考音频克隆”：每套先用 Voice Design 生成一段 3~5 秒的方言参考
音频，再用 reference_wav_path 克隆出该套的 40 条短词，保证同一套 40 条是同一个
人的音色；VoxCPM2 不做参考音频时每次都是随机音色。

依赖（见 README Installation）：
  Python >=3.10,<3.13；PyTorch >=2.5.0；CUDA >=12.0；pip install voxcpm
  权重：openbmb/VoxCPM2（约 4.6 GB，Apache-2.0）

用法（必须在装了 torch 的虚拟环境里跑）：
  G:\\AI\\voxcpm\\venv\\Scripts\\python.exe tools\\sfx\\generate_voice_voxcpm.py
  可选参数：--sets 1,2      只生成指定套
            --force         忽略已缓存的原始输出，重新调用模型
            --refs-only     只生成 9 段参考音频
            --post-only     不跑模型，仅用已缓存的原始输出重新做后处理
"""

from __future__ import annotations

import argparse
import csv
import math
import time
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 44100
ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / "app" / "src" / "androidMain" / "res" / "raw"
CACHE = Path(r"G:\AI\voxcpm\raw")
REF_DIR = Path(r"G:\AI\voxcpm\refs")
MODEL_DIR = Path(r"G:\AI\voxcpm\models\VoxCPM2")

# 目标响度：与旧 edge-tts 版实测区间（A 加权 -15.3 ~ -20.1 dB）取中间值，
# 9 套统一到同一响度，避免某套特别响。
TARGET_A_DB = -16.5
PEAK_CEILING_DB = -0.9
MAX_CLIP_MS = 550.0
# 目标语速：旧 edge-tts 版单条平均约 330 ms。VoxCPM2 的 2 音节短词自然语速约
# 500~650 ms，靠“语速快”的指令无法压缩（实测同样 640 ms），只能在后期做保音高变速。
TARGET_CLIP_MS = 360.0
MAX_STRETCH = 1.8
# 明显异常的输出（几乎没声 / 拖到 1 秒以上，多半是多念或拖长）直接重生成。
BAD_MIN_MS = 200.0
BAD_MAX_MS = 950.0

# 引擎牌序号：0..8 万，9..17 筒，18..26 条，27 东 28 南 29 西 30 北 31 中 32 发 33 白
TILE_WORDS = [
    "一万", "二万", "三万", "四万", "五万", "六万", "七万", "八万", "九万",
    "一筒", "二筒", "三筒", "四筒", "五筒", "六筒", "七筒", "八筒", "九筒",
    "一条", "二条", "三条", "四条", "五条", "六条", "七条", "八条", "九条",
    "东风", "南风", "西风", "北风", "红中", "发财", "白板",
]
ACTION_KEYS = ["chi", "peng", "gang", "hu", "zimo", "liuju"]
ACTION_WORDS = {
    "chi": "吃",
    "peng": "碰",
    "gang": "杠",
    "hu": "胡",
    "zimo": "自摸",
    "liuju": "流局",
}

# 每套：voiceN, 指令里的方言名, 音色描述, 参考句, 牌名/动作改写字（None 表示沿用普通话写法）
DIALECTS = [
    dict(
        set_id=1,
        dialect="四川话",
        voice="中年男性，嗓音浑厚，语速偏快",
        ref_text="今天天气巴适得很，我们几个来打两圈麻将，要得嘛。",
    ),
    dict(
        set_id=2,
        dialect="广东话",
        voice="中年男性，低沉，语气干脆",
        ref_text="今日天气几好，我哋几个一齐打返几圈麻雀，好冇？",
        tile_words=[
            "一萬", "二萬", "三萬", "四萬", "五萬", "六萬", "七萬", "八萬", "九萬",
            "一筒", "二筒", "三筒", "四筒", "五筒", "六筒", "七筒", "八筒", "九筒",
            "一條", "二條", "三條", "四條", "五條", "六條", "七條", "八條", "九條",
            "東風", "南風", "西風", "北風", "紅中", "發財", "白板",
        ],
        action_words={
            "chi": "食", "peng": "碰", "gang": "槓",
            "hu": "糊", "zimo": "自摸", "liuju": "流局",
        },
    ),
    dict(
        set_id=3,
        dialect="吴语",
        voice="青年女性，软糯，语速偏快",
        ref_text="今朝天气蛮好，阿拉几个人一道搓两圈麻将，好伐？",
    ),
    dict(
        set_id=4,
        dialect="东北话",
        voice="中年女性，爽朗，语速快",
        ref_text="今儿个天儿挺好，咱几个整两圈麻将，咋样？",
    ),
    dict(
        set_id=5,
        dialect="河南话",
        voice="青年男性，明亮，语速快",
        ref_text="今儿个天不赖，咱几个来两圈麻将，中不中？",
    ),
    dict(
        set_id=6,
        dialect="陕西话",
        voice="中年男性，粗犷，语气重",
        ref_text="今儿个天气嘹得很，咱几个来两圈麻将，成不成？",
    ),
    dict(
        set_id=7,
        dialect="山东话",
        voice="中年女性，沉稳，吐字清楚",
        ref_text="今儿个天挺好，咱几个来两圈麻将，行不行？",
    ),
    dict(
        set_id=8,
        dialect="天津话",
        voice="青年男性，轻快，带笑意",
        ref_text="今儿个天儿不错，咱几个来两圈麻将，行嘛？",
    ),
    dict(
        set_id=9,
        dialect="闽南话",
        voice="中年女性，温和，语速偏快",
        ref_text="今仔日天气真好，咱几个来拍两圈麻雀，好无？",
    ),
]


# ---------------------------------------------------------------- 音频后处理

def fade_edges(x: np.ndarray, ms_in: float = 3.0, ms_out: float = 12.0) -> None:
    n = len(x)
    a = min(int(SR * ms_in / 1000), n // 2)
    b = min(int(SR * ms_out / 1000), n // 2)
    if a > 0:
        x[:a] *= np.linspace(0.0, 1.0, a) ** 1.3
    if b > 0:
        x[-b:] *= np.linspace(1.0, 0.0, b) ** 1.3


def trim_speech(x: np.ndarray, sr: int, lead_ms: float = 8.0, tail_ms: float = 16.0) -> np.ndarray:
    env = np.abs(x)
    peak = float(env.max()) or 1.0
    idx = np.where(env > max(peak * 0.07, 3e-4))[0]
    if len(idx) == 0:
        return x
    a = max(0, idx[0] - int(sr * lead_ms / 1000))
    b = min(len(x), idx[-1] + int(sr * tail_ms / 1000))
    y = x[a:b].copy()
    fade_edges(y)
    return y


def resample(x: np.ndarray, sr_in: int, sr_out: int = SR) -> np.ndarray:
    if sr_in == sr_out:
        return x
    from scipy.signal import resample_poly

    g = math.gcd(sr_in, sr_out)
    return resample_poly(x, sr_out // g, sr_in // g)


def a_weighted_db(x: np.ndarray, sr: int = SR) -> float:
    """A 加权 RMS（dBFS），用来做等响归一。"""
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1.0 / sr)
    f2 = np.maximum(freqs, 1e-6) ** 2
    num = (12194.0 ** 2) * f2 * f2
    den = (f2 + 20.6 ** 2) * np.sqrt((f2 + 107.7 ** 2) * (f2 + 737.9 ** 2)) * (f2 + 12194.0 ** 2)
    weight = (num / den) * (10 ** (2.0 / 20))
    y = np.fft.irfft(spec * weight, len(x))
    return 20.0 * math.log10(float(np.sqrt(np.mean(y ** 2))) + 1e-12)


def normalize_equal_loudness(x: np.ndarray) -> np.ndarray:
    """对齐 A 加权响度；峰值超限时用 tanh 软限幅，而不是整体回退音量。

    旧做法“超峰值就整体降音量”会让某些套低 2~3 dB（实测 8 号套 -19.4），
    9 套响度不齐；这里改为软限幅 + 迭代补增益，既守住峰值也守住等响。
    """
    ceiling = 10 ** (PEAK_CEILING_DB / 20.0)
    y = x.copy()
    for _ in range(3):
        cur = a_weighted_db(y)
        y = y * (10 ** ((TARGET_A_DB - cur) / 20.0))
        peak = float(np.max(np.abs(y))) or 1.0
        if peak > ceiling:
            y = ceiling * np.tanh(y / ceiling)
    return y


def compress_to(x: np.ndarray, rate: float) -> np.ndarray:
    """时长压缩：优先用 librosa 的相位声码器，保持音高不变。"""
    import librosa

    return librosa.effects.time_stretch(y=x.astype(np.float32), rate=float(rate)).astype(np.float64)


def postprocess(x: np.ndarray, sr_in: int) -> tuple[np.ndarray, dict]:
    y = resample(x.astype(np.float64), sr_in)
    y = trim_speech(y, SR)
    dur = len(y) / SR
    raw_ms = dur * 1000.0
    stretched = 1.0
    if dur > (TARGET_CLIP_MS + 30.0) / 1000.0:
        stretched = min(dur / (TARGET_CLIP_MS / 1000.0), MAX_STRETCH)
        y = compress_to(y, stretched)
        y = trim_speech(y, SR)
        dur = len(y) / SR
        if dur > MAX_CLIP_MS / 1000.0:
            y = y[: int(SR * MAX_CLIP_MS / 1000.0)].copy()
            fade_edges(y, 1.0, 6.0)
            dur = len(y) / SR
    y = normalize_equal_loudness(y)
    info = {
        "raw_ms": round(raw_ms, 1),
        "ms": round(dur * 1000.0, 1),
        "stretch": round(stretched, 3),
        "a_db": round(a_weighted_db(y), 2),
        "peak_db": round(20.0 * math.log10(float(np.max(np.abs(y))) + 1e-12), 2),
    }
    return y, info


def write_wav(path: Path, x: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    pcm = np.clip(x * 32767.0, -32768, 32767).astype("<i2")
    import wave

    with wave.open(str(path), "wb") as f:
        f.setnchannels(1)
        f.setsampwidth(2)
        f.setframerate(SR)
        f.writeframes(pcm.tobytes())


# ---------------------------------------------------------------- VoxCPM2

def load_model():
    from voxcpm import VoxCPM

    if not MODEL_DIR.exists():
        raise SystemExit(f"模型目录不存在：{MODEL_DIR}（先用 ModelScope 下载 OpenBMB/VoxCPM2）")
    print(f"加载 VoxCPM2：{MODEL_DIR}", flush=True)
    model = VoxCPM.from_pretrained(
        str(MODEL_DIR),
        load_denoiser=False,
        local_files_only=True,
        optimize=False,
        device="cuda",
    )
    print(f"  sample_rate={model.tts_model.sample_rate}  device=cuda", flush=True)
    return model


def gen_text(model, text: str, ref: Path | None):
    kwargs = dict(cfg_value=2.0, inference_timesteps=10, retry_badcase=False)
    if ref is not None:
        kwargs["reference_wav_path"] = str(ref)
    wav = model.generate(text=text, **kwargs)
    return np.asarray(wav, dtype=np.float64).reshape(-1)


def build_refs(model, sets: list[dict], force: bool) -> None:
    REF_DIR.mkdir(parents=True, exist_ok=True)
    for d in sets:
        path = REF_DIR / f"voice{d['set_id']}.wav"
        if path.exists() and not force:
            print(f"  参考音频已存在：{path.name}", flush=True)
            continue
        text = f"({d['dialect']}，{d['voice']}){d['ref_text']}"
        t0 = time.time()
        wav = gen_text(model, text, None)
        sr = model.tts_model.sample_rate
        y = trim_speech(resample(wav, sr), SR)
        write_wav(path, y)
        print(f"  参考音频 voice{d['set_id']} {d['dialect']}：{len(y) / SR:.2f}s"
              f"（{time.time() - t0:.1f}s）", flush=True)


def build_clips(model, sets: list[dict], force: bool) -> list[dict]:
    CACHE.mkdir(parents=True, exist_ok=True)
    rows: list[dict] = []
    total = sum(len(TILE_WORDS) + len(ACTION_KEYS) for _ in sets)
    done = 0
    for d in sets:
        set_id = d["set_id"]
        ref = REF_DIR / f"voice{set_id}.wav"
        tiles = d.get("tile_words") or TILE_WORDS
        actions = d.get("action_words") or ACTION_WORDS
        instruction = f"({d['dialect']}，语速快，短促)"
        jobs = [(f"tile_{i:02d}", w) for i, w in enumerate(tiles)]
        jobs += [(key, actions[key]) for key in ACTION_KEYS]
        for key, word in jobs:
            raw_path = CACHE / f"voice{set_id}_{key}.wav"
            t0 = time.time()
            wav, sr = None, None
            if raw_path.exists() and not force:
                wav, sr = sf.read(str(raw_path), dtype="float64")
                dur_ms = len(wav) / sr * 1000.0
                if not (BAD_MIN_MS <= dur_ms <= BAD_MAX_MS):
                    print(f"    异常样本 voice{set_id}_{key} {dur_ms:.0f}ms，重新生成", flush=True)
                    wav, sr = None, None
            if wav is None:
                last_exc = None
                best = None
                for _ in range(3):
                    try:
                        cand = gen_text(model, f"{instruction}{word}。", ref)
                        cand_sr = model.tts_model.sample_rate
                        dur_ms = len(cand) / cand_sr * 1000.0
                        if BAD_MIN_MS <= dur_ms <= BAD_MAX_MS:
                            best = (cand, cand_sr)
                            break
                        # 都不合格时保留最接近 550 ms 的一条
                        if best is None or abs(dur_ms - 550.0) < abs(
                            len(best[0]) / best[1] * 1000.0 - 550.0
                        ):
                            best = (cand, cand_sr)
                    except Exception as exc:  # noqa: BLE001
                        last_exc = exc
                        print(f"    重试 {set_id}/{key}：{exc}", flush=True)
                        time.sleep(1.0)
                if best is None:
                    raise RuntimeError(f"生成失败 voice{set_id}_{key}: {last_exc}")
                wav, sr = best
                sf.write(str(raw_path), wav, sr)
            y, info = postprocess(wav, sr)
            write_wav(RAW / f"voice{set_id}_{key}.wav", y)
            rows.append(dict(
                set_id=set_id,
                dialect=d["dialect"],
                clip=key,
                word=word,
                **info,
                gen_s=round(time.time() - t0, 2),
            ))
            done += 1
            if done % 10 == 0 or done == total:
                print(f"  [{done}/{total}] voice{set_id}_{key} "
                      f"{info['ms']:.0f}ms raw {info['raw_ms']:.0f}ms "
                      f"A {info['a_db']:.1f}dB", flush=True)
    return rows


def write_stats(rows: list[dict]) -> Path:
    out = ROOT / "assets" / "audio" / "voice_stats.csv"
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)
    print(f"统计写入：{out}", flush=True)
    return out


def summarize(rows: list[dict]) -> None:
    print("\n每套统计：")
    print("  voice  方言      条数  平均ms  最长ms  平均A(dB)  峰值(dB)")
    for set_id in sorted({r["set_id"] for r in rows}):
        sub = [r for r in rows if r["set_id"] == set_id]
        print(f"  {set_id:<5g}  {sub[0]['dialect']:<6}  {len(sub):>3}  "
              f"{np.mean([r['ms'] for r in sub]):>6.0f}  {max(r['ms'] for r in sub):>6.0f}  "
              f"{np.mean([r['a_db'] for r in sub]):>8.1f}  {np.mean([r['peak_db'] for r in sub]):>6.1f}")
    total = sum((RAW / f"voice{r['set_id']}_{r['clip']}.wav").stat().st_size for r in rows)
    print(f"\nWAV 总体积：{total / 1024 / 1024:.2f} MB（{len(rows)} 条）")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sets", default="", help="逗号分隔的套号，例如 1,2；默认全部")
    ap.add_argument("--force", action="store_true", help="忽略缓存，重新调用模型")
    ap.add_argument("--refs-only", action="store_true")
    ap.add_argument("--post-only", action="store_true")
    args = ap.parse_args()

    wanted = {int(s) for s in args.sets.split(",") if s.strip()} if args.sets else None
    sets = [d for d in DIALECTS if wanted is None or d["set_id"] in wanted]
    if not sets:
        raise SystemExit("没有匹配的套号")

    if args.post_only:
        rows = []
        for d in sets:
            ref_words = d.get("tile_words") or TILE_WORDS
            actions = d.get("action_words") or ACTION_WORDS
            jobs = [(f"tile_{i:02d}", w) for i, w in enumerate(ref_words)]
            jobs += [(key, actions[key]) for key in ACTION_KEYS]
            for key, word in jobs:
                raw_path = CACHE / f"voice{d['set_id']}_{key}.wav"
                wav, sr = sf.read(str(raw_path), dtype="float64")
                y, info = postprocess(wav, sr)
                write_wav(RAW / f"voice{d['set_id']}_{key}.wav", y)
                rows.append(dict(set_id=d["set_id"], dialect=d["dialect"], clip=key,
                                 word=word, **info, gen_s=0))
        write_stats(rows)
        summarize(rows)
        return

    model = load_model()
    build_refs(model, sets, args.force)
    if args.refs_only:
        return
    rows = build_clips(model, sets, args.force)
    write_stats(rows)
    summarize(rows)


if __name__ == "__main__":
    main()
