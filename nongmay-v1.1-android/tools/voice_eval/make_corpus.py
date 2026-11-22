#!/usr/bin/env python3
"""Synthetic speech corpus for evaluating the offline Voice Shutter matcher on the JVM.

IMPORTANT: this is espeak-ng text-to-speech, not human speech. It exercises the exact KwsCore code
path (VAD -> noise tracker -> mel -> compensation -> MFCC -> DTW -> decision) with controlled
variation: 4 synthetic "speakers", speed/pitch/level changes, steady and babble noise, room reverb.
Real accuracy must be measured with Pol's own voice on the S25 Ultra (see PERFORMANCE_REPORT.md).

Everything is generated as continuous streams (like the app, which listens continuously):
  enroll/<spk>_<kw>.wav            1.5 s lead-in + 3 clean takes with pauses (the enrollment screen)
  pos/<spk>_<kw>_<cond>.wav        4 s of background, then 6 takes with 2.5–3.5 s pauses
  neg/<spk>_<cond>.wav             4 s of background, then 18 other words x 2 with pauses
  idle/quiet.wav, idle/cafe.wav    12 min of Thai conversation (quiet room / cafe background)
manifest.tsv: group, spk, kw, cond, path, utterance intervals (start:end samples), texts
"""
import os
import random
import subprocess
import sys

import numpy as np
from scipy.io import wavfile
from scipy.signal import resample_poly, fftconvolve

SR = 16000
SPEAKERS = {
    "S1": dict(voice="th+m1", pitch=45, speed=150),
    "S2": dict(voice="th+m3", pitch=35, speed=168),
    "S3": dict(voice="th+f2", pitch=62, speed=155),
    "S4": dict(voice="th+f4", pitch=72, speed=140),
}
KEYWORDS = {
    "nongme": ("th", "น้องเม"),
    "hong": ("th", "โฮ่ง โฮ่ง"),
    "charlie": ("th", "ชาลี"),
    "thailoey": ("th", "ถ่ายเลย"),
    "cheese": ("en", "cheese"),
}
NEGATIVES = ["น้องเมย์", "น้องแม่", "น้องหมา", "แม่", "เม", "นอนเลย", "สวัสดี", "ไปไหน", "ถ่ายรูป", "ทำไม",
             "ขอบคุณ", "เดี๋ยวก่อน", "ใช่", "ไม่ใช่", "อร่อยมาก", "ชาเย็น", "ข้าวมันไก่", "หมาเห่า"]
# everyday talk written for this test; several lines contain a trigger phrase inside a sentence on purpose
CONVERSATION = [
    "วันนี้อากาศดีมากเลยนะ เราไปเดินเล่นที่สวนกันไหม",
    "เดี๋ยวน้องเมก็มาถึงแล้ว รอแป๊บเดียว",
    "ช่วยถือกล้องให้หน่อยได้ไหม ฉันจะจัดผมก่อน",
    "ตอนเย็นเราจะกินข้าวที่ร้านข้างตลาดนะ",
    "รถติดมากเลย น่าจะถึงช้ากว่าที่คิด",
    "ชาลีนอนอยู่บนโซฟาทั้งวันเลย",
    "ถ่ายรูปตรงนี้สวยดี แสงกำลังพอดี",
    "พรุ่งนี้มีประชุมตอนเก้าโมงเช้า อย่าลืมนะ",
    "น้องเมไปไหนมา ทำไมกลับมาช้าจัง",
    "ขอเสียงหัวเราะหน่อย ทุกคนยิ้มกว้าง ๆ",
    "หมาข้างบ้านเห่าทั้งคืนเลย นอนไม่หลับ",
    "เอาน้ำเปล่าสองขวดกับกาแฟเย็นหนึ่งแก้วค่ะ",
    "ชีสเค้กร้านนี้อร่อยมาก ต้องลองนะ",
    "ไม่ต้องรีบนะ ค่อย ๆ เดินก็ได้",
    "เมื่อกี้ใครเรียกฉันหรือเปล่า",
    "ถ่ายเลยก็ได้ ฉันพร้อมแล้ว แต่ยังไม่ต้องกดนะ",
]
CONDS = {  # name: (noise kind, SNR dB vs. mean phrase power); reverb handled separately
    "clean": (None, None),
    "white20": ("white", 20),
    "pink10": ("pink", 10),
    "babble15": ("babble", 15),
    "reverb": (None, None),
}
TMP = None


def tts(text, lang, spk, speed, pitch, amp=100):
    voice = spk["voice"] if lang == "th" else "en+" + spk["voice"].split("+")[1]
    subprocess.run(["espeak-ng", "-v", voice, "-s", str(int(speed)), "-p", str(int(max(0, min(99, pitch)))),
                    "-a", str(int(amp)), "-w", TMP, text], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    sr, x = wavfile.read(TMP)
    x = x.astype(np.float64)
    y = resample_poly(x, 160, 221) if sr == 22050 else x
    return trim(y)


def trim(y, thr=0.01):
    a = np.abs(y)
    m = a.max() if a.size else 0
    if m == 0:
        return y
    idx = np.where(a > m * thr)[0]
    return y[max(0, idx[0] - 160): idx[-1] + 160]


def noise_like(n, kind, rng):
    w = rng.standard_normal(n)
    if kind == "white":
        return w
    f = np.fft.rfft(w)
    f /= np.sqrt(np.arange(1, f.size + 1))
    y = np.fft.irfft(f, n)
    return y / (np.sqrt(np.mean(y ** 2)) + 1e-12)   # unit RMS, so the dBFS levels below are what they say


def room(sig, rng, rt60=0.4, drr_db=4.0):
    """Phone at arm's length in a furnished room: direct path + exponential diffuse tail (DRR ~ +4 dB)."""
    n = int(SR * rt60)
    t = np.arange(n) / SR
    tail = rng.standard_normal(n) * np.exp(-6.9 * t / rt60)
    tail[: int(0.002 * SR)] = 0
    tail *= np.sqrt(10 ** (-drr_db / 10) / np.sum(tail ** 2))
    ir = tail
    ir[0] = 1.0
    return fftconvolve(sig, ir)[: sig.size + n // 2]


def level(y, rng, lo=-14, hi=-4):
    return y / (np.max(np.abs(y)) + 1e-9) * 32767 * 10 ** (rng.uniform(lo, hi) / 20)


def build_stream(utts, gaps, lead, rng, cond, babble):
    """utts: list of float arrays (already levelled). Returns int16 stream + intervals."""
    kind, snr = CONDS[cond]
    parts, ivals, pos = [np.zeros(int(lead * SR))], [], int(lead * SR)
    for u, g in zip(utts, gaps):
        if cond == "reverb":
            u = room(u, rng)
        ivals.append((pos, pos + u.size))
        parts += [u, np.zeros(int(g * SR))]
        pos += u.size + int(g * SR)
    s = np.concatenate(parts)
    if kind is not None:
        p_speech = np.mean([np.mean(u ** 2) for u in utts])
        if kind == "babble":
            off = int(rng.integers(0, babble.size))
            nz = np.resize(np.roll(babble, -off), s.size)
        else:
            nz = noise_like(s.size, kind, rng)
        nz = nz * np.sqrt(p_speech / (np.mean(nz ** 2) * 10 ** (snr / 10)))
        s = s + nz
    s = s + rng.standard_normal(s.size) * 32767 * 10 ** (-68 / 20)  # mic self-noise
    return np.clip(s, -32767, 32767).astype(np.int16), ivals


def write(path, pcm):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    wavfile.write(path, SR, pcm)


def main(out):
    global TMP
    os.makedirs(out, exist_ok=True)
    TMP = os.path.join(out, "_tts.wav")
    rng = np.random.default_rng(20261122)
    random.seed(20261122)
    rows = []

    def row(group, spk, kw, cond, path, ivals, texts=()):
        iv = ";".join(f"{a}:{b}" for a, b in ivals)
        rows.append("\t".join([group, spk, kw, cond, os.path.relpath(path, out), iv, "|".join(texts)]))

    # babble bed: overlapping conversation lines from the four voices, ~24 s
    babble = np.zeros(SR * 24)
    for i, s in enumerate(["S1", "S2", "S3", "S4"] * 3):
        spk = SPEAKERS[s]
        y = tts(CONVERSATION[(i * 5) % len(CONVERSATION)], "th", spk, spk["speed"], spk["pitch"])
        y /= np.sqrt(np.mean(y ** 2)) + 1e-9
        start = int(rng.integers(0, babble.size - y.size))
        babble[start:start + y.size] += y

    for sname, spk in SPEAKERS.items():
        for kname, (lang, text) in KEYWORDS.items():
            utts = [level(tts(text, lang, spk, spk["speed"] * rng.uniform(0.95, 1.05), spk["pitch"] + rng.uniform(-3, 3),
                              rng.uniform(85, 115)), rng) for _ in range(3)]
            pcm, iv = build_stream(utts, [rng.uniform(1.5, 2.0) for _ in utts], 1.5, rng, "clean", babble)
            p = os.path.join(out, "enroll", f"{sname}_{kname}.wav")
            write(p, pcm)
            row("enroll", sname, kname, "clean", p, iv)
            for cond in CONDS:
                utts = [level(tts(text, lang, spk, spk["speed"] * rng.uniform(0.85, 1.15), spk["pitch"] + rng.uniform(-8, 8),
                                  rng.uniform(70, 130)), rng) for _ in range(6)]
                pcm, iv = build_stream(utts, [rng.uniform(2.5, 3.5) for _ in utts], 4.0, rng, cond, babble)
                p = os.path.join(out, "pos", f"{sname}_{kname}_{cond}.wav")
                write(p, pcm)
                row("pos", sname, kname, cond, p, iv)
        for cond in CONDS:
            texts = NEGATIVES * 2
            utts = [level(tts(t, "th", spk, spk["speed"] * rng.uniform(0.9, 1.1), spk["pitch"] + rng.uniform(-6, 6)), rng)
                    for t in texts]
            pcm, iv = build_stream(utts, [rng.uniform(1.8, 2.5) for _ in utts], 4.0, rng, cond, babble)
            p = os.path.join(out, "neg", f"{sname}_{cond}.wav")
            write(p, pcm)
            row("neg", sname, "-", cond, p, iv, texts)

    # 12 minutes of conversation, quiet room and cafe (other people talking underneath)
    for name in ["quiet", "cafe"]:
        parts, total, k = [], 0, 0
        while total < SR * 60 * 12:
            spk = SPEAKERS[random.choice(list(SPEAKERS))]
            y = level(tts(random.choice(CONVERSATION), "th", spk, spk["speed"] * rng.uniform(0.9, 1.15),
                          spk["pitch"] + rng.uniform(-6, 6)), rng, -16, -5)
            gap = np.zeros(int(SR * rng.uniform(0.25, 1.6)))
            parts += [y, gap]
            total += y.size + gap.size
            k += 1
        s = np.concatenate(parts)
        if name == "cafe":
            p_fg = np.mean(s ** 2)
            bed = np.resize(babble, s.size)
            s = s + bed * np.sqrt(p_fg / (np.mean(bed ** 2) * 10 ** (12 / 10)))
            s = s + noise_like(s.size, "pink", rng) * 32767 * 10 ** (-45 / 20)
        else:
            s = s + noise_like(s.size, "pink", rng) * 32767 * 10 ** (-58 / 20)
        p = os.path.join(out, "idle", f"{name}.wav")
        write(p, np.clip(s, -32767, 32767).astype(np.int16))
        row("idle", "-", "-", name, p, [(0, s.size)], [f"{k} sentences"])
    os.remove(TMP)
    with open(os.path.join(out, "manifest.tsv"), "w", encoding="utf-8") as f:
        f.write("\n".join(rows) + "\n")
    print("streams:", len(rows))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "build/voice_corpus")
