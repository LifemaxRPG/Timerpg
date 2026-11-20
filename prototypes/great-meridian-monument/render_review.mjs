#!/usr/bin/env node
/**
 * Software glTF 2.0 renderer generating ONE comprehensive review sheet for THE GREAT MERIDIAN MONUMENT:
 *   great-meridian-review.png (2620 x 2380)
 *
 * Includes EVERY view required by docs/GEMINI_MERIDIAN_REVISION.md:
 *   Row 1: All 4 historical states — DAY (1926 Dedication, 1941 Mature, 1976 Worn, 2000+ Restored)
 *   Row 2: All 4 historical states — NIGHT with projected flood_01..12 & beacon_beam markers
 *   Row 3: Solid Black Silhouette Test (Near vs Far at 128px, 64px, 32px) | Far beside Near (same camera) |
 *          Person-Height Reflecting Pool Approach | Rotunda Looking Up at 3-Node Armillary Sphere
 *   Row 4: Four 7m Art Deco Guardians (2x2 close-up) | Inside Hollow NW Pier (lift_car & winding stairs) |
 *          24m Cornice Gallery Walkway | 58m Sky Observation Deck (direction plate & telescope)
 *   Row 5: Octagonal Lantern Machinery Chamber (gears & Fresnel lens) | Open 4m Bronze Doors & West Hall
 *          Dedication Wall | Era Details (1976 Scaffolding + 2000+ Visitor Centre) | Plan View From Above
 *
 * NOTE: Software renders, not phone/Filament captures.
 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const CZ_M = -773.0 * 0.0776; // -59.9848 m

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
  const floodMarkers = [];
  let beaconMarker = null;

  function walk(nodeIdx, pPos, pRot) {
    const node = gltf.nodes[nodeIdx];
    if (!node || node.name === 'collision' || (node.extras && node.extras.collision_only)) return;
    const lt = node.translation || [0, 0, 0];
    const lr = node.rotation || [0, 0, 0, 1];
    const rlt = quatRotateVec(pRot, lt);
    const wPos = [pPos[0] + rlt[0], pPos[1] + rlt[1], pPos[2] + rlt[2]];
    const wRot = quatMul(pRot, lr);

    if (node.name && node.name.startsWith('flood_')) {
      floodMarkers.push({ name: node.name, pos: wPos, dir: (node.extras && node.extras.direction) || [0, 1, 0] });
    }
    if (node.name === 'beacon_beam') {
      beaconMarker = {
        pos: wPos,
        dir: (node.extras && node.extras.direction) || [0, -0.05, 1],
        active: !(node.extras && node.extras.active === false),
      };
    }

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
  return { tris: drawTriangles, floodMarkers, beaconMarker };
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

function drawLine(rgba, W, H, x0, y0, x1, y1, col, thickness = 1) {
  const dx = x1 - x0;
  const dy = y1 - y0;
  const steps = Math.max(1, Math.ceil(Math.hypot(dx, dy)));
  for (let i = 0; i <= steps; i++) {
    const px = Math.round(x0 + (dx * i) / steps);
    const py = Math.round(y0 + (dy * i) / steps);
    for (let oy = -thickness; oy <= thickness; oy++) {
      for (let ox = -thickness; ox <= thickness; ox++) {
        const x = px + ox, y = py + oy;
        if (x >= 0 && x < W && y >= 0 && y < H) {
          const off = (y * W + x) * 4;
          rgba[off] = col[0]; rgba[off + 1] = col[1]; rgba[off + 2] = col[2]; rgba[off + 3] = 255;
        }
      }
    }
  }
}

function drawCircle(rgba, W, H, cx, cy, r, col) {
  for (let y = Math.floor(cy - r); y <= Math.ceil(cy + r); y++) {
    for (let x = Math.floor(cx - r); x <= Math.ceil(cx + r); x++) {
      if (x >= 0 && x < W && y >= 0 && y < H && (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) {
        const off = (y * W + x) * 4;
        rgba[off] = col[0]; rgba[off + 1] = col[1]; rgba[off + 2] = col[2]; rgba[off + 3] = 255;
      }
    }
  }
}

function renderPanel(sceneObj, opts) {
  const tris = sceneObj.tris;
  const {
    width, height, eye, target, up = [0, 1, 0],
    fovDeg = 46, orthoScale = null,
    nightMode = false,
    silhouetteBlack = false,
    interiorBright = false,
    showFloodMarkers = false,
    sunDir = [-0.46, 0.76, 0.45],
    skyTop = [58, 110, 180], skyBottom = [202, 224, 246],
    clipMinY = -1e6,
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
  const nearPlane = 0.20;

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
    if (clipMinY > -1e5 && Math.max(tr.p0[1], tr.p1[1], tr.p2[1]) < clipMinY) continue;
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
    if (interiorBright) {
      shade = 0.62 + 0.16 * hemi + 0.30 * Math.abs(dot(tr.n0, sun));
    } else if (nightMode) {
      shade = 0.22 + 0.14 * hemi + 0.14 * ndl;
    } else {
      shade = 0.44 + 0.16 * hemi + 0.44 * ndl;
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

          if (silhouetteBlack) {
            zbuf[pIdx] = z;
            const off = pIdx * 4;
            rgba[off] = 12; rgba[off + 1] = 14; rgba[off + 2] = 18; rgba[off + 3] = 255;
            continue;
          }

          const u = (w0 * u0w + w1 * u1w + w2 * u2w) / invW;
          const v = (w0 * v0w + w1 * v1w + w2 * v2w) / invW;

          const [br, bg, bb, ba] = sampleNearest(tr.baseImg, u, v);
          if (ba < 10) continue;

          let r = br * shade;
          let g = bg * shade;
          let b = bb * shade;

          if (tr.emImg && (tr.ef[0] > 0 || tr.ef[1] > 0 || tr.ef[2] > 0)) {
            const [er, eg, eb] = sampleNearest(tr.emImg, u, v);
            const boost = nightMode ? 1.85 : 0.45;
            r += er * tr.ef[0] * boost;
            g += eg * tr.ef[1] * boost;
            b += eb * tr.ef[2] * boost;
          } else if (nightMode && tr.isGlass && ba < 245) {
            r += 96; g += 68; b += 26;
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
  if (!silhouetteBlack) {
    blends.sort((a, b) => {
      const za = toCam(a.p0)[2] + toCam(a.p1)[2] + toCam(a.p2)[2];
      const zb = toCam(b.p0)[2] + toCam(b.p1)[2] + toCam(b.p2)[2];
      return zb - za;
    });
    for (const tr of blends) rasterTri(tr, true);
  }

  // Draw crisp projected flood_01..12 aim rays & beacon_beam cone on night review panels!
  if (showFloodMarkers) {
    for (const fl of sceneObj.floodMarkers) {
      const c0 = toCam(fl.pos);
      const tip = [fl.pos[0] + fl.dir[0] * 14, fl.pos[1] + fl.dir[1] * 14, fl.pos[2] + fl.dir[2] * 14];
      const c1 = toCam(tip);
      if (c0[2] > nearPlane && c1[2] > nearPlane) {
        const s0 = projectCam(c0);
        const s1 = projectCam(c1);
        drawLine(rgba, width, height, s0[0], s0[1], s1[0], s1[1], [96, 232, 255], 1);
        drawCircle(rgba, width, height, s0[0], s0[1], 3.4, [255, 224, 96]);
      }
    }
    if (sceneObj.beaconMarker) {
      const bm = sceneObj.beaconMarker;
      const c0 = toCam(bm.pos);
      if (c0[2] > nearPlane) {
        const s0 = projectCam(c0);
        if (bm.active) {
          const tip = [bm.pos[0] + 28, bm.pos[1] - 2, bm.pos[2] + 18];
          const c1 = toCam(tip);
          if (c1[2] > nearPlane) {
            const s1 = projectCam(c1);
            drawLine(rgba, width, height, s0[0], s0[1], s1[0], s1[1], [255, 246, 168], 2);
          }
          drawCircle(rgba, width, height, s0[0], s0[1], 5.5, [255, 252, 200]);
        } else {
          // Draw crisp red X showing beacon is OFF in 1976!
          drawLine(rgba, width, height, s0[0] - 6, s0[1] - 6, s0[0] + 6, s0[1] + 6, [255, 86, 86], 1);
          drawLine(rgba, width, height, s0[0] - 6, s0[1] + 6, s0[0] + 6, s0[1] - 6, [255, 86, 86], 1);
        }
      }
    }
  }

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
  R: ['11110','10001','10001','11110','10100','10010','00001'],
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
  '&': ['01100','10010','10100','01000','10101','10010','01101'],
  '<': ['00010','00100','01000','10000','01000','00100','00010'],
  '=': ['00000','00000','11111','00000','11111','00000','00000'],
  ',': ['00000','00000','00000','00000','00110','00010','00100'],
  ' ': ['00000','00000','00000','00000','00000','00000','00000'],
};
FONT.R = ['11110','10001','10001','11110','10100','10010','10001'];

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

function drawBadge(dst, CW, CH, x, y, text, fg = [250, 232, 162]) {
  const w = text.length * 6 + 12;
  fillRect(dst, CW, CH, x, y, w, 17, [16, 18, 26]);
  drawText(dst, CW, CH, x + 6, y + 5, text, fg, 1);
}

console.log('Loading 4 historical state GLBs + Far LOD...');
const s1926 = loadGlb(path.join(__dirname, 'great-meridian-1926.glb'));
const s1941 = loadGlb(path.join(__dirname, 'great-meridian-1941.glb'));
const s1976 = loadGlb(path.join(__dirname, 'great-meridian-1976.glb'));
const s2000 = loadGlb(path.join(__dirname, 'great-meridian-2000.glb'));
const sFar  = loadGlb(path.join(__dirname, 'great-meridian-far.glb'));

const PW = 640;
const PH = 420;
const PAD = 12;
const HEADER_H = 68;
const ROW_TITLE_H = 26;
const CW = PAD + 4 * (PW + PAD);
const CH = HEADER_H + 5 * (ROW_TITLE_H + PH + PAD) + PAD;
const sheet = new Uint8Array(CW * CH * 4);

fillRect(sheet, CW, CH, 0, 0, CW, CH, [16, 18, 24]);
fillRect(sheet, CW, CH, PAD, PAD, CW - 2 * PAD, HEADER_H - PAD - 4, [28, 32, 44]);
drawText(sheet, CW, CH, 24, 18, 'THE GREAT MERIDIAN MONUMENT - 4 HISTORICAL STATES (1926 / 1941 / 1976 / 2000+) + FAR LOD (SOFTWARE RENDERS)', [248, 224, 144], 2);
drawText(sheet, CW, CH, 24, 42, '3-PART ICONIC SILHOUETTE | <=23.7K TRIS NEAR / 282 TRIS FAR | <16M2 GLOW | 4 GUARDIANS | HOLLOW NW PIER | 3-NODE ARMILLARY', [174, 212, 242], 2);

function rowY(idx) {
  return HEADER_H + idx * (ROW_TITLE_H + PH + PAD);
}

function drawRowBanner(idx, title, col) {
  const y = rowY(idx);
  fillRect(sheet, CW, CH, PAD, y, CW - 2 * PAD, ROW_TITLE_H - 2, [34, 38, 50]);
  drawText(sheet, CW, CH, PAD + 10, y + 6, title, col, 2);
}

// ==============================================================================
// ROW 1: ALL 4 HISTORICAL STATES — DAY
// ==============================================================================
drawRowBanner(0, 'ROW 1 - ALL 4 STATES (DAY):  1926 DEDICATION  |  1941 MATURE  |  1976 WORN (STAINS + SCAFFOLDING)  |  2000+ RESTORED (RAMP + VISITOR CTR)', [248, 228, 168]);
const dayCam = {
  eye: [-86.0, 44.0, CZ_M + 124.0],
  target: [0.0, 48.0, CZ_M + 4.0],
  fovDeg: 49,
  sunDir: [-0.48, 0.76, 0.44],
  skyTop: [54, 108, 178],
  skyBottom: [198, 222, 244],
};
const row1Defs = [
  { sc: s1926, cam: dayCam, badge: '1926 DEDICATION: FRESH COPPER + BUNTING + YOUNG TREES' },
  { sc: s1941, cam: dayCam, badge: '1941 MATURE: VERDIGRIS SPIRE + WAR PLAQUE + MATURE TREES' },
  { sc: s1976, cam: { ...dayCam, eye: [86.0, 44.0, CZ_M + 124.0], sunDir: [0.46, 0.76, 0.44] }, badge: '1976 WORN: WATER STREAKS + SE PIER SCAFFOLDING' },
  { sc: s2000, cam: dayCam, badge: '2000+ RESTORED: CLEAN STONE + EAST RAMP + SW VISITOR CTR' },
];
for (let c = 0; c < 4; c++) {
  const p = renderPanel(row1Defs[c].sc, { ...row1Defs[c].cam, width: PW, height: PH });
  const x = PAD + c * (PW + PAD), y = rowY(0) + ROW_TITLE_H;
  blit(sheet, CW, CH, p, x, y);
  drawBadge(sheet, CW, CH, x + 8, y + 8, row1Defs[c].badge);
}
console.log('Rendered Row 1 (4 states Day)');

// ==============================================================================
// ROW 2: ALL 4 HISTORICAL STATES — NIGHT + FLOODLIGHT & BEACON MARKERS
// ==============================================================================
drawRowBanner(1, 'ROW 2 - ALL 4 STATES (NIGHT + FLOOD_01..12 CYAN RAYS + BEACON_BEAM):  1926  |  1941  |  1976 (BEACON OFF + 3 BROKEN LAMPS)  |  2000+', [168, 222, 255]);
const nightCam = {
  eye: [86.0, 44.0, CZ_M + 124.0],
  target: [0.0, 48.0, CZ_M + 4.0],
  fovDeg: 49,
  nightMode: true,
  showFloodMarkers: true,
  sunDir: [0.35, 0.58, 0.50],
  skyTop: [10, 14, 28],
  skyBottom: [26, 36, 66],
};
const row2Defs = [
  { sc: s1926, badge: '1926 NIGHT: GAS LAMPS + BEACON ON + FLOOD NODES' },
  { sc: s1941, badge: '1941 NIGHT: ELECTRIC LAMPS + BEACON ON + FLOOD NODES' },
  { sc: s1976, badge: '1976 NIGHT: BEACON OFF (RED X) + 3 BROKEN LAMPS' },
  { sc: s2000, badge: '2000+ NIGHT: RELIT BEACON + VISIBLE FLOOD FIXTURES' },
];
for (let c = 0; c < 4; c++) {
  const p = renderPanel(row2Defs[c].sc, { ...nightCam, width: PW, height: PH });
  const x = PAD + c * (PW + PAD), y = rowY(1) + ROW_TITLE_H;
  blit(sheet, CW, CH, p, x, y);
  drawBadge(sheet, CW, CH, x + 8, y + 8, row2Defs[c].badge, [164, 232, 255]);
}
console.log('Rendered Row 2 (4 states Night + flood markers)');

// ==============================================================================
// ROW 3: SILHOUETTE TEST (128 / 64 / 32 PX) | FAR BESIDE NEAR | POOL APPROACH | ROTUNDA UP
// ==============================================================================
drawRowBanner(2, 'ROW 3 - SILHOUETTE & GRAND VIEWS:  BLACK SILHOUETTE (128/64/32PX)  |  FAR BESIDE NEAR  |  REFLECTING POOL APPROACH  |  ROTUNDA LOOKING UP', [176, 236, 200]);

// Panel 3.1: SOLID BLACK SILHOUETTE TEST (Near vs Far at 128 px, 64 px, 32 px tall!)
{
  const px = PAD, py = rowY(2) + ROW_TITLE_H;
  fillRect(sheet, CW, CH, px, py, PW, PH, [224, 234, 246]);
  const silCam = {
    eye: [-110.0, 48.0, CZ_M + 110.0],
    target: [0.0, 52.0, CZ_M],
    orthoScale: 58.0,
    silhouetteBlack: true,
    clipMinY: 1.8, // isolate monument above flat plaza so base-arch-legs-sphere-deck-lantern-needle silhouette is pure!
    skyTop: [224, 234, 246],
    skyBottom: [224, 234, 246],
  };
  const sizes = [
    { h: 128, w: 128, label: '128PX', xOff: 18 },
    { h: 64,  w: 64,  label: '64PX',  xOff: 168 },
    { h: 32,  w: 32,  label: '32PX',  xOff: 252 },
  ];
  drawText(sheet, CW, CH, px + 18, py + 34, 'NEAR MODEL (2000+)', [24, 30, 44], 2);
  drawText(sheet, CW, CH, px + 332, py + 34, 'FAR LOD (282 TRIS)', [24, 30, 44], 2);
  fillRect(sheet, CW, CH, px + 318, py + 28, 2, PH - 44, [156, 170, 190]);
  for (const sz of sizes) {
    const pNear = renderPanel(s2000, { ...silCam, width: sz.w, height: sz.h });
    const pFar  = renderPanel(sFar,  { ...silCam, width: sz.w, height: sz.h });
    const baseY = py + 280 - sz.h;
    blit(sheet, CW, CH, pNear, px + sz.xOff, baseY);
    blit(sheet, CW, CH, pFar,  px + 316 + sz.xOff, baseY);
    drawText(sheet, CW, CH, px + sz.xOff, py + 292, sz.label, [36, 44, 62], 1);
    drawText(sheet, CW, CH, px + 316 + sz.xOff, py + 292, sz.label, [36, 44, 62], 1);
  }
  // Also show exact front (+Z) orthographic silhouette at 96px tall at bottom!
  const frontSil = { ...silCam, eye: [0.0, 52.0, CZ_M + 140.0], target: [0.0, 52.0, CZ_M] };
  blit(sheet, CW, CH, renderPanel(s2000, { ...frontSil, width: 96, height: 96 }), px + 110, py + 312);
  blit(sheet, CW, CH, renderPanel(sFar,  { ...frontSil, width: 96, height: 96 }), px + 426, py + 312);
  drawText(sheet, CW, CH, px + 18, py + 362, 'FRONT +Z 96PX:', [36, 44, 62], 1);
  drawBadge(sheet, CW, CH, px + 8, py + 8, 'SOLID BLACK SILHOUETTE TEST: NEAR VS FAR (128 / 64 / 32 PX)');
}

// Panel 3.2: FAR MODEL BESIDE NEAR MODEL AT IDENTICAL CAMERA & SIZE!
{
  const px = PAD + 1 * (PW + PAD), py = rowY(2) + ROW_TITLE_H;
  const halfW = PW / 2;
  const cmpCam = {
    eye: [-98.0, 44.0, CZ_M + 126.0],
    target: [0.0, 50.0, CZ_M],
    fovDeg: 54,
    sunDir: [-0.46, 0.76, 0.44],
    skyTop: [62, 114, 180],
    skyBottom: [204, 224, 244],
  };
  blit(sheet, CW, CH, renderPanel(s2000, { ...cmpCam, width: halfW, height: PH }), px, py);
  blit(sheet, CW, CH, renderPanel(sFar,  { ...cmpCam, width: halfW, height: PH }), px + halfW, py);
  fillRect(sheet, CW, CH, px + halfW - 1, py, 2, PH, [248, 224, 144]);
  drawBadge(sheet, CW, CH, px + 8, py + 8, 'NEAR (23,722 TRIS)');
  drawBadge(sheet, CW, CH, px + halfW + 8, py + 8, 'FAR LOD (282 TRIS) - SAME CAM');
}

// Panel 3.3: PERSON-HEIGHT APPROACH FROM FAR END OF REFLECTING POOL!
{
  const px = PAD + 2 * (PW + PAD), py = rowY(2) + ROW_TITLE_H;
  const p = renderPanel(s2000, {
    width: PW, height: PH,
    eye: [0.0, 2.2, 2.0],
    target: [0.0, 46.0, CZ_M],
    fovDeg: 66,
    sunDir: [-0.42, 0.76, 0.48],
    skyTop: [52, 104, 178],
    skyBottom: [204, 226, 246],
  });
  blit(sheet, CW, CH, p, px, py);
  drawBadge(sheet, CW, CH, px + 8, py + 8, 'PERSON-HEIGHT APPROACH FROM REFLECTING POOL (Y=0 WATER)');
}

// Panel 3.4: LOOKING UP FROM COMPASS ROSE ROTUNDA AT 3-NODE ARMILLARY SPHERE!
{
  const px = PAD + 3 * (PW + PAD), py = rowY(2) + ROW_TITLE_H;
  const p = renderPanel(s2000, {
    width: PW, height: PH,
    eye: [0.0, 8.2, CZ_M + 10.0],
    target: [0.0, 41.0, CZ_M - 1.5],
    fovDeg: 74,
    sunDir: [0.24, 0.92, 0.28],
    skyTop: [54, 108, 178],
    skyBottom: [176, 212, 242],
  });
  blit(sheet, CW, CH, p, px, py);
  drawBadge(sheet, CW, CH, px + 8, py + 8, 'ROTUNDA LOOKING UP: 3-NODE ARMILLARY (OUTER/ECLIPTIC/GLOBE)');
}
console.log('Rendered Row 3 (Silhouette + Far/Near + Pool Approach + Rotunda Up)');

// ==============================================================================
// ROW 4: FOUR 7M GUARDIANS | HOLLOW NW PIER ELEVATOR & STAIRS | 24M CORNICE GALLERY | 58M SKY DECK
// ==============================================================================
drawRowBanner(3, 'ROW 4 - GUARDIANS & CLIMB VIEWS:  4 ART DECO GUARDIANS  |  HOLLOW NW PIER (LIFT_CAR & STAIRS)  |  24M CORNICE GALLERY  |  58M SKY DECK', [252, 218, 136]);

// Panel 4.1: FOUR 7 M ART DECO GUARDIANS CLOSE-UP (2x2 composite!)
{
  const px = PAD, py = rowY(3) + ROW_TITLE_H;
  const hw = PW / 2, hh = PH / 2;
  // Pier upper outer centers: mx_u = +/- 146 * 0.0776 = +/- 11.33 m, mz_u = CZ_M +/- 11.33 m; y = 49.2..56.0 m
  const gCams = [
    { label: 'SW: DEFENSE (SWORD)',   eye: [-11.33, 52.6, CZ_M + 27.6], target: [-11.33, 52.1, CZ_M + 13.7], sunDir: [-0.25, 0.72, 0.65] },
    { label: 'SE: LEARNING (BOOK)',   eye: [11.33, 52.6, CZ_M + 27.6],  target: [11.33, 52.1, CZ_M + 13.7],  sunDir: [0.25, 0.72, 0.65] },
    { label: 'NW: INDUSTRY (GEAR)',   eye: [-11.33, 52.6, CZ_M - 27.6], target: [-11.33, 52.1, CZ_M - 13.7], sunDir: [-0.25, 0.72, -0.65] },
    { label: 'NE: HARVEST (WHEAT)',   eye: [11.33, 52.6, CZ_M - 27.6],  target: [11.33, 52.1, CZ_M - 13.7],  sunDir: [0.25, 0.72, -0.65] },
  ];
  for (let gi = 0; gi < 4; gi++) {
    const gx = px + (gi % 2) * hw;
    const gy = py + Math.floor(gi / 2) * hh;
    const gp = renderPanel(s2000, {
      width: hw, height: hh,
      eye: gCams[gi].eye, target: gCams[gi].target,
      fovDeg: 48, sunDir: gCams[gi].sunDir,
      skyTop: [54, 108, 178], skyBottom: [192, 218, 242],
    });
    blit(sheet, CW, CH, gp, gx, gy);
    drawBadge(sheet, CW, CH, gx + 4, gy + 4, gCams[gi].label);
  }
  fillRect(sheet, CW, CH, px + hw - 1, py, 2, PH, [248, 224, 144]);
  fillRect(sheet, CW, CH, px, py + hh - 1, PW, 2, [248, 224, 144]);
}

// Panel 4.2: INSIDE HOLLOW NW PIER — OPEN-CAGE ELEVATOR (`lift_car`) & WINDING STAIRCASE!
{
  const px = PAD + 1 * (PW + PAD), py = rowY(3) + ROW_TITLE_H;
  // NW shaft center: (-11.795, y, -71.780); interior open width: X in [-14.36..-9.86], Z in [-74.34..-69.84]; lift_car at y = 24.06..26.47
  const p = renderPanel(s2000, {
    width: PW, height: PH,
    eye: [-10.05, 29.4, -70.05],
    target: [-12.35, 25.0, -72.30],
    fovDeg: 80,
    interiorBright: true,
    sunDir: [0.35, 0.85, 0.40],
    skyTop: [34, 40, 54],
    skyBottom: [48, 56, 72],
  });
  blit(sheet, CW, CH, p, px, py);
  drawBadge(sheet, CW, CH, px + 8, py + 8, 'INSIDE HOLLOW NW PIER: LIFT_CAR CAGE & WINDING STAIRCASE');
}

// Panel 4.3: 24 M CORNICE GALLERY WALKWAY, BRONZE RAILING & FRIEZE!
{
  const px = PAD + 2 * (PW + PAD), py = rowY(3) + ROW_TITLE_H;
  const p = renderPanel(s2000, {
    width: PW, height: PH,
    eye: [-16.6, 26.2, CZ_M + 16.4],
    target: [6.0, 26.8, CZ_M + 12.2],
    fovDeg: 62,
    sunDir: [-0.42, 0.76, 0.48],
    skyTop: [54, 108, 178],
    skyBottom: [200, 224, 246],
  });
  blit(sheet, CW, CH, p, px, py);
  drawBadge(sheet, CW, CH, px + 8, py + 8, '24M CORNICE GALLERY: WALKWAY, BRONZE RAILING & FLUTED PIERS');
}

// Panel 4.4: 58 M SKY OBSERVATION DECK — ENGRAVED BRASS DIRECTION PLATE & VIEWING TELESCOPE!
{
  const px = PAD + 3 * (PW + PAD), py = rowY(3) + ROW_TITLE_H;
  const halfW = PW / 2;
  // Left half: close-up looking down onto the SW corner engraved brass direction plate ("TO CAPITOL") right-side up!
  const pPlate = renderPanel(s2000, {
    width: halfW, height: PH,
    eye: [-11.64, 62.10, CZ_M + 11.40],
    target: [-11.64, 59.35, CZ_M + 14.00],
    fovDeg: 68,
    sunDir: [-0.30, 0.86, 0.40],
    skyTop: [54, 108, 178],
    skyBottom: [200, 224, 246],
  });
  // Right half: walking along the 58 m Sky Observation Deck beside the brass viewing telescope & railing!
  const pTele = renderPanel(s2000, {
    width: halfW, height: PH,
    eye: [-3.4, 59.9, CZ_M + 10.6],
    target: [0.5, 59.0, CZ_M + 14.2],
    fovDeg: 62,
    sunDir: [-0.42, 0.76, 0.48],
    skyTop: [54, 108, 178],
    skyBottom: [200, 224, 246],
  });
  blit(sheet, CW, CH, pPlate, px, py);
  blit(sheet, CW, CH, pTele, px + halfW, py);
  fillRect(sheet, CW, CH, px + halfW - 1, py, 2, PH, [248, 224, 144]);
  drawBadge(sheet, CW, CH, px + 8, py + 8, '58M DECK: BRASS PLATE');
  drawBadge(sheet, CW, CH, px + halfW + 8, py + 8, '58M DECK: TELESCOPE');
}
console.log('Rendered Row 4 (Guardians + NW Pier Interior + Cornice Gallery + Sky Deck)');

// ==============================================================================
// ROW 5: LANTERN MACHINERY | BRONZE DOORS & DEDICATION WALL | ERA DETAILS | ABOVE PLAN
// ==============================================================================
drawRowBanner(4, 'ROW 5 - INTERIOR & ERA DETAILS:  LANTERN MACHINERY CHAMBER  |  OPEN BRONZE DOORS & DEDICATION WALL  |  1976 & 2000 DETAILS  |  ABOVE PLAN', [192, 214, 250]);

// Panel 5.1: OCTAGONAL LANTERN MACHINERY CHAMBER (GEARS, FRESNEL LENS, DRIVE SHAFT, AMBER LANCETS)
{
  const px = PAD, py = rowY(4) + ROW_TITLE_H;
  // Lantern interior floor y = 61.77 m; bull gear y = 63.9 m, bevel gears y = 64.6..65.0 m, Fresnel lens y = 66.6..71.1 m
  const p = renderPanel(s2000, {
    width: PW, height: PH,
    eye: [-4.2, 65.0, CZ_M + 3.4],
    target: [0.5, 66.8, CZ_M - 0.5],
    fovDeg: 78,
    interiorBright: true,
    sunDir: [0.40, 0.80, 0.44],
    skyTop: [46, 86, 152],
    skyBottom: [176, 204, 234],
  });
  blit(sheet, CW, CH, p, px, py);
  drawBadge(sheet, CW, CH, px + 8, py + 8, 'LANTERN MACHINERY CHAMBER: GEARS, FRESNEL LENS & LANCETS');
}

// Panel 5.2: SPLIT — 4 M OPEN BRONZE DOORS (LEFT) & WEST HALL DEDICATION WALL (RIGHT)
{
  const px = PAD + 1 * (PW + PAD), py = rowY(4) + ROW_TITLE_H;
  const halfW = PW / 2;
  // Left: South main arch looking right at the 4m open bronze door folded back against the reveal + Meridian Line!
  const pDoor = renderPanel(s2000, {
    width: halfW, height: PH,
    eye: [3.2, 8.8, CZ_M + 23.2],
    target: [-7.5, 8.6, CZ_M + 14.8],
    fovDeg: 58,
    sunDir: [-0.30, 0.72, 0.62],
    skyTop: [54, 108, 178],
    skyBottom: [198, 222, 244],
  });
  // Right: Standing inside West Memorial Hall looking straight at the carved Dedication Wall!
  // Wall at x = (-219+16)*0.0776 = -15.75 m, z center = CZ_M + 164.5*0.0776 = CZ_M + 12.765 m, y = 6.83..10.86 m
  const pWall = renderPanel(s2000, {
    width: halfW, height: PH,
    eye: [-10.6, 8.85, CZ_M + 12.76],
    target: [-15.75, 8.85, CZ_M + 12.76],
    fovDeg: 56,
    interiorBright: true,
    sunDir: [0.85, 0.45, 0.25],
    skyTop: [36, 40, 50],
    skyBottom: [48, 52, 62],
  });
  blit(sheet, CW, CH, pDoor, px, py);
  blit(sheet, CW, CH, pWall, px + halfW, py);
  fillRect(sheet, CW, CH, px + halfW - 1, py, 2, PH, [248, 224, 144]);
  drawBadge(sheet, CW, CH, px + 8, py + 8, '4M OPEN BRONZE DOOR');
  drawBadge(sheet, CW, CH, px + halfW + 8, py + 8, 'WEST HALL DEDICATION WALL');
}

// Panel 5.3: SPLIT — 1976 SE PIER SCAFFOLDING + WATER STREAKS (LEFT) & 2000+ SW VISITOR CENTRE (RIGHT)
{
  const px = PAD + 2 * (PW + PAD), py = rowY(4) + ROW_TITLE_H;
  const halfW = PW / 2;
  const pScaf = renderPanel(s1976, {
    width: halfW, height: PH,
    eye: [40.0, 36.0, CZ_M + 40.0],
    target: [12.0, 38.0, CZ_M + 12.0],
    fovDeg: 58,
    sunDir: [0.42, 0.76, 0.48],
    skyTop: [68, 108, 162],
    skyBottom: [198, 214, 232],
  });
  const pVis = renderPanel(s2000, {
    width: halfW, height: PH,
    eye: [-32.0, 6.2, 10.0],
    target: [-45.0, 3.0, -14.0],
    fovDeg: 52,
    sunDir: [-0.42, 0.76, 0.48],
    skyTop: [54, 108, 178],
    skyBottom: [198, 222, 244],
  });
  blit(sheet, CW, CH, pScaf, px, py);
  blit(sheet, CW, CH, pVis, px + halfW, py);
  fillRect(sheet, CW, CH, px + halfW - 1, py, 2, PH, [248, 224, 144]);
  drawBadge(sheet, CW, CH, px + 8, py + 8, '1976: SE SCAFFOLDING');
  drawBadge(sheet, CW, CH, px + halfW + 8, py + 8, '2000+: SW VISITOR CTR');
}

// Panel 5.4: DIRECTLY ABOVE PLAN VIEW (2000+ RESTORED — 120M X 120M PLAZA)
{
  const px = PAD + 3 * (PW + PAD), py = rowY(4) + ROW_TITLE_H;
  const p = renderPanel(s2000, {
    width: PW, height: PH,
    eye: [0.0, 220.0, CZ_M],
    target: [0.0, 0.0, CZ_M],
    up: [0, 0, -1],
    orthoScale: 64.0,
    sunDir: [0.34, 0.88, 0.32],
    skyTop: [28, 32, 42],
    skyBottom: [28, 32, 42],
  });
  blit(sheet, CW, CH, p, px, py);
  drawBadge(sheet, CW, CH, px + 8, py + 8, 'ABOVE PLAN (2000+): MERIDIAN LINE, RAMP, VISITOR CTR, 8 TREES');
}
console.log('Rendered Row 5 (Lantern Machinery + Doors/Dedication + Era Details + Plan)');

const outPath = path.join(__dirname, 'great-meridian-review.png');
fs.writeFileSync(outPath, encodePng(CW, CH, sheet));
console.log(`Wrote great-meridian-review.png (${CW}x${CH})`);
