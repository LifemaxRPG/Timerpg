#!/usr/bin/env python3
"""Re-skins the gravel of the Alder Bend Ballpark's car park (C003, docs/LANDMARKS_GLB.md "The third intake"): the contributor's `ground_gravel`
picture is a blue-grey 32 x 32 (139,139,158 with white flecks) that reads almost white under the town's light. It is replaced by the town's own
gravel, the picture the general store's yard is paved with (shared/src/filament/kotlin/scene3d/StoreGrounds.kt `GRAVEL`: 32 x 32, one texel in
a hundred and the same four warm tones, drawn by the same generator, `Paint.Rnd(7)`), so the lot is the same gravel as the yards'.
The grid is already the art's: the model's UVs lay one 32-texel picture over 1.2416 m, i.e. 0.0388 m a texel (checked, `--check`).
The new picture replaces the old in every state and every level (near, mid, far): the .glb's bin chunk gets the new PNG appended and the
image's bufferView points at it (the old 500 bytes stay as dead weight; nothing else moves).
Run from kotlin-street/:  python3 tools/landmarks/reskin_gravel.py [--check]"""
import json, struct, sys, os, zlib, glob

A = 'app/src/main/assets/landmarks/baseball-park'
MASK = (1 << 64) - 1

def town_gravel():
    """StoreGrounds.GRAVEL: PixImg(32, 32), Paint.Rnd(7), for y, for x: t < .2 -> 8A8070, < .4 -> B0A690, < .45 -> 6A6458, else 9E9480."""
    s = (7 * 6364136223846793005 + 1442695040888963407) & MASK
    cols = {'a': (0x8A, 0x80, 0x70), 'b': (0xB0, 0xA6, 0x90), 'c': (0x6A, 0x64, 0x58), 'd': (0x9E, 0x94, 0x80)}
    px = []
    for y in range(32):
        row = []
        for x in range(32):
            s = (s * 6364136223846793005 + 1442695040888963407) & MASK
            t = ((s >> 40) & 0xFFFFFF) / 16777216.0
            row.append(cols['a'] if t < .2 else cols['b'] if t < .4 else cols['c'] if t < .45 else cols['d'])
        px.append(row)
    return px

def png(px):
    h, w = len(px), len(px[0])
    raw = b''.join(b'\x00' + b''.join(bytes(c) for c in r) for r in px)
    def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')

def read(path):
    b = open(path, 'rb').read()
    n = struct.unpack('<I', b[12:16])[0]
    j = json.loads(b[20:20 + n])
    off = 20 + n
    bl = struct.unpack('<I', b[off:off + 4])[0]
    return j, b[off + 8:off + 8 + bl]

def write(path, j, binb):
    jb = json.dumps(j, separators=(',', ':')).encode()
    jb += b' ' * (-len(jb) % 4)
    binb += b'\x00' * (-len(binb) % 4)
    out = struct.pack('<III', 0x46546C67, 2, 12 + 8 + len(jb) + 8 + len(binb)) + struct.pack('<II', len(jb), 0x4E4F534A) + jb + struct.pack('<II', len(binb), 0x004E4942) + binb
    open(path, 'wb').write(out)

def gravel_image(j):
    m = next(m for m in j['materials'] if m['name'] == 'ground_gravel')
    return j['textures'][m['pbrMetallicRoughness']['baseColorTexture']['index']]['source']

def main(check):
    new = png(town_gravel())
    for f in sorted(glob.glob(f'{A}/alder-bend-*.glb')):
        j, binb = read(f)
        i = gravel_image(j)
        if check:
            bv = j['bufferViews'][j['images'][i]['bufferView']]
            print(f, 'image', i, j['images'][i].get('name'), bv['byteLength'], 'bytes')
            continue
        if j['images'][i].get('name') != 'gravel' or j['bufferViews'][j['images'][i]['bufferView']]['byteLength'] == len(new):
            print('skip (done already?)', f); continue
        binb += b'\x00' * (-len(binb) % 4)
        j['bufferViews'].append({'buffer': 0, 'byteOffset': len(binb), 'byteLength': len(new)})
        j['images'][i]['bufferView'] = len(j['bufferViews']) - 1
        binb += new
        j['buffers'][0]['byteLength'] = len(binb)
        write(f, j, binb)
        print('re-skinned', f)

if __name__ == '__main__':
    main('--check' in sys.argv)
