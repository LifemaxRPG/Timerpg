#!/usr/bin/env node
/**
 * Software glTF 2.0 renderer (with full node transform + quaternion instancing support)
 * generating ONE high-resolution, readable review sheet: courthouse-review.png.
 *
 * Layout (2620 x 3380):
 *   Rows 1-4: 1894, 1962, 1988, 2008 x 4 views (Day 3/4, Night, Opposite -Z, Above)
 *   Row 5 (4 street-height corner views, 640x420 each):
 *     SW, SE, NE, NW street-height silhouette checks of the cross plan, gabled pavilions, dormers & tower base (2008)
 *   Row 6 (3 big 858x460 close-ups):
 *     1) Main entrance & porch (2008 — open doorway, carved stone title, flush 1894 stone, small bronze plaque)
 *     2) Clock face & open belfry with bronze bell (2008 — circular dial in stone bezel, 10:10 lit)
 *     3) Clock stopped at 4:17 + SAVE OUR COURTHOUSE banner (1988 — unlit dark dial)
 *   Row 7 (3 big 858x460 close-ups):
 *     4) Stair hall looking up through Floor 2 & 3 open galleries to ONE clear skylight (1894)
 *     5) Civil War soldier memorial on SE lawn (1894 — stepped base, stout pedestal "1861-1865", 1.8m bronze soldier)
 *     6) Double-height courtroom with matching oak wainscot, bar rail & raised judge's bench (1894)
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

  // Pre-decode all meshes once
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
        isInterior: mat.name === 'interior_wall' || mat.name === 'interior_floor',
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
        // Transform vertices once for this node instance
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
            isInterior: pr.isInterior,
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
    fovDeg = 42, orthoScale = null,
    nightMode = false, interiorMode = false,
    sunDir = [0.45, 0.78, 0.42],
    skyTop = [110, 152, 204], skyBottom = [212, 228, 244],
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
  const nearPlane = 0.25;

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

  // Near-plane clipping for interior/close-up cameras!
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
    let shade;
    if (nightMode) {
      shade = 0.24 + 0.14 * hemi + 0.12 * ndl;
    } else if (interiorMode) {
      // Soft skylight + warm architectural bounce lighting inside the courthouse!
      shade = 0.48 + 0.24 * hemi + 0.30 * ndl;
    } else {
      shade = 0.44 + 0.16 * hemi + 0.44 * ndl;
      // Shadow ONLY interior vestibule/stair surfaces seen through open walk-in doorways from outside!
      if (tr.isInterior) {
        const my = (tr.p0[1] + tr.p1[1] + tr.p2[1]) / 3;
        if (my > 0.8 && my < 14.4) {
          shade *= (tr.n0[1] > 0.7 ? 0.48 : 0.28);
        }
      }
    }

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
            const boost = nightMode ? 1.25 : 0.30;
            r += er * tr.ef[0] * boost;
            g += eg * tr.ef[1] * boost;
            b += eb * tr.ef[2] * boost;
          } else if (nightMode && tr.isGlass && ba < 220) {
            // Subtle warm interior window glow at night (mirrors Filament glass lighting)
            r += 54; g += 44; b += 22;
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

// Compact 5x7 font for sheet headers
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

const STATES = [
  { year: 1894, file: 'courthouse-1894.glb', label: '1894-1950 AS BUILT (GAS LAMPS / BANDSTAND / HITCHING RAILS / WORKING CLOCK)' },
  { year: 1962, file: 'courthouse-1962.glb', label: '1951-1975 MODERNISED (BRICKED GROUND ARCHES / -Z CONCRETE ANNEX / COBRA LAMPS / BANDSTAND PAD)' },
  { year: 1988, file: 'courthouse-1988.glb', label: '1976-1999 NEGLECT & PROTEST (CLOCK STOPPED 4:17 & DARK / STAINED ANNEX / BOARDED WINDOWS / BANNER)' },
  { year: 2008, file: 'courthouse-2008.glb', label: '2000+ RESTORED (REOPENED ARCHES / PATCHED BRICK / NEW BANDSTAND / RAMP / TOWER FLOODLIGHTS)' },
];

const PW = 640;
const PH = 420;
const CU_W = 858;
const CU_H = 460;
const PAD = 12;
const HEADER_H = 68;
const ROW_TITLE_H = 28;
const CW = PAD + 4 * (PW + PAD);
const CH = HEADER_H + 5 * (ROW_TITLE_H + PH + PAD) + 2 * (ROW_TITLE_H + CU_H + PAD) + PAD;
const sheet = new Uint8Array(CW * CH * 4);
fillRect(sheet, CW, CH, 0, 0, CW, CH, [20, 22, 28]);
fillRect(sheet, CW, CH, PAD, PAD, CW - 2 * PAD, HEADER_H - PAD - 4, [32, 36, 46]);
drawText(sheet, CW, CH, 24, 18, 'HARLAN COUNTY COURTHOUSE & SQUARE - FOUR ERAS + 4 CORNER SILHOUETTES + CLOSE-UPS (SOFTWARE RENDERS)', [244, 232, 196], 2);
drawText(sheet, CW, CH, 24, 42, 'CROSS PLAN / 4 GABLED PAVILIONS / 38.0M TOWER / <15M2 GLOW | ROWS 1-4: DAY, NIGHT, -Z, ABOVE | ROW 5: 4 CORNERS | ROWS 6-7: CLOSE-UPS', [176, 208, 236], 2);

const CZ_M = -512 * 0.0776; // -39.7312 m (square & building center)

const STATE_VIEWS = [
  {
    name: 'DAY',
    eye: [-36.0, 26.0, CZ_M + 58.0],
    target: [0.0, 12.0, CZ_M],
    fovDeg: 44,
    sunDir: [-0.48, 0.78, 0.40],
    skyTop: [78, 124, 184],
    skyBottom: [194, 218, 242],
  },
  {
    name: 'NIGHT',
    eye: [34.0, 22.0, CZ_M + 56.0],
    target: [0.0, 13.0, CZ_M],
    fovDeg: 44,
    nightMode: true,
    sunDir: [0.3, 0.6, 0.5],
    skyTop: [12, 16, 30],
    skyBottom: [28, 34, 58],
  },
  {
    name: 'OPPOSITE',
    eye: [38.0, 24.0, CZ_M - 62.0],
    target: [0.0, 11.0, CZ_M - 4.0],
    fovDeg: 44,
    sunDir: [0.46, 0.78, -0.42],
    skyTop: [78, 124, 184],
    skyBottom: [194, 218, 242],
  },
  {
    name: 'ABOVE',
    eye: [0.0, 118.0, CZ_M],
    target: [0.0, 0.0, CZ_M],
    up: [0, 0, -1],
    orthoScale: 44.0,
    sunDir: [0.36, 0.86, 0.34],
    skyTop: [34, 38, 46],
    skyBottom: [34, 38, 46],
  },
];

// Cache loaded scenes so close-ups reuse them cleanly
const loadedScenes = {};
for (let r = 0; r < STATES.length; r++) {
  const st = STATES[r];
  const scene = loadGlb(path.join(__dirname, st.file));
  loadedScenes[st.year] = scene;
  const rowY = HEADER_H + r * (ROW_TITLE_H + PH + PAD);
  fillRect(sheet, CW, CH, PAD, rowY, CW - 2 * PAD, ROW_TITLE_H - 2, [34, 38, 48]);
  drawText(sheet, CW, CH, PAD + 10, rowY + 6, st.label, [244, 232, 196], 2);

  for (let c = 0; c < STATE_VIEWS.length; c++) {
    const panel = renderPanel(scene, { ...STATE_VIEWS[c], width: PW, height: PH });
    blit(sheet, CW, CH, panel, PAD + c * (PW + PAD), rowY + ROW_TITLE_H);
  }
  console.log(`Rendered 4 overview views for ${st.year} (${st.file})`);
}

// ROW 5: 4 STREET-HEIGHT CORNER VIEWS (SW, SE, NE, NW) — judge cross plan, gabled pavilions, valleys, dormers & tower base!
const CORNER_VIEWS = [
  {
    name: 'SW CORNER (STREET)',
    eye: [-35.5, 2.8, CZ_M + 36.5],
    target: [0.0, 14.5, CZ_M],
    fovDeg: 52,
    sunDir: [-0.45, 0.74, 0.48],
    skyTop: [78, 124, 184],
    skyBottom: [194, 218, 242],
  },
  {
    name: 'SE CORNER (STREET)',
    eye: [35.5, 2.8, CZ_M + 36.5],
    target: [0.0, 14.5, CZ_M],
    fovDeg: 52,
    sunDir: [0.42, 0.74, 0.50],
    skyTop: [78, 124, 184],
    skyBottom: [194, 218, 242],
  },
  {
    name: 'NE CORNER (STREET)',
    eye: [35.5, 2.8, CZ_M - 36.5],
    target: [0.0, 14.5, CZ_M],
    fovDeg: 52,
    sunDir: [0.45, 0.74, -0.48],
    skyTop: [78, 124, 184],
    skyBottom: [194, 218, 242],
  },
  {
    name: 'NW CORNER (STREET)',
    eye: [-35.5, 2.8, CZ_M - 36.5],
    target: [0.0, 14.5, CZ_M],
    fovDeg: 52,
    sunDir: [-0.42, 0.74, -0.50],
    skyTop: [78, 124, 184],
    skyBottom: [194, 218, 242],
  },
];

const cornerRowY = HEADER_H + 4 * (ROW_TITLE_H + PH + PAD);
fillRect(sheet, CW, CH, PAD, cornerRowY, CW - 2 * PAD, ROW_TITLE_H - 2, [38, 44, 58]);
drawText(sheet, CW, CH, PAD + 10, cornerRowY + 6, 'STREET-HEIGHT CORNER SILHOUETTES (2008): SW CORNER  |  SE CORNER  |  NE CORNER  |  NW CORNER', [182, 236, 206], 2);
for (let c = 0; c < CORNER_VIEWS.length; c++) {
  const panel = renderPanel(loadedScenes[2008], { ...CORNER_VIEWS[c], width: PW, height: PH });
  blit(sheet, CW, CH, panel, PAD + c * (PW + PAD), cornerRowY + ROW_TITLE_H);
}
console.log('Rendered 4 street-height corner views (2008)');

// 6 LARGE PERSON-HEIGHT & ARCHITECTURAL CLOSE-UPS (2 rows of 3 panels, 858x460 each!)
const CLOSEUP_ROWS = [
  {
    title: 'CLOSE-UPS (ROW 1): MAIN ENTRANCE & OPEN DOORWAY (2008)  |  CIRCULAR CLOCK & BELFRY BELL (2008)  |  STOPPED CLOCK 4:17 & BANNER (1988)',
    panels: [
      {
        year: 2008,
        eye: [-2.8, 3.8, CZ_M + 27.6],
        target: [0.0, 4.4, CZ_M + 14.8],
        fovDeg: 48,
        sunDir: [-0.30, 0.72, 0.62],
        skyTop: [78, 124, 184],
        skyBottom: [194, 218, 242],
      },
      {
        year: 2008,
        eye: [3.8, 26.2, CZ_M + 13.6],
        target: [0.0, 27.6, CZ_M + 2.0],
        fovDeg: 44,
        sunDir: [-0.35, 0.72, 0.60],
        skyTop: [78, 124, 184],
        skyBottom: [194, 218, 242],
      },
      {
        year: 1988,
        eye: [-3.2, 23.6, CZ_M + 17.6],
        target: [0.0, 24.4, CZ_M + 3.6],
        fovDeg: 46,
        sunDir: [-0.35, 0.72, 0.60],
        skyTop: [96, 114, 138],
        skyBottom: [182, 192, 204],
      },
    ],
  },
  {
    title: 'CLOSE-UPS (ROW 2): STAIR HALL -> ONE CLEAR SKYLIGHT (1894)  |  CIVIL WAR SOLDIER MEMORIAL (1894)  |  DOUBLE-HEIGHT COURTROOM (1894)',
    panels: [
      {
        // Inside ground-floor rotunda looking north & UP past clean staircase, mid-landing & open railings to ONE skylight!
        year: 1894,
        eye: [0.0, 2.5, CZ_M + 3.8],
        target: [0.0, 11.4, CZ_M - 1.8],
        fovDeg: 74,
        interiorMode: true,
        sunDir: [0.18, 0.96, 0.22],
        skyTop: [216, 232, 248],
        skyBottom: [216, 232, 248],
      },
      {
        // Dedicated close-up of Civil War Soldier Memorial on SE lawn: stepped base, stout pedestal "1861-1865", 1.8m bronze soldier!
        year: 1894,
        eye: [15.4, 3.0, -12.4],
        target: [18.0, 3.2, -19.55],
        fovDeg: 46,
        sunDir: [-0.42, 0.78, 0.46],
        skyTop: [78, 124, 184],
        skyBottom: [194, 218, 242],
      },
      {
        // Inside Floor 2 double-height courtroom looking south across oak bar rail & raised judge's bench!
        year: 1894,
        eye: [-2.4, 7.6, CZ_M + 5.0],
        target: [0.4, 7.2, CZ_M + 13.8],
        fovDeg: 64,
        interiorMode: true,
        sunDir: [0.28, 0.85, -0.42],
        skyTop: [196, 218, 240],
        skyBottom: [196, 218, 240],
      },
    ],
  },
];

for (let cr = 0; cr < CLOSEUP_ROWS.length; cr++) {
  const row = CLOSEUP_ROWS[cr];
  const rowY = HEADER_H + 5 * (ROW_TITLE_H + PH + PAD) + cr * (ROW_TITLE_H + CU_H + PAD);
  fillRect(sheet, CW, CH, PAD, rowY, CW - 2 * PAD, ROW_TITLE_H - 2, [42, 46, 58]);
  drawText(sheet, CW, CH, PAD + 10, rowY + 6, row.title, [252, 226, 152], 2);

  for (let c = 0; c < row.panels.length; c++) {
    const cfg = row.panels[c];
    const px = PAD + c * (CU_W + PAD);
    const py = rowY + ROW_TITLE_H;
    const panel = renderPanel(loadedScenes[cfg.year], { ...cfg, width: CU_W, height: CU_H });
    blit(sheet, CW, CH, panel, px, py);
  }
  console.log(`Rendered close-up row ${cr + 1}`);
}

const outPath = path.join(__dirname, 'courthouse-review.png');
fs.writeFileSync(outPath, encodePng(CW, CH, sheet));
console.log(`Wrote courthouse-review.png (${CW}x${CH})`);
