// Usage: node scripts/extract-assets.mjs --exteriors <Modern_Exteriors.zip> --interiors <Modern_Interiors.zip>
//
// Copies ONLY the sprites this game uses out of your purchased LimeZu packs into
// public/assets/ (git-ignored; the pack licenses forbid redistributing the files) and writes
// public/assets/manifest.json. Without these files the game falls back to placeholder art.
import AdmZip from 'adm-zip';
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
fs.writeFileSync(path.join(OUT, 'manifest.json'), JSON.stringify(manifest, null, 1));
const total = Object.values(manifest).reduce((s, g) => s + g.length, 0);
console.log(`Extracted ${total} sprites to ${path.relative(process.cwd(), OUT)}`);
