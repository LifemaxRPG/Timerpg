/**
 * Pure Node.js software 3D rasterizer for prototypes/mirage-nightclub/mirage-nightclub.glb.
 * Parses the binary glTF 2.0 (.glb), decodes all embedded PNG textures, and renders
 * 7 high-resolution (1280x960) inspection views (day front, blue hour, rear, above,
 * street person-height, interior overview, and dance floor & DJ stage close-up) with
 * perspective-correct NEAREST+REPEAT UV sampling, near-plane polygon clipping, directional
 * sun + hemisphere sky lighting, emissive neon glow, and ground shadow projection.
 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const GLB_PATH = path.join(__dirname, 'mirage-nightclub.glb');
const RENDERS_DIR = path.join(__dirname, 'renders');
fs.mkdirSync(RENDERS_DIR, { recursive: true });

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
      const texIdx = mat.pbrMetallicRoughness.baseColorTexture.index;
      const img = images[gltf.textures[texIdx].source];
      const isBlend = mat.alphaMode === 'BLEND';
      const isEmissive = Boolean(mat.emissiveTexture);

      for (let t = 0; t < idx.length; t += 3) {
        const i0 = idx[t], i1 = idx[t + 1], i2 = idx[t + 2];
        drawTriangles.push({
          v0: pos[i0], v1: pos[i1], v2: pos[i2],
          n: nrm[i0],
          uv0: uv[i0], uv1: uv[i1], uv2: uv[i2],
          img,
          isBlend,
          isEmissive,
          doubleSided: Boolean(mat.doubleSided),
          nodeName: node.name,
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
    if (aIn && bIn) {
      out.push(b);
    } else if (aIn && !bIn) {
      const t = (nearZ - a.cz) / (b.cz - a.cz);
      out.push({
        cx: a.cx + t * (b.cx - a.cx),
        cy: a.cy + t * (b.cy - a.cy),
        cz: nearZ,
        u: a.u + t * (b.u - a.u),
        v: a.v + t * (b.v - a.v),
      });
    } else if (!aIn && bIn) {
      const t = (nearZ - a.cz) / (b.cz - a.cz);
      out.push({
        cx: a.cx + t * (b.cx - a.cx),
        cy: a.cy + t * (b.cy - a.cy),
        cz: nearZ,
        u: a.u + t * (b.u - a.u),
        v: a.v + t * (b.v - a.v),
      });
      out.push(b);
    }
  }
  return out;
}

function renderView(scene, opts) {
  const W = opts.width || 1280;
  const H = opts.height || 960;
  const rgba = new Uint8Array(W * H * 4);
  const zbuf = new Float32Array(W * H);
  zbuf.fill(1e9);

  // Fill gradient sky / ceiling backdrop
  const topSky = opts.skyTop || [28, 38, 68];
  const botSky = opts.skyBottom || [84, 114, 165];
  for (let y = 0; y < H; y++) {
    const t = y / (H - 1);
    const r = Math.round(topSky[0] * (1 - t) + botSky[0] * t);
    const g = Math.round(topSky[1] * (1 - t) + botSky[1] * t);
    const b = Math.round(topSky[2] * (1 - t) + botSky[2] * t);
    for (let x = 0; x < W; x++) {
      const off = (y * W + x) * 4;
      rgba[off] = r;
      rgba[off + 1] = g;
      rgba[off + 2] = b;
      rgba[off + 3] = 255;
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
  const sunDir = vNorm(opts.sunDir || [0.48, 0.78, 0.40]);
  const nightMode = Boolean(opts.nightMode);

  function toCam(p, uv) {
    const d = vSub(p, eye);
    return {
      cx: vDot(d, right),
      cy: vDot(d, up),
      cz: vDot(d, fwd),
      u: uv[0],
      v: uv[1],
    };
  }

  function projectVert(c) {
    if (opts.orthoScale) {
      const s = opts.orthoScale;
      return {
        sx: W * 0.5 + c.cx * s,
        sy: H * 0.5 - c.cy * s,
        cz: c.cz,
        invZ: 1.0,
        uOverZ: c.u,
        vOverZ: c.v,
      };
    }
    const invZ = 1.0 / c.cz;
    return {
      sx: W * 0.5 + c.cx * focal * invZ,
      sy: H * 0.5 - c.cy * focal * invZ,
      cz: c.cz,
      invZ,
      uOverZ: c.u * invZ,
      vOverZ: c.v * invZ,
    };
  }

  const opaqueTris = scene.drawTriangles.filter((t) => !t.isBlend);
  const blendTris = scene.drawTriangles.filter((t) => t.isBlend);

  function rasterTriangle(p0, p1, p2, tri, isTransparentPass) {
    const area = (p1.sx - p0.sx) * (p2.sy - p0.sy) - (p1.sy - p0.sy) * (p2.sx - p0.sx);
    if (Math.abs(area) < 1e-4) return;
    if (!tri.doubleSided && !opts.interiorMode && area > 0) return;

    const minX = Math.max(0, Math.floor(Math.min(p0.sx, p1.sx, p2.sx)));
    const maxX = Math.min(W - 1, Math.ceil(Math.max(p0.sx, p1.sx, p2.sx)));
    const minY = Math.max(0, Math.floor(Math.min(p0.sy, p1.sy, p2.sy)));
    const maxY = Math.min(H - 1, Math.ceil(Math.max(p0.sy, p1.sy, p2.sy)));
    if (minX > maxX || minY > maxY) return;

    const invArea = 1.0 / area;
    const n = tri.n;
    const ndotl = Math.max(0, vDot(n, sunDir));
    const hemi = 0.5 + 0.5 * n[1];
    let shade = 1.0;
    if (!tri.isEmissive) {
      if (nightMode) {
        shade = 0.24 + 0.22 * hemi + 0.26 * ndotl;
      } else if (opts.interiorMode) {
        shade = 0.48 + 0.28 * Math.abs(n[1]) + 0.24 * Math.abs(n[2]);
      } else {
        shade = 0.44 + 0.22 * hemi + 0.44 * ndotl;
      }
    } else {
      shade = nightMode || opts.interiorMode ? 1.22 : 1.08;
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

        const off = idx * 4;
        let r = Math.min(255, Math.round(tex[0] * shade));
        let g = Math.min(255, Math.round(tex[1] * shade));
        let b = Math.min(255, Math.round(tex[2] * shade));

        if (!isTransparentPass || tex[3] >= 245) {
          zbuf[idx] = cz;
          rgba[off] = r;
          rgba[off + 1] = g;
          rgba[off + 2] = b;
          rgba[off + 3] = 255;
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
  for (const pass of [
    { list: opaqueTris, transparent: false },
    { list: blendTris, transparent: true },
  ]) {
    for (const tri of pass.list) {
      if (opts.filterTri && !opts.filterTri(tri)) continue;
      const rawPoly = [
        toCam(tri.v0, tri.uv0),
        toCam(tri.v1, tri.uv1),
        toCam(tri.v2, tri.uv2),
      ];
      const clipped = opts.orthoScale ? rawPoly : clipNear(rawPoly, nearZ);
      if (clipped.length < 3) continue;
      const proj = clipped.map(projectVert);
      for (let i = 1; i < proj.length - 1; i++) {
        rasterTriangle(proj[0], proj[i], proj[i + 1], tri, pass.transparent);
      }
    }
  }

  const pngBuf = encodePng(W, H, rgba);
  const outPath = path.join(RENDERS_DIR, opts.filename);
  fs.writeFileSync(outPath, pngBuf);
  console.log(`Rendered ${opts.filename} (${W}x${H}, ${pngBuf.length} bytes)`);
}

const scene = loadGlb(GLB_PATH);

const views = [
  {
    filename: '01-day-front-clean.png',
    eye: [19.5, 11.2, 18.8],
    target: [0.5, 3.8, -5.0],
    fovDeg: 42,
    sunDir: [0.52, 0.76, 0.42],
    skyTop: [46, 86, 152],
    skyBottom: [152, 192, 232],
  },
  {
    filename: '02-blue-hour-clean.png',
    eye: [-18.5, 8.8, 17.8],
    target: [1.2, 4.2, -4.5],
    fovDeg: 42,
    nightMode: true,
    sunDir: [-0.35, 0.65, 0.55],
    skyTop: [12, 10, 32],
    skyBottom: [48, 22, 74],
  },
  {
    filename: '03-rear-clean.png',
    eye: [-19.5, 11.5, -36.5],
    target: [0.0, 3.4, -14.0],
    fovDeg: 42,
    sunDir: [-0.45, 0.78, -0.44],
    skyTop: [46, 86, 152],
    skyBottom: [152, 192, 232],
  },
  {
    filename: '04-above-clean.png',
    eye: [0.0, 42.0, -10.8],
    target: [0.0, 0.0, -10.8],
    up: [0, 0, -1],
    orthoScale: 27.5,
    sunDir: [0.38, 0.85, 0.35],
    skyTop: [36, 48, 72],
    skyBottom: [36, 48, 72],
  },
  {
    filename: '05-person-height-clean.png',
    eye: [-4.2, 1.75, 12.2],
    target: [2.2, 3.8, -2.0],
    fovDeg: 50,
    nightMode: true,
    sunDir: [0.42, 0.72, 0.52],
    skyTop: [16, 14, 42],
    skyBottom: [64, 28, 92],
  },
  {
    filename: '06-interior-clean.png',
    eye: [4.8, 2.6, -2.5],
    target: [-1.2, 2.3, -13.5],
    fovDeg: 64,
    interiorMode: true,
    nightMode: true,
    sunDir: [0.2, 0.9, 0.3],
    skyTop: [14, 10, 28],
    skyBottom: [28, 16, 48],
  },
  {
    filename: '07-dance-floor-and-stage-clean.png',
    eye: [-4.6, 2.4, -6.2],
    target: [0.0, 2.2, -15.5],
    fovDeg: 58,
    interiorMode: true,
    nightMode: true,
    sunDir: [0.15, 0.88, 0.45],
    skyTop: [14, 10, 28],
    skyBottom: [28, 16, 48],
  },
];

for (const v of views) {
  renderView(scene, v);
}
