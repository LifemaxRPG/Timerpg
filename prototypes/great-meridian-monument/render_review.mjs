#!/usr/bin/env node
/**
 * Software glTF 2.0 renderer (with perspective-correct texturing, full node transform instancing,
 * near-plane clipping & pure-black emissive glow) generating ONE high-resolution review sheet:
 *   great-meridian-review.png (2620 x 2040)
 *
 * Layout:
 *   Row 1 (4 panels, 640x440): Front 3/4 Day  |  Front 3/4 Night  |  Opposite Back (-Z)  |  Directly Above
 *   Row 2 (4 panels, 640x440): Person-Height Reflecting Pool Approach  |  SW Corner Silhouette  |  SE Corner Silhouette  |  Far LOD Silhouette
 *   Row 3 (3 huge 858x480 close-ups):
 *     1) South Triumphal Archway & Flush Carved Frieze ("THE GREAT MERIDIAN / DEDICATED MCMXXVI")
 *     2) Suspended 15 m Celestial Armillary Sphere & Eiffel-Style Open-Truss Steel Arches
 *     3) 53 m Sky Observation Crown, Octagonal Lantern & 94 m Astral Beacon Spire
 *   Row 4 (3 huge 858x480 walkable & dusk close-ups):
 *     4) Standing on Compass Rose Rotunda Looking STRAIGHT UP into the 15 m Armillary Sphere & Vault
 *     5) Walking the 53 m Sky Observation Deck (Brass Telescope & Panoramic Colonnade)
 *     6) Sweeping Peristyle Colonnade Wing & Eternal-Flame Bronze Brazier at Night
 *
 * NOTE: Software renders, not phone/Filament captures.
 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

function decodePng(buf) {
  let pos = 8;
  let w = 0, h = 0;
  const idats = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    pos += 12 + len;
    if (type === 'IHDR') {
      w = data.readUInt32BE(0);
      h = data.readUInt32BE(4);
    } else if (type === 'IDAT') {
      idats.push(data);
    }
  }
  const raw = zlib.inflateSync(Buffer.concat(idats));
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

function quatMul(a, b) {
  return [
    a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
    a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
    a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
    a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
  ];
}

function quatRotateVec(q, v) {
  const [qx, qy, qz, qw] = q;
  const [vx, vy, vz] = v;
  const tx = 2 * (qy * vz - qz * vy);
  const ty = 2 * (qz * vx - qx * vz);
  const tz = 2 * (qx * vy - qy * vx);
  return [
    vx + qw * tx + (qy * tz - qz * ty),
    vy + qw * ty + (qz * tx - qx * tz),
    vz + qw * tz + (qx * ty - qy * tx),
  ];
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
    const off = (bv.byteOffset || 0) + (acc.byteOffset || 0);
    const comp = { SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4 }[acc.type];
    const out = [];
    for (let i = 0; i < acc.count; i++) {
      if (acc.componentType === 5126) {
        const arr = [];
        for (let k = 0; k < comp; k++) arr.push(bin.readFloatLE(off + (i * comp + k) * 4));
        out.push(arr);
      } else if (acc.componentType === 5125) {
        out.push(bin.readUInt32LE(off + i * 4));
      }
    }
    return out;
  }

  const decodedMeshes = gltf.meshes.map((mesh) =>
    mesh.primitives.map((prim) => {
      const mat = gltf.materials[prim.material];
      const baseTexIdx = mat.pbrMetallicRoughness.baseColorTexture.index;
      const baseImg = images[gltf.textures[baseTexIdx].source];
      const emImg = mat.emissiveTexture ? images[gltf.textures[mat.emissiveTexture.index].source] : null;
      const ef = mat.emissiveFactor || [0, 0, 0];
      return {
        pos: readAcc(prim.attributes.POSITION),
        nrm: readAcc(prim.attributes.NORMAL),
        uv: readAcc(prim.attributes.TEXCOORD_0),
        idx: readAcc(prim.indices),
        baseImg,
        emImg,
        ef,
        isBlend: mat.alphaMode === 'BLEND',
        doubleSided: !!mat.doubleSided,
        isGlass: mat.name.includes('glass'),
      };
    })
  );

  const drawTriangles = [];
  function walk(nodeIdx, pPos, pRot) {
    const node = gltf.nodes[nodeIdx];
    if (!node || node.name === 'collision' || (node.extras && node.extras.collision_only)) return;
    const lt = node.translation || [0, 0, 0];
    const lr = node.rotation || [0, 0, 0, 1];
    const rlt = quatRotateVec(pRot, lt);
    const wPos = [pPos[0] + rlt[0], pPos[1] + rlt[1], pPos[2] + rlt[2]];
    const wRot = quatMul(pRot, lr);

    if (node.mesh !== undefined) {
      for (const pr of decodedMeshes[node.mesh]) {
        const xPos = pr.pos.map((v) => {
          const rv = quatRotateVec(wRot, v);
          return [rv[0] + wPos[0], rv[1] + wPos[1], rv[2] + wPos[2]];
        });
        const xNrm = pr.nrm.map((n) => quatRotateVec(wRot, n));
        for (let t = 0; t < pr.idx.length; t += 3) {
          const i0 = pr.idx[t], i1 = pr.idx[t + 1], i2 = pr.idx[t + 2];
          drawTriangles.push({
            p0: xPos[i0], p1: xPos[i1], p2: xPos[i2],
            n0: xNrm[i0],
            uv0: pr.uv[i0], uv1: pr.uv[i1], uv2: pr.uv[i2],
            baseImg: pr.baseImg,
            emImg: pr.emImg,
            ef: pr.ef,
            isBlend: pr.isBlend,
            doubleSided: pr.doubleSided,
            isGlass: pr.isGlass,
          });
        }
      }
    }
    if (node.children) {
      for (const ci of node.children) walk(ci, wPos, wRot);
    }
  }

  for (const rootIdx of gltf.scenes[gltf.scene || 0].nodes) {
    walk(rootIdx, [0, 0, 0], [0, 0, 0, 1]);
  }
  return drawTriangles;
}

function sub(a, b) { return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]; }
function dot(a, b) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }
function cross(a, b) { return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]; }
function norm(a) { const l = Math.hypot(a[0], a[1], a[2]) || 1; return [a[0] / l, a[1] / l, a[2] / l]; }

function sampleNearest(img, u, v) {
  let fu = u - Math.floor(u);
  let fv = v - Math.floor(v);
  if (fu < 0) fu += 1;
  if (fv < 0) fv += 1;
  const x = Math.min(img.width - 1, Math.floor(fu * img.width));
  const y = Math.min(img.height - 1, Math.floor(fv * img.height));
  const off = (y * img.width + x) * 4;
  return [img.data[off], img.data[off + 1], img.data[off + 2], img.data[off + 3]];
}

function renderPanel(tris, opts) {
  const {
    width, height, eye, target, up = [0, 1, 0],
    fovDeg = 46, orthoScale = null,
    nightMode = false,
    sunDir = [-0.46, 0.76, 0.45],
    skyTop = [64, 114, 182], skyBottom = [204, 224, 246],
  } = opts;

  const rgba = new Uint8Array(width * height * 4);
  const zbuf = new Float32Array(width * height);
  zbuf.fill(Infinity);

  for (let y = 0; y < height; y++) {
    const t = y / Math.max(1, height - 1);
    const r = Math.round(skyTop[0] + (skyBottom[0] - skyTop[0]) * t);
    const g = Math.round(skyTop[1] + (skyBottom[1] - skyTop[1]) * t);
    const b = Math.round(skyTop[2] + (skyBottom[2] - skyTop[2]) * t);
    for (let x = 0; x < width; x++) {
      const off = (y * width + x) * 4;
      rgba[off] = r; rgba[off + 1] = g; rgba[off + 2] = b; rgba[off + 3] = 255;
    }
  }

  const fwd = norm(sub(target, eye));
  const right = norm(cross(fwd, up));
  const camUp = cross(right, fwd);
  const sun = norm(sunDir);
  const aspect = width / height;
  const f = 1.0 / Math.tan((fovDeg * Math.PI) / 360);
  const nearPlane = 0.35;

  function toCam(p) {
    const d = sub(p, eye);
    return [dot(d, right), dot(d, camUp), dot(d, fwd)];
  }

  function projectCam(c) {
    if (orthoScale !== null) {
      const sx = (c[0] / (orthoScale * aspect) * 0.5 + 0.5) * width;
      const sy = (0.5 - c[1] / orthoScale * 0.5) * height;
      return [sx, sy, c[2], 1.0];
    }
    const invZ = 1.0 / c[2];
    const ndcX = (c[0] * f / aspect) * invZ;
    const ndcY = (c[1] * f) * invZ;
    return [(ndcX * 0.5 + 0.5) * width, (0.5 - ndcY * 0.5) * height, c[2], invZ];
  }

  function clipTriNear(v0, v1, v2) {
    const verts = [v0, v1, v2];
    const out = [];
    for (let i = 0; i < 3; i++) {
      const cur = verts[i];
      const nxt = verts[(i + 1) % 3];
      const cIn = cur.c[2] >= nearPlane;
      const nIn = nxt.c[2] >= nearPlane;
      if (cIn) out.push(cur);
      if (cIn !== nIn) {
        const t = (nearPlane - cur.c[2]) / (nxt.c[2] - cur.c[2]);
        out.push({
          c: [
            cur.c[0] + (nxt.c[0] - cur.c[0]) * t,
            cur.c[1] + (nxt.c[1] - cur.c[1]) * t,
            nearPlane,
          ],
          uv: [
            cur.uv[0] + (nxt.uv[0] - cur.uv[0]) * t,
            cur.uv[1] + (nxt.uv[1] - cur.uv[1]) * t,
          ],
        });
      }
    }
    if (out.length < 3) return [];
    if (out.length === 3) return [[out[0], out[1], out[2]]];
    return [[out[0], out[1], out[2]], [out[0], out[2], out[3]]];
  }

  const opaque = [];
  const blends = [];
  for (const tr of tris) {
    if (tr.isBlend) blends.push(tr);
    else opaque.push(tr);
  }

  function rasterTri(tr, isBlendPass) {
    const c0 = toCam(tr.p0);
    const c1 = toCam(tr.p1);
    const c2 = toCam(tr.p2);
    if (orthoScale === null && c0[2] < nearPlane && c1[2] < nearPlane && c2[2] < nearPlane) return;

    const clipped = orthoScale !== null
      ? [[{ c: c0, uv: tr.uv0 }, { c: c1, uv: tr.uv1 }, { c: c2, uv: tr.uv2 }]]
      : clipTriNear({ c: c0, uv: tr.uv0 }, { c: c1, uv: tr.uv1 }, { c: c2, uv: tr.uv2 });

    const ndl = Math.max(0, dot(tr.n0, sun));
    const hemi = 0.5 + 0.5 * tr.n0[1];
    const shade = nightMode
      ? (0.20 + 0.14 * hemi + 0.14 * ndl)
      : (0.42 + 0.16 * hemi + 0.46 * ndl);

    for (const [va, vb, vc] of clipped) {
      const s0 = projectCam(va.c);
      const s1 = projectCam(vb.c);
      const s2 = projectCam(vc.c);
      const area = (s1[0] - s0[0]) * (s2[1] - s0[1]) - (s2[0] - s0[0]) * (s1[1] - s0[1]);
      if (Math.abs(area) < 0.01) continue;
      if (!tr.doubleSided && area > 0) continue;

      const minX = Math.max(0, Math.floor(Math.min(s0[0], s1[0], s2[0])));
      const maxX = Math.min(width - 1, Math.ceil(Math.max(s0[0], s1[0], s2[0])));
      const minY = Math.max(0, Math.floor(Math.min(s0[1], s1[1], s2[1])));
      const maxY = Math.min(height - 1, Math.ceil(Math.max(s0[1], s1[1], s2[1])));
      if (minX > maxX || minY > maxY) continue;

      const invArea = 1.0 / area;
      const invW0 = s0[3], invW1 = s1[3], invW2 = s2[3];
      const u0w = va.uv[0] * invW0, v0w = va.uv[1] * invW0;
      const u1w = vb.uv[0] * invW1, v1w = vb.uv[1] * invW1;
      const u2w = vc.uv[0] * invW2, v2w = vc.uv[1] * invW2;

      for (let py = minY; py <= maxY; py++) {
        const fy = py + 0.5;
        for (let px = minX; px <= maxX; px++) {
          const fx = px + 0.5;
          const w0 = ((s1[0] - fx) * (s2[1] - fy) - (s2[0] - fx) * (s1[1] - fy)) * invArea;
          const w1 = ((s2[0] - fx) * (s0[1] - fy) - (s0[0] - fx) * (s2[1] - fy)) * invArea;
          const w2 = 1.0 - w0 - w1;
          if (w0 < -1e-4 || w1 < -1e-4 || w2 < -1e-4) continue;

          const invW = w0 * invW0 + w1 * invW1 + w2 * invW2;
          const z = orthoScale !== null ? (w0 * s0[2] + w1 * s1[2] + w2 * s2[2]) : (1.0 / invW);
          const pIdx = py * width + px;
          if (z >= zbuf[pIdx]) continue;

          const u = (w0 * u0w + w1 * u1w + w2 * u2w) / invW;
          const v = (w0 * v0w + w1 * v1w + w2 * v2w) / invW;

          const [br, bg, bb, ba] = sampleNearest(tr.baseImg, u, v);
          if (ba < 10) continue;

          let r = br * shade;
          let g = bg * shade;
          let b = bb * shade;

          if (tr.emImg && (tr.ef[0] > 0 || tr.ef[1] > 0 || tr.ef[2] > 0)) {
            const [er, eg, eb] = sampleNearest(tr.emImg, u, v);
            const boost = nightMode ? 1.55 : 0.48;
            r += er * tr.ef[0] * boost;
            g += eg * tr.ef[1] * boost;
            b += eb * tr.ef[2] * boost;
          } else if (nightMode && tr.isGlass && ba < 245) {
            // Warm golden-amber cathedral & lantern interior glow at night (mirrors Filament glass role)
            r += 148; g += 106; b += 42;
          }

          r = Math.min(255, Math.max(0, Math.round(r)));
          g = Math.min(255, Math.max(0, Math.round(g)));
          b = Math.min(255, Math.max(0, Math.round(b)));

          const off = pIdx * 4;
          if (!isBlendPass || ba >= 250) {
            zbuf[pIdx] = z;
            rgba[off] = r; rgba[off + 1] = g; rgba[off + 2] = b; rgba[off + 3] = 255;
          } else {
            const a = ba / 255;
            const ia = 1 - a;
            rgba[off] = Math.round(r * a + rgba[off] * ia);
            rgba[off + 1] = Math.round(g * a + rgba[off + 1] * ia);
            rgba[off + 2] = Math.round(b * a + rgba[off + 2] * ia);
          }
        }
      }
    }
  }

  for (const tr of opaque) rasterTri(tr, false);
  blends.sort((a, b) => {
    const za = toCam(a.p0)[2] + toCam(a.p1)[2] + toCam(a.p2)[2];
    const zb = toCam(b.p0)[2] + toCam(b.p1)[2] + toCam(b.p2)[2];
    return zb - za;
  });
  for (const tr of blends) rasterTri(tr, true);

  return { width, height, rgba };
}

const FONT = {
  A: ['01110','10001','10001','11111','10001','10001','10001'],
  B: ['11110','10001','10001','11110','10001','10001','11110'],
  C: ['01111','10000','10000','10000','10000','10000','01111'],
  D: ['11110','10001','10001','10001','10001','10001','11110'],
  E: ['11111','10000','10000','11110','10000','10000','11111'],
  F: ['11111','10000','10000','11110','10000','10000','10000'],
  G: ['01111','10000','10000','10111','10001','10001','01111'],
  H: ['10001','10001','10001','11111','10001','10001','10001'],
  I: ['11111','00100','00100','00100','00100','00100','11111'],
  J: ['00111','00010','00010','00010','10010','10010','01100'],
  K: ['10001','10010','10100','11000','10100','10010','10001'],
  L: ['10000','10000','10000','10000','10000','10000','11111'],
  M: ['10001','11011','10101','10101','10001','10001','10001'],
  N: ['10001','11001','10101','10011','10001','10001','10001'],
  O: ['01110','10001','10001','10001','10001','10001','01110'],
  P: ['11110','10001','10001','11110','10000','10000','10000'],
  Q: ['01110','10001','10001','10001','10101','10010','01101'],
  R: ['11110','10001','10001','11110','10100','10010','10001'],
  S: ['01111','10000','10000','01110','00001','00001','11110'],
  T: ['11111','00100','00100','00100','00100','00100','00100'],
  U: ['10001','10001','10001','10001','10001','10001','01110'],
  V: ['10001','10001','10001','10001','10001','01010','00100'],
  W: ['10001','10001','10001','10101','10101','11011','10001'],
  X: ['10001','10001','01010','00100','01010','10001','10001'],
  Y: ['10001','10001','01010','00100','00100','00100','00100'],
  Z: ['11111','00001','00010','00100','01000','10000','11111'],
  '0': ['01110','10011','10101','10101','11001','10001','01110'],
  '1': ['00100','01100','00100','00100','00100','00100','01110'],
  '2': ['01110','10001','00001','00110','01000','10000','11111'],
  '3': ['11110','00001','00001','01110','00001','00001','11110'],
  '4': ['00010','00110','01010','10010','11111','00010','00010'],
  '5': ['11111','10000','11110','00001','00001','10001','01110'],
  '6': ['01110','10000','11110','10001','10001','10001','01110'],
  '7': ['11111','00001','00010','00100','01000','01000','01000'],
  '8': ['01110','10001','10001','01110','10001','10001','01110'],
  '9': ['01110','10001','10001','01111','00001','00001','01110'],
  '-': ['00000','00000','00000','11111','00000','00000','00000'],
  ':': ['00000','00100','00100','00000','00100','00100','00000'],
  '.': ['00000','00000','00000','00000','00000','01100','01100'],
  '/': ['00001','00010','00010','00100','01000','01000','10000'],
  '(': ['00010','00100','01000','01000','01000','00100','00010'],
  ')': ['01000','00100','00010','00010','00010','00100','01000'],
  '+': ['00000','00100','00100','11111','00100','00100','00000'],
  '|': ['00100','00100','00100','00100','00100','00100','00100'],
  ' ': ['00000','00000','00000','00000','00000','00000','00000'],
};

function drawText(canvas, CW, CH, x, y, text, fg, scale = 2) {
  let cx = x;
  for (const ch of text.toUpperCase()) {
    const g = FONT[ch] || FONT[' '];
    for (let r = 0; r < 7; r++) {
      for (let c = 0; c < 5; c++) {
        if (g[r][c] === '1') {
          for (let sy = 0; sy < scale; sy++) {
            for (let sx = 0; sx < scale; sx++) {
              const px = cx + c * scale + sx;
              const py = y + r * scale + sy;
              if (px >= 0 && px < CW && py >= 0 && py < CH) {
                const off = (py * CW + px) * 4;
                canvas[off] = fg[0]; canvas[off + 1] = fg[1]; canvas[off + 2] = fg[2]; canvas[off + 3] = 255;
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

const mainScene = loadGlb(path.join(__dirname, 'great-meridian-monument.glb'));
const farScene = loadGlb(path.join(__dirname, 'great-meridian-monument-far.glb'));
const CZ_M = -773.0 * 0.0776; // -59.9848 m

const PW = 640;
const PH = 440;
const CU_W = 858;
const CU_H = 480;
const PAD = 12;
const HEADER_H = 68;
const ROW_TITLE_H = 28;
const CW = PAD + 4 * (PW + PAD);
const CH = HEADER_H + 2 * (ROW_TITLE_H + PH + PAD) + 2 * (ROW_TITLE_H + CU_H + PAD) + PAD;
const sheet = new Uint8Array(CW * CH * 4);

fillRect(sheet, CW, CH, 0, 0, CW, CH, [18, 20, 26]);
fillRect(sheet, CW, CH, PAD, PAD, CW - 2 * PAD, HEADER_H - PAD - 4, [30, 34, 46]);
drawText(sheet, CW, CH, 24, 18, 'THE GREAT MERIDIAN MONUMENT (1926) - 105.5M NATIONAL MONUMENT + 120M CEREMONIAL ACROPOLIS (SOFTWARE RENDERS)', [248, 224, 144], 2);
drawText(sheet, CW, CH, 24, 42, '0.0388M/TEXEL | 15.2M SUSPENDED CELESTIAL ARMILLARY SPHERE | RIVETED STEEL TRUSS ARCHES | 58.2M SKY DECK | 105.5M BEACON', [176, 212, 242], 2);

// ROW 1: GRAND OVERVIEWS (Day 3/4, Night Illuminated, Opposite Back -Z, Above Plan)
const ROW1 = [
  {
    scene: mainScene,
    eye: [-92.0, 52.0, CZ_M + 132.0],
    target: [0.0, 46.0, CZ_M + 2.0],
    fovDeg: 48,
    sunDir: [-0.48, 0.76, 0.44],
    skyTop: [56, 108, 178],
    skyBottom: [198, 222, 244],
  },
  {
    scene: mainScene,
    eye: [88.0, 48.0, CZ_M + 130.0],
    target: [0.0, 46.0, CZ_M + 2.0],
    fovDeg: 48,
    nightMode: true,
    sunDir: [0.35, 0.58, 0.50],
    skyTop: [10, 14, 28],
    skyBottom: [28, 36, 66],
  },
  {
    scene: mainScene,
    eye: [94.0, 52.0, CZ_M - 126.0],
    target: [0.0, 46.0, CZ_M],
    fovDeg: 48,
    sunDir: [0.46, 0.76, -0.46],
    skyTop: [56, 108, 178],
    skyBottom: [198, 222, 244],
  },
  {
    scene: mainScene,
    eye: [0.0, 220.0, CZ_M],
    target: [0.0, 0.0, CZ_M],
    up: [0, 0, -1],
    orthoScale: 64.0,
    sunDir: [0.34, 0.88, 0.32],
    skyTop: [30, 34, 42],
    skyBottom: [30, 34, 42],
  },
];

// ROW 2: STREET-HEIGHT MONUMENTAL SILHOUETTES & FAR LOD
const ROW2 = [
  {
    // Person-height standing right at the +Z front entrance looking down the Grand Reflecting Pool!
    scene: mainScene,
    eye: [0.0, 2.8, 10.0],
    target: [0.0, 48.0, CZ_M],
    fovDeg: 64,
    sunDir: [-0.42, 0.76, 0.48],
    skyTop: [54, 106, 178],
    skyBottom: [204, 226, 246],
  },
  {
    // SW Street-height corner view showing Peristyle Wing + Cross Arch + 105.5 m Spire!
    scene: mainScene,
    eye: [-74.0, 3.6, CZ_M + 74.0],
    target: [0.0, 43.0, CZ_M],
    fovDeg: 60,
    sunDir: [-0.46, 0.74, 0.48],
    skyTop: [54, 106, 178],
    skyBottom: [204, 226, 246],
  },
  {
    // SE Street-height corner view!
    scene: mainScene,
    eye: [74.0, 3.6, CZ_M + 74.0],
    target: [0.0, 43.0, CZ_M],
    fovDeg: 60,
    sunDir: [0.44, 0.74, 0.50],
    skyTop: [54, 106, 178],
    skyBottom: [204, 226, 246],
  },
  {
    // Distant landmark LOD silhouette verification!
    scene: farScene,
    eye: [-94.0, 38.0, CZ_M + 128.0],
    target: [0.0, 44.0, CZ_M],
    fovDeg: 46,
    sunDir: [-0.48, 0.76, 0.44],
    skyTop: [72, 118, 178],
    skyBottom: [208, 226, 242],
  },
];

// ROW 3: 3 HUGE ARCHITECTURAL CLOSE-UPS
const ROW3 = [
  {
    // South Triumphal Archway, flush carved "THE GREAT MERIDIAN / DEDICATED MCMXXVI", & deep cathedral windows!
    scene: mainScene,
    eye: [-6.5, 22.0, CZ_M + 54.0],
    target: [0.0, 24.5, CZ_M + 12.0],
    fovDeg: 46,
    sunDir: [-0.36, 0.72, 0.58],
    skyTop: [56, 108, 178],
    skyBottom: [198, 222, 244],
  },
  {
    // Unobstructed hero view straight into the open riveted-steel cathedral vault & 15.2 m Celestial Armillary Sphere!
    scene: mainScene,
    eye: [6.8, 41.5, CZ_M + 40.0],
    target: [0.0, 42.0, CZ_M],
    fovDeg: 46,
    sunDir: [-0.32, 0.78, 0.54],
    skyTop: [52, 102, 174],
    skyBottom: [188, 214, 240],
  },
  {
    // 58.2 m Sky Observation Crown, Octagonal Lantern, 8 Flying Buttress Fins, Starburst Crown & 105.5 m Astral Beacon!
    scene: mainScene,
    eye: [-36.0, 76.0, CZ_M + 54.0],
    target: [0.0, 82.0, CZ_M],
    fovDeg: 48,
    sunDir: [-0.42, 0.76, 0.48],
    skyTop: [46, 94, 168],
    skyBottom: [176, 206, 236],
  },
];

// ROW 4: 3 HUGE WALKABLE & DUSK/NIGHT EXPERIENCE VIEWS
const ROW4 = [
  {
    // Standing right on the Compass Rose Rotunda looking STRAIGHT UP into the 15.2 m Armillary Sphere & Open Steel Vault!
    scene: mainScene,
    eye: [0.0, 8.2, CZ_M + 11.5],
    target: [0.0, 42.0, CZ_M - 1.5],
    fovDeg: 74,
    sunDir: [0.24, 0.92, 0.28],
    skyTop: [56, 108, 178],
    skyBottom: [168, 206, 238],
  },
  {
    // Standing on the 58.2 m Sky Observation Deck beside the open bronze railing, colonnade & brass telescope looking out over the reflecting pool!
    scene: mainScene,
    eye: [-10.5, 60.6, CZ_M + 11.0],
    target: [0.5, 55.0, CZ_M + 26.0],
    fovDeg: 66,
    sunDir: [-0.45, 0.76, 0.46],
    skyTop: [56, 108, 178],
    skyBottom: [204, 226, 246],
  },
  {
    // Sweeping West Peristyle Colonnade & Glowing Eternal-Flame Bronze Brazier at Night!
    scene: mainScene,
    eye: [-38.0, 8.5, CZ_M + 44.0],
    target: [-26.0, 9.5, CZ_M + 12.0],
    fovDeg: 52,
    nightMode: true,
    sunDir: [-0.30, 0.62, 0.55],
    skyTop: [12, 16, 32],
    skyBottom: [32, 42, 74],
  },
];

function drawRow4(rowIdx, title, cfgs, titleCol) {
  const rowY = HEADER_H + rowIdx * (ROW_TITLE_H + PH + PAD);
  fillRect(sheet, CW, CH, PAD, rowY, CW - 2 * PAD, ROW_TITLE_H - 2, [34, 38, 50]);
  drawText(sheet, CW, CH, PAD + 10, rowY + 6, title, titleCol, 2);
  for (let c = 0; c < cfgs.length; c++) {
    const panel = renderPanel(cfgs[c].scene, { ...cfgs[c], width: PW, height: PH });
    blit(sheet, CW, CH, panel, PAD + c * (PW + PAD), rowY + ROW_TITLE_H);
  }
  console.log(`Rendered 4-panel row ${rowIdx + 1}`);
}

function drawRow3(cuIdx, title, cfgs, titleCol) {
  const rowY = HEADER_H + 2 * (ROW_TITLE_H + PH + PAD) + cuIdx * (ROW_TITLE_H + CU_H + PAD);
  fillRect(sheet, CW, CH, PAD, rowY, CW - 2 * PAD, ROW_TITLE_H - 2, [40, 44, 58]);
  drawText(sheet, CW, CH, PAD + 10, rowY + 6, title, titleCol, 2);
  for (let c = 0; c < cfgs.length; c++) {
    const panel = renderPanel(cfgs[c].scene, { ...cfgs[c], width: CU_W, height: CU_H });
    blit(sheet, CW, CH, panel, PAD + c * (CU_W + PAD), rowY + ROW_TITLE_H);
  }
  console.log(`Rendered 3-panel close-up row ${cuIdx + 1}`);
}

drawRow4(0, 'GRAND OVERVIEWS: FRONT 3/4 DAY  |  NIGHT ILLUMINATED  |  OPPOSITE BACK (-Z)  |  DIRECTLY ABOVE (120M X 120M)', ROW1, [248, 228, 168]);
drawRow4(1, 'STREET-HEIGHT SILHOUETTES: REFLECTING POOL APPROACH  |  SW CORNER  |  SE CORNER  |  DISTANT FAR LOD SILHOUETTE', ROW2, [174, 232, 204]);
drawRow3(0, 'ARCHITECTURAL CLOSE-UPS: SOUTH TRIUMPHAL ARCHWAY  |  15.2M ARMILLARY SPHERE & STEEL TRUSSES  |  SKY CROWN & 105.5M BEACON', ROW3, [252, 220, 140]);
drawRow3(1, 'WALKABLE & DUSK VIEWS: ROTUNDA LOOKING UP TO ARMILLARY  |  58.2M SKY OBSERVATION DECK  |  PERISTYLE & ETERNAL FLAME AT NIGHT', ROW4, [184, 218, 250]);

const outPath = path.join(__dirname, 'great-meridian-review.png');
fs.writeFileSync(outPath, encodePng(CW, CH, sheet));
console.log(`Wrote great-meridian-review.png (${CW}x${CH})`);
