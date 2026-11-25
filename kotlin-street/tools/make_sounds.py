#!/usr/bin/env python3
"""Turns the downloaded CC0 / CC-BY recordings (docs/SOUND.md, assets/audio/CREDITS.md) into the game's sound assets:
footsteps cut into single steps, loudness-matched, faded, mono Ogg Vorbis 48 kHz in audio/step/<set>/<surface>_NN.ogg;
ambience beds cut to seamless loops (a crossfade over the seam) in audio/amb/; one-shots trimmed and levelled in
audio/sfx/; engine loops for the recorded engine mode in audio/engine/.

  python3 -I make_sounds.py SRC DST        (SRC: the downloads, laid out as the job's scratchpad had them; needs ffmpeg, numpy)

Nothing from SRC is run: every file is only decoded by ffmpeg. The licence of each source is in CREDITS.md, checked on the
source's own page before it was downloaded."""
import os, sys, glob, subprocess, json
import numpy as np

R = 48000


def decode(path, mono=True):
    cmd = ['ffmpeg', '-loglevel', 'error', '-i', path, '-f', 'f32le', '-ac', '1' if mono else '2', '-ar', str(R), '-']
    a = np.frombuffer(subprocess.run(cmd, check=True, capture_output=True).stdout, dtype=np.float32)
    return a if mono else a.reshape(-1, 2)


def encode(a, out, q=4):
    os.makedirs(os.path.dirname(out), exist_ok=True)
    a = np.clip(a, -1, 1).astype(np.float32)
    ch = 1 if a.ndim == 1 else 2
    cmd = ['ffmpeg', '-loglevel', 'error', '-y', '-f', 'f32le', '-ac', str(ch), '-ar', str(R), '-i', '-', '-c:a', 'libvorbis', '-q:a', str(q), out]
    subprocess.run(cmd, input=a.tobytes(), check=True)


def env(a, win=240):
    """The loudness envelope (RMS over 5 ms windows), one value per window."""
    n = len(a) // win
    return np.sqrt((a[:n * win].reshape(n, win) ** 2).mean(axis=1) + 1e-12)


def highpass(a, hz=70):
    # a one-pole high-pass (rumble, wind on the mic)
    k = np.exp(-2 * np.pi * hz / R)
    y = np.empty_like(a); lp = 0.0
    for i in range(len(a)):
        lp = k * lp + (1 - k) * a[i]; y[i] = a[i] - lp
    return y


def steps(a, max_len=.42, min_gap=.16, keep=16):
    """Single steps cut out of a recording: an onset where the envelope jumps well over the floor after a quiet gap, to
    the next onset or the step's own tail."""
    e = env(a); win = 240
    if len(e) < 4: return []
    # a file that is one step already (the game packs): the whole of it, trimmed
    if len(a) < .9 * R:
        idx = np.where(e > e.max() * .03)[0]
        seg = a[max(0, idx[0] * win - int(.004 * R)):min(len(a), (idx[-1] + 3) * win)]
        return [seg[:int(max_len * R * 1.3)]]
    floor = np.percentile(e, 20); peak = e.max()
    thr = max(floor * 5, peak * .12)
    out = []; i = 0; gap = int(min_gap * R / win)
    onsets = []
    while i < len(e):
        if e[i] > thr and (not onsets or i - onsets[-1] > gap) and (i < 2 or e[i - 2] < thr * .7):
            onsets.append(i); i += gap
        else: i += 1
    for j, o in enumerate(onsets):
        s = max(0, o * win - int(.008 * R))
        end = min(len(a), s + int(max_len * R), (onsets[j + 1] * win - int(.015 * R)) if j + 1 < len(onsets) else len(a))
        seg = a[s:end].copy()
        if len(seg) < int(.06 * R): continue
        se = env(seg)
        # the tail: where it falls under 3 % of its peak for good
        tail = len(se)
        for k in range(len(se) - 1, 0, -1):
            if se[k] > se.max() * .03: tail = k + 4; break
        seg = seg[:min(len(seg), tail * win)]
        if (np.abs(seg) > .995).sum() > 8: continue      # clipped
        out.append(seg)
    # the clearest steps: the most peak over their own floor
    out.sort(key=lambda s: -env(s).max())
    return out[:keep]


def level(seg, target_db):
    # loudness: the RMS of the step's loudest 60 ms to target_db (dBFS)
    e = env(seg); w = max(1, int(.06 * R / 240))
    m = np.convolve(e ** 2, np.ones(w) / w, mode='valid').max() ** .5
    seg = seg * (10 ** (target_db / 20) / max(m, 1e-6))
    pk = np.abs(seg).max()
    if pk > .9: seg *= .9 / pk
    return seg


def fades(seg, fin=.002, fout=.03):
    n = len(seg); a = int(fin * R); b = min(n // 2, int(fout * R))
    seg = seg.copy(); seg[:a] *= np.linspace(0, 1, a); seg[n - b:] *= np.linspace(1, 0, b)
    return seg


# per surface loudness (dBFS of the loudest 60 ms): soft ground quieter, as it is
STEP_DB = {'concrete': -17, 'asphalt': -17.5, 'floor': -17, 'wood': -16.5, 'grass': -21, 'gravel': -18.5, 'carpet': -23, 'snow': -20}


def make_steps(dst, set_id, table):
    for surface, files in table.items():
        segs = []
        for f in files:
            if not os.path.exists(f): print('  missing', f); continue
            a = highpass(decode(f))
            segs += steps(a)
        segs = segs[:16]
        for n, s in enumerate(segs):
            encode(fades(level(s, STEP_DB[surface])), os.path.join(dst, 'step', set_id, f'{surface}_{n + 1:02d}.ogg'), q=4)
        print(f'step/{set_id}/{surface}: {len(segs)}')


def loop(a, seconds, fade=1.5, start=0.0):
    """A seamless loop of [seconds] from [start]: the end crossfaded (equal power) into the start."""
    s = int(start * R); n = int(seconds * R); f = int(fade * R)
    a = a[s:s + n + f]
    if len(a) < n + f: n = len(a) - f
    body = a[:n].copy()
    t = np.linspace(0, np.pi / 2, f)[:, None] if a.ndim == 2 else np.linspace(0, np.pi / 2, f)
    body[:f] = body[:f] * np.sin(t) + a[n:n + f] * np.cos(t)
    return body


def norm_rms(a, db):
    r = np.sqrt((a ** 2).mean() + 1e-12)
    a = a * (10 ** (db / 20) / r)
    pk = np.abs(a).max()
    return a * (.95 / pk) if pk > .95 else a


def one_shot(a, db, max_s=None, fout=.08):
    e = env(a if a.ndim == 1 else a.mean(axis=1)); thr = e.max() * .02
    idx = np.where(e > thr)[0]
    if len(idx): a = a[max(0, idx[0] * 240 - 240): min(len(a), (idx[-1] + 2) * 240)]
    if max_s: a = a[:int(max_s * R)]
    a = norm_rms(a, db)
    n = len(a); b = min(n // 3, int(fout * R)); fi = int(.003 * R)
    w = np.ones(n); w[:fi] = np.linspace(0, 1, fi); w[n - b:] = np.linspace(1, 0, b)
    return a * (w[:, None] if a.ndim == 2 else w)


def main(src, dst):
    S = lambda *p: os.path.join(src, *p)
    fs = lambda n: S('fsget', n + '.ogg')
    congus = lambda k, r: [S('foot_congus', 'x', f'footsteps__{k}__{i}.ogg') for i in r]
    # A: Freesound CC0 field recordings
    make_steps(dst, 'a', {
        'concrete': [fs('fb_concrete_1'), fs('fb_concrete_2'), fs('fb_concrete_3')],
        'asphalt': [fs('fb_asphalt_1'), fs('fb_asphalt_2')],
        'grass': [fs('fb_grass_1'), fs('fb_grass_2')],
        'gravel': [fs('fb_gravel_1'), fs('fb_gravel_2')],
        'wood': [fs('fb_wood_1'), fs('fb_wood_2')],
        'floor': [fs('fb_floor_1'), fs('fb_floor_2')],
        'carpet': [fs('fb_carpet_1'), fs('fb_carpet_2')],
        'snow': [fs('fb_snow_1'), fs('fb_snow_2')],
    })
    # B: swuing's foley (congusbongus's cut, CC-BY 3.0), Ali_6868's gravel (CC0)
    make_steps(dst, 'b', {
        'concrete': congus('boots', range(9)),
        'floor': congus('tile', range(9)),
        'grass': congus('grass', range(9)),
        'gravel': congus('gravel', range(10)),
        'wood': congus('wood', range(9)),
    })
    # C: OpenGameArt CC0 game packs
    fan = lambda k: sorted(glob.glob(S('foot_fantozzi', 'x', 'Fantozzi-footsteps', 'ogg', f'Fantozzi-{k}*.ogg')))
    tw = lambda k: sorted(glob.glob(S('foot_tinyworlds', 'x', f'{k}*.ogg')))
    rd = lambda k: sorted(glob.glob(S('foot_rubberduck', 'x', f'sfx100v2_{k}_0*.ogg')))
    make_steps(dst, 'c', {
        'concrete': fan('Stone') + rd('footstep') + tw('stone'),
        'floor': sorted(glob.glob(S('foot_kenney', 'x', 'OGG__footstep0*.ogg'))),
        'grass': tw('leaves'),
        'gravel': tw('gravel') + tw('mud') + fan('Sand'),
        'wood': tw('wood') + rd('footstep_wood'),
        'snow': sorted(glob.glob(S('foot_corsica', 'x', '*', '*.flac'))),
    })
    # ambience beds (stereo loops)
    beds = {
        'city_day': (fs('amb_city'), 40, 4, -27),
        'city_morning': (fs('amb_city_morning'), 40, 6, -27),
        'residential': (fs('amb_residential_traffic'), 50, 20, -29),
        'night_town': (fs('amb_night_traffic'), 50, 10, -31),
        'birds_day': (fs('amb_birdsong'), 50, 30, -27),
        'birds_park': (fs('amb_park_birds'), 40, 5, -27),
        'birds_garden': (S('amb_birds1', 'birds-isaiah658.ogg'), 28, 0, -28),
        'crowd': (fs('amb_crowd_murmur'), 20, 1, -26),
        'waves': (fs('amb_waves_1') if os.path.exists(fs('amb_waves_1')) else fs('waves_1'), 45, 10, -25),
        'river': (fs('river_1'), 30, 1, -26),
        'fountain': (fs('fountain_1'), 25, 2, -25),
        'highway_far': (S('foot_rubberduck', 'x', 'sfx100v2_loop_highway.ogg'), 15, 0, -30),
    }
    for name, (f, sec, start, db) in beds.items():
        if not os.path.exists(f): print('  missing', f); continue
        a = decode(f, mono=False)
        if len(a) < int((sec + 1.5) * R) + int(start * R): start = 0; sec = min(sec, len(a) / R - 1.6)
        encode(norm_rms(loop(a, sec, start=start), db), os.path.join(dst, 'amb', name + '.ogg'), q=3)
        print(f'amb/{name}: {sec:.0f} s')
    # one-shots
    shots = {
        'dog_far_1': (fs('dog_distant_1'), -22, None, True), 'dog_far_2': (fs('dog_distant_2'), -22, 8, True),
        'dog_far_3': (fs('dog_distant_3'), -22, None, True), 'dog_near_1': (S('amb_dog1', 'dog_barking_mono.wav'), -20, None, True),
        'shop_bell_1': (fs('shop_bell_1'), -20, 3, True), 'shop_bell_2': (fs('shop_bell_2'), -20, None, True),
        'car_door_close_1': (fs('door_close_1'), -18, None, True), 'car_door_close_2': (fs('door_close_2'), -18, None, True),
        'car_door_close_3': (fs('door_close_3'), -18, None, True),
        'car_door_open': (S('eng_ggbot', 'x', 'Car_Door_Open.ogg'), -22, None, True),
        'crash_clank': (fs('crash_clank'), -16, None, True), 'crash_glass': (fs('crash_glass'), -15, None, True),
        'crash_big': (fs('crash_big'), -14, 3.5, True),
        'thunder_1': (S('foot_rubberduck', 'x', 'sfx100v2_thunder_01.ogg'), -20, None, False),
        'horses_pass': (fs('horses'), -22, 20, False),
    }
    for name, (f, db, mx, mono) in shots.items():
        if not os.path.exists(f): print('  missing', f); continue
        encode(one_shot(decode(f, mono=mono), db, mx), os.path.join(dst, 'sfx', name + '.ogg'), q=4)
        print(f'sfx/{name}')
    # the recorded engine mode's loops (mono): idles by family, and load loops at their measured engine speeds
    eng = {
        'idle_v8': (fs('eng_v8_idle'), 6, 2), 'idle_old': (fs('eng_worn_idle'), 5, 1), 'idle_car': (fs('eng_car_idle'), 6, 4),
        'idle_four': (fs('eng_idle_loop'), 6, 2),
    }
    for name, (f, sec, start) in eng.items():
        if not os.path.exists(f): continue
        a = decode(f)
        encode(norm_rms(loop(a, sec, fade=.25, start=start), -18), os.path.join(dst, 'engine', name + '.ogg'), q=4)
        print(f'engine/{name}')
    for i in range(6):
        f = S(f'eng_domasx2_{i}', f'loop_{i}.wav' if i == 0 else f'loop_{i}_0.wav')
        if os.path.exists(f):
            a = decode(f)
            encode(norm_rms(a, -18), os.path.join(dst, 'engine', f'load_{i}.ogg'), q=4)
            print(f'engine/load_{i}')


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
