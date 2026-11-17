#!/usr/bin/env python3
"""Turns generated sounds (MOSS-SoundEffect, ACE-Step: WAV) into the game's audio assets: leading and trailing silence
trimmed (one-shots), loudness evened out per kind, short fades so nothing clicks, and Ogg Vorbis at 48 kHz (what
audio/Pcm.kt decodes). Music gets a long fade at both ends so it loops through the mixer's crossfade.
  python3 make_audio.py SRC_DIR ASSETS_AUDIO_DIR      (needs ffmpeg)
Files named music_*.wav go to music/ (music_night.wav -> music/night.ogg); amb_*.wav to amb/; the rest to sfx/."""
import os, subprocess, sys, wave, struct, math

def stats(path):
    w = wave.open(path); n = w.getnframes(); ch = w.getnchannels(); d = w.readframes(n)
    s = struct.unpack('<%dh' % (n * ch), d) if w.getsampwidth() == 2 else []
    rms = math.sqrt(sum(x * x for x in s[::7]) / max(1, len(s[::7]))) / 32768 if s else 0
    return n / w.getframerate(), rms

def run(src, dst):
    for f in sorted(os.listdir(src)):
        if not f.endswith('.wav') or f.startswith('test'): continue
        name = f[:-4]
        if name.startswith('music_'): kind, out = 'music', os.path.join(dst, 'music', name[6:] + '.ogg')
        elif name.startswith('amb_'): kind, out = 'amb', os.path.join(dst, 'amb', name[4:] + '.ogg')
        else: kind, out = 'sfx', os.path.join(dst, 'sfx', name + '.ogg')
        os.makedirs(os.path.dirname(out), exist_ok=True)
        dur, rms = stats(os.path.join(src, f))
        if kind == 'sfx':
            # trim the silence round it, a 5 ms fade in and 40 ms out, loudness to about -16 LUFS
            af = ('silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.01,areverse,'
                  'silenceremove=start_periods=1:start_threshold=-50dB:start_silence=0.02,areverse,'
                  'afade=t=in:d=0.005,areverse,afade=t=in:d=0.04,areverse,loudnorm=I=-16:TP=-1.5:LRA=11')
        elif kind == 'music':
            af = f'afade=t=in:d=2,afade=t=out:st={max(0.0, dur - 3):.2f}:d=3,loudnorm=I=-18:TP=-1.5:LRA=11'
        else:
            af = 'loudnorm=I=-22:TP=-2:LRA=11'
        cmd = ['ffmpeg', '-loglevel', 'error', '-y', '-i', os.path.join(src, f), '-af', af, '-ar', '48000', '-c:a', 'libvorbis', '-q:a', '6', out]
        subprocess.run(cmd, check=True)
        print(f'{f} ({dur:.1f} s, rms {rms:.3f}) -> {os.path.relpath(out, dst)}')

if __name__ == '__main__':
    run(sys.argv[1], sys.argv[2])
