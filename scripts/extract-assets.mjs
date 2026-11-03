// Usage: node scripts/extract-assets.mjs --exteriors <Modern_Exteriors.zip> --interiors <Modern_Interiors.zip>
//
// Copies ONLY the sprites this game uses out of your purchased LimeZu packs into
// public/assets/ (git-ignored; the pack licenses forbid redistributing the files) and writes
// public/assets/manifest.json. Without these files the game falls back to placeholder art.
import AdmZip from 'adm-zip';
import { PNG } from 'pngjs';
import fs from 'node:fs';
import path from 'node:path';

const args = Object.fromEntries(process.argv.slice(2).reduce((a, v, i, all) => (v.startsWith('--') ? [...a, [v.slice(2), all[i + 1]]] : a), []));
if (!args.exteriors || !args.interiors) {
  console.error('usage: node scripts/extract-assets.mjs --exteriors <zip> --interiors <zip>');
  process.exit(1);
}
const OUT = path.resolve('public/assets');
fs.rmSync(OUT, { recursive: true, force: true });

const ME = 'Modern_Exteriors_32x32/ME_Theme_Sorter_32x32/';
const FM = `${ME}5_Floor_Modular_Building_Singles_32x32/ME_Singles_Floor_Modular_Building_32x32_`;
const range = (a, b) => Array.from({ length: b - a + 1 }, (_, i) => a + i);

/** group -> [{ key, zip, src }] ; manifest groups are what the game reads. */
const exteriors = {
  grass: [22, 9, 11, 12].map((n) => [`grass_${n}`, `${ME}1_Terrains_and_Fences_Singles_32x32/ME_Singles_Terrains_and_Fences_32x32_Grass_1_${n}.png`]),
  asphalt: [24, 25, 26, 27].map((n) => [`asphalt_${n}`, `${ME}2_City_Terrains_Singles_32x32/ME_Singles_City_Terrains_32x32_Asphalt_1_Variation_${n}.png`]),
  sidewalk: [25, 26, 27].map((n) => [`sidewalk_${n}`, `${ME}2_City_Terrains_Singles_32x32/ME_Singles_City_Terrains_32x32_Sidewalk_1_${n}.png`]),
  houseSmall: range(1, 8).map((n) => [`house_small_${n}`, `${ME}11_Camping_Singles_32x32/ME_Singles_Camping_32x32_Mobile_House_Small_${n}.png`]),
  houseMedium: range(1, 8).map((n) => [`house_medium_${n}`, `${ME}11_Camping_Singles_32x32/ME_Singles_Camping_32x32_Mobile_House_Medium_${n}.png`]),
  villa: range(1, 5).map((n) => [`villa_${n}`, `${ME}7_Villas_Singles_32x32/ME_Singles_Villas_32x32_Villa_${n}.png`]),
  roof: range(1, 11).map((n) => [`roof_${n}`, `${FM}Roof_${n}.png`]),
};
// Water: shore autotile set (Grass_Water_2; index n-1 = tile n; 22 = plain water, 23 = plain grass),
// ripple/lily-pad overlays, and the iron railing (horizontal 2 and 6, vertical right 4 / left 8) for bridges.
const TF = `${ME}1_Terrains_and_Fences_Singles_32x32/ME_Singles_Terrains_and_Fences_32x32_`;
exteriors.waterShore = range(1, 23).map((n) => [`water_shore_${n}`, `${TF}Grass_Water_2_${n}.png`]);
exteriors.waterDecor = [1, 2, 3, 4, 5, 11, 12, 13].map((n) => [`water_decor_${n}`, `${TF}Props_Water_${n}.png`]);
exteriors.bridgeRail = [2, 4, 6, 8].map((n) => [`bridge_rail_${n}`, `${TF}Fence_3_${n}.png`]);
// Ground texture and yards: the pack's grass is flat, so variety comes from overlays - light-green
// patches and ground clutter (pebbles, sprouts, twigs) - plus picket fences, hedges and a dirt path tile.
exteriors.grassPatch = [8, 9, 10, 11, 12, 13].map((n) => [`grass_patch_${n}`, `${TF}Props_Grass_${n}.png`]);
exteriors.clutter = [1, 2, 5, 6, 8, 9, 10, 11, 13, 14, 16, 17, 20, 21, 22, 24].map((n) => [`clutter_${n}`, `${TF}Props_Dirt_${n}.png`]);
exteriors.fence = [9, 10].map((n) => [`fence_${n}`, `${TF}Props_Fence_${n}.png`]);
exteriors.dirtPath = [['dirt_path_21', `${TF}Grass_3_21.png`]];
exteriors.hedge = [1, 2, 3, 4, 5].map((n) => [`hedge_${n}`, `${ME}17_Garden_Singles_32x32/ME_Singles_Garden_32x32_Bush_${n}.png`]);

// Housing variety (see src/town/housing.ts for how these are used).
const AH = `${ME}24_Additional_Houses_Singles_32x32/24_Additional_Houses_`;
const GB = `${ME}4_Generic_Building_Singles_32x32/ME_Singles_Generic_Building_32x32_`;
// Terraced family houses (12x15 tiles); 3 is a taller variant that does not fit and is skipped.
exteriors.houseTerraced = [1, 2, 4, 5, 6].map((n) => [`house_terraced_${n}`, `${AH}Terraced_House_${n}_32x32.png`]);
// Big detached houses for the wealthy.
exteriors.houseEstate = ['Country_House', 'Japanese_House', 'One_Story_House', 'Modern_House'].map((n) => [`estate_${n.toLowerCase()}`, `${AH}${n}_32x32.png`]);
// Whole apartment buildings (single sprites).
exteriors.condo = ['5_1', '5_2', '5_3', '3_44', '3_45', '9'].map((n) => [`condo_${n}`, `${GB}Condo_${n}.png`]);
// Modular parts: condo ground floors, shared middle floors, and roof props, stacked in TownScene.
exteriors.condoGround = range(1, 12).map((n) => [`condo_ground_${n}`, `${FM}Ground_Floor_Condo_${n}.png`]);
exteriors.middle = range(1, 18).map((n) => [`middle_${n}`, `${FM}Middle_Floor_${n}.png`]);
exteriors.roofProp = [12, 13, 14, 15, 16].map((n) => [`roofprop_${n}`, `${FM}Roof_Props_${n}.png`]);
const CP = `${ME}3_City_Props_Singles_32x32/ME_Singles_City_Props_32x32_`;
const GD = `${ME}17_Garden_Singles_32x32/ME_Singles_Garden_32x32_`;
const props = (group, base, nums) => (exteriors[group] = nums.map((n) => [`${group}_${n}`, `${base}_${n}.png`]));
props('tree', `${CP}Tree`, [1, 2, 3, 4, 5, 6, 8, 11, 12]);
props('lamp', `${CP}Street_Lamp`, [1, 2, 3, 4, 5]);
props('bench', `${CP}Bench`, [1, 2, 7]);
props('flowerBush', `${CP}Flower_Bush`, [1, 2, 3, 4, 5, 6]);
props('bush', `${GD}Bush`, [1, 2, 3, 4, 5, 6, 8, 10, 12, 16]);
props('flowers', `${CP}Flowers`, [1, 2, 3, 4, 5]);
props('fountain1', `${GD}Fountain_1`, [1, 2, 3, 4]);
props('fountain2', `${GD}Fountain_2`, [1, 2, 3, 4]);
// Open-for-business variants only (even numbers are shutters-down; generic shops also include
// "CLOSED"/"DANGER" signs that don't suit a living town).
const odds = (n) => range(1, n).filter((i) => i % 2 === 1);
const SHOPS = {
  Shop: [1, 5, 7, 9, 13, 17, 19, 21, 25, 29, 31, 33],
  Bakery: odds(12),
  Butchery: odds(12),
  Music_Store: range(1, 12),
  Bait_Shop: odds(18),
  Ice_Cream_Shop: odds(6),
};
for (const [kind, nums] of Object.entries(SHOPS))
  exteriors[`shop:${kind}`] = nums.map((i) => [`shop_${kind}_${i}`, `${FM}Ground_Floor_${kind}_${i}.png`]);

// Civic landmark buildings (whole-building sprites; tile sizes are mirrored in src/town/landmarks.ts).
const LM = {
  post: ['22_Post_Office_Singles_32x32/22_Post_Office_32x32_Building_', [1, 2, 3, 4]],
  school: ['13_School_Singles_32x32/ME_Singles_School_32x32_School_', [1]],
  fire: ['18_Fire_Station_Singles_32x32/ME_Singles_Fire_Station_32x32_Building', ['']],
  police: ['15_Police_Station_Singles_32x32/ME_Singles_Police_Station_32x32_Police_Station_', [1, 2, 3]],
  hospital: ['12_Hotel_and_Hospital_Singles_32x32/ME_Singles_Hotel_and_Hospital_32x32_Hospital_', [1]],
  hotel: ['12_Hotel_and_Hospital_Singles_32x32/ME_Singles_Hotel_and_Hospital_32x32_Hotel_', [1]],
  clocktower: ['13_School_Singles_32x32/ME_Singles_School_32x32_Clock_Tower_', [1, 2]],
};
for (const [kind, [base, nums]] of Object.entries(LM))
  exteriors[`landmark:${kind}`] = nums.map((n) => [`landmark_${kind}_${n || 1}`, `${ME}${base}${n}.png`]);

const interiors = {
  characters: range(1, 8).map((n) => {
    const id = String(n).padStart(2, '0');
    return [`char_${id}`, `2_Characters/Character_Generator/0_Premade_Characters/32x32/Premade_Character_32x32_${id}.png`];
  }),
};

function pngSize(buf) {
  return [buf.readUInt32BE(16), buf.readUInt32BE(20)];
}

const manifest = {};
function pull(zipPath, groups) {
  const zip = new AdmZip(zipPath);
  for (const [group, items] of Object.entries(groups)) {
    manifest[group] = [];
    for (const [key, src] of items) {
      const entry = zip.getEntry(src);
      if (!entry) { console.warn('missing in zip:', src); continue; }
      const data = entry.getData();
      const rel = `${group.replace(':', '_')}/${key}.png`;
      fs.mkdirSync(path.dirname(path.join(OUT, rel)), { recursive: true });
      fs.writeFileSync(path.join(OUT, rel), data);
      const [w, h] = pngSize(data);
      manifest[group].push({ key, path: `assets/${rel}`, w, h });
    }
  }
}
pull(path.resolve(args.exteriors), exteriors);
pull(path.resolve(args.interiors), interiors);

// ---- Recolored variants of the mobile homes and terraced houses (the pack has only one gray colorway,
// which made towns look samey). The license allows editing the assets. Walls (light, unsaturated pixels)
// and roofs (mid-dark, unsaturated pixels) are re-tinted; outlines, windows, doors and decks are untouched.
const WALLS = [null, [250, 238, 200], [200, 225, 245], [205, 230, 205], [245, 215, 215], [250, 220, 190]];
const ROOFS = [null, [150, 62, 54], [62, 102, 70], [104, 74, 56], [58, 76, 118], [172, 92, 58]];
function hsl(r, g, b) {
  r /= 255; g /= 255; b /= 255;
  const max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2;
  const d = max - min;
  const sat = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1));
  return [sat, l];
}
function recolor(buf, wi, ri) {
  const png = PNG.sync.read(buf);
  const d = png.data;
  for (let i = 0; i < d.length; i += 4) {
    if (d[i + 3] === 0) continue;
    const [sat, l] = hsl(d[i], d[i + 1], d[i + 2]);
    if (l < 0.22 || sat >= 0.25) continue;
    let t, k;
    if (l > 0.7 && WALLS[wi]) { t = WALLS[wi]; k = l / 0.93; }
    else if (l <= 0.7 && ROOFS[ri]) { t = ROOFS[ri]; k = Math.min(1.2, l / 0.42); }
    else continue;
    d[i] = Math.min(255, Math.round(t[0] * k));
    d[i + 1] = Math.min(255, Math.round(t[1] * k));
    d[i + 2] = Math.min(255, Math.round(t[2] * k));
  }
  return PNG.sync.write(png);
}
/** [wall, roof] palette indices for each recolored variant; _c0 would be the original. */
const COMBOS = {
  houseSmall: [[1, 1], [2, 2], [3, 3], [4, 4], [5, 5], [0, 1], [0, 2], [0, 3], [2, 4]],
  houseMedium: [[1, 1], [2, 2], [3, 3], [4, 4], [5, 5], [0, 1], [0, 2], [0, 3], [2, 4]],
  houseTerraced: [[1, 1], [2, 2], [3, 3], [4, 4], [5, 5]],
};
for (const [group, combos] of Object.entries(COMBOS)) {
  const originals = [...manifest[group]];
  for (const e of originals) {
    const src = fs.readFileSync(path.join(path.dirname(OUT), e.path));
    combos.forEach(([wi, ri], ci) => {
      const key = `${e.key}_c${ci + 1}`;
      const rel = `${group}/${key}.png`;
      fs.writeFileSync(path.join(OUT, rel), recolor(src, wi, ri));
      manifest[group].push({ key, path: `assets/${rel}`, w: e.w, h: e.h });
    });
  }
}
fs.writeFileSync(path.join(OUT, 'manifest.json'), JSON.stringify(manifest, null, 1));
const total = Object.values(manifest).reduce((s, g) => s + g.length, 0);
console.log(`Extracted ${total} sprites to ${path.relative(process.cwd(), OUT)}`);
