import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { PNG } from 'pngjs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const GLB_PATH = path.join(__dirname, 'starlight-drive-in.glb');
const RENDERS_DIR = path.join(__dirname, 'renders');
fs.mkdirSync(RENDERS_DIR, { recursive: true });

// 1. Parse binary glTF 2.0 (.glb)
const buf = fs.readFileSync(GLB_PATH);
const magic = buf.readUInt32LE(0);
if (magic !== 0x46546c67) throw new Error('Not a valid GLB');
const jlen = buf.readUInt32LE(12);
const gltf = JSON.parse(buf.subarray(20, 20 + jlen).toString('utf8'));
const binStart = 28 + jlen;
const bin = buf.subarray(binStart);

function readAccessor(idx) {
  const acc = gltf.accessors[idx];
  const bv = gltf.bufferViews[acc.bufferView];
  const offset = (bv.byteOffset || 0) + (acc.byteOffset || 0);
  const count = acc.count;
  const ncomp = { SCALAR: 1, VEC2: 2, VEC3: 3, VEC4: 4 }[acc.type];
  const out = new Float32Array(count * ncomp);
  for (let i = 0; i < count * ncomp; i++) {
    if (acc.componentType === 5126) {
      out[i] = bin.readFloatLE(offset + i * 4);
    } else if (acc.componentType === 5125) {
      out[i] = bin.readUInt32LE(offset + i * 4);
    } else if (acc.componentType === 5123) {
      out[i] = bin.readUInt16LE(offset + i * 2);
    }
  }
  return out;
}

// 2. Decode embedded PNG textures
const images = gltf.images.map((img) => {
  const bv = gltf.bufferViews[img.bufferView];
  const slice = bin.subarray(bv.byteOffset || 0, (bv.byteOffset || 0) + bv.byteLength);
  return PNG.sync.read(slice);
});

const materials = gltf.materials.map((m) => {
  const ti = m.pbrMetallicRoughness.baseColorTexture.index;
  const si = gltf.textures[ti].source;
  return {
    name: m.name,
    image: images[si],
    alphaMode: m.alphaMode || 'OPAQUE',
    emissive: Boolean(m.emissiveTexture),
  };
});

// 3. Collect all visible triangles in world space
const opaqueTris = [];
const blendTris = [];
const emissiveLights = [];

for (const node of gltf.nodes) {
  if (node.mesh === undefined) continue;
  if (node.name === 'collision' || (node.extras && node.extras.render === false)) continue;
  const tx = node.translation ? node.translation[0] : 0;
  const ty = node.translation ? node.translation[1] : 0;
  const tz = node.translation ? node.translation[2] : 0;
  const mesh = gltf.meshes[node.mesh];

  for (const prim of mesh.primitives) {
    const pos = readAccessor(prim.attributes.POSITION);
    const nrm = readAccessor(prim.attributes.NORMAL);
    const uv = readAccessor(prim.attributes.TEXCOORD_0);
    const idx = readAccessor(prim.indices);
    const mat = materials[prim.material];

    for (let i = 0; i < idx.length; i += 3) {
      const i0 = idx[i], i1 = idx[i + 1], i2 = idx[i + 2];
      const v0 = [pos[i0 * 3] + tx, pos[i0 * 3 + 1] + ty, pos[i0 * 3 + 2] + tz];
      const v1 = [pos[i1 * 3] + tx, pos[i1 * 3 + 1] + ty, pos[i1 * 3 + 2] + tz];
      const v2 = [pos[i2 * 3] + tx, pos[i2 * 3 + 1] + ty, pos[i2 * 3 + 2] + tz];
      const n = [nrm[i0 * 3], nrm[i0 * 3 + 1], nrm[i0 * 3 + 2]];
      const uv0 = [uv[i0 * 2], uv[i0 * 2 + 1]];
      const uv1 = [uv[i1 * 2], uv[i1 * 2 + 1]];
      const uv2 = [uv[i2 * 2], uv[i2 * 2 + 1]];

      const tri = { v0, v1, v2, n, uv0, uv1, uv2, mat };
      if (mat.alphaMode === 'BLEND') {
        blendTris.push(tri);
      } else {
        opaqueTris.push(tri);
      }

      if (mat.emissive && i % 6 === 0) {
        const cx = (v0[0] + v1[0] + v2[0]) / 3;
        const cy = (v0[1] + v1[1] + v2[1]) / 3;
        const cz = (v0[2] + v1[2] + v2[2]) / 3;
        const isDownLamp = n[1] < -0.8;
        emissiveLights.push({
          pos: [cx + n[0] * 0.5, cy + n[1] * 0.5, cz + n[2] * 0.5],
          color: isDownLamp ? [1.0, 0.85, 0.55] : [0.55, 0.9, 0.88],
          radius: isDownLamp ? 7.5 : 11.0,
          intensity: isDownLamp ? 0.85 : 0.45,
        });
      }
    }
  }
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

function sampleNearest(img, u, v) {
  let fu = u - Math.floor(u);
  let fv = v - Math.floor(v);
  if (fu < 0) fu += 1;
  if (fv < 0) fv += 1;
  const x = Math.min(img.width - 1, Math.floor(fu * img.width));
  const y = Math.min(img.height - 1, Math.floor(fv * img.height));
  const idx = (y * img.width + x) * 4;
  return [
    img.data[idx],
    img.data[idx + 1],
    img.data[idx + 2],
    img.data[idx + 3],
  ];
}

function renderShot({ name, eye, target, fovDeg = 38, orthoScale = null, night = false }) {
  const W = 1280, H = 960;
  const aspect = W / H;
  const zbuf = new Float32Array(W * H).fill(1e9);
  const fb = new Uint8Array(W * H * 4);

  // Sky background gradient
  const topSky = night ? [18, 28, 46] : [138, 168, 188];
  const botSky = night ? [38, 52, 74] : [192, 206, 212];
  for (let y = 0; y < H; y++) {
    const t = y / H;
    const r = Math.round(topSky[0] * (1 - t) + botSky[0] * t);
    const g = Math.round(topSky[1] * (1 - t) + botSky[1] * t);
    const b = Math.round(topSky[2] * (1 - t) + botSky[2] * t);
    for (let x = 0; x < W; x++) {
      const p = (y * W + x) * 4;
      fb[p] = r; fb[p + 1] = g; fb[p + 2] = b; fb[p + 3] = 255;
    }
  }

  // Camera basis
  const fwd = vNorm(vSub(target, eye));
  const right = vNorm(vCross(fwd, [0, 1, 0]));
  const up = vNorm(vCross(right, fwd));
  const tanHalfFov = Math.tan((fovDeg * Math.PI) / 360);

  function toCam(v, uv) {
    const rel = vSub(v, eye);
    return {
      cx: vDot(rel, right),
      cy: vDot(rel, up),
      cz: vDot(rel, fwd),
      wx: v[0], wy: v[1], wz: v[2],
      u: uv[0], v: uv[1],
    };
  }

  function clipNear(verts, nearZ) {
    if (orthoScale !== null) return verts;
    const out = [];
    for (let i = 0; i < verts.length; i++) {
      const a = verts[i];
      const b = verts[(i + 1) % verts.length];
      const aIn = a.cz >= nearZ;
      const bIn = b.cz >= nearZ;
      if (aIn) out.push(a);
      if (aIn !== bIn) {
        const t = (nearZ - a.cz) / (b.cz - a.cz);
        out.push({
          cx: a.cx + (b.cx - a.cx) * t,
          cy: a.cy + (b.cy - a.cy) * t,
          cz: nearZ,
          wx: a.wx + (b.wx - a.wx) * t,
          wy: a.wy + (b.wy - a.wy) * t,
          wz: a.wz + (b.wz - a.wz) * t,
          u: a.u + (b.u - a.u) * t,
          v: a.v + (b.v - a.v) * t,
        });
      }
    }
    return out;
  }

  function projectCamVert(c) {
    let ndcX, ndcY;
    if (orthoScale !== null) {
      ndcX = c.cx / (orthoScale * 0.5 * aspect);
      ndcY = c.cy / (orthoScale * 0.5);
    } else {
      ndcX = c.cx / (c.cz * tanHalfFov * aspect);
      ndcY = c.cy / (c.cz * tanHalfFov);
    }
    return {
      sx: (ndcX * 0.5 + 0.5) * W,
      sy: (0.5 - ndcY * 0.5) * H,
      cz: orthoScale !== null ? c.cz + 200 : c.cz,
      invZ: orthoScale !== null ? 1.0 : 1.0 / c.cz,
      wx: c.wx, wy: c.wy, wz: c.wz,
      u: c.u, v: c.v,
    };
  }

  const sunDir = vNorm(night ? [-0.38, 0.72, 0.58] : [-0.48, 0.76, 0.44]);

  function drawTriangleList(tris, isBlend) {
    let list = tris;
    if (isBlend) {
      list = [...tris].sort((a, b) => {
        const za = vDot(vSub(a.v0, eye), fwd);
        const zb = vDot(vSub(b.v0, eye), fwd);
        return zb - za;
      });
    }

    for (const tri of list) {
      const clipped = clipNear([
        toCam(tri.v0, tri.uv0),
        toCam(tri.v1, tri.uv1),
        toCam(tri.v2, tri.uv2),
      ], 0.12);
      if (clipped.length < 3) continue;
      const proj = clipped.map(projectCamVert);

      for (let ti = 1; ti < proj.length - 1; ti++) {
        const p0 = proj[0], p1 = proj[ti], p2 = proj[ti + 1];

        const minX = Math.max(0, Math.floor(Math.min(p0.sx, p1.sx, p2.sx)));
        const maxX = Math.min(W - 1, Math.ceil(Math.max(p0.sx, p1.sx, p2.sx)));
        const minY = Math.max(0, Math.floor(Math.min(p0.sy, p1.sy, p2.sy)));
        const maxY = Math.min(H - 1, Math.ceil(Math.max(p0.sy, p1.sy, p2.sy)));
        if (minX > maxX || minY > maxY) continue;

        const area = (p1.sx - p0.sx) * (p2.sy - p0.sy) - (p1.sy - p0.sy) * (p2.sx - p0.sx);
        if (Math.abs(area) < 1e-4) continue;
        if (!isBlend && area > 0) continue;
        const invArea = 1.0 / area;

        const ndotl = Math.max(0, vDot(tri.n, sunDir));
        const hemi = 0.5 + 0.5 * tri.n[1];
        const baseLight = night
          ? 0.28 + 0.14 * hemi + 0.18 * ndotl
          : 0.52 + 0.22 * hemi + 0.44 * ndotl;

        for (let py = minY; py <= maxY; py++) {
          const sy = py + 0.5;
          for (let px = minX; px <= maxX; px++) {
            const sx = px + 0.5;
            const w0 = ((p1.sx - sx) * (p2.sy - sy) - (p1.sy - sy) * (p2.sx - sx)) * invArea;
            const w1 = ((p2.sx - sx) * (p0.sy - sy) - (p2.sy - sy) * (p0.sx - sx)) * invArea;
            const w2 = 1.0 - w0 - w1;
            if (w0 < -1e-4 || w1 < -1e-4 || w2 < -1e-4) continue;

            const invZ = w0 * p0.invZ + w1 * p1.invZ + w2 * p2.invZ;
            const z = orthoScale !== null ? (w0 * p0.cz + w1 * p1.cz + w2 * p2.cz) : 1.0 / invZ;
            const pidx = py * W + px;
            if (z > zbuf[pidx]) continue;

            const u = (w0 * p0.u * p0.invZ + w1 * p1.u * p1.invZ + w2 * p2.u * p2.invZ) / invZ;
            const v = (w0 * p0.v * p0.invZ + w1 * p1.v * p1.invZ + w2 * p2.v * p2.invZ) / invZ;

          const rgba = sampleNearest(tri.mat.image, u, v);
          if (rgba[3] < 10) continue;

          let lr = baseLight, lg = baseLight, lb = baseLight * (night ? 1.18 : 1.0);

          if (tri.mat.emissive) {
            const boost = night ? 1.25 : 0.98;
            lr = boost; lg = boost; lb = boost;
          } else if (night) {
            const wx = (w0 * p0.wx * p0.invZ + w1 * p1.wx * p1.invZ + w2 * p2.wx * p2.invZ) / invZ;
            const wy = (w0 * p0.wy * p0.invZ + w1 * p1.wy * p1.invZ + w2 * p2.wy * p2.invZ) / invZ;
            const wz = (w0 * p0.wz * p0.invZ + w1 * p1.wz * p1.invZ + w2 * p2.wz * p2.invZ) / invZ;
            for (let li = 0; li < emissiveLights.length; li++) {
              const L = emissiveLights[li];
              const dx = L.pos[0] - wx, dy = L.pos[1] - wy, dz = L.pos[2] - wz;
              const d2 = dx * dx + dy * dy + dz * dz;
              if (d2 < L.radius * L.radius) {
                const att = Math.pow(1 - Math.sqrt(d2) / L.radius, 2) * L.intensity;
                lr += L.color[0] * att;
                lg += L.color[1] * att;
                lb += L.color[2] * att;
              }
            }
          }

          const cr = Math.min(255, Math.round(rgba[0] * lr));
          const cg = Math.min(255, Math.round(rgba[1] * lg));
          const cb = Math.min(255, Math.round(rgba[2] * lb));
          const fbIdx = pidx * 4;

          if (!isBlend) {
            zbuf[pidx] = z;
            fb[fbIdx] = cr;
            fb[fbIdx + 1] = cg;
            fb[fbIdx + 2] = cb;
            fb[fbIdx + 3] = 255;
          } else {
            const alpha = (rgba[3] / 255) * (night ? 0.65 : 0.72);
            fb[fbIdx] = Math.round(cr * alpha + fb[fbIdx] * (1 - alpha));
            fb[fbIdx + 1] = Math.round(cg * alpha + fb[fbIdx + 1] * (1 - alpha));
            fb[fbIdx + 2] = Math.round(cb * alpha + fb[fbIdx + 2] * (1 - alpha));
            }
          }
        }
      }
    }
  }

  drawTriangleList(opaqueTris, false);
  drawTriangleList(blendTris, true);

  const outPng = new PNG({ width: W, height: H });
  outPng.data = Buffer.from(fb.buffer);
  const outPath = path.join(RENDERS_DIR, `${name}.png`);
  fs.writeFileSync(outPath, PNG.sync.write(outPng));
  console.log(`Rendered ${name}.png (${W}x${H})`);
}

const SHOTS = [
  {
    name: '01-day-front-clean',
    eye: [-26, 22, 24],
    target: [0, 3.2, -16],
    orthoScale: 38,
    night: false,
  },
  {
    name: '02-blue-hour-clean',
    eye: [-26, 19, 23],
    target: [0, 3.2, -16],
    orthoScale: 38,
    night: true,
  },
  {
    name: '03-rear-clean',
    eye: [28, 22, -56],
    target: [0, 4.5, -22],
    orthoScale: 38,
    night: false,
  },
  {
    name: '04-above-clean',
    eye: [-10, 56, 8],
    target: [0, 1.0, -19],
    orthoScale: 45,
    night: false,
  },
  {
    name: '05-person-height-clean',
    eye: [-12, 1.75, 13.5],
    target: [1.5, 3.4, -4.0],
    fovDeg: 45,
    orthoScale: null,
    night: false,
  },
  {
    name: '06-interior-clean',
    eye: [5.5, 2.25, -1.6],
    target: [-4.2, 1.35, -4.8],
    fovDeg: 54,
    orthoScale: null,
    night: true,
  },
  {
    name: '07-screen-and-marquee-clean',
    eye: [11.5, 3.2, -16.5],
    target: [-1.5, 6.2, -33.5],
    fovDeg: 46,
    orthoScale: null,
    night: true,
  },
];

for (const shot of SHOTS) {
  renderShot(shot);
}
