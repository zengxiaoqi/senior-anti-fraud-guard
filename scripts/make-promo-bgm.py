#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SAFG 推广视频 BGM 合成器（零依赖：纯标准库 wave + math）
为 52s 竖屏推广片生成原创配乐，鼓点精准卡场景切换点：
  0-4s   Hook     心跳双跳 + 紧张 Am pad 渐入
  4-13s  骗局快闪  每张卡落地一记 kick+hat（卡切换 = 4 + i*1.333s）
  13-19s 品牌      6s riser 爬升 → 19.0s 大 impact（对齐盾牌入场）
  19-42s 三大能力  128bpm 四四拍 kick/hat + Am 五声 bass riff
                  27s / 35s 场景切换各补一记 impact
  42-52s CTA       转 A 大调上行琶音 + 结尾大和弦，51.5s fade out
用法：python scripts/make-promo-bgm.py [输出wav路径]
"""
import array
import math
import random
import sys
import wave

SR = 44100
DUR = 52.0
N = int(SR * DUR)

# 音名频率
A1, C2, D2, E2, A2 = 55.0, 65.41, 73.42, 82.41, 110.0
A3, Cs4, E4, A4 = 220.0, 277.18, 329.63, 440.0

buf = [0.0] * N
random.seed(42)


def add(sig, start):
    """把片段叠加进主缓冲"""
    i = int(start * SR)
    if i >= N:
        return
    j = min(N, i + len(sig))
    for k in range(i, j):
        buf[k] += sig[k - i]


def env_exp(n, rate):
    return [math.exp(-rate * k / SR) for k in range(n)]


def kick(dur=0.30, f0=170.0, f1=42.0, level=1.0):
    """底鼓：指数扫频正弦 + 起振click"""
    n = int(SR * dur)
    sig = array.array("f", bytes(4 * n))
    phase = 0.0
    for k in range(n):
        t = k / SR
        f = f0 * (f1 / f0) ** (t / dur)
        phase += 2.0 * math.pi * f / SR
        e = math.exp(-11.0 * t) + 0.02 * math.exp(-40.0 * t)
        sig[k] = math.sin(phase) * e
    # click
    nc = int(0.004 * SR)
    for k in range(nc):
        sig[k] += random.uniform(-1, 1) * 0.35 * math.exp(-k / SR * 400)
    return array.array("f", [v * level for v in sig])


def hat(dur=0.055, level=0.5):
    """军鼓噪声hat：差分高通 + 快衰减"""
    n = int(SR * dur)
    noise = [random.uniform(-1, 1) for _ in range(n)]
    sig = array.array("f", bytes(4 * n))
    prev = 0.0
    for k in range(n):
        hp = noise[k] - prev
        prev = noise[k]
        sig[k] = hp * math.exp(-k / SR * 55) * level
    return sig


def bass(freq, dur=0.42, level=0.55):
    """贝斯：正弦+泛音 + 指数衰减"""
    n = int(SR * dur)
    sig = array.array("f", bytes(4 * n))
    p1 = p2 = 0.0
    w1 = 2.0 * math.pi * freq / SR
    w2 = 2.0 * math.pi * freq * 2.0 / SR
    for k in range(n):
        p1 += w1
        p2 += w2
        e = math.exp(-k / SR * 3.5)
        sig[k] = (math.sin(p1) + 0.35 * math.sin(p2)) * e * level
    return sig


def pluck(freq, dur=0.40, level=0.30):
    """拨弦（琶音用）：基音+三倍频，中速衰减"""
    n = int(SR * dur)
    sig = array.array("f", bytes(4 * n))
    p1 = p2 = 0.0
    w1 = 2.0 * math.pi * freq / SR
    w2 = 2.0 * math.pi * freq * 3.0 / SR
    for k in range(n):
        p1 += w1
        p2 += w2
        e = math.exp(-k / SR * 7.0)
        sig[k] = (math.sin(p1) + 0.22 * math.sin(p2)) * e * level
    return sig


def pad(freqs, dur, level=0.10, detune=1.006):
    """氛围pad：多正弦微失谐 + 慢速振幅呼吸"""
    n = int(SR * dur)
    sig = array.array("f", bytes(4 * n))
    phases = [0.0] * len(freqs)
    ws = [2.0 * math.pi * f / SR for f in freqs]
    wds = [2.0 * math.pi * f * detune / SR for f in freqs]
    for k in range(n):
        v = 0.0
        for i in range(len(freqs)):
            phases[i] += ws[i]
            v += math.sin(phases[i]) + 0.4 * math.sin(phases[i] * detune)
        # 起音 0.8s 释音 1.2s
        t = k / SR
        a = min(1.0, t / 0.8) * min(1.0, (dur - t) / 1.2)
        sig[k] = v * a * level / len(freqs)
    return sig


def riser(dur=6.0, level=0.5):
    """上升器：白噪渐强 + 200→2600Hz chirp"""
    n = int(SR * dur)
    sig = array.array("f", bytes(4 * n))
    p = 0.0
    for k in range(n):
        t = k / SR
        f = 200.0 * (2600.0 / 200.0) ** (t / dur)
        p += 2.0 * math.pi * f / SR
        e = (t / dur) ** 2.2
        sig[k] = (math.sin(p) * 0.7 + random.uniform(-1, 1) * 0.3) * e * level
    return sig


def impact(level=1.0):
    """重击：50Hz 低频 + 噪声爆发（场景切换/品牌亮相用）"""
    dur = 1.4
    n = int(SR * dur)
    sig = array.array("f", bytes(4 * n))
    p = 0.0
    for k in range(n):
        t = k / SR
        p += 2.0 * math.pi * 50.0 / SR
        low = math.sin(p) * math.exp(-t * 4.0)
        noise = random.uniform(-1, 1) * math.exp(-t * 9.0)
        sig[k] = (low * 0.9 + noise * 0.5) * level
    return sig


def heartbeat(at, strong=1.0, gap=0.19):
    add(kick(level=strong), at)
    add(kick(level=strong * 0.55, f0=120, f1=38), at + gap)


def main(out_path):
    # ── S1 Hook 0-4s：心跳 + 紧张 pad 渐入 ──
    add(pad([A2, 130.81, 164.81], 4.6, level=0.13), 0.0)
    hb = 0.0
    while hb < 3.6:
        heartbeat(hb)
        hb += 0.8

    # ── S2 骗局快闪 4-13s：每卡落地一击 ──
    for i in range(6):
        t = 4.0 + i * 1.333
        add(kick(level=0.9), t)
        add(hat(level=0.45), t)
    add(riser(1.0, level=0.35), 12.0)  # 段尾小爬升

    # ── S3 品牌 13-19s：6s riser → 19.0s impact（对齐盾牌） ──
    add(riser(6.0, level=0.55), 13.0)
    add(impact(1.0), 19.0)
    add(pad([A2, 130.81, 164.81], 6.0, level=0.10), 19.0)

    # ── S4-S6 能力三连 19-42s：128bpm 四四拍 ──
    beat = 60.0 / 128.0
    t = 19.0
    riff = [A1, C2, D2, E2]
    bi = 0
    while t < 42.0:
        add(kick(level=0.85), t)
        add(hat(level=0.32), t + beat * 0.5)
        if bi % 2 == 0:
            add(bass(riff[(bi // 2) % 4]), t)
        if bi % 8 == 4:  # 每 2 小节一次闪音
            add(hat(level=0.5, dur=0.09), t)
        t += beat
        bi += 1
    add(impact(0.8), 27.0)   # 干预入场
    add(impact(0.8), 35.0)   # 存证入场

    # ── S7 CTA 42-52s：A 大调上行琶音 + 结尾大和弦 ──
    add(impact(0.9), 42.0)
    arp = [A3, Cs4, E4, A4]
    t = 42.0
    i = 0
    while t < 49.0:
        add(pluck(arp[i % 4]), t)
        if i % 2 == 0:
            add(kick(level=0.8), t)
            add(hat(level=0.35), t + beat * 0.5)
        t += beat
        i += 1
    # 49-52s 大和弦 + 收尾
    add(pad([A3, Cs4, E4, A4], 3.0, level=0.14), 49.0)
    add(impact(0.9), 49.0)

    # ── 母带：软限幅 + 结尾淡出 ──
    fade_start = int(51.2 * SR)
    out = array.array("f", bytes(4 * N))
    peak = 0.0
    for k in range(N):
        v = math.tanh(buf[k] * 1.15) * 0.85
        if k >= fade_start:
            v *= max(0.0, 1.0 - (k - fade_start) / (N - fade_start))
        out[k] = v
        a = abs(v)
        if a > peak:
            peak = a

    with wave.open(out_path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        frames = array.array("h", [int(max(-1.0, min(1.0, v)) * 32767) for v in out])
        w.writeframes(frames.tobytes())

    print(f"OK {out_path} dur={DUR}s peak={peak:.3f} samples={N}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "safg-bgm.wav")
