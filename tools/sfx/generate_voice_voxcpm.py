#!/usr/bin/env python3
"""麻将报牌语音生成器（VoxCPM2 本地推理，16 套中国方言人声）。

!!! 当前未采用（已停用，请勿运行）!!!
  本方案产出的报牌人声经试听与核查判定不合格：粤语发音不准，且混入普通话与其他方言。
  2026-11 已清理全部生成音频（res/raw 下的 voice*.wav、assets/audio/model_compare/ 等），
  语音这条线停止。本文件仅作历史参考保留，不要再执行；其中的路径与输出目录可能已不存在。

与 generate_audio.py 的分工：
  - generate_audio.py：用 numpy 合成敲桌/洗牌/结算等音效（不需要 GPU）。
  - 本脚本：用 VoxCPM2 在本地 GPU 生成 voice1..voice16 的 34 张牌名 + 6 个动作，
    写入 app/src/androidMain/res/raw/voiceN_*.wav，命名与播放层
    （AndroidAudio.kt / GameAudio.kt）完全一致。

套数与方言的对应（广东话 8 位不同发音人，其余 8 种方言各 1 位）：
  voice1..voice8   广东话（8 套不同音色）
  voice9           四川话    voice10 吴语
  voice11          东北话    voice12 河南话
  voice13          陕西话    voice14 山东话
  voice15          天津话    voice16 闽南话

音色一致性靠“参考音频克隆”：每套先用 Voice Design 生成一段 3~5 秒的方言参考
音频，再用 reference_wav_path 克隆出该套的 40 条短词，保证同一套 40 条是同一个
人的音色；VoxCPM2 不做参考音频时每次都是随机音色。广东话的 8 套必须使用 8 段
不同的参考音频（不同年龄/性别/语气），不能靠同一段参考变调。

不做变速、不做截断：保留模型原始语速与自然停顿，只做重采样、静音裁剪与等响
归一。`--preview-raw` 导出的试听文件连静音裁剪都不做（仅响度归一），用于让
耳朵判断模型原始效果。

依赖（见 README Installation）：
  Python >=3.10,<3.13；PyTorch >=2.5.0；CUDA >=12.0；pip install voxcpm
  权重：openbmb/VoxCPM2（约 4.6 GB，Apache-2.0）

用法（必须在装了 torch 的虚拟环境里跑）：
  G:\\AI\\voxcpm\\venv\\Scripts\\python.exe tools\\sfx\\generate_voice_voxcpm.py
  可选参数：--sets 1,2          只生成指定套
            --force             忽略已缓存的原始输出，重新调用模型
            --refs-only         只生成参考音频
            --post-only         不跑模型，仅用已缓存的原始输出重新做后处理
            --preview-raw       导出未变速/未截断的原始试听 WAV 并打包 zip
"""

from __future__ import annotations

import argparse
import csv
import math
import shutil
import time
import zipfile
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 48000  # 仅作为无参考时的兜底；实际写盘用模型输出自带的采样率
ROOT = Path(__file__).resolve().parents[2]
RAW = ROOT / "app" / "src" / "androidMain" / "res" / "raw"
CACHE = Path(r"G:\AI\voxcpm\raw")
REF_DIR = Path(r"G:\AI\voxcpm\refs")
MODEL_DIR = Path(r"G:\AI\voxcpm\models\VoxCPM2")
PREVIEW_DIR = ROOT / "assets" / "audio" / "preview_raw"
PREVIEW_ZIP = ROOT / "assets" / "audio" / "preview_raw.zip"

# 只做响度归一：A 加权目标 -18 dBFS，峰值上限 -0.2 dBFS。
# 达到目标所需的增益若会把峰值推过上限，就只取“不削波的最大增益”，
# 不用 tanh / 限幅器 / 压缩器，避免改变波形。
TARGET_A_DB = -18.0
PEAK_CEILING_DB = -0.2
# 只有“几乎没有声音”的坏样本才重生成；不按时长筛选，模型输出多长就用多长。
BROKEN_MIN_MS = 100.0
BROKEN_PEAK = 1e-4

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

CANTONESE_TILE_WORDS = [
    "一萬", "二萬", "三萬", "四萬", "五萬", "六萬", "七萬", "八萬", "九萬",
    "一筒", "二筒", "三筒", "四筒", "五筒", "六筒", "七筒", "八筒", "九筒",
    "一條", "二條", "三條", "四條", "五條", "六條", "七條", "八條", "九條",
    "東風", "南風", "西風", "北風", "紅中", "發財", "白板",
]
CANTONESE_ACTION_WORDS = {
    "chi": "食", "peng": "碰", "gang": "槓",
    "hu": "糊", "zimo": "自摸", "liuju": "流局",
}


def cantonese(set_id: int, voice: str, ref_text: str) -> dict:
    """广东话 8 套共用繁体牌面与粤语动作词，只有发音人不同。"""
    return dict(
        set_id=set_id,
        dialect="广东话",
        voice=voice,
        ref_text=ref_text,
        tile_words=CANTONESE_TILE_WORDS,
        action_words=CANTONESE_ACTION_WORDS,
        cantonese=True,
    )


# 每套：voiceN, 指令里的方言名, 音色描述, 参考句, 牌名/动作改写字（None 表示沿用普通话写法）
DIALECTS = [
    # ---------------- 广东话 8 套：8 段不同参考音频，对应 8 个不同发音人 ----------------
    cantonese(1, "中年男性，低沉，语气干脆",
              "今日天气几好，我哋几个一齐打返几圈麻雀，好冇？"),
    cantonese(2, "青年女性，清脆，语速轻快",
              "今晚得闲，一齐去饮茶打麻雀，好唔好呀？"),
    cantonese(3, "老年男性，沙哑，语速偏慢",
              "我后生嗰阵成日打麻雀，而家都仲记得点样食糊。"),
    cantonese(4, "青年男性，明亮，语气上扬",
              "喂，快啲啦，摸完牌就出，唔好成日谂咁耐。"),
    cantonese(5, "中年女性，温柔，吐字清楚",
              "你哋慢慢打啦，我煲咗汤，打完返嚟饮。"),
    cantonese(6, "老年女性，和蔼，语速偏慢",
              "孙仔今日返嚟食饭，我买咗好多餸，好开心。"),
    cantonese(7, "少年男孩，清亮，语速快",
              "妈，我做完功课喇，可唔可以打一阵机呀？"),
    cantonese(8, "中年男性，洪亮，带笑意",
              "哈哈，今日手气几好，连赢三铺，真系开心。"),
    # ---------------- 其余 8 种方言各 1 套 ----------------
    dict(
        set_id=9,
        dialect="四川话",
        voice="中年男性，嗓音浑厚，语速偏快",
        ref_text="今天天气巴适得很，我们几个来打两圈麻将，要得嘛。",
    ),
    dict(
        set_id=10,
        dialect="吴语",
        voice="青年女性，软糯，语速偏快",
        ref_text="今朝天气蛮好，阿拉几个人一道搓两圈麻将，好伐？",
    ),
    dict(
        set_id=11,
        dialect="东北话",
        voice="中年女性，爽朗，语速快",
        ref_text="今儿个天儿挺好，咱几个整两圈麻将，咋样？",
    ),
    dict(
        set_id=12,
        dialect="河南话",
        voice="青年男性，明亮，语速快",
        ref_text="今儿个天不赖，咱几个来两圈麻将，中不中？",
    ),
    dict(
        set_id=13,
        dialect="陕西话",
        voice="中年男性，粗犷，语气重",
        ref_text="今儿个天气嘹得很，咱几个来两圈麻将，成不成？",
    ),
    dict(
        set_id=14,
        dialect="山东话",
        voice="中年女性，沉稳，吐字清楚",
        ref_text="今儿个天挺好，咱几个来两圈麻将，行不行？",
    ),
    dict(
        set_id=15,
        dialect="天津话",
        voice="青年男性，轻快，带笑意",
        ref_text="今儿个天儿不错，咱几个来两圈麻将，行嘛？",
    ),
    dict(
        set_id=16,
        dialect="闽南话",
        voice="中年女性，温和，语速偏快",
        ref_text="今仔日天气真好，咱几个来拍两圈麻雀，好无？",
    ),
]

# 原始试听：每种方言至少 万 / 筒 / 条 各一张 + 一个风牌。
PREVIEW_TILES = [0, 9, 18, 27]


# ---------------------------------------------------------------- 音频后处理

def a_weighted_db(x: np.ndarray, sr: int) -> float:
    """A 加权 RMS（dBFS），用来做等响归一。"""
    spec = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(len(x), 1.0 / sr)
    f2 = np.maximum(freqs, 1e-6) ** 2
    num = (12194.0 ** 2) * f2 * f2
    den = (f2 + 20.6 ** 2) * np.sqrt((f2 + 107.7 ** 2) * (f2 + 737.9 ** 2)) * (f2 + 12194.0 ** 2)
    weight = (num / den) * (10 ** (2.0 / 20))
    y = np.fft.irfft(spec * weight, len(x))
    return 20.0 * math.log10(float(np.sqrt(np.mean(y ** 2))) + 1e-12)


def normalize_gain(x: np.ndarray, sr: int) -> np.ndarray:
    """纯增益响度归一：不压缩、不削波、不改变波形形状。

    - 先算达到目标响度需要的增益；
    - 若该增益会把峰值推过 -0.2 dBFS，就退到“刚好不削波”的增益；
    - 不做 tanh / 限幅器 / 压缩器，也不做整体归一化后的二次整形。
    """
    y = x.astype(np.float64, copy=True)
    peak = float(np.max(np.abs(y)))
    if peak <= 1e-9:
        return y
    needed = 10 ** ((TARGET_A_DB - a_weighted_db(y, sr)) / 20.0)
    ceiling = 10 ** (PEAK_CEILING_DB / 20.0)
    gain = min(needed, ceiling / peak)
    return y * gain


def postprocess(x: np.ndarray, sr_in: int) -> tuple[np.ndarray, dict]:
    """不做重采样、不裁剪、不变速，只做增益响度归一。"""
    y = normalize_gain(x, sr_in)
    info = {
        "sr": sr_in,
        "raw_ms": round(len(x) / sr_in * 1000.0, 1),
        "ms": round(len(y) / sr_in * 1000.0, 1),
        "stretch": 1.0,
        "a_db": round(a_weighted_db(y, sr_in), 2),
        "peak_db": round(20.0 * math.log10(float(np.max(np.abs(y))) + 1e-12), 2),
    }
    return y, info


def write_wav(path: Path, x: np.ndarray, sr: int) -> None:
    """32-bit float WAV，保留模型原始采样率与位深，无任何有损编码。"""
    path.parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(path), np.asarray(x, dtype=np.float32), sr, subtype="FLOAT")


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
        sr = int(model.tts_model.sample_rate)
        # 参考音频只用于克隆，保留原始采样率与位深即可，不做裁剪/重采样。
        write_wav(path, wav, sr)
        print(f"  参考音频 voice{d['set_id']} {d['dialect']}：{len(wav) / sr:.2f}s"
              f"（{time.time() - t0:.1f}s）", flush=True)


def generate_clip(model, d: dict, key: str, word: str, ref: Path, force: bool) -> tuple[np.ndarray, int]:
    """取一条原始输出：优先用缓存；缓存几乎无声时才重新调用模型。"""
    raw_path = CACHE / f"voice{d['set_id']}_{key}.wav"
    if raw_path.exists() and not force:
        wav, sr = sf.read(str(raw_path), dtype="float64")
        dur_ms = len(wav) / sr * 1000.0
        if dur_ms >= BROKEN_MIN_MS and float(np.max(np.abs(wav))) > BROKEN_PEAK:
            return np.asarray(wav, dtype=np.float64).reshape(-1), int(sr)
        print(f"    空样本 voice{d['set_id']}_{key} {dur_ms:.0f}ms，重新生成", flush=True)

    instruction = f"({d['dialect']}，自然语速)"
    last_exc = None
    best = None
    for _ in range(3):
        try:
            cand = gen_text(model, f"{instruction}{word}。", ref)
            cand_sr = int(model.tts_model.sample_rate)
            if len(cand) / cand_sr * 1000.0 >= BROKEN_MIN_MS and float(np.max(np.abs(cand))) > BROKEN_PEAK:
                best = (cand, cand_sr)
                break
            if best is None:
                best = (cand, cand_sr)
        except Exception as exc:  # noqa: BLE001
            last_exc = exc
            print(f"    重试 {d['set_id']}/{key}：{exc}", flush=True)
            time.sleep(1.0)
    if best is None:
        raise RuntimeError(f"生成失败 voice{d['set_id']}_{key}: {last_exc}")
    wav, sr = best
    CACHE.mkdir(parents=True, exist_ok=True)
    sf.write(str(raw_path), wav, sr)
    return wav, sr


def build_clips(model, sets: list[dict], force: bool) -> list[dict]:
    CACHE.mkdir(parents=True, exist_ok=True)
    rows: list[dict] = []
    total = sum(len(TILE_WORDS) + len(ACTION_KEYS) for _ in sets)
    done = 0
    for d in sets:
        set_id = d["set_id"]
        ref = REF_DIR / f"voice{set_id}.wav"
        if not ref.exists():
            raise SystemExit(f"缺少参考音频 {ref}，先跑 --refs-only")
        tiles = d.get("tile_words") or TILE_WORDS
        actions = d.get("action_words") or ACTION_WORDS
        jobs = [(f"tile_{i:02d}", w) for i, w in enumerate(tiles)]
        jobs += [(key, actions[key]) for key in ACTION_KEYS]
        for key, word in jobs:
            t0 = time.time()
            wav, sr = generate_clip(model, d, key, word, ref, force)
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
                      f"{info['ms']:.0f}ms（原始 {info['raw_ms']:.0f}ms）"
                      f"A {info['a_db']:.1f}dB", flush=True)
    return rows


def build_assets_from_cache(sets: list[dict]) -> list[dict]:
    """不跑模型，直接用缓存重做 16 套资源（用于去掉旧变速版）。"""
    rows = []
    for d in sets:
        tiles = d.get("tile_words") or TILE_WORDS
        actions = d.get("action_words") or ACTION_WORDS
        jobs = [(f"tile_{i:02d}", w) for i, w in enumerate(tiles)]
        jobs += [(key, actions[key]) for key in ACTION_KEYS]
        for key, word in jobs:
            raw_path = CACHE / f"voice{d['set_id']}_{key}.wav"
            if not raw_path.exists():
                raise SystemExit(f"缺少原始输出 {raw_path}")
            wav, sr = sf.read(str(raw_path), dtype="float64")
            y, info = postprocess(np.asarray(wav).reshape(-1), int(sr))
            write_wav(RAW / f"voice{d['set_id']}_{key}.wav", y)
            rows.append(dict(set_id=d["set_id"], dialect=d["dialect"], clip=key,
                             word=word, **info, gen_s=0))
    return rows


# ---------------------------------------------------------------- 原始试听导出

def export_preview(model, sets: list[dict], generate_missing: bool) -> None:
    """导出未变速、未截断的原始语音：只做响度归一，保留模型原生采样率。"""
    if PREVIEW_DIR.exists():
        shutil.rmtree(PREVIEW_DIR)
    PREVIEW_DIR.mkdir(parents=True, exist_ok=True)
    for d in sets:
        tiles = d.get("tile_words") or TILE_WORDS
        ref = REF_DIR / f"voice{d['set_id']}.wav"
        folder = PREVIEW_DIR / f"voice{d['set_id']:02d}_{d['dialect']}"
        folder.mkdir(parents=True, exist_ok=True)
        for idx in PREVIEW_TILES:
            key = f"tile_{idx:02d}"
            word = tiles[idx]
            raw_path = CACHE / f"voice{d['set_id']}_{key}.wav"
            if not raw_path.exists():
                if not generate_missing or model is None:
                    print(f"  跳过（无原始输出）：{raw_path.name}", flush=True)
                    continue
                if not ref.exists():
                    build_refs(model, [d], force=False)
                wav, sr = generate_clip(model, d, key, word, ref, force=False)
            else:
                wav, sr = sf.read(str(raw_path), dtype="float64")
                wav, sr = np.asarray(wav).reshape(-1), int(sr)
            y = normalize_equal_loudness(wav, sr)
            write_wav(folder / f"{key}_{word}.wav", y, sr)
            print(f"  {folder.name}/{key}_{word}.wav  {len(y) / sr * 1000:.0f}ms", flush=True)

    readme = PREVIEW_DIR / "README.txt"
    readme.write_text(
        "麻将报牌语音 · VoxCPM2 原始输出试听\n"
        "=====================================\n"
        "这些 WAV 由 VoxCPM2 直接生成，未做保音高变速、未截断（保留自然停顿），\n"
        "仅做了等响归一（A 加权 -16.5 dBFS）与峰值软限幅，采样率为模型原生 48 kHz。\n\n"
        "每个方言目录包含 一万 / 一筒 / 一条 / 东风 各一条；\n"
        "voice01..voice08 是广东话的 8 位不同发音人（不同参考音频克隆）。\n"
        "voice09 四川话  voice10 吴语  voice11 东北话  voice12 河南话\n"
        "voice13 陕西话  voice14 山东话  voice15 天津话  voice16 闽南话\n",
        encoding="utf-8",
    )

    with zipfile.ZipFile(PREVIEW_ZIP, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in sorted(PREVIEW_DIR.rglob("*")):
            if path.is_file():
                zf.write(path, Path("preview_raw") / path.relative_to(PREVIEW_DIR))
    print(f"\n试听包：{PREVIEW_ZIP}（{PREVIEW_ZIP.stat().st_size / 1024 / 1024:.2f} MB）", flush=True)


# ---------------------------------------------------------------- 统计

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
    ap.add_argument("--preview-raw", action="store_true")
    args = ap.parse_args()

    wanted = {int(s) for s in args.sets.split(",") if s.strip()} if args.sets else None
    sets = [d for d in DIALECTS if wanted is None or d["set_id"] in wanted]
    if not sets:
        raise SystemExit("没有匹配的套号")

    if args.post_only:
        rows = build_assets_from_cache(sets)
        write_stats(rows)
        summarize(rows)
        return

    if args.preview_raw:
        model = load_model()
        rows = build_refs(model, sets, args.force)
        export_preview(model, sets, generate_missing=True)
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
