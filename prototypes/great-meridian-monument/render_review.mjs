#!/usr/bin/env node
/**
 * Software glTF 2.0 renderer generating ONE comprehensive, judgeable Revision 2 review sheet
 * for THE GREAT MERIDIAN MONUMENT:
 *   great-meridian-review.png (2560 x 5520)
 *
 * Covers ALL 10 numbered views in docs/GEMINI_MERIDIAN_REVISION_2.md (Section 10),
 * with hero shots >= 832-1256 px wide:
 *   Band 1 (Item 1): Approach from far end of Reflecting Pool at person height — DAY & NIGHT MONEY SHOT
 *                    (1256x700 each; night approximates 3000K stone flood pools, warm-gold sphere uplighting,
 *                    amber lancets, sweeping white aviation beacon beam, orange flames & soft faked water reflection)
 *   Band 2 (Item 2): All 4 Corner Views at street height (2000+ state, 4 x 1256x440)
 *   Band 3 (Item 3): All 4 Colossal 8 m Art Deco Guardians — Close-ups + Upward Views from 24 m Gallery/Plaza
 *   Band 4 (Item 4): 18 m Celestial Armillary Sphere — Outside DAY, Outside NIGHT (4 flood_sphere_*), Rotunda Looking Up
 *   Band 5 (Items 5 & 6): Art Deco Crown Close-up (3-tier lantern, 8 buttress fins, sunburst, spire) |
 *                         Stone, Weathering, Deep Coffered Arch Soffits, Voussoirs & Keystone Close-up
 *   Band 6 (Items 7 & 8): Open 5 m Bronze Doors (6 relief panels) | West Hall Dedication Wall | East Hall (Model & Map)
 *   Band 7 (Item 9): All 4 Eras from EXACT same camera (Day: 1926, 1941, 1976, 2000+) +
 *                    1976 Close-ups ("CLOSED FOR REPAIRS" sawhorse, tarped SE scaffolding, chain-link fence)
 *   Band 8 (Item 10): Near / Mid / Far side-by-side at identical camera & size + Solid Black Silhouettes (128/64/32 px)
 *
 * NOTE: Software renders, not phone/Filament captures. Night floodlight pools, sweeping beacon cone,
 * and reflecting pool water reflection are approximated in this review renderer (not baked into the .glb).
 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const U = 0.0776;
const CZ_M = -773.0 * U; // -59.9848 m

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

function parseHexColor(hex, fallback = [1.0, 0.89, 0.71]) {
  if (!hex || typeof hex !== 'string' || !hex.startsWith('#') || hex.length !== 7) return fallback;
  return [
    parseInt(hex.slice(1, 3), 16) / 255,
    parseInt(hex.slice(3, 5), 16) / 255,
    parseInt(hex.slice(5, 7), 16) / 255,
  ];
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
        matName: mat.name || '',
        isBlend: mat.alphaMode === 'BLEND',
        doubleSided: !!mat.doubleSided,
        isGlass: (mat.name || '').includes('glass'),
        isWater: (mat.name || '') === 'water_pool',
        isBronze: (mat.name || '') === 'bronze_gilded',
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
      const isSphere = node.name.startsWith('flood_sphere_');
      floodMarkers.push({
        name: node.name,
        pos: wPos,
        dir: norm((node.extras && node.extras.direction) || [0, 1, 0]),
        color: parseHexColor(node.extras && node.extras.color, isSphere ? [1.0, 0.85, 0.54] : [1.0, 0.89, 0.71]),
        isSphere,
      });
    }
    if (node.name === 'beacon_beam') {
      beaconMarker = {
        pos: wPos,
        dir: norm((node.extras && node.extras.direction) || [0, -0.05, 1]),
        active: !(node.extras && node.extras.active === false),
      };
    }

    if (node.mesh !== undefined) {
      const isArmillary = (node.name || '').startsWith('armillary_');
      for (const pr of decodedMeshes[node.mesh]) {
        const xPos = pr.pos.map((v) => {
          const rv = quatRotateVec(wRot, v);
          return [rv[0] + wPos[0], rv[1] + wPos[1], rv[2] + wPos[2]];
        });
        const xNrm = pr.nrm.map((n) => quatRotateVec(wRot, n));
        for (let t = 0; t < pr.idx.length; t += 3) {
          const i0 = pr.idx[t], i1 = pr.idx[t + 1], i2 = pr.idx[t + 2];
          const p0 = xPos[i0], p1 = xPos[i1], p2 = xPos[i2];
          drawTriangles.push({
            p0, p1, p2,
            cMid: [(p0[0] + p1[0] + p2[0]) / 3, (p0[1] + p1[1] + p2[1]) / 3, (p0[2] + p1[2] + p2[2]) / 3],
            n0: xNrm[i0],
            uv0: pr.uv[i0], uv1: pr.uv[i1], uv2: pr.uv[i2],
            baseImg: pr.baseImg,
            emImg: pr.emImg,
            ef: pr.ef,
            isBlend: pr.isBlend,
            doubleSided: pr.doubleSided,
            isGlass: pr.isGlass,
            isWater: pr.isWater,
            isBronze: pr.isBronze,
            isArmillary,
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

function addSoftBeaconBeam(rgba, W, H, sx0, sy0, sx1, sy1, halfWidthEnd, col, strength = 0.55) {
  const dx = sx1 - sx0;
  const dy = sy1 - sy0;
  const len = Math.hypot(dx, dy) || 1;
  const ux = dx / len, uy = dy / len;
  const nx = -uy, ny = ux;
  const minX = Math.max(0, Math.floor(Math.min(sx0, sx1) - halfWidthEnd - 4));
  const maxX = Math.min(W - 1, Math.ceil(Math.max(sx0, sx1) + halfWidthEnd + 4));
  const minY = Math.max(0, Math.floor(Math.min(sy0, sy1) - halfWidthEnd - 4));
  const maxY = Math.min(H - 1, Math.ceil(Math.max(sy0, sy1) + halfWidthEnd + 4));
  for (let py = minY; py <= maxY; py++) {
    for (let px = minX; px <= maxX; px++) {
      const rx = px - sx0, ry = py - sy0;
      const along = rx * ux + ry * uy;
      if (along < -2 || along > len) continue;
      const t = Math.max(0, along / len);
      const perp = Math.abs(rx * nx + ry * ny);
      const hw = 2.5 + t * halfWidthEnd;
      if (perp > hw) continue;
      const falloffPerp = Math.pow(1 - perp / hw, 1.8);
      const falloffLen = Math.pow(1 - t, 1.15);
      const a = strength * falloffPerp * falloffLen;
      const off = (py * W + px) * 4;
      rgba[off]     = Math.min(255, Math.round(rgba[off]     + col[0] * a));
      rgba[off + 1] = Math.min(255, Math.round(rgba[off + 1] + col[1] * a));
      rgba[off + 2] = Math.min(255, Math.round(rgba[off + 2] + col[2] * a));
    }
  }
}

function addSoftHalo(rgba, W, H, cx, cy, rad, col, strength = 0.65) {
  const minX = Math.max(0, Math.floor(cx - rad));
  const maxX = Math.min(W - 1, Math.ceil(cx + rad));
  const minY = Math.max(0, Math.floor(cy - rad));
  const maxY = Math.min(H - 1, Math.ceil(cy + rad));
  for (let py = minY; py <= maxY; py++) {
    for (let px = minX; px <= maxX; px++) {
      const d = Math.hypot(px - cx, py - cy);
      if (d > rad) continue;
      const a = strength * Math.pow(1 - d / rad, 2.2);
      const off = (py * W + px) * 4;
      rgba[off]     = Math.min(255, Math.round(rgba[off]     + col[0] * a));
      rgba[off + 1] = Math.min(255, Math.round(rgba[off + 1] + col[1] * a));
      rgba[off + 2] = Math.min(255, Math.round(rgba[off + 2] + col[2] * a));
    }
  }
}

function renderPanel(sceneObj, opts) {
  const tris = sceneObj.tris;
  const {
    width, height, eye, target, up = [0, 1, 0],
    fovDeg = 46, orthoScale = null,
    nightMode = false,
    nightFloodSim = false,
    fakePoolReflection = false,
    silhouetteBlack = false,
    interiorBright = false,
    sunDir = [-0.46, 0.76, 0.45],
    skyTop = [56, 108, 178], skyBottom = [202, 224, 246],
    clipMinY = -1e6,
  } = opts;

  const rgba = new Uint8Array(width * height * 4);
  const zbuf = new Float32Array(width * height);
  const waterMask = fakePoolReflection ? new Uint8Array(width * height) : null;
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

  // Subtle stars in deep night sky
  if (nightMode && !silhouetteBlack) {
    for (let si = 0; si < 110; si++) {
      const sx = ((si * 2654435761) >>> 0) % width;
      const sy = ((si * 2246822519) >>> 0) % Math.floor(height * 0.52);
      const off = (sy * width + sx) * 4;
      const br = 120 + (si % 95);
      rgba[off] = br; rgba[off + 1] = br + 8; rgba[off + 2] = Math.min(255, br + 24);
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

  const floods = sceneObj.floodMarkers || [];

  function evalTriLighting(tr) {
    const ndl = Math.max(0, dot(tr.n0, sun));
    const hemi = 0.5 + 0.5 * tr.n0[1];
    if (interiorBright) {
      const s = 0.64 + 0.16 * hemi + 0.28 * Math.abs(dot(tr.n0, sun));
      return [s, s, s];
    }
    if (!nightMode) {
      const s = 0.44 + 0.16 * hemi + 0.44 * ndl;
      // Subtle warm sunlight tint vs cool sky ambient
      return [s * 1.02, s * 1.00, s * 0.96];
    }
    // Deep twilight blue-slate ambient for night
    let lr = 0.11 + 0.06 * hemi;
    let lg = 0.14 + 0.07 * hemi;
    let lb = 0.22 + 0.10 * hemi;

    if (nightFloodSim && floods.length > 0) {
      const mp = tr.cMid;
      for (let fi = 0; fi < floods.length; fi++) {
        const fl = floods[fi];
        // Sphere floods illuminate the 18 m Armillary Sphere & immediate inner vault warmly!
        if (fl.isSphere && !tr.isArmillary && mp[1] < 24.0) continue;
        const dx = mp[0] - fl.pos[0];
        const dy = mp[1] - fl.pos[1];
        const dz = mp[2] - fl.pos[2];
        const d2 = dx * dx + dy * dy + dz * dz;
        const dist = Math.sqrt(d2) + 0.001;
        const lx = dx / dist, ly = dy / dist, lz = dz / dist;
        const cosAng = lx * fl.dir[0] + ly * fl.dir[1] + lz * fl.dir[2];
        const cosOuter = fl.isSphere ? 0.56 : 0.62;
        const cosInner = fl.isSphere ? 0.90 : 0.92;
        if (cosAng <= cosOuter) continue;
        const cone = Math.pow(Math.min(1, (cosAng - cosOuter) / (cosInner - cosOuter)), 1.7);
        const ndot = Math.max(fl.isSphere && tr.isArmillary ? 0.52 : 0.20, -(tr.n0[0] * lx + tr.n0[1] * ly + tr.n0[2] * lz));
        const rng = fl.isSphere ? 28.0 : 38.0;
        const att = 1.0 / (1.0 + (dist * dist) / (rng * rng));
        const power = fl.isSphere ? (tr.isArmillary ? 0.95 : 0.16) : 0.34;
        const contrib = power * cone * ndot * att;
        lr += fl.color[0] * contrib;
        lg += fl.color[1] * contrib;
        lb += fl.color[2] * contrib;
      }
      // Subtle amber crown spill on the 3-tier lantern stone immediately around the glowing lancets!
      if (mp[1] >= 57.0 && mp[1] <= 80.0 && Math.hypot(mp[0], mp[2] - CZ_M) < 16.0) {
        const spill = 0.28 * (1.0 - Math.abs(mp[1] - 66.0) / 16.0);
        if (spill > 0) {
          lr += 1.0 * spill;
          lg += 0.66 * spill;
          lb += 0.22 * spill;
        }
      }
      // Warm orange firelight spill from the 4 South stair eternal braziers!
      if (mp[1] <= 15.0 && Math.abs(mp[0]) < 14.0 && mp[2] > CZ_M + 18.0 && mp[2] < CZ_M + 34.0) {
        const fspill = 0.22;
        lr += 1.0 * fspill;
        lg += 0.44 * fspill;
        lb += 0.12 * fspill;
      }
      // Soft Reinhard tone curve so warm 3000K stone & golden sphere never clip to flat white!
      lr = lr / (1.0 + 0.38 * lr);
      lg = lg / (1.0 + 0.38 * lg);
      lb = lb / (1.0 + 0.38 * lb);
    }
    return [lr, lg, lb];
  }

  function rasterTri(tr, isBlendPass) {
    const c0 = toCam(tr.p0);
    const c1 = toCam(tr.p1);
    const c2 = toCam(tr.p2);
    if (orthoScale === null && c0[2] < nearPlane && c1[2] < nearPlane && c2[2] < nearPlane) return;

    const clipped = orthoScale !== null
      ? [[{ c: c0, uv: tr.uv0 }, { c: c1, uv: tr.uv1 }, { c: c2, uv: tr.uv2 }]]
      : clipTriNear({ c: c0, uv: tr.uv0 }, { c: c1, uv: tr.uv1 }, { c: c2, uv: tr.uv2 });

    const [sr, sg, sb] = silhouetteBlack ? [0, 0, 0] : evalTriLighting(tr);

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
            rgba[off] = 10; rgba[off + 1] = 12; rgba[off + 2] = 16; rgba[off + 3] = 255;
            continue;
          }

          const u = (w0 * u0w + w1 * u1w + w2 * u2w) / invW;
          const v = (w0 * v0w + w1 * v1w + w2 * v2w) / invW;

          const [br, bg, bb, ba] = sampleNearest(tr.baseImg, u, v);
          if (ba < 10) continue;

          let r = br * sr;
          let g = bg * sg;
          let b = bb * sb;

          if (tr.emImg && (tr.ef[0] > 0 || tr.ef[1] > 0 || tr.ef[2] > 0)) {
            const [er, eg, eb] = sampleNearest(tr.emImg, u, v);
            const boost = nightMode ? 1.95 : 0.45;
            r += er * tr.ef[0] * boost;
            g += eg * tr.ef[1] * boost;
            b += eb * tr.ef[2] * boost;
          } else if (nightMode && tr.isGlass && ba < 245) {
            r += 118; g += 78; b += 26;
          }

          r = Math.min(255, Math.max(0, Math.round(r)));
          g = Math.min(255, Math.max(0, Math.round(g)));
          b = Math.min(255, Math.max(0, Math.round(b)));

          const off = pIdx * 4;
          if (!isBlendPass || ba >= 250) {
            zbuf[pIdx] = z;
            rgba[off] = r; rgba[off + 1] = g; rgba[off + 2] = b; rgba[off + 3] = 255;
            if (waterMask) waterMask[pIdx] = tr.isWater ? 1 : 0;
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

  // Sweeping white aviation beacon cone + soft warm flame halos on Night Money Shot!
  if (nightFloodSim && sceneObj.beaconMarker && sceneObj.beaconMarker.active) {
    const bm = sceneObj.beaconMarker;
    const c0 = toCam(bm.pos);
    if (c0[2] > nearPlane) {
      const s0 = projectCam(c0);
      const tipR = [bm.pos[0] + 62, bm.pos[1] + 9, bm.pos[2] + 18];
      const tipL = [bm.pos[0] - 58, bm.pos[1] + 4, bm.pos[2] - 12];
      const cR = toCam(tipR), cL = toCam(tipL);
      if (cR[2] > nearPlane) {
        const sR = projectCam(cR);
        addSoftBeaconBeam(rgba, width, height, s0[0], s0[1], sR[0], sR[1], Math.round(width * 0.045), [255, 255, 255], 0.56);
      }
      if (cL[2] > nearPlane) {
        const sL = projectCam(cL);
        addSoftBeaconBeam(rgba, width, height, s0[0], s0[1], sL[0], sL[1], Math.round(width * 0.042), [235, 245, 255], 0.44);
      }
      addSoftHalo(rgba, width, height, s0[0], s0[1], 24, [255, 252, 232], 0.85);
    }
    // Soft warm glow halos on the 4 eternal flames & golden armillary sphere center!
    const armC = toCam([0, 528 * U, CZ_M]);
    if (armC[2] > nearPlane) {
      const sA = projectCam(armC);
      addSoftHalo(rgba, width, height, sA[0], sA[1], Math.round(height * 0.11), [255, 212, 128], 0.24);
    }
    for (const bp of [
      [-105 * U, 120 * U, (CZ_M / U + 286) * U], [105 * U, 120 * U, (CZ_M / U + 286) * U],
      [-105 * U, 64 * U, (CZ_M / U + 372) * U], [105 * U, 64 * U, (CZ_M / U + 372) * U],
    ]) {
      const cb = toCam(bp);
      if (cb[2] > nearPlane) {
        const sb = projectCam(cb);
        addSoftHalo(rgba, width, height, sb[0], sb[1], 18, [255, 136, 48], 0.55);
      }
    }
  }

  // Soft faked vertical water reflection in the Reflecting Pool (strictly inside waterMask, not in the GLB!)
  if (fakePoolReflection && waterMask) {
    const cHoriz = toCam([0.0, 0.0, -346.0 * U]);
    const yH = cHoriz[2] > nearPlane ? projectCam(cHoriz)[1] : height * 0.72;
    const snap = new Uint8Array(rgba);
    for (let py = 0; py < height; py++) {
      const rip = Math.round(Math.sin(py * 0.42) * 3.0);
      const sy = Math.max(0, Math.min(height - 1, Math.round(2 * yH - py - 8)));
      for (let px = 0; px < width; px++) {
        const pIdx = py * width + px;
        if (waterMask[pIdx] !== 1) continue;
        // 3-sample soft horizontal blur for rippling water mirror sheen
        let rr = 0, rg = 0, rb = 0;
        for (const ox of [-2, 0, 2]) {
          const sx = Math.max(0, Math.min(width - 1, px + rip + ox));
          const so = (sy * width + sx) * 4;
          rr += snap[so]; rg += snap[so + 1]; rb += snap[so + 2];
        }
        rr /= 3; rg /= 3; rb /= 3;
        const off = pIdx * 4;
        const luma = (rr * 0.3 + rg * 0.59 + rb * 0.11) / 255;
        const blend = 0.44 + 0.34 * luma;
        rgba[off]     = Math.min(255, Math.round(rgba[off]     * (1 - blend) + rr * blend * 1.12));
        rgba[off + 1] = Math.min(255, Math.round(rgba[off + 1] * (1 - blend) + rg * blend * 1.06));
        rgba[off + 2] = Math.min(255, Math.round(rgba[off + 2] * (1 - blend) + rb * blend * 1.02));
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
  '&': ['01100','10010','10100','01000','10101','10010','01101'],
  '<': ['00010','00100','01000','10000','01000','00100','00010'],
  '=': ['00000','00000','11111','00000','11111','00000','00000'],
  ',': ['00000','00000','00000','00000','00110','00010','00100'],
  '"': ['01010','01010','00000','00000','00000','00000','00000'],
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

function drawBadge(dst, CW, CH, x, y, text, fg = [252, 232, 162], scale = 2) {
  const padX = scale === 2 ? 10 : 6;
  const h = scale === 2 ? 26 : 18;
  const w = text.length * 6 * scale + padX * 2;
  fillRect(dst, CW, CH, x, y, w, h, [14, 16, 24]);
  drawText(dst, CW, CH, x + padX, y + (scale === 2 ? 6 : 5), text, fg, scale);
}

console.log('Loading all 4 historical GLBs + Mid LOD + Far LOD...');
const s1926 = loadGlb(path.join(__dirname, 'great-meridian-1926.glb'));
const s1941 = loadGlb(path.join(__dirname, 'great-meridian-1941.glb'));
const s1976 = loadGlb(path.join(__dirname, 'great-meridian-1976.glb'));
const s2000 = loadGlb(path.join(__dirname, 'great-meridian-2000.glb'));
const sMid  = loadGlb(path.join(__dirname, 'great-meridian-mid.glb'));
const sFar  = loadGlb(path.join(__dirname, 'great-meridian-far.glb'));

// Large judgeable canvas: 2560 px wide, hero panels 832..1256 px wide!
const CW = 2560;
const PAD = 16;
const INNER = CW - 2 * PAD; // 2528
const W2 = (INNER - PAD) / 2;         // 1256 px wide!
const W3 = Math.floor((INNER - 2 * PAD) / 3); // 832 px wide!
const W4 = (INNER - 3 * PAD) / 4;     // 620 px wide!
const BANNER_H = 32;

const BANDS = [
  { id: 'b1', h: 700 },               // Item 1: Hero Pool Approach Day & Night Money Shot (2 x 1256x700)
  { id: 'b2', h: 430 * 2 + PAD },     // Item 2: 4 Street-Height Corners (2x2 x 1256x430)
  { id: 'b3', h: 440 + PAD + 390 },   // Item 3: 4 Guardians Close-up + Upward from 24m Gallery/Plaza
  { id: 'b4', h: 520 },               // Item 4: 18m Sphere Outside Day, Outside Night, Rotunda Looking Up (3 x 832x520)
  { id: 'b5', h: 540 },               // Items 5 & 6: Crown Close-up | Stone, Weathering, Coffering & Keystone (2 x 1256x540)
  { id: 'b6', h: 500 },               // Items 7 & 8: Open 5m Bronze Doors | West Hall Dedication | East Hall Model+Map (3 x 832x500)
  { id: 'b7', h: 420 + PAD + 440 },   // Item 9: All 4 Eras Same Cam (Day) + 1976 "CLOSED FOR REPAIRS" & SE Scaffolding Close-ups
  { id: 'b8', h: 480 },               // Item 10: Near / Mid / Far Side-by-Side + Black Silhouettes (128 / 64 / 32 px)
];

const HEADER_H = 84;
const bandTop = {};
let curY = HEADER_H;
for (const b of BANDS) {
  bandTop[b.id] = curY;
  curY += BANNER_H + b.h + PAD;
}
const CH = curY + PAD;
const sheet = new Uint8Array(CW * CH * 4);
fillRect(sheet, CW, CH, 0, 0, CW, CH, [14, 16, 22]);
fillRect(sheet, CW, CH, PAD, PAD, INNER, HEADER_H - PAD - 6, [26, 30, 44]);
drawText(sheet, CW, CH, 28, 22, 'THE GREAT MERIDIAN MONUMENT - REVISION 2 REVIEW SHEET (SOFTWARE RENDERS, HERO PANELS 832-1256PX)', [250, 226, 146], 2);
drawText(sheet, CW, CH, 28, 48, '4 ERAS + MID LOD (5,042 TRIS) + FAR LOD (330 TRIS) | 8M GUARDIANS | 18M SPHERE | 3-TIER CROWN | 3 STONES | NOTE: NIGHT LIGHT POOLS & REFLECTION APPROXIMATED', [172, 214, 246], 2);

function drawBanner(id, title, col) {
  const y = bandTop[id];
  fillRect(sheet, CW, CH, PAD, y, INNER, BANNER_H - 4, [34, 40, 56]);
  drawText(sheet, CW, CH, PAD + 12, y + 7, title, col, 2);
  return y + BANNER_H;
}

// ==============================================================================
// BAND 1 (ITEM 1): HERO REFLECTING POOL APPROACH AT PERSON HEIGHT — DAY & NIGHT MONEY SHOT!
// ==============================================================================
{
  const y0 = drawBanner('b1', '1. REFLECTING POOL APPROACH (PERSON HEIGHT, 1256PX HEROES):  DAY (2000+)  |  NIGHT MONEY SHOT (3000K FLOODS + GOLD SPHERE + AMBER CROWN + BEACON + REFLECTION)', [252, 230, 152]);
  const poolCam = {
    eye: [0.0, 2.3, -4.6],
    target: [0.0, 34.0, CZ_M],
    fovDeg: 82,
  };
  const pDay = renderPanel(s2000, {
    ...poolCam, width: W2, height: 700,
    sunDir: [-0.42, 0.74, 0.52],
    skyTop: [46, 98, 174], skyBottom: [198, 224, 248],
  });
  blit(sheet, CW, CH, pDay, PAD, y0);
  drawBadge(sheet, CW, CH, PAD + 10, y0 + 10, 'DAY APPROACH: EXEDRA, POOL, 8M GUARDIANS, 18M SPHERE IN SKY VAULT, 3-TIER CROWN');

  const pNight = renderPanel(s2000, {
    ...poolCam, width: W2, height: 700,
    nightMode: true,
    nightFloodSim: true,
    fakePoolReflection: true,
    sunDir: [0.25, 0.45, 0.40],
    skyTop: [8, 12, 26], skyBottom: [24, 34, 62],
  });
  blit(sheet, CW, CH, pNight, PAD + W2 + PAD, y0);
  drawBadge(sheet, CW, CH, PAD + W2 + PAD + 10, y0 + 10, 'NIGHT MONEY SHOT: WARM 3000K FLOODS, GOLD SPHERE, AMBER LANCETS, BEACON & REFLECTION', [168, 232, 255]);
  console.log('Rendered Band 1 (Day + Night Money Shot)');
}

// ==============================================================================
// BAND 2 (ITEM 2): FOUR CORNER VIEWS AT STREET HEIGHT (2000+ STATE, 1256PX WIDE EACH!)
// ==============================================================================
{
  const y0 = drawBanner('b2', '2. FOUR CORNER VIEWS AT STREET HEIGHT (2000+ RESTORED STATE):  SW (+ VISITOR CTR)  |  SE (+ EAST RAMP)  |  NW (HOLLOW PIER)  |  NE (+ 1926 CORNERSTONE)', [176, 224, 252]);
  const cornerDefs = [
    { x: PAD,            y: y0,             eye: [-54.0, 2.4, CZ_M + 56.0], target: [-4.0, 38.0, CZ_M + 4.0], sun: [-0.45, 0.72, 0.52], badge: 'SW CORNER (STREET HEIGHT): VISITOR CTR, DEFENSE GUARDIAN, SOUTH ARCH' },
    { x: PAD + W2 + PAD, y: y0,             eye: [54.0, 2.4, CZ_M + 56.0],  target: [4.0, 38.0, CZ_M + 4.0],  sun: [0.45, 0.72, 0.52],  badge: 'SE CORNER (STREET HEIGHT): EAST ACCESS RAMP, LEARNING GUARDIAN, SOUTH ARCH' },
    { x: PAD,            y: y0 + 430 + PAD, eye: [-54.0, 2.4, CZ_M - 56.0], target: [-4.0, 38.0, CZ_M - 4.0], sun: [-0.45, 0.72, -0.52], badge: 'NW CORNER (STREET HEIGHT): HOLLOW ELEVATOR PIER, INDUSTRY GUARDIAN' },
    { x: PAD + W2 + PAD, y: y0 + 430 + PAD, eye: [54.0, 2.4, CZ_M - 56.0],  target: [4.0, 38.0, CZ_M - 4.0],  sun: [0.45, 0.72, -0.52],  badge: 'NE CORNER (STREET HEIGHT): 1926 CORNERSTONE, HARVEST GUARDIAN, NORTH ARCH' },
  ];
  for (const cd of cornerDefs) {
    const p = renderPanel(s2000, {
      width: W2, height: 430, eye: cd.eye, target: cd.target, fovDeg: 66, sunDir: cd.sun,
      skyTop: [52, 104, 178], skyBottom: [198, 224, 246],
    });
    blit(sheet, CW, CH, p, cd.x, cd.y);
    drawBadge(sheet, CW, CH, cd.x + 10, cd.y + 10, cd.badge);
  }
  console.log('Rendered Band 2 (4 Street-Height Corner Views)');
}

// ==============================================================================
// BAND 3 (ITEM 3): FOUR COLOSSAL 8 M GUARDIANS — CLOSE-UP & UPWARD FROM PLAZA / 24 M GALLERY
// ==============================================================================
{
  const y0 = drawBanner('b3', '3. FOUR COLOSSAL 8M ART DECO GUARDIANS (LEDGES AT 44-52M):  TOP = CLOSE-UPS (ATTRIBUTES + 0.6M LEDGE NAMES)  |  BOTTOM = UPWARD SKY SILHOUETTE FROM GALLERY/PLAZA', [252, 218, 138]);
  // Guardian ledge world centers: (sx * 210 * U, 44.54, CZ_M + sz * 210 * U) = (+/-16.30, 44.54, CZ_M +/-16.30)
  const gSpecs = [
    { sx: -1, sz: 1,  title: 'SW: DEFENSE (SWORD + SHIELD)',     upTitle: 'DEFENSE - UPWARD SKY SILHOUETTE (PLAZA/GALLERY)' },
    { sx: 1,  sz: 1,  title: 'SE: LEARNING (OPEN BOOK + TORCH)', upTitle: 'LEARNING - UPWARD SKY SILHOUETTE (PLAZA/GALLERY)' },
    { sx: -1, sz: -1, title: 'NW: INDUSTRY (HAMMER + GEAR)',     upTitle: 'INDUSTRY - UPWARD SKY SILHOUETTE (PLAZA/GALLERY)' },
    { sx: 1,  sz: -1, title: 'NE: HARVEST (WHEAT + SICKLE)',     upTitle: 'HARVEST - UPWARD SKY SILHOUETTE (PLAZA/GALLERY)' },
  ];
  for (let i = 0; i < 4; i++) {
    const g = gSpecs[i];
    const gx = g.sx * 16.30;
    const gz = CZ_M + g.sz * 16.30;
    const xPos = PAD + i * (W4 + PAD);
    const invS2 = 0.70710678;
    const dx = g.sx * invS2, dz = g.sz * invS2;
    const tx = dz, tz = -dx;
    // Heroic 3/4 sculptural close-up framing BOTH the 0.6 m granite ledge name AND Learning's full raised torch!
    const pClose = renderPanel(s2000, {
      width: W4, height: 440,
      eye: [gx + dx * 13.0 + tx * 2.8, 49.4, gz + dz * 13.0 + tz * 2.8],
      target: [gx, 49.2, gz],
      fovDeg: 52,
      sunDir: [dx * 0.48 + tx * 0.58, 0.65, dz * 0.48 + tz * 0.58],
      skyTop: [46, 98, 174], skyBottom: [190, 218, 244],
    });
    blit(sheet, CW, CH, pClose, xPos, y0);
    drawBadge(sheet, CW, CH, xPos + 8, y0 + 8, g.title, [252, 232, 162], 1);

    // Upward dramatic sky-silhouette view from the 24 m gallery / plaza off-diagonal so full figure silhouettes against blue sky!
    const pUp = renderPanel(s2000, {
      width: W4, height: 390,
      eye: [gx + dx * 20.0 + tx * 11.0, 26.8, gz + dz * 20.0 + tz * 11.0],
      target: [gx, 49.6, gz],
      fovDeg: 38,
      sunDir: [dx * 0.45 + tx * 0.62, 0.64, dz * 0.45 + tz * 0.62],
      skyTop: [44, 96, 172], skyBottom: [188, 216, 244],
    });
    blit(sheet, CW, CH, pUp, xPos, y0 + 440 + PAD);
    drawBadge(sheet, CW, CH, xPos + 8, y0 + 440 + PAD + 8, g.upTitle, [176, 232, 255], 1);
  }
  console.log('Rendered Band 3 (4 Guardians Close-ups + Upward Views)');
}

// ==============================================================================
// BAND 4 (ITEM 4): 18 M CELESTIAL ARMILLARY SPHERE — OUTSIDE DAY, OUTSIDE NIGHT, ROTUNDA UP!
// ==============================================================================
{
  const y0 = drawBanner('b4', '4. THE 18M CELESTIAL ARMILLARY SPHERE (832PX PANELS):  OUTSIDE DAY (OPEN VAULT)  |  OUTSIDE NIGHT (4 FLOOD_SPHERE UPLIGHTS)  |  ROTUNDA LOOKING UP', [176, 236, 200]);
  const sphOutCam = {
    eye: [-14.0, 31.0, CZ_M + 42.0],
    target: [0.0, 41.2, CZ_M],
    fovDeg: 54,
  };
  const pSphDay = renderPanel(s2000, {
    ...sphOutCam, width: W3, height: 520,
    sunDir: [-0.42, 0.76, 0.48], skyTop: [48, 100, 176], skyBottom: [196, 222, 246],
  });
  blit(sheet, CW, CH, pSphDay, PAD, y0);
  drawBadge(sheet, CW, CH, PAD + 8, y0 + 8, 'OUTSIDE DAY: 18M GOLD BANDS, 5M EARTH GLOBE, OPEN SKY VAULT', [252, 232, 162], 1);

  const pSphNight = renderPanel(s2000, {
    ...sphOutCam, width: W3, height: 520,
    nightMode: true, nightFloodSim: true,
    sunDir: [0.25, 0.55, 0.40], skyTop: [8, 12, 26], skyBottom: [20, 30, 56],
  });
  blit(sheet, CW, CH, pSphNight, PAD + W3 + PAD, y0);
  drawBadge(sheet, CW, CH, PAD + W3 + PAD + 8, y0 + 8, 'OUTSIDE NIGHT: FLOOD_SPHERE_01..04 WARM GOLD IN DARK VAULT', [168, 232, 255], 1);

  const pSphUp = renderPanel(s2000, {
    width: W3, height: 520,
    eye: [0.0, 8.2, CZ_M + 9.2],
    target: [0.0, 41.0, CZ_M - 1.2],
    fovDeg: 74,
    sunDir: [0.26, 0.90, 0.34], skyTop: [48, 102, 178], skyBottom: [176, 214, 246],
  });
  blit(sheet, CW, CH, pSphUp, PAD + 2 * (W3 + PAD), y0);
  drawBadge(sheet, CW, CH, PAD + 2 * (W3 + PAD) + 8, y0 + 8, 'ROTUNDA LOOKING STRAIGHT UP: 12 ZODIAC PLAQUES + 0.4M POLAR AXIS', [252, 232, 162], 1);
  console.log('Rendered Band 4 (18m Armillary Sphere Day, Night & Rotunda Up)');
}

// ==============================================================================
// BAND 5 (ITEMS 5 & 6): CROWN CLOSE-UP FROM 58M DECK | STONE, WEATHERING & ARCH COFFERING
// ==============================================================================
{
  const y0 = drawBanner('b5', '5 & 6. ART DECO CROWN CLOSE-UP (3-TIER LANTERN, 8 BUTTRESS FINS, 16-RIB SUNBURST, SPIRE)  |  THREE STONES, WEATHERING, COFFERED ARCHES, VOUSSOIRS & KEYSTONE', [250, 214, 154]);
  const pCrown = renderPanel(s2000, {
    width: W2, height: 540,
    eye: [-22.0, 62.5, CZ_M + 26.5],
    target: [0.0, 77.5, CZ_M],
    fovDeg: 66,
    sunDir: [-0.46, 0.72, 0.52], skyTop: [46, 98, 174], skyBottom: [194, 220, 246],
  });
  blit(sheet, CW, CH, pCrown, PAD, y0);
  drawBadge(sheet, CW, CH, PAD + 10, y0 + 10, '5. ART DECO CROWN FROM 58M DECK: 3-TIER OCTAGON, 8 FINS, SUNBURST & RIBBED SPIRE');

  const pStone = renderPanel(s2000, {
    width: W2, height: 540,
    eye: [-11.5, 13.8, CZ_M + 31.0],
    target: [1.5, 20.0, CZ_M + 13.5],
    fovDeg: 54,
    sunDir: [-0.44, 0.72, 0.54], skyTop: [50, 102, 176], skyBottom: [198, 224, 246],
  });
  blit(sheet, CW, CH, pStone, PAD + W2 + PAD, y0);
  drawBadge(sheet, CW, CH, PAD + W2 + PAD + 10, y0 + 10, '6. STONE CLOSE-UP: DARK GRANITE, CREAM LIMESTONE, ASHLAR, COFFERED SOFFITS & VOUSSOIRS');
  console.log('Rendered Band 5 (Crown Close-up + Stone/Arch Coffering Close-up)');
}

// ==============================================================================
// BAND 6 (ITEMS 7 & 8): OPEN 5M BRONZE DOORS | WEST HALL DEDICATION WALL | EAST HALL (MODEL + MAP)
// ==============================================================================
{
  const y0 = drawBanner('b6', '7 & 8. GROUND LEVEL INTERIORS (832PX PANELS):  OPEN 5M BRONZE DOORS (6 RELIEF PANELS)  |  WEST HALL DEDICATION WALL  |  EAST HALL (SCALE MODEL + STATE MAP)', [198, 222, 252]);
  // Item 7: Looking straight at the West 5m open bronze door leaf (x=-7.06, z=-45.24..-43.07, y=6.52..11.48)
  const pDoor = renderPanel(s2000, {
    width: W3, height: 500,
    eye: [-1.4, 9.0, CZ_M + 15.85],
    target: [-7.1, 9.0, CZ_M + 15.85],
    fovDeg: 56,
    interiorBright: true,
    sunDir: [0.80, 0.45, 0.35], skyTop: [48, 98, 172], skyBottom: [194, 220, 244],
  });
  blit(sheet, CW, CH, pDoor, PAD, y0);
  drawBadge(sheet, CW, CH, PAD + 8, y0 + 8, '7. OPEN 5M BRONZE DOOR: TRAIN, PLOUGH, SHIP, FACTORY, SCHOOL, BRIDGE', [252, 232, 162], 1);

  // Item 8A: Standing inside West Memorial Hall looking straight at the Dedication Wall + low bronze rail!
  const pWest = renderPanel(s2000, {
    width: W3, height: 500,
    eye: [-10.5, 9.05, CZ_M + 12.42],
    target: [-16.7, 9.05, CZ_M + 12.42],
    fovDeg: 58,
    interiorBright: true,
    sunDir: [0.85, 0.42, 0.28], skyTop: [36, 40, 52], skyBottom: [48, 52, 64],
  });
  blit(sheet, CW, CH, pWest, PAD + W3 + PAD, y0);
  drawBadge(sheet, CW, CH, PAD + W3 + PAD + 8, y0 + 8, '8A. WEST HALL DEDICATION WALL: CARVED LINE, WREATHS, NAME ROWS & RAIL', [252, 232, 162], 1);

  // Item 8B: Inside East Memorial Hall showing 1:35 Scale Model on Plinth + State Wall Map w/ Meridian Line!
  const pEast = renderPanel(s2000, {
    width: W3, height: 500,
    eye: [9.0, 9.4, CZ_M + 16.0],
    target: [12.5, 8.9, CZ_M + 10.6],
    fovDeg: 66,
    interiorBright: true,
    sunDir: [-0.45, 0.65, 0.60], skyTop: [36, 40, 52], skyBottom: [48, 52, 64],
  });
  blit(sheet, CW, CH, pEast, PAD + 2 * (W3 + PAD), y0);
  drawBadge(sheet, CW, CH, PAD + 2 * (W3 + PAD) + 8, y0 + 8, '8B. EAST HALL: 1:35 SCALE MONUMENT MODEL ON PLINTH + STATE WALL MAP', [252, 232, 162], 1);
  console.log('Rendered Band 6 (Bronze Doors + West Hall + East Hall)');
}

// ==============================================================================
// BAND 7 (ITEM 9): ALL 4 ERAS FROM EXACT SAME CAMERA (DAY) + 1976 "CLOSED FOR REPAIRS" & SE CORNER
// ==============================================================================
{
  const y0 = drawBanner('b7', '9. ALL 4 ERAS FROM EXACT SAME CAMERA (DAY):  1926  |  1941  |  1976  |  2000+  +  BOTTOM: 1976 "CLOSED FOR REPAIRS" SAWHORSE & TARPED SE SCAFFOLDING', [250, 224, 152]);
  const eraCam = {
    eye: [-48.0, 24.0, CZ_M + 82.0],
    target: [0.0, 40.0, CZ_M + 8.0],
    fovDeg: 62,
    sunDir: [-0.44, 0.74, 0.50],
    skyTop: [50, 102, 176], skyBottom: [198, 224, 246],
  };
  const eras = [
    { sc: s1926, badge: '1926: ORANGE COPPER, BUNTING, DEDICATION STAND, YOUNG TREES' },
    { sc: s1941, badge: '1941: STREAKY COPPER, SANDBAGS, TAPED WINDOWS, WAR PLAQUE' },
    { sc: s1976, badge: '1976: BLACK-STREAKED GREEN, DRIPS, TARPED SCAFFOLD, WEEDS' },
    { sc: s2000, badge: '2000+: CLEAN STONE, BRIGHT GREEN, VISITOR CTR, LED FLOODS' },
  ];
  for (let i = 0; i < 4; i++) {
    const p = renderPanel(eras[i].sc, { ...eraCam, width: W4, height: 420 });
    const xPos = PAD + i * (W4 + PAD);
    blit(sheet, CW, CH, p, xPos, y0);
    drawBadge(sheet, CW, CH, xPos + 6, y0 + 6, eras[i].badge, [252, 232, 162], 1);
  }

  // Bottom strip: 1976 North Stairs "CLOSED FOR REPAIRS" Sawhorse + 1976 SE Tarped Scaffolding & Chain-Link Fence!
  const yBot = y0 + 420 + PAD;
  const pClosed = renderPanel(s1976, {
    width: W2, height: 440,
    eye: [2.2, 2.3, CZ_M - 35.8],
    target: [0.0, 1.9, CZ_M - 29.0],
    fovDeg: 52,
    sunDir: [0.35, 0.72, -0.60], skyTop: [62, 102, 158], skyBottom: [194, 212, 232],
  });
  blit(sheet, CW, CH, pClosed, PAD, yBot);
  drawBadge(sheet, CW, CH, PAD + 10, yBot + 10, '1976 CLOSE-UP A: HAND-PAINTED "CLOSED FOR REPAIRS" SAWHORSE AT NORTH STAIRS + WEEDS');

  const pScaf = renderPanel(s1976, {
    width: W2, height: 440,
    eye: [43.0, 9.5, CZ_M + 44.0],
    target: [16.0, 21.5, CZ_M + 16.0],
    fovDeg: 66,
    sunDir: [0.46, 0.74, 0.48], skyTop: [62, 102, 158], skyBottom: [194, 212, 232],
  });
  blit(sheet, CW, CH, pScaf, PAD + W2 + PAD, yBot);
  drawBadge(sheet, CW, CH, PAD + W2 + PAD + 10, yBot + 10, '1976 CLOSE-UP B: SE CORNER CHAIN-LINK FENCE, TARPED SCAFFOLDING & HEAVY CORNICE DRIPS');
  console.log('Rendered Band 7 (4 Eras Same Camera + 1976 Close-ups)');
}

// ==============================================================================
// BAND 8 (ITEM 10): NEAR / MID / FAR SIDE-BY-SIDE + SOLID BLACK SILHOUETTES (128 / 64 / 32 PX)
// ==============================================================================
{
  const y0 = drawBanner('b8', '10. LEVEL-OF-DETAIL & SILHOUETTE CHECK:  NEAR (29.0K) / MID (5,042) / FAR (330) AT IDENTICAL CAMERA  |  BLACK SILHOUETTES AT 128PX / 64PX / 32PX', [182, 236, 210]);
  // Left half (1256x480): Near, Mid, Far side-by-side at EXACT same camera & size!
  const lodW = Math.floor(W2 / 3);
  const lodCam = {
    eye: [-94.0, 46.0, CZ_M + 124.0],
    target: [0.0, 50.0, CZ_M],
    fovDeg: 56,
    sunDir: [-0.44, 0.74, 0.50], skyTop: [54, 108, 178], skyBottom: [200, 224, 246],
  };
  const lodList = [
    { sc: s2000, label: 'NEAR: 29,017 TRIS' },
    { sc: sMid,  label: 'MID LOD: 5,042 TRIS' },
    { sc: sFar,  label: 'FAR LOD: 330 TRIS' },
  ];
  for (let i = 0; i < 3; i++) {
    const lx = PAD + i * lodW;
    const p = renderPanel(lodList[i].sc, { ...lodCam, width: lodW, height: 480 });
    blit(sheet, CW, CH, p, lx, y0);
    if (i > 0) fillRect(sheet, CW, CH, lx - 1, y0, 2, 480, [248, 224, 144]);
    drawBadge(sheet, CW, CH, lx + 8, y0 + 8, lodList[i].label, [252, 232, 162], 1);
  }

  // Right half (1256x480): Solid Black Silhouette Test — Near, Mid, Far at 128px, 64px, 32px tall!
  const sxBase = PAD + W2 + PAD;
  fillRect(sheet, CW, CH, sxBase, y0, W2, 480, [224, 234, 246]);
  const silCam = {
    eye: [-110.0, 50.0, CZ_M + 110.0],
    target: [0.0, 53.0, CZ_M],
    orthoScale: 58.0,
    silhouetteBlack: true,
    clipMinY: 1.8,
    skyTop: [224, 234, 246], skyBottom: [224, 234, 246],
  };
  const frontSil = { ...silCam, eye: [0.0, 53.0, CZ_M + 140.0], target: [0.0, 53.0, CZ_M] };
  const cols = [
    { sc: s2000, title: 'NEAR MODEL (29.0K)', ox: 20 },
    { sc: sMid,  title: 'MID LOD (5,042)',    ox: 438 },
    { sc: sFar,  title: 'FAR LOD (330)',      ox: 856 },
  ];
  const sizes = [
    { h: 128, w: 128, label: '128PX', dx: 0 },
    { h: 64,  w: 64,  label: '64PX',  dx: 146 },
    { h: 32,  w: 32,  label: '32PX',  dx: 226 },
  ];
  for (let ci = 0; ci < 3; ci++) {
    const col = cols[ci];
    const bx = sxBase + col.ox;
    if (ci > 0) fillRect(sheet, CW, CH, bx - 16, y0 + 44, 2, 420, [156, 172, 192]);
    drawText(sheet, CW, CH, bx, y0 + 48, col.title, [20, 26, 40], 2);
    for (const sz of sizes) {
      const pSil = renderPanel(col.sc, { ...silCam, width: sz.w, height: sz.h });
      blit(sheet, CW, CH, pSil, bx + sz.dx, y0 + 230 - sz.h);
      drawText(sheet, CW, CH, bx + sz.dx + 4, y0 + 240, sz.label, [32, 40, 58], 1);
    }
    // Front (+Z) orthographic silhouette at 128px & 64px below!
    drawText(sheet, CW, CH, bx, y0 + 276, 'FRONT (+Z) 128PX / 64PX:', [36, 44, 62], 1);
    blit(sheet, CW, CH, renderPanel(col.sc, { ...frontSil, width: 128, height: 128 }), bx + 12, y0 + 436 - 128);
    blit(sheet, CW, CH, renderPanel(col.sc, { ...frontSil, width: 64, height: 64 }), bx + 160, y0 + 436 - 64);
  }
  drawBadge(sheet, CW, CH, sxBase + 10, y0 + 8, 'SOLID BLACK SILHOUETTES: NEAR VS MID VS FAR AT 128PX / 64PX / 32PX');
  console.log('Rendered Band 8 (Near/Mid/Far LOD Comparison + Black Silhouettes)');
}

const outPath = path.join(__dirname, 'great-meridian-review.png');
fs.writeFileSync(outPath, encodePng(CW, CH, sheet));
console.log(`Wrote ${outPath} (${CW}x${CH})`);
