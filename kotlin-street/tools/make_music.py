#!/usr/bin/env python3
"""The game's music, made with ACE-Step 1.5 (acestep.cpp, MIT; run on a computer): one instrumental track for every
kind of town (src/sim/archetype.ts) and every mood the street has (audio/SoundRules.kt), named music_TOWN_MOOD.wav for
tools/make_audio.py.  python3 make_music.py ACE_SYNTH MODELS_DIR OUT_DIR [TOWN ...]"""
import json, os, subprocess, sys

# each town's own sound: its instruments and the music its people would play
TOWNS = {
    'suburb': 'mid-century American easy listening, soft jazz piano, light strings, brushed drums, celesta accents',
    'harbor': 'maritime folk, accordion, concertina, fiddle, tin whistle, gentle sea-shanty lilt, distant harbor feel',
    'farm': 'old-time American country, fiddle, banjo, harmonica, acoustic guitar, upright bass, front-porch feel',
    'mill': 'working-class American folk, upright honky-tonk piano, small brass band, acoustic guitar, steady and sturdy',
    'mining': 'lonesome Appalachian folk, slide dobro guitar, banjo, harmonica, sparse fiddle, mountain hollow mood',
    'resort': 'breezy lakeside lounge, ukulele, vibraphone, lap steel guitar, soft bongos, sunny vacation exotica',
}
# each mood of the day (the music's key and pace with it)
MOODS = {
    'morning': ('gentle and hopeful, a town waking up, bright, unhurried', 84, 'G major'),
    'day': ('cheerful and lively, a stroll down Main Street, warm', 104, 'C major'),
    'evening': ('mellow and warm, golden hour winding down, relaxed', 76, 'F major'),
    'night': ('quiet and sparse, slow and tender, late night, lamplight, soft dynamics', 62, 'A minor'),
    'rain': ('melancholy and reflective, slow, rain on the windows, soft and intimate', 66, 'D minor'),
}

def main(synth, models, out, towns):
    os.makedirs(out, exist_ok=True)
    for town in towns or list(TOWNS):
        for mood, (feel, bpm, key) in MOODS.items():
            wav = os.path.join(out, f'music_{town}_{mood}.wav')
            if os.path.isfile(wav):
                continue
            req = {
                'synth_model': 'acestep-v15-turbo-Q8_0.gguf',
                'caption': f'Instrumental background music for a cozy pixel-art life simulation game, {TOWNS[town]}, {feel}. '
                           'No vocals. Loopable, consistent, not too busy, warm analog recording.',
                'lyrics': '[Instrumental]',
                'bpm': bpm, 'duration': 75, 'keyscale': key, 'timesignature': '4', 'vocal_language': 'en',
                'inference_steps': 8, 'guidance_scale': 1.0, 'shift': 3.0, 'seed': 1234,
            }
            rq = os.path.join(out, f'music_{town}_{mood}.json')
            json.dump(req, open(rq, 'w'), indent=1)
            before = set(os.listdir(out))
            r = subprocess.run([synth, '--models', models, '--request', rq], capture_output=True, text=True, cwd=out)
            # the tool names its output after the request: take whatever audio file it just wrote
            new = [f for f in os.listdir(out) if f not in before and f.endswith(('.wav', '.mp3'))]
            if new:
                src = os.path.join(out, new[0])
                if src.endswith('.mp3'): subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-i', src, wav], check=True); os.remove(src)
                else: os.rename(src, wav)
            print(town, mood, 'rc', r.returncode, 'ok' if new else 'NO AUDIO', (r.stdout + r.stderr).strip().splitlines()[-1:])
            sys.stdout.flush()

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4:])
