// Builds ONE self-contained file (dist/street25d.html): the page plus our sprites as data URIs.
// Usage: node prototypes/street25d/build.mjs
import fs from 'node:fs';
import path from 'node:path';
const root = path.resolve(import.meta.dirname, '..', '..', 'public', 'assets');
const files = {
  shop_Bakery_1: 'shop_Bakery/shop_Bakery_1.png', shop_Ice_Cream_Shop_1: 'shop_Ice_Cream_Shop/shop_Ice_Cream_Shop_1.png',
  shop_Gym_1: 'shop_Gym/shop_Gym_1.png', shop_Music_Store_1: 'shop_Music_Store/shop_Music_Store_1.png',
  classic_bank_11: 'classic_bank/classic_bank_11.png', classic_butcher_1: 'classic_butcher/classic_butcher_1.png',
  classic_music_store_1: 'classic_music_store/classic_music_store_1.png',
  house_medium_1: 'houseMedium/house_medium_1.png', house_small_1: 'houseSmall/house_small_1.png',
  lamp_1: 'lamp/lamp_1.png', tree_1: 'tree/tree_1.png', tree_2: 'tree/tree_2.png', tree_3: 'tree/tree_3.png', bench_1: 'bench/bench_1.png',
  char_01: 'characters/char_01.png', char_02: 'characters/char_02.png', char_03: 'characters/char_03.png', char_04: 'characters/char_04.png', char_05: 'characters/char_05.png',
};
const assets = {};
for (const [k, f] of Object.entries(files)) assets[k] = 'data:image/png;base64,' + fs.readFileSync(path.join(root, f)).toString('base64');
let html = fs.readFileSync(path.join(import.meta.dirname, 'street.html'), 'utf8');
html = html.replace('/*__ASSETS__*/{}', () => JSON.stringify(assets));
fs.mkdirSync(path.join(import.meta.dirname, 'dist'), { recursive: true });
const out = path.join(import.meta.dirname, 'dist', 'street25d.html');
fs.writeFileSync(out, html);
console.log(out, (html.length / 1048576).toFixed(2) + ' MB');
