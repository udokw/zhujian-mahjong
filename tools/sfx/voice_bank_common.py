#!/usr/bin/env python3
"""麻将报牌人声 · 9 套普通话音色配置（Fun-CosyVoice3 最终采用版）。

与之前的模型对比不同：这里是 App 正式资源，9 套音色要明显区分且套内一致。
每套覆盖 34 张牌名（万/筒/条各 1-9 + 东南西北中发白）与 6 个动作词，
共 40 条。牌面文本与引擎 Tiles 序号 0..33 一一对应。

音色一致性靠跨语种音色克隆：每套一条参考音频（由 VoxCPM2 Voice Design
生成，非真人素材），CosyVoice3 用 instruct 指定普通话发音，用 prompt 复制音色。
"""
from __future__ import annotations

from pathlib import Path

ROOT = Path(r"g:\AI\麻将")
REF_DIR = Path(r"G:\AI\tts_refs_mandarin")
RAW_DIR = ROOT / "app" / "src" / "androidMain" / "res" / "raw"
STAGE_DIR = ROOT / "assets" / "audio" / "voice_bank_mandarin"
CACHE_DIR = Path(r"G:\AI\cosyvoice\voice_bank_raw")

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

# 9 套音色：set_id 与 App 的 voice1..voice9 完全对应。
VOICES = [
    dict(
        set_id=1, key="男青年", desc="青年男性，清亮，语速中等",
        design="普通话，青年男性，音色清亮，语速中等，语气自然",
        ref_text="今天开始打麻将，我先摸一张牌，看看手气怎么样。",
    ),
    dict(
        set_id=2, key="女青年", desc="青年女性，清脆，语速轻快",
        design="普通话，青年女性，清脆明亮，语速轻快，语气活泼",
        ref_text="该你出牌了，快一点，我已经等了很久啦。",
    ),
    dict(
        set_id=3, key="男中年", desc="中年男性，低沉浑厚，语速偏慢",
        design="普通话，中年男性，低沉浑厚，语速偏慢，语气沉稳",
        ref_text="手气不错，这一圈已经胡了两把，得稳住别乱出牌。",
    ),
    dict(
        set_id=4, key="女中年", desc="中年女性，温和圆润，语速平稳",
        design="普通话，中年女性，温和圆润，语速平稳，语气亲和",
        ref_text="打麻将要记住别人出过什么牌，别只顾着看自己手里。",
    ),
    dict(
        set_id=5, key="男老年", desc="老年男性，沙哑低沉，语速偏慢",
        design="普通话，老年男性，嗓音沙哑低沉，语速偏慢，语气沉稳",
        ref_text="我打了四十年麻将，什么牌没见过，慢慢来，不着急。",
    ),
    dict(
        set_id=6, key="女老年", desc="老年女性，温和略沙哑，语速偏慢",
        design="普通话，老年女性，嗓音温和略沙哑，语速偏慢，语气慈祥",
        ref_text="年轻的时候天天打牌，现在腿脚慢了，手还稳得很。",
    ),
    dict(
        set_id=7, key="少年男孩", desc="十岁男孩，童声清亮，语速偏快",
        design="普通话，十岁男孩，童声清亮，语速偏快，语气活泼",
        ref_text="叔叔阿姨好，我也会打麻将，我奶奶教我的。",
    ),
    dict(
        set_id=8, key="少女", desc="十岁女孩，童声甜美，语速轻快",
        design="普通话，十岁女孩，童声甜美，语速轻快，语气俏皮",
        ref_text="今天我要坐东边，我掷骰子最厉害了。",
    ),
    dict(
        set_id=9, key="男播音", desc="成年男性，播音腔，浑厚洪亮",
        design="普通话，成年男性，播音腔，嗓音浑厚洪亮，吐字清晰，语速平稳",
        ref_text="各位好，欢迎来到今天的牌局，请各位坐好，我们马上开始。",
    ),
]

LICENSE = "Apache-2.0 (FunAudioLLM/Fun-CosyVoice3-0.5B)"


def jobs():
    """按固定顺序产出 (clip_key, 文本)：34 张牌 + 6 个动作。"""
    items = [(f"tile_{i:02d}", word) for i, word in enumerate(TILE_WORDS)]
    items += [(key, ACTION_WORDS[key]) for key in ACTION_KEYS]
    return items


def ref_path(voice: dict) -> Path:
    return REF_DIR / f"ref_{voice['key']}.wav"


def clip_name(set_id: int, clip: str) -> str:
    return f"voice{set_id}_{clip}.wav"
