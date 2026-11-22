/**
 * Pure Node.js software 3D rasterizer for Rex — Jacksonville Beach Blvd Dinosaur Statue (Est. 1964).
 * Produces ONE clean composite review sheet (beach-blvd-rex-review.png, 2336x1360) arranged as:
 *   2 rows x 3 columns:
 *     1. Front 3/4 Day (Hero)  | 2. Night / Blue Hour (Glowing Red Eyes) | 3. Side Profile + 1.7m Human Scale
 *     4. Head & Eyes Close-Up  | 5. Back 3/4 (Tail & Dorsal Ridge)       | 6. Person-Height Sidewalk View (1.6m)
 * Supports separate baseColorTexture + black-masked emissiveTexture (eyes + ground uplights).
 * Note: Software renders (not in-engine Filament phone captures).
 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

const FONT_5X7 = {
  ' ': ['00000', '00000', '00000', '00000', '00000', '00000', '00000'],
  '-': ['00000', '00000', '00000', '11111', '00000', '00000', '00000'],
  '.': ['00000', '00000', '00000', '00000', '00000', '01100', '01100'],
  ',': ['00000', '00000', '00000', '00000', '00110', '00100', '01000'],
  '/': ['00001', '00010', '00010', '00100', '01000', '01000', '10000'],
  '|': ['00100', '00100', '00100', '00100', '00100', '00100', '00100'],
  ':': ['00000', '00100', '00100', '00000', '00100', '00100', '00000'],
  '(': ['00010', '00100', '01000', '01000', '01000', '00100', '00010'],
  ')': ['01000', '00100', '00010', '00010', '00010', '00100', '01000'],
  '+': ['00000', '00100', '00100', '11111', '00100', '00100', '00000'],
  '&': ['01100', '10010', '10100', '01000', '10101', '10010', '01101'],
  '0': ['01110', '10001', '10011', '10101', '11001', '10001', '01110'],
  '1': ['00100', '01100', '00100', '00100', '00100', '00100', '01110'],
  '2': ['01110', '10001', '00001', '00110', '01000', '10000', '11111'],
  '3': ['11110', '00001', '00001', '01110', '00001', '00001', '11110'],
  '4': ['00010', '00110', '01010', '10010', '11111', '00010', '00010'],
  '5': ['11111', '10000', '11110', '00001', '00001', '10001', '01110'],
  '6': ['01110', '10000', '11110', '10001', '10001', '10001', '01110'],
  '7': ['11111', '00001', '00010', '00100', '01000', '01000', '01000'],
  '8': ['01110', '10001', '10001', '01110', '10001', '10001', '01110'],
  '9': ['01110', '10001', '10001', '01111', '00001', '00010', '11100'],
  'A': ['01110', '10001', '10001', '11111', '10001', '10001', '10001'],
  'B': ['11110', '10001', '10001', '11110', '10001', '10001', '11110'],
  'C': ['01110', '10001', '10000', '10000', '10000', '10001', '01110'],
  'D': ['11100', '10010', '10001', '10001', '10001', '10010', '11100'],
  'E': ['11111', '10000', '10000', '11110', '10000', '10000', '11111'],
  'F': ['11111', '10000', '10000', '11110', '10000', '10000', '10000'],
  'G': ['01110', '10001', '10000', '10111', '10001', '10001', '01110'],
  'H': ['10001', '10001', '10001', '11111', '10001', '10001', '10001'],
  'I': ['01110', '00100', '00100', '00100', '00100', '00100', '01110'],
  'J': ['00111', '00010', '00010', '00010', '10010', '10010', '01100'],
  'K': ['10001', '10010', '10100', '11000', '10100', '10010', '10001'],
  'L': ['10000', '10000', '10000', '10000', '10000', '10000', '11111'],
  'M': ['10001', '11011', '10101', '10101', '10001', '10001', '10001'],
  'N': ['10001', '11001', '10101', '10011', '10001', '10001', '10001'],
  'O': ['01110', '10001', '10001', '10001', '10001', '10001', '01110'],
  'P': ['11110', '10001', '10001', '11110', '10000', '10000', '10000'],
  'Q': ['01110', '10001', '10001', '10001', '10101', '10010', '01101'],
  'R': ['11110', '10001', '10001', '11110', '10100', '10010', '10001'],
  'S': ['01111', '10000', '10000', '01110', '00001', '00001', '11110'],
  'T': ['11111', '00100', '00100', '00100', '00100', '00100', '00100'],
  'U': ['10001', '10001', '10001', '10001', '10001', '10001', '01110'],
  'V': ['10001', '10001', '10001', '10001', '10001', '01010', '00100'],
  'W': ['10001', '10001', '10001', '10101', '10101', '11011', '10001'],
  'X': ['10001', '10001', '01010', '00100', '01010', '10001', '10001'],
  'Y': ['10001', '10001', '01010', '00100', '00100', '00100', '00100'],
  'Z': ['11111', '00001', '00010', '00100', '01000', '10000', '11111'],
};

function decodePng(buf) {
  let pos = 8;
  let w = 0, h = 0;
  const idatChunks = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    const chunk = buf.subarray(pos + 8, pos + 8 + len);
    pos += 12 + len;
    if (type === 'IHDR') {
      w = chunk.readUInt32BE(0);
      h = chunk.readUInt32BE(4);
    } else if (type === 'IDAT') {
      idatChunks.push(chunk);
    }
  }
  const raw = zlib.inflateSync(Buffer.concat(idatChunks));
  const stride = w * 4;
  const pixels = new Uint8Array(w * h * 4);
  let rIdx = 0;
  for (let y = 0; y < h; y++) {
    const filter = raw[rIdx++];
    const rowOff = y * stride;
    const prevOff = (y - 1) * stride;
    for (let i = 0; i < stride; i++) {
      const x = raw[rIdx++];
      const a = i >= 4 ? pixels[rowOff + i - 4] : 0;
      const b = y > 0 ? pixels[prevOff + i] : 0;
      const c = (y > 0 && i >= 4) ? pixels[prevOff + i - 4] : 0;
      let val = x;
      if (filter === 1) val = (x + a) & 0xff;
      else if (filter === 2) val = (x + b) & 0xff;
      else if (filter === 3) val = (x + ((a + b) >> 1)) & 0xff;
      else if (filter === 4) {
        const p = a + b - c;
        const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        const pr = (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
        val = (x + pr) & 0xff;
      }
      pixels[rowOff + i] = val;
    }
  }
  return { width: w, height: h, data: pixels };
}

function encodePng(width, height, rgba) {
  const stride = width * 4;
  const raw = Buffer.alloc((stride + 1) * height);
  for (let y = 0; y < height; y++) {
    raw[y * (stride + 1)] = 0;
    Buffer.from(rgba.buffer, rgba.byteOffset + y * stride, stride).copy(raw, y * (stride + 1) + 1);
  }
  const compressed = zlib.deflateSync(raw, { level: 6 });
  const crcTable = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
    crcTable[n] = c >>> 0;
  }
  function crc32(buf) {
    let c = 0xffffffff;
    for (let i = 0; i < buf.length; i++) c = crcTable[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
    return (c ^ 0xffffffff) >>> 0;
  }
  function makeChunk(type, data) {
    const out = Buffer.alloc(12 + data.length);
    out.writeUInt32BE(data.length, 0);
    out.write(type, 4, 4, 'ascii');
    data.copy(out, 8);
    out.writeUInt32BE(crc32(out.subarray(4, 8 + data.length)), 8 + data.length);
    return out;
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    makeChunk('IHDR', ihdr),
    makeChunk('IDAT', compressed),
    makeChunk('IEND', Buffer.alloc(0)),
  ]);
}

function loadGlb(glbPath) {
  const buf = fs.readFileSync(glbPath);
  const jsonLen = buf.readUInt32LE(12);
  const gltf = JSON.parse(buf.toString('utf8', 20, 20 + jsonLen));
  const binOff = 20 + jsonLen + 8;
  const binLen = buf.readUInt32LE(20 + jsonLen);
  const bin = buf.subarray(binOff, binOff + binLen);

  const images = gltf.images.map((img) => {
    const bv = gltf.bufferViews[img.bufferView];
    return decodePng(bin.subarray(bv.byteOffset || 0, (bv.byteOffset || 0) + bv.byteLength));
  });

  function readAcc(idx) {
    const acc = gltf.accessors[idx];
    const bv = gltf.bufferViews[acc.bufferView];
    const off = bv.byteOffset || 0;
    const comp = { SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4 }[acc.type];
    const out = [];
    for (let i = 0; i < acc.count; i++) {
      if (acc.componentType === 5126) {
        const arr = [];
        for (let k = 0; k < comp; k++) arr.push(bin.readFloatLE(off + (i * comp + k) * 4));
        out.push(arr);
      } else if (acc.componentType === 5125) {
        out.push(bin.readUInt32LE(off + i * 4));
      } else if (acc.componentType === 5123) {
        out.push(bin.readUInt16LE(off + i * 2));
      }
    }
    return out;
  }

  const drawTriangles = [];
  for (const node of gltf.nodes) {
    if (node.name === 'collision' || (node.extras && node.extras.collision_only)) continue;
    if (node.mesh === undefined) continue;
    const mesh = gltf.meshes[node.mesh];
    for (const prim of mesh.primitives) {
      const pos = readAcc(prim.attributes.POSITION);
      const nrm = readAcc(prim.attributes.NORMAL);
      const uv = readAcc(prim.attributes.TEXCOORD_0);
      const idx = readAcc(prim.indices);
      const mat = gltf.materials[prim.material];
      const baseTex = mat.pbrMetallicRoughness.baseColorTexture.index;
      const img = images[gltf.textures[baseTex].source];
      const emImg = mat.emissiveTexture ? images[gltf.textures[mat.emissiveTexture.index].source] : null;
      const isBlend = mat.alphaMode === 'BLEND';
      const isGlass = mat.name.startsWith('glass_');

      for (let t = 0; t < idx.length; t += 3) {
        const i0 = idx[t], i1 = idx[t + 1], i2 = idx[t + 2];
        drawTriangles.push({
          v0: pos[i0], v1: pos[i1], v2: pos[i2],
          n: nrm[i0],
          uv0: uv[i0], uv1: uv[i1], uv2: uv[i2],
          img, emImg, isBlend, isGlass,
          doubleSided: Boolean(mat.doubleSided),
        });
      }
    }
  }
  return { drawTriangles };
}

function vSub(a, b) { return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]; }
function vDot(a, b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
function vCross(a, b) {
  return [
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
  ];
}
function vNorm(a) {
  const l = Math.hypot(a[0], a[1], a[2]) || 1;
  return [a[0] / l, a[1] / l, a[2] / l];
}

function sampleNearestRepeat(img, u, v) {
  let fu = u - Math.floor(u);
  let fv = v - Math.floor(v);
  if (fu < 0) fu += 1;
  if (fv < 0) fv += 1;
  const px = Math.min(img.width - 1, Math.floor(fu * img.width));
  const py = Math.min(img.height - 1, Math.floor(fv * img.height));
  const off = (py * img.width + px) * 4;
  return [img.data[off], img.data[off + 1], img.data[off + 2], img.data[off + 3]];
}

function clipNear(poly, nearZ) {
  const out = [];
  for (let i = 0; i < poly.length; i++) {
    const a = poly[i];
    const b = poly[(i + 1) % poly.length];
    const aIn = a.cz >= nearZ;
    const bIn = b.cz >= nearZ;
    if (aIn && bIn) out.push(b);
    else if (aIn && !bIn) {
      const t = (nearZ - a.cz) / (b.cz - a.cz);
      out.push({ cx: a.cx + t * (b.cx - a.cx), cy: a.cy + t * (b.cy - a.cy), cz: nearZ, u: a.u + t * (b.u - a.u), v: a.v + t * (b.v - a.v) });
    } else if (!aIn && bIn) {
      const t = (nearZ - a.cz) / (b.cz - a.cz);
      out.push({ cx: a.cx + t * (b.cx - a.cx), cy: a.cy + t * (b.cy - a.cy), cz: nearZ, u: a.u + t * (b.u - a.u), v: a.v + t * (b.v - a.v) });
      out.push(b);
    }
  }
  return out;
}

function renderPanel(scene, opts) {
  const W = opts.width || 500;
  const H = opts.height || 420;
  const rgba = new Uint8Array(W * H * 4);
  const zbuf = new Float32Array(W * H);
  zbuf.fill(1e9);

  const topSky = opts.skyTop;
  const botSky = opts.skyBottom;
  for (let y = 0; y < H; y++) {
    const t = y / (H - 1);
    const r = Math.round(topSky[0] * (1 - t) + botSky[0] * t);
    const g = Math.round(topSky[1] * (1 - t) + botSky[1] * t);
    const b = Math.round(topSky[2] * (1 - t) + botSky[2] * t);
    for (let x = 0; x < W; x++) {
      const off = (y * W + x) * 4;
      rgba[off] = r; rgba[off + 1] = g; rgba[off + 2] = b; rgba[off + 3] = 255;
    }
  }

  const eye = opts.eye;
  const target = opts.target;
  const upHint = opts.up || [0, 1, 0];
  const fwd = vNorm(vSub(target, eye));
  const right = vNorm(vCross(fwd, upHint));
  const up = vNorm(vCross(right, fwd));

  const fovRad = ((opts.fovDeg || 42) * Math.PI) / 180;
  const focal = (H * 0.5) / Math.tan(fovRad * 0.5);
  const sunDir = vNorm(opts.sunDir || [0.45, 0.78, 0.42]);
  const nightMode = Boolean(opts.nightMode);

  function toCam(p, uv) {
    const d = vSub(p, eye);
    return { cx: vDot(d, right), cy: vDot(d, up), cz: vDot(d, fwd), u: uv[0], v: uv[1] };
  }

  function projectVert(c) {
    if (opts.orthoScale) {
      const s = opts.orthoScale;
      return { sx: W * 0.5 + c.cx * s, sy: H * 0.5 - c.cy * s, cz: c.cz, invZ: 1.0, uOverZ: c.u, vOverZ: c.v };
    }
    const invZ = 1.0 / c.cz;
    return { sx: W * 0.5 + c.cx * focal * invZ, sy: H * 0.5 - c.cy * focal * invZ, cz: c.cz, invZ, uOverZ: c.u * invZ, vOverZ: c.v * invZ };
  }

  const opaqueTris = scene.drawTriangles.filter((t) => !t.isBlend);
  const blendTris = scene.drawTriangles.filter((t) => t.isBlend);

  function rasterTriangle(p0, p1, p2, tri, isTransparentPass) {
    const area = (p1.sx - p0.sx) * (p2.sy - p0.sy) - (p1.sy - p0.sy) * (p2.sx - p0.sx);
    if (Math.abs(area) < 1e-4) return;
    if (!tri.doubleSided && area > 0) return;

    const minX = Math.max(0, Math.floor(Math.min(p0.sx, p1.sx, p2.sx)));
    const maxX = Math.min(W - 1, Math.ceil(Math.max(p0.sx, p1.sx, p2.sx)));
    const minY = Math.max(0, Math.floor(Math.min(p0.sy, p1.sy, p2.sy)));
    const maxY = Math.min(H - 1, Math.ceil(Math.max(p0.sy, p1.sy, p2.sy)));
    if (minX > maxX || minY > maxY) return;

    const invArea = 1.0 / area;
    const n = tri.n;
    const ndotl = Math.max(0, vDot(n, sunDir));
    const hemi = 0.5 + 0.5 * n[1];
    let shade = nightMode ? (0.20 + 0.14 * hemi + 0.18 * ndotl) : (0.48 + 0.20 * hemi + 0.40 * ndotl);

    // Subtle warm upward floodlight bounce from the two front mulch uplights at night
    let upR = 0, upG = 0, upB = 0;
    if (nightMode) {
      const cx = (tri.v0[0] + tri.v1[0] + tri.v2[0]) / 3;
      const cy = (tri.v0[1] + tri.v1[1] + tri.v2[1]) / 3;
      const cz = (tri.v0[2] + tri.v1[2] + tri.v2[2]) / 3;
      for (const lx of [-1.45, 1.55]) {
        const dx = cx - lx, dy = cy - 0.35, dz = cz - (-2.42);
        const dist2 = dx * dx + dy * dy + dz * dz + 0.8;
        const dist = Math.sqrt(dist2);
        const ldir = [-dx / dist, -dy / dist, -dz / dist];
        const ndl = Math.max(0, vDot(n, ldir));
        const cone = Math.max(0, (dy / dist) * 0.78 + (-dz / dist) * 0.62);
        const atten = (ndl * cone * cone) / (0.18 + 0.11 * dist2);
        upR += atten * 0.48;
        upG += atten * 0.38;
        upB += atten * 0.22;
      }
    }

    for (let py = minY; py <= maxY; py++) {
      const sy = py + 0.5;
      for (let px = minX; px <= maxX; px++) {
        const sx = px + 0.5;
        const w0 = ((p1.sx - sx) * (p2.sy - sy) - (p1.sy - sy) * (p2.sx - sx)) * invArea;
        const w1 = ((p2.sx - sx) * (p0.sy - sy) - (p2.sy - sy) * (p0.sx - sx)) * invArea;
        const w2 = 1.0 - w0 - w1;
        if (w0 < -1e-5 || w1 < -1e-5 || w2 < -1e-5) continue;

        const invZ = w0 * p0.invZ + w1 * p1.invZ + w2 * p2.invZ;
        const cz = opts.orthoScale ? (w0 * p0.cz + w1 * p1.cz + w2 * p2.cz) : (1.0 / invZ);
        const idx = py * W + px;
        if (cz >= zbuf[idx]) continue;

        const u = (w0 * p0.uOverZ + w1 * p1.uOverZ + w2 * p2.uOverZ) / invZ;
        const v = (w0 * p0.vOverZ + w1 * p1.vOverZ + w2 * p2.vOverZ) / invZ;
        const tex = sampleNearestRepeat(tri.img, u, v);
        if (tex[3] < 12) continue;

        let r = Math.min(255, Math.max(0, Math.round(tex[0] * (shade + upR))));
        let g = Math.min(255, Math.max(0, Math.round(tex[1] * (shade + upG))));
        let b = Math.min(255, Math.max(0, Math.round(tex[2] * (shade + upB))));

        if (tri.emImg) {
          const em = sampleNearestRepeat(tri.emImg, u, v);
          const emBoost = nightMode ? 1.25 : 0.28;
          r = Math.min(255, Math.round(r + em[0] * emBoost));
          g = Math.min(255, Math.round(g + em[1] * emBoost));
          b = Math.min(255, Math.round(b + em[2] * emBoost));
        } else if (nightMode && tri.isGlass && tex[3] < 240) {
          r = Math.min(255, r + 132);
          g = Math.min(255, g + 96);
          b = Math.min(255, b + 38);
        }

        const off = idx * 4;
        if (!isTransparentPass || tex[3] >= 245) {
          zbuf[idx] = cz;
          rgba[off] = r; rgba[off + 1] = g; rgba[off + 2] = b; rgba[off + 3] = 255;
        } else {
          const a = tex[3] / 255.0;
          rgba[off] = Math.round(r * a + rgba[off] * (1 - a));
          rgba[off + 1] = Math.round(g * a + rgba[off + 1] * (1 - a));
          rgba[off + 2] = Math.round(b * a + rgba[off + 2] * (1 - a));
        }
      }
    }
  }

  const nearZ = opts.orthoScale ? -500.0 : 0.12;
  for (const pass of [{ list: opaqueTris, transparent: false }, { list: blendTris, transparent: true }]) {
    for (const tri of pass.list) {
      const rawPoly = [toCam(tri.v0, tri.uv0), toCam(tri.v1, tri.uv1), toCam(tri.v2, tri.uv2)];
      const clipped = opts.orthoScale ? rawPoly : clipNear(rawPoly, nearZ);
      if (clipped.length < 3) continue;
      const proj = clipped.map(projectVert);
      for (let i = 1; i < proj.length - 1; i++) {
        rasterTriangle(proj[0], proj[i], proj[i + 1], tri, pass.transparent);
      }
    }
  }
  return { width: W, height: H, rgba };
}

function drawText(canvas, CW, CH, x, y, text, fg, scale = 2) {
  let cx = x;
  for (const ch of text.toUpperCase()) {
    const g = FONT_5X7[ch];
    if (g) {
      for (let gy = 0; gy < 7; gy++) {
        for (let gx = 0; gx < 5; gx++) {
          if (g[gy][gx] === '1') {
            for (let dy = 0; dy < scale; dy++) {
              for (let dx = 0; dx < scale; dx++) {
                const px = cx + gx * scale + dx;
                const py = y + gy * scale + dy;
                if (px >= 0 && px < CW && py >= 0 && py < CH) {
                  const off = (py * CW + px) * 4;
                  canvas[off] = fg[0]; canvas[off + 1] = fg[1]; canvas[off + 2] = fg[2]; canvas[off + 3] = 255;
                }
              }
            }
          }
        }
      }
    }
    cx += 6 * scale;
  }
}

function fillRect(canvas, CW, CH, x0, y0, w, h, col) {
  for (let y = Math.max(0, y0); y < Math.min(CH, y0 + h); y++) {
    for (let x = Math.max(0, x0); x < Math.min(CW, x0 + w); x++) {
      const off = (y * CW + x) * 4;
      canvas[off] = col[0]; canvas[off + 1] = col[1]; canvas[off + 2] = col[2]; canvas[off + 3] = 255;
    }
  }
}

function blit(dst, CW, CH, src, sx, sy) {
  for (let y = 0; y < src.height; y++) {
    const dy = sy + y;
    if (dy < 0 || dy >= CH) continue;
    for (let x = 0; x < src.width; x++) {
      const dx = sx + x;
      if (dx < 0 || dx >= CW) continue;
      const so = (y * src.width + x) * 4;
      const doff = (dy * CW + dx) * 4;
      dst[doff] = src.rgba[so];
      dst[doff + 1] = src.rgba[so + 1];
      dst[doff + 2] = src.rgba[so + 2];
      dst[doff + 3] = 255;
    }
  }
}

function drawBadge(canvas, CW, CH, x, y, text, fg = [250, 232, 168]) {
  const bw = text.length * 6 + 12;
  fillRect(canvas, CW, CH, x, y, bw, 15, [16, 20, 30]);
  drawText(canvas, CW, CH, x + 6, y + 4, text, fg, 1);
}


const scene = loadGlb(path.join(__dirname, 'beach-blvd-rex.glb'));
const modelInfo = JSON.parse(fs.readFileSync(path.join(__dirname, 'model-info.json'), 'utf8'));

// Exact camera-projected 1.70m human scale reference silhouette & vertical ruler on the side-profile view
function drawHumanScaleOverlay(panel, pw, ph, eye, target, fovDeg, rexHeightM) {
  const fwd = vNorm(vSub(target, eye));
  const right = vNorm(vCross(fwd, [0, 1, 0]));
  const up = vNorm(vCross(right, fwd));
  const focal = (ph * 0.5) / Math.tan(((fovDeg * Math.PI) / 180) * 0.5);

  function projectWorld(p) {
    const d = vSub(p, eye);
    const cx = vDot(d, right);
    const cy = vDot(d, up);
    const cz = vDot(d, fwd);
    return {
      sx: Math.round(pw * 0.5 + (cx * focal) / cz),
      sy: Math.round(ph * 0.5 - (cy * focal) / cz),
    };
  }

  // Exact Y coordinates at Rex's centerline plane (X=0, Z=-4.8)
  function yOf(m) {
    return projectWorld([0.0, m, -4.8]).sy;
  }

  const y0 = yOf(0.0);
  const yTop = yOf(rexHeightM);
  const yH = yOf(1.7);

  // Place vertical scale ruler cleanly on the left margin
  const bx = 54;
  fillRect(panel.rgba, pw, ph, bx - 1, yTop, 4, y0 - yTop, [16, 20, 30]);
  fillRect(panel.rgba, pw, ph, bx, yTop, 2, y0 - yTop, [238, 244, 252]);

  const ticks = [
    { m: 0.0, lbl: '0M', col: [248, 224, 152] },
    { m: 1.7, lbl: '1.7M', col: [110, 246, 184] },
    { m: 3.0, lbl: '3M', col: [248, 224, 152] },
    { m: 5.0, lbl: '5M', col: [248, 224, 152] },
    { m: rexHeightM, lbl: `${rexHeightM.toFixed(1)}M`, col: [255, 196, 108] },
  ];
  for (const t of ticks) {
    const yy = yOf(t.m);
    fillRect(panel.rgba, pw, ph, bx - 6, yy - 1, 16, 4, [16, 20, 30]);
    fillRect(panel.rgba, pw, ph, bx - 5, yy, 14, 2, t.col);
    const lx = bx + 12;
    fillRect(panel.rgba, pw, ph, lx - 3, yy - 6, t.lbl.length * 6 + 6, 11, [16, 20, 30]);
    drawText(panel.rgba, pw, ph, lx, yy - 4, t.lbl, t.col, 1);
  }

  // 1.70m pixel-human silhouette standing on the front grass strip outside the fence (X=0, Z=-1.45)
  const pFoot = projectWorld([0.0, 0.0, -1.45]);
  const pHead = projectWorld([0.0, 1.7, -1.45]);
  const px = Math.max(bx + 62, pFoot.sx);
  const hPx = Math.max(20, pFoot.sy - pHead.sy);
  const headR = Math.max(4, Math.round(hPx * 0.11));
  const cyH = pHead.sy;
  const col = [110, 246, 184];
  const out = [16, 20, 30];
  fillRect(panel.rgba, pw, ph, px - headR - 1, cyH - 1, headR * 2 + 2, headR * 2 + 2, out);
  fillRect(panel.rgba, pw, ph, px - headR, cyH, headR * 2, headR * 2, col);
  const torsoW = Math.round(headR * 2.3);
  const torsoH = Math.round(hPx * 0.36);
  const torsoY = cyH + headR * 2 + 1;
  fillRect(panel.rgba, pw, ph, px - Math.floor(torsoW / 2) - 1, torsoY - 1, torsoW + 2, torsoH + 2, out);
  fillRect(panel.rgba, pw, ph, px - Math.floor(torsoW / 2), torsoY, torsoW, torsoH, col);
  const legY = torsoY + torsoH;
  const legH = Math.max(4, pFoot.sy - legY);
  const legW = Math.max(3, Math.round(headR * 0.72));
  fillRect(panel.rgba, pw, ph, px - legW - 2, legY, legW + 2, legH + 1, out);
  fillRect(panel.rgba, pw, ph, px, legY, legW + 2, legH + 1, out);
  fillRect(panel.rgba, pw, ph, px - legW - 1, legY, legW, legH, col);
  fillRect(panel.rgba, pw, ph, px + 1, legY, legW, legH, col);
  fillRect(panel.rgba, pw, ph, px - 24, cyH - 16, 52, 12, out);
  drawText(panel.rgba, pw, ph, px - 21, cyH - 13, '1.7M YOU', col, 1);
}

const PW = 760;
const PH = 620;
const PAD = 14;
const HEADER_H = 78;
const CW = PAD + 3 * (PW + PAD);
const CH = HEADER_H + 2 * (PH + PAD) + PAD;
const sheet = new Uint8Array(CW * CH * 4);
fillRect(sheet, CW, CH, 0, 0, CW, CH, [18, 20, 26]);
fillRect(sheet, CW, CH, PAD, PAD, CW - 2 * PAD, HEADER_H - PAD - 4, [30, 34, 46]);
const dim = modelInfo.statue_dimensions_m;
const rexHeightM = dim.height_from_ground ?? dim.height_from_sidewalk;
const trisFmt = modelInfo.triangles.visible_total.toLocaleString('en-US');
const subline = `${rexHeightM.toFixed(2)}M (${dim.height_feet.toFixed(1)} FT) TALL X ${dim.length_nose_to_tail.toFixed(2)}M (${dim.length_feet.toFixed(1)} FT) LONG | 0.0388M/TEXEL (2X2 ART PX) | ${trisFmt} TRIS | 1-TO-1 BEACH BLVD REX`;
drawText(sheet, CW, CH, 24, 18, 'REX - 10150 BEACH BLVD, JACKSONVILLE FL (EST. 1964) | SOFTWARE RENDERS', [252, 152, 92], 2);
drawText(sheet, CW, CH, 24, 44, subline, [176, 214, 244], 2);

const VIEWS = [
  {
    badge: '1. LEFT 3/4 HERO (MATCHES BEACH BLVD PHOTOS)',
    eye: [7.8, 3.05, 1.95],
    target: [0.0, 3.30, -4.55],
    fovDeg: 46,
    sunDir: [0.46, 0.78, 0.42],
    skyTop: [62, 128, 208],
    skyBottom: [194, 224, 248],
  },
  {
    badge: '2. NIGHT / BLUE HOUR (GLOWING RED EYES)',
    eye: [7.6, 3.05, 1.95],
    target: [0.0, 3.30, -4.55],
    fovDeg: 46,
    nightMode: true,
    sunDir: [0.22, -0.45, 0.86],
    skyTop: [10, 14, 30],
    skyBottom: [26, 36, 68],
  },
  {
    badge: '3. LEFT SIDE PROFILE + 1.7M HUMAN SCALE',
    eye: [12.2, 3.15, -4.8],
    target: [0.0, 3.15, -4.8],
    fovDeg: 38,
    sunDir: [0.40, 0.78, 0.48],
    skyTop: [68, 132, 208],
    skyBottom: [196, 226, 248],
    scaleOverlay: true,
  },
  {
    badge: '4. HEAD CLOSE-UP (MATCHES REFERENCE PHOTO)',
    eye: [2.25, 4.75, 0.85],
    target: [0.0, 5.32, -2.00],
    fovDeg: 38,
    sunDir: [0.45, 0.72, 0.52],
    skyTop: [56, 126, 212],
    skyBottom: [188, 222, 250],
  },
  {
    badge: '5. BACK 3/4 (ROUGH PEBBLED BACK & TAIL)',
    eye: [-8.2, 3.8, -11.6],
    target: [0.0, 2.95, -5.0],
    fovDeg: 46,
    sunDir: [-0.45, 0.78, -0.42],
    skyTop: [66, 130, 208],
    skyBottom: [194, 224, 248],
  },
  {
    badge: '6. FRONT-RIGHT STREET VIEW (1.6M EYE)',
    eye: [-5.2, 1.70, 3.20],
    target: [0.0, 3.45, -4.2],
    fovDeg: 50,
    sunDir: [0.42, 0.78, 0.46],
    skyTop: [62, 128, 208],
    skyBottom: [192, 224, 248],
  },
];

for (let i = 0; i < VIEWS.length; i++) {
  const v = VIEWS[i];
  const r = Math.floor(i / 3);
  const c = i % 3;
  const panel = renderPanel(scene, { ...v, width: PW, height: PH });
  if (v.scaleOverlay) drawHumanScaleOverlay(panel, PW, PH, v.eye, v.target, v.fovDeg, rexHeightM);
  const px = PAD + c * (PW + PAD);
  const py = HEADER_H + r * (PH + PAD);
  blit(sheet, CW, CH, panel, px, py);
  drawBadge(sheet, CW, CH, px + 10, py + 10, v.badge, v.nightMode ? [255, 120, 110] : [252, 232, 168]);
  console.log(`Rendered panel ${i + 1}/6: ${v.badge}`);
}

const outPath = path.join(__dirname, 'beach-blvd-rex-review.png');
fs.writeFileSync(outPath, encodePng(CW, CH, sheet));
console.log(`Wrote ${outPath} (${CW}x${CH})`);
