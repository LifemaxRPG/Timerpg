import {
  COAST,
  CREEKS,
  DISTRICTS,
  DUNES,
  MARSH,
  MOLES,
  PIER,
  RAIL,
  RIVER,
} from './site';
import {
  type DoveBeachEra,
  type DoveBeachPlan,
  type StandingBuilding,
  type StandingLandmark,
} from './ledger';
import type { XZ } from './lots';

// TOP-DOWN CARTOGRAPHIC SVG MAP RENDERER FOR DOVE BEACH (G023).
// Renders Dove Beach for any year in 1700..2050+: coastline, ocean, Long Strand dunes,
// Heron Marsh, Sallow River & tidal creeks, railway (1886+), harbour moles, Strand Pier (1909+),
// era-filtered curved roads (highlighting the 5-road dove loop) and Seagrove grid (1905+),
// every standing building lot & pad colored by construction era, and active landmark plots.

export interface MapSvgOptions {
  width?: number;
  height?: number;
  title?: string;
}

const WORLD_MIN_X = -4650;
const WORLD_MAX_X = 2850;
const WORLD_MIN_Z = -3450;
const WORLD_MAX_Z = 2950;

const DOVE_LOOP_IDS = new Set(['esplanade', 'fellside', 'crown', 'ridge', 'neckhill']);

const ERA_PALETTE: Record<DoveBeachEra, { fill: string; stroke: string; label: string }> = {
  hamlet: { fill: '#6b3e26', stroke: '#3f2213', label: '1700s Hamlet' },
  fort_harbour: { fill: '#8c4f32', stroke: '#542c1a', label: '1790–1850 Fort & Harbour' },
  mills_rail: { fill: '#b35c37', stroke: '#6e341c', label: '1851–1904 Mills & Rail' },
  resort: { fill: '#d48a3b', stroke: '#8a5319', label: '1905–1929 Resort & Seagrove' },
  interwar_war: { fill: '#c7a43a', stroke: '#7a6219', label: '1930–1947 Ridge & War' },
  car_boom: { fill: '#4e8f7e', stroke: '#29574b', label: '1948–1969 Car Boom' },
  decline: { fill: '#5b7b9e', stroke: '#334a63', label: '1970–1989 Uplands & Decline' },
  revival: { fill: '#4f629c', stroke: '#2c3961', label: '1990–2009 Revival' },
  modern: { fill: '#7a5a96', stroke: '#463059', label: '2010+ Modern' },
};

const ERA_ORDER: readonly DoveBeachEra[] = [
  'hamlet',
  'fort_harbour',
  'mills_rail',
  'resort',
  'interwar_war',
  'car_boom',
  'decline',
  'revival',
  'modern',
];

function esc(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function fmt(n: number): string {
  return n.toFixed(1);
}

function polyPoints(
  pts: readonly XZ[],
  toX: (x: number) => number,
  toY: (z: number) => number,
): string {
  return pts.map(([x, z]) => `${fmt(toX(x))},${fmt(toY(z))}`).join(' ');
}

function polylinePath(
  pts: readonly XZ[],
  toX: (x: number) => number,
  toY: (z: number) => number,
): string {
  if (pts.length === 0) return '';
  let d = `M ${fmt(toX(pts[0]![0]))} ${fmt(toY(pts[0]![1]))}`;
  for (let i = 1; i < pts.length; i++) {
    d += ` L ${fmt(toX(pts[i]![0]))} ${fmt(toY(pts[i]![1]))}`;
  }
  return d;
}

export function renderDoveBeachSvg(
  plan: DoveBeachPlan,
  year: number,
  options: MapSvgOptions = {},
): string {
  const width = options.width ?? 1600;
  const height = options.height ?? 1360;
  const padTop = 74;
  const padRight = 250;
  const padBottom = 42;
  const padLeft = 24;

  const mapW = width - padLeft - padRight;
  const mapH = height - padTop - padBottom;

  const worldW = WORLD_MAX_X - WORLD_MIN_X;
  const worldH = WORLD_MAX_Z - WORLD_MIN_Z;
  const scale = Math.min(mapW / worldW, mapH / worldH);
  const drawW = worldW * scale;
  const drawH = worldH * scale;
  const offX = padLeft + (mapW - drawW) * 0.5;
  const offY = padTop + (mapH - drawH) * 0.5;

  const toX = (x: number) => offX + (x - WORLD_MIN_X) * scale;
  const toY = (z: number) => offY + (z - WORLD_MIN_Z) * scale;

  const standing = plan.standingIn(year);

  const landPoly: XZ[] = [
    [-5400, 3200],
    ...COAST,
    [5400, 900],
    [5400, -4500],
    [-5400, -4500],
  ];

  const strandCoast = COAST.slice(0, 12);
  const beachPoly: XZ[] = [...strandCoast, ...[...DUNES].reverse()];

  const parts: string[] = [];
  parts.push(
    `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${width} ${height}" width="${width}" height="${height}">`,
  );
  parts.push(`<defs>
  <clipPath id="map-clip"><rect x="${fmt(offX)}" y="${fmt(offY)}" width="${fmt(drawW)}" height="${fmt(drawH)}" rx="4"/></clipPath>
  <pattern id="marsh-hatch" width="8" height="8" patternUnits="userSpaceOnUse">
    <path d="M 0 4 L 8 4 M 2 2 L 6 2" stroke="#6f9478" stroke-width="0.8" opacity="0.55"/>
  </pattern>
</defs>`);

  parts.push(`<rect x="0" y="0" width="${width}" height="${height}" fill="#f6f3ea"/>`);

  const eraMeta = ERA_PALETTE[standing.era];
  const titleText = options.title ?? `Dove Beach — ${year}`;
  parts.push(
    `<text x="${fmt(offX)}" y="34" font-family="Georgia, 'Times New Roman', serif" font-size="26" font-weight="700" fill="#1e242b">${esc(titleText)}</text>`,
  );
  parts.push(
    `<text x="${fmt(offX)}" y="56" font-family="system-ui, -apple-system, sans-serif" font-size="13" fill="#4a5562">${esc(eraMeta.label)} · ${standing.buildings.length.toLocaleString('en-US')} standing buildings · ${standing.landmarks.length} active landmarks · seed "${esc(plan.seed)}"</text>`,
  );

  parts.push(`<g clip-path="url(#map-clip)">`);
  parts.push(`<rect x="${fmt(offX)}" y="${fmt(offY)}" width="${fmt(drawW)}" height="${fmt(drawH)}" fill="#bfd8e6"/>`);
  parts.push(`<polygon points="${polyPoints(landPoly, toX, toY)}" fill="#ebe6d6" stroke="#7d98a6" stroke-width="1.5"/>`);
  parts.push(`<polygon points="${polyPoints(beachPoly, toX, toY)}" fill="#ead9ad" opacity="0.88"/>`);
  parts.push(`<path d="${polylinePath(DUNES, toX, toY)}" fill="none" stroke="#c9b078" stroke-width="2.2" stroke-dasharray="5 3"/>`);
  parts.push(`<polygon points="${polyPoints(MARSH, toX, toY)}" fill="#c5d9c3" stroke="#6c8f73" stroke-width="1.4"/>`);
  parts.push(`<polygon points="${polyPoints(MARSH, toX, toY)}" fill="url(#marsh-hatch)"/>`);

  for (const creek of CREEKS) {
    parts.push(`<path d="${polylinePath(creek, toX, toY)}" fill="none" stroke="#85b4c9" stroke-width="1.8" stroke-linecap="round"/>`);
  }
  parts.push(`<path d="${polylinePath(RIVER.map((p) => p.at), toX, toY)}" fill="none" stroke="#6fa4be" stroke-width="5.5" stroke-linecap="round" stroke-linejoin="round"/>`);

  if (year >= 1780) {
    for (const mole of MOLES) {
      parts.push(`<path d="${polylinePath(mole.line, toX, toY)}" fill="none" stroke="#6e6b64" stroke-width="3.2" stroke-linecap="round"/>`);
    }
  }
  if (year >= 1909) {
    parts.push(`<path d="${polylinePath(PIER, toX, toY)}" fill="none" stroke="#8c5a2b" stroke-width="3.4" stroke-linecap="round"/>`);
  }

  for (const d of DISTRICTS) {
    if (d.id === 'marsh') {
      const cx = MARSH.reduce((a, p) => a + p[0], 0) / MARSH.length;
      const cz = MARSH.reduce((a, p) => a + p[1], 0) / MARSH.length;
      parts.push(
        `<text x="${fmt(toX(cx))}" y="${fmt(toY(cz))}" text-anchor="middle" font-family="Georgia, serif" font-style="italic" font-size="11" fill="#3f5c45" opacity="0.9">Heron Marsh</text>`,
      );
      continue;
    }
    const cx = d.poly.reduce((a, p) => a + p[0], 0) / d.poly.length;
    const cz = d.poly.reduce((a, p) => a + p[1], 0) / d.poly.length;
    parts.push(
      `<text x="${fmt(toX(cx))}" y="${fmt(toY(cz))}" text-anchor="middle" font-family="system-ui, sans-serif" font-size="10" font-weight="600" letter-spacing="1.1" fill="#5e5a4f" opacity="0.45">${esc(d.name.toUpperCase())}</text>`,
    );
  }

  if (year >= 1886) {
    parts.push(`<path d="${polylinePath(RAIL, toX, toY)}" fill="none" stroke="#3c434d" stroke-width="2.1"/>`);
    parts.push(`<path d="${polylinePath(RAIL, toX, toY)}" fill="none" stroke="#ebe6d6" stroke-width="1.3" stroke-dasharray="4 5"/>`);
  }

  if (year >= 1905) {
    parts.push(`<g stroke="#c6bfb0" stroke-width="${fmt(Math.max(1.0, 8 * scale))}" stroke-linecap="round">`);
    for (const st of plan.layout.streets) {
      parts.push(`<line x1="${fmt(toX(st.a[0]))}" y1="${fmt(toY(st.a[1]))}" x2="${fmt(toX(st.b[0]))}" y2="${fmt(toY(st.b[1]))}"/>`);
    }
    parts.push(`</g>`);
  }

  for (const road of plan.layout.roads) {
    if (road.spec.built > year) continue;
    const pts: XZ[] = [];
    const stride = Math.max(1, Math.floor(road.a.samples.length / 180));
    for (let i = 0; i < road.a.samples.length; i += stride) {
      const s = road.a.samples[i]!;
      pts.push([s.x, s.z]);
    }
    const last = road.a.samples[road.a.samples.length - 1];
    if (last) pts.push([last.x, last.z]);

    const isDove = DOVE_LOOP_IDS.has(road.spec.id);
    const wPx = Math.max(isDove ? 2.6 : 1.7, road.spec.width * scale);
    const pathD = polylinePath(pts, toX, toY);
    if (isDove) {
      parts.push(`<path d="${pathD}" fill="none" stroke="#ffffff" stroke-width="${fmt(wPx + 1.5)}" stroke-linecap="round" stroke-linejoin="round" opacity="0.85"/>`);
      parts.push(`<path d="${pathD}" fill="none" stroke="#2c3e50" stroke-width="${fmt(wPx)}" stroke-linecap="round" stroke-linejoin="round"/>`);
    } else {
      parts.push(`<path d="${pathD}" fill="none" stroke="#857f73" stroke-width="${fmt(wPx)}" stroke-linecap="round" stroke-linejoin="round"/>`);
    }
  }

  const byEra = new Map<DoveBeachEra, StandingBuilding[]>();
  for (const b of standing.buildings) {
    const arr = byEra.get(b.era) ?? [];
    arr.push(b);
    byEra.set(b.era, arr);
  }
  for (const era of ERA_ORDER) {
    const list = byEra.get(era);
    if (!list || list.length === 0) continue;
    const pal = ERA_PALETTE[era];
    parts.push(`<g fill="${pal.fill}" fill-opacity="0.22" stroke="${pal.stroke}" stroke-width="0.35" stroke-opacity="0.45">`);
    for (const b of list) {
      parts.push(`<polygon points="${polyPoints(b.corners, toX, toY)}"/>`);
    }
    parts.push(`</g>`);
    parts.push(`<g fill="${pal.fill}" stroke="${pal.stroke}" stroke-width="0.45">`);
    for (const b of list) {
      parts.push(`<polygon points="${polyPoints(b.padCorners, toX, toY)}"/>`);
    }
    parts.push(`</g>`);
  }

  const sortedLandmarks: StandingLandmark[] = [...standing.landmarks].sort((a, b) => a.at[1] - b.at[1]);
  parts.push(`<g>`);
  for (const lm of sortedLandmarks) {
    parts.push(
      `<polygon points="${polyPoints(lm.corners, toX, toY)}" fill="#d93829" fill-opacity="0.88" stroke="#4a0f0a" stroke-width="1"/>`,
    );
  }
  for (const lm of sortedLandmarks) {
    const lx = toX(lm.at[0]);
    const ly = toY(lm.at[1]);
    parts.push(`<circle cx="${fmt(lx)}" cy="${fmt(ly)}" r="3.2" fill="#ffd166" stroke="#4a0f0a" stroke-width="1"/>`);
    parts.push(
      `<text x="${fmt(lx + 5)}" y="${fmt(ly - 4)}" font-family="system-ui, sans-serif" font-size="9.5" font-weight="700" fill="#1b1f24" stroke="#f6f3ea" stroke-width="2.4" paint-order="stroke">${esc(lm.name)}</text>`,
    );
  }
  parts.push(`</g>`);

  const barX = offX + 18;
  const barY = offY + drawH - 22;
  const barLen = 500 * scale;
  parts.push(`<rect x="${fmt(barX - 8)}" y="${fmt(barY - 22)}" width="${fmt(barLen + 88)}" height="32" rx="4" fill="#f6f3ea" fill-opacity="0.88" stroke="#c8c2b4" stroke-width="0.8"/>`);
  parts.push(`<line x1="${fmt(barX)}" y1="${fmt(barY)}" x2="${fmt(barX + barLen)}" y2="${fmt(barY)}" stroke="#1e242b" stroke-width="2.5"/>`);
  parts.push(`<line x1="${fmt(barX)}" y1="${fmt(barY - 4)}" x2="${fmt(barX)}" y2="${fmt(barY + 4)}" stroke="#1e242b" stroke-width="1.8"/>`);
  parts.push(`<line x1="${fmt(barX + barLen)}" y1="${fmt(barY - 4)}" x2="${fmt(barX + barLen)}" y2="${fmt(barY + 4)}" stroke="#1e242b" stroke-width="1.8"/>`);
  parts.push(`<text x="${fmt(barX + barLen * 0.5)}" y="${fmt(barY - 7)}" text-anchor="middle" font-family="system-ui, sans-serif" font-size="10.5" font-weight="600" fill="#1e242b">500 m</text>`);
  const nx = barX + barLen + 48;
  parts.push(`<path d="M ${fmt(nx)} ${fmt(barY - 14)} L ${fmt(nx - 5)} ${fmt(barY + 1)} L ${fmt(nx)} ${fmt(barY - 3)} L ${fmt(nx + 5)} ${fmt(barY + 1)} Z" fill="#1e242b"/>`);
  parts.push(`<text x="${fmt(nx + 10)}" y="${fmt(barY - 3)}" font-family="system-ui, sans-serif" font-size="11" font-weight="700" fill="#1e242b">N</text>`);

  parts.push(`</g>`);
  parts.push(`<rect x="${fmt(offX)}" y="${fmt(offY)}" width="${fmt(drawW)}" height="${fmt(drawH)}" rx="4" fill="none" stroke="#9e988a" stroke-width="1.2"/>`);

  const legX = offX + drawW + 18;
  let curY = offY + 14;
  parts.push(`<text x="${fmt(legX)}" y="${fmt(curY)}" font-family="system-ui, sans-serif" font-size="12" font-weight="700" letter-spacing="0.8" fill="#1e242b">BUILDING ERAS</text>`);
  curY += 16;
  for (const era of ERA_ORDER) {
    const pal = ERA_PALETTE[era];
    const count = byEra.get(era)?.length ?? 0;
    const opacity = count > 0 ? '1' : '0.35';
    parts.push(`<g opacity="${opacity}">`);
    parts.push(`<rect x="${fmt(legX)}" y="${fmt(curY - 9)}" width="12" height="12" rx="2" fill="${pal.fill}" stroke="${pal.stroke}" stroke-width="0.8"/>`);
    parts.push(`<text x="${fmt(legX + 18)}" y="${fmt(curY + 1)}" font-family="system-ui, sans-serif" font-size="10.5" fill="#2c333b">${esc(pal.label)} (${count})</text>`);
    parts.push(`</g>`);
    curY += 20;
  }

  curY += 14;
  parts.push(`<text x="${fmt(legX)}" y="${fmt(curY)}" font-family="system-ui, sans-serif" font-size="12" font-weight="700" letter-spacing="0.8" fill="#1e242b">FEATURES</text>`);
  curY += 16;
  const feats: Array<{ swatch: string; label: string }> = [
    { swatch: `<line x1="${fmt(legX)}" y1="${fmt(curY - 3)}" x2="${fmt(legX + 14)}" y2="${fmt(curY - 3)}" stroke="#2c3e50" stroke-width="3"/>`, label: 'Dove-loop roads' },
    { swatch: `<line x1="${fmt(legX)}" y1="${fmt(curY + 15)}" x2="${fmt(legX + 14)}" y2="${fmt(curY + 15)}" stroke="#857f73" stroke-width="2"/>`, label: 'Town & grid streets' },
    { swatch: `<circle cx="${fmt(legX + 7)}" cy="${fmt(curY + 33)}" r="4" fill="#d93829" stroke="#4a0f0a" stroke-width="1"/>`, label: 'Landmark plot' },
    { swatch: `<rect x="${fmt(legX)}" y="${fmt(curY + 45)}" width="14" height="10" fill="#c5d9c3" stroke="#6c8f73" stroke-width="0.9"/>`, label: 'Heron Marsh (0 lots)' },
    { swatch: `<rect x="${fmt(legX)}" y="${fmt(curY + 63)}" width="14" height="10" fill="#ead9ad" stroke="#c9b078" stroke-width="0.9"/>`, label: 'Long Strand & dunes' },
  ];
  for (let i = 0; i < feats.length; i++) {
    const f = feats[i]!;
    parts.push(f.swatch);
    parts.push(`<text x="${fmt(legX + 20)}" y="${fmt(curY + i * 18 + 1)}" font-family="system-ui, sans-serif" font-size="10.5" fill="#2c333b">${esc(f.label)}</text>`);
  }

  curY += feats.length * 18 + 22;
  parts.push(`<text x="${fmt(legX)}" y="${fmt(curY)}" font-family="system-ui, sans-serif" font-size="12" font-weight="700" letter-spacing="0.8" fill="#1e242b">STANDING BY DISTRICT</text>`);
  curY += 16;
  const distCounts = new Map<string, number>();
  for (const b of standing.buildings) {
    distCounts.set(b.districtId, (distCounts.get(b.districtId) ?? 0) + 1);
  }
  for (const d of DISTRICTS) {
    const c = distCounts.get(d.id) ?? 0;
    const opacity = c > 0 ? '1' : '0.4';
    parts.push(`<text x="${fmt(legX)}" y="${fmt(curY)}" font-family="system-ui, sans-serif" font-size="10.5" fill="#2c333b" opacity="${opacity}">${esc(d.name)}: ${c}</text>`);
    curY += 17;
  }

  parts.push(`</svg>`);
  return parts.join('\n');
}
