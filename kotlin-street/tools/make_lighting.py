#!/usr/bin/env python3
"""Lighting maps for the building fronts, from their depth (Depth Anything V2 Small, Apache-2.0, ONNX; run on a computer,
never on the phone). For each bN_front.png in the buildings folder:
  bN_lit.png    R, G, B: how much brighter each pixel gets with the sun from the left, the right and above (than a flat
                wall does): ledges, sills and signs catch the light on their tops and their sunny side
  bN_shade.png  R, G, B: how much darker, for the same three lights (the undersides and the far side of what sticks out);
                A: ambient occlusion (recesses: windows' reveals, doorways, under ledges), dark at every hour
The engine (BuildingPainter) lays them over the front by the sun's side and height. Run after make_buildings.py (which
clears the folder).  Usage: python3 make_lighting.py depth_anything_v2_small.onnx buildings_dir"""
import glob, os, sys
import numpy as np
import onnxruntime as ort
from PIL import Image

STRENGTH = 3.2   # depth (0..1 over a front) to slope

def blur(a, sigma):
    """A separable Gaussian blur of a float image (edges held)."""
    r = int(3 * sigma); x = np.arange(-r, r + 1); k = np.exp(-x * x / (2 * sigma * sigma)); k /= k.sum()
    p = np.pad(a, r, mode='edge')
    p = np.apply_along_axis(lambda v: np.convolve(v, k, mode='valid'), 0, p)
    return np.apply_along_axis(lambda v: np.convolve(v, k, mode='valid'), 1, p)

def depth(sess, im):
    """Relative depth (bigger nearer) at the sprite's own size, the model's sense of the whole picture taken out."""
    bg = Image.new('RGBA', im.size, (128, 150, 180, 255)); bg.alpha_composite(im)
    w = 518; h = max(14, round(w * im.height / im.width / 14) * 14)
    x = bg.convert('RGB').resize((w, h), Image.NEAREST)
    a = (np.asarray(x).astype(np.float32) / 255 - [.485, .456, .406]) / [.229, .224, .225]
    out = sess.run(None, {'pixel_values': a.transpose(2, 0, 1)[None].astype(np.float32)})[0][0]
    d = np.asarray(Image.fromarray(out).resize(im.size, Image.BILINEAR)).astype(np.float32)
    d = (d - d.min()) / (np.ptp(d) + 1e-6)
    # the model reads a picture as a scene seen from above (lower = nearer): keep only what stands out of the wall nearby
    big = blur(d, 18)
    return d - big

def stepped(d, rgba):
    """The depth snapped to the art: each patch of one colour (4-connected) takes its median depth, so the relief has
    the sprite's own crisp edges (light and shade fall along the pixel art's lines, not in soft halos)."""
    h, w = d.shape
    key = (rgba[..., 0].astype(np.int64) << 24) | (rgba[..., 1].astype(np.int64) << 16) | (rgba[..., 2].astype(np.int64) << 8) | rgba[..., 3]
    lab = -np.ones((h, w), np.int64); out = d.copy(); n = 0
    for y in range(h):
        for x in range(w):
            if lab[y, x] >= 0: continue
            k = key[y, x]; st = [(y, x)]; lab[y, x] = n; px = []
            while st:
                cy, cx = st.pop(); px.append((cy, cx))
                for ny, nx in ((cy + 1, cx), (cy - 1, cx), (cy, cx + 1), (cy, cx - 1)):
                    if 0 <= ny < h and 0 <= nx < w and lab[ny, nx] < 0 and key[ny, nx] == k:
                        lab[ny, nx] = n; st.append((ny, nx))
            ys, xs = zip(*px)
            out[ys, xs] = np.median(d[ys, xs])
            n += 1
    return out

def maps(d, alpha):
    gy, gx = np.gradient(d)
    n = np.dstack([-gx * STRENGTH * d.shape[1] / 22, -gy * STRENGTH * d.shape[1] / 22, np.ones_like(d)])
    n /= np.linalg.norm(n, axis=2, keepdims=True)
    lights = [(-1, -.55, .75), (1, -.55, .75), (0, -1, .55)]       # from the left, the right, above (y down)
    lit = np.zeros(d.shape + (3,), np.float32); shade = np.zeros(d.shape + (4,), np.float32)
    for i, l in enumerate(lights):
        l = np.array(l, np.float32); l /= np.linalg.norm(l)
        delta = (n @ l) - l[2]                                         # against a flat wall's light
        lit[..., i] = np.clip(delta * 2.2, 0, 1)
        shade[..., i] = np.clip(-delta * 2.2, 0, 1)
    # recesses: below their surroundings
    mean = blur(d, 3)
    shade[..., 3] = np.clip((mean - d) * 9, 0, 1)
    solid = (alpha > 40)[..., None]
    return (lit * solid * 255).astype(np.uint8), (shade * solid * 255).astype(np.uint8)

def main(model, folder):
    sess = ort.InferenceSession(model)
    n = 0
    for f in sorted(glob.glob(os.path.join(folder, '*_front.png'))):
        im = Image.open(f).convert('RGBA')
        rgba = np.asarray(im)
        alpha = rgba[..., 3].copy()
        # what is behind the shopfront glass is not the wall: no relief light on the display (make_buildings.py's mask)
        g = f[:-len('_front.png')] + '_glass.png'
        if os.path.isfile(g):
            gm = np.asarray(Image.open(g).convert('RGBA'))[..., 3]
            if gm.shape == alpha.shape: alpha[gm > 60] = 0
        lit, shade = maps(stepped(depth(sess, im), rgba), alpha)
        base = f[:-len('_front.png')]
        Image.fromarray(lit, 'RGB').save(base + '_lit.png')
        Image.fromarray(shade, 'RGBA').save(base + '_shade.png')
        n += 1
    print('lighting maps for', n, 'fronts in', folder)

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
