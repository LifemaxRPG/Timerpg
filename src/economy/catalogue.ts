import { FIRST_YEAR, LAST_YEAR } from './eras';

export const SHOP_KINDS = [
  'general', 'market', 'hardware', 'pharmacy', 'five_and_dime', 'pawn', 'gas_station', 'diner', 'clothing',
] as const;
export type ShopKind = typeof SHOP_KINDS[number];
export type Weight = 'pocket' | 'handheld' | 'bulky' | 'heavy';

type Row = readonly [
  id: string, label: string, cents2025: number, from?: number, to?: number, serialFrom?: number,
];

const GROUPS = {
  pantry: { shops: ['general', 'market'], weight: 'handheld', pawnShare: 0, stock: [5, 15], rows: [
    ['peaches', 'Can of peaches', 271], ['sardines', 'Tin of sardines', 269],
    ['tomato_tin', 'Can of tomatoes', 219, 1890], ['green_bean_tin', 'Can of green beans', 179, 1900],
    ['corn_tin', 'Can of sweet corn', 189], ['condensed_milk', 'Tin of condensed milk', 319],
    ['rolled_oats', 'Box of rolled oats', 469], ['white_rice', 'Bag of rice', 369],
    ['dry_beans', 'Bag of dried beans', 248], ['flour', 'Bag of flour', 429],
    ['sugar', 'Bag of sugar', 390], ['salt', 'Box of table salt', 149],
    ['black_tea', 'Box of black tea', 499], ['whole_coffee', 'Bag of coffee beans', 1100],
    ['molasses', 'Jar of molasses', 589], ['pickles', 'Jar of pickles', 442],
    ['apple_butter', 'Jar of apple butter', 565], ['peanut_butter', 'Jar of peanut butter', 420, 1895],
    ['cooking_oil', 'Bottle of cooking oil', 479, 1910], ['tomato_soup', 'Can of tomato soup', 169, 1897],
    ['instant_coffee', 'Jar of instant coffee', 639, 1910], ['boxed_macaroni', 'Boxed macaroni dinner', 139, 1937],
    ['instant_noodles', 'Packet of instant noodles', 76, 1970], ['granola', 'Bag of granola', 549, 1968],
  ] },
  produce: { shops: ['market'], weight: 'handheld', pawnShare: 0, stock: [4, 12], rows: [
    ['apples', 'Bag of apples', 429], ['oranges', 'Bag of oranges', 489],
    ['pears', 'Bag of pears', 529], ['potatoes', 'Bag of potatoes', 549],
    ['carrots', 'Bunch of carrots', 229], ['onions', 'Bag of onions', 389],
    ['cabbage', 'Head of cabbage', 235], ['turnips', 'Bunch of turnips', 275],
    ['eggs', 'Dozen eggs', 389], ['milk', 'Quart of milk', 239],
    ['butter', 'Pound of butter', 499], ['cheddar', 'Block of cheddar', 359],
    ['bread', 'Loaf of bread', 369], ['bacon', 'Packet of bacon', 679],
    ['frozen_peas', 'Bag of frozen peas', 249, 1935], ['frozen_dinner', 'Frozen dinner', 449, 1953],
  ] },
  counter: { shops: ['diner'], weight: 'pocket', pawnShare: 0, stock: [6, 24], rows: [
    ['coffee', 'Cup of coffee', 77], ['hot_tea', 'Cup of tea', 115],
    ['pie_slice', 'Slice of apple pie', 475], ['toast', 'Buttered toast', 240],
    ['egg_plate', 'Eggs and hash browns', 790, 1890], ['oatmeal_bowl', 'Bowl of oatmeal', 495],
    ['ham_sandwich', 'Ham sandwich', 730], ['grilled_cheese', 'Grilled cheese sandwich', 680, 1920],
    ['soup_bowl', 'Bowl of vegetable soup', 590], ['meatloaf_plate', 'Meatloaf plate', 1050, 1910],
    ['burger', 'Hamburger', 795, 1905], ['fries', 'Basket of fries', 385, 1915],
    ['root_beer', 'Glass of root beer', 280], ['lime_soda', 'Glass of lime soda', 260, 1895],
    ['milkshake', 'Ice cream milkshake', 555, 1922], ['chili_bowl', 'Bowl of chili', 660],
  ] },
  fuel: { shops: ['gas_station'], weight: 'handheld', pawnShare: 0, stock: [24, 64], rows: [
    ['gasoline', 'Gallon of gasoline', 325, 1905],
    ['diesel', 'Gallon of diesel', 365, 1930],
  ] },
  roadside: { shops: ['gas_station'], weight: 'pocket', pawnShare: 0, stock: [4, 14], rows: [
    ['paper_road_map', 'Folded highway map', 395, 1915, 2015],
    ['waxed_cup', 'Pack of paper travel cups', 209, 1915],
    ['motor_oil', 'Quart of motor oil', 650, 1910], ['washer_fluid', 'Jug of washer fluid', 479, 1940],
    ['tire_patch', 'Tire patch kit', 769, 1905], ['air_freshener', 'Paper air freshener', 290, 1952],
    ['trail_mix', 'Bag of trail mix', 325, 1968], ['soft_drink', 'Bottle of soda', 220, 1920],
    ['jerky', 'Packet of beef jerky', 529, 1920], ['crackers', 'Packet of crackers', 179, 1915],
    ['bagged_ice', 'Bag of ice', 399, 1935], ['bottled_water', 'Bottle of water', 158, 1975],
  ] },
  hardware_supply: { shops: ['hardware', 'general'], weight: 'handheld', pawnShare: 0, stock: [3, 10], rows: [
    ['cut_nails', 'Packet of cut nails', 599], ['wood_screws', 'Box of wood screws', 575],
    ['wire', 'Coil of utility wire', 980], ['sash_cord', 'Bundle of sash cord', 829],
    ['hemp_rope', 'Coil of hemp rope', 1600], ['nylon_rope', 'Coil of nylon rope', 1450, 1946],
    ['sandpaper', 'Packet of sandpaper', 591], ['wood_glue', 'Bottle of wood glue', 579],
    ['linseed_oil', 'Tin of linseed oil', 1459], ['paint', 'Tin of house paint', 2495],
    ['electrical_tape', 'Roll of electrical tape', 393, 1946], ['duct_tape', 'Roll of cloth tape', 649, 1945],
    ['hose_washer', 'Packet of hose washers', 259], ['fuses', 'Box of screw-in fuses', 729, 1900],
    ['lamp_oil', 'Bottle of lamp oil', 998], ['garden_seed', 'Packet of garden seed', 299],
  ] },
  hand_tools: { shops: ['hardware'], weight: 'handheld', pawnShare: .28, stock: [1, 5], rows: [
    ['hammer', 'Claw hammer', 1790], ['mallet', 'Wooden mallet', 2050], ['brace', 'Hand drill brace', 4900],
    ['hand_saw', 'Crosscut handsaw', 2400], ['hack_saw', 'Hacksaw', 1680],
    ['plane', 'Block plane', 3895], ['chisel', 'Bench chisel', 1270], ['rasp', 'Half-round rasp', 1230],
    ['pliers', 'Combination pliers', 1395], ['pipe_wrench', 'Pipe wrench', 3100],
    ['socket_set', 'Socket wrench set', 5390, 1915], ['tin_snips', 'Tin snips', 1960],
    ['awl', 'Scratch awl', 785], ['spirit_level', 'Spirit level', 1895],
    ['tape_measure', 'Pocket tape measure', 1290, 1900], ['trowel', 'Garden trowel', 925],
    ['pruners', 'Pruning shears', 1900], ['soldering_iron', 'Electric soldering iron', 2420, 1920],
  ] },
  power_tools: { shops: ['hardware'], weight: 'bulky', pawnShare: .32, stock: [1, 3], rows: [
    ['corded_drill', 'Corded drill', 6400, 1917, undefined, 1917],
    ['circular_saw', 'Portable circular saw', 10900, 1928, undefined, 1928],
    ['belt_sander', 'Portable belt sander', 10400, 1926, undefined, 1926],
    ['jigsaw', 'Electric jigsaw', 8700, 1947, undefined, 1947],
    ['cordless_drill', 'Cordless drill', 11900, 1961, undefined, 1961],
    ['orbital_sander', 'Orbital sander', 6800, 1952, undefined, 1952],
    ['shop_vacuum', 'Wet and dry shop vacuum', 10800, 1955, undefined, 1955],
    ['heat_gun', 'Electric heat gun', 4900, 1965, undefined, 1965],
  ] },
  pharmacy: { shops: ['pharmacy'], weight: 'pocket', pawnShare: 0, stock: [4, 12], rows: [
    ['gauze', 'Packet of gauze', 389], ['cotton', 'Bag of cotton balls', 329],
    ['adhesive_bandages', 'Box of adhesive bandages', 430, 1921], ['aspirin', 'Bottle of aspirin', 538, 1899],
    ['cough_drops', 'Tin of cough drops', 329], ['cough_syrup', 'Bottle of cough syrup', 849],
    ['antacid', 'Roll of antacid tablets', 225, 1930], ['petroleum_jelly', 'Jar of petroleum jelly', 489],
    ['witch_hazel', 'Bottle of witch hazel', 532], ['rubbing_alcohol', 'Bottle of rubbing alcohol', 299, 1920],
    ['tooth_powder', 'Tin of tooth powder', 373, 1880, 1965],
    ['toothpaste', 'Tube of toothpaste', 356, 1896], ['toothbrush', 'Toothbrush', 279],
    ['cold_cream', 'Jar of cold cream', 549], ['shave_soap', 'Cake of shaving soap', 549],
    ['sunscreen', 'Bottle of sunscreen', 895, 1946], ['contact_solution', 'Bottle of contact solution', 775, 1971],
    ['digital_thermometer', 'Digital thermometer', 929, 1985],
  ] },
  notions: { shops: ['five_and_dime', 'general'], weight: 'pocket', pawnShare: .10, stock: [3, 10], rows: [
    ['buttons', 'Card of shirt buttons', 189], ['thread', 'Spool of thread', 299],
    ['needles', 'Packet of sewing needles', 229], ['thimble', 'Metal thimble', 275],
    ['safety_pins', 'Card of safety pins', 199], ['pincushion', 'Cloth pincushion', 525],
    ['shoe_laces', 'Pair of shoelaces', 229], ['shoe_polish', 'Tin of shoe polish', 439, 1900],
    ['comb', 'Pocket comb', 189], ['hand_mirror', 'Hand mirror', 449],
    ['pencil', 'Graphite pencil', 75], ['notebook', 'Ruled notebook', 295],
    ['fountain_pen', 'Fountain pen', 2390, 1884], ['ballpoint', 'Ballpoint pen', 125, 1945],
    ['postcards', 'Bundle of postcards', 399], ['playing_cards', 'Deck of playing cards', 379],
    ['pocketknife', 'Folding pocketknife', 2495], ['film_roll', 'Roll of photographic film', 1190, 1888],
  ] },
  housewares: { shops: ['general', 'five_and_dime'], weight: 'handheld', pawnShare: .22, stock: [2, 7], rows: [
    ['enamel_mug', 'Enamel mug', 625], ['mason_jar', 'Canning jar', 269],
    ['skillet', 'Cast-iron skillet', 2790], ['pie_tin', 'Tin pie plate', 593],
    ['bread_box', 'Bread box', 2190], ['wash_board', 'Galvanized washboard', 2990, 1880, 1960],
    ['flatiron', 'Cast-iron stovetop iron', 3900, 1880, 1945],
    ['electric_iron', 'Electric clothes iron', 3330, 1905],
    ['hand_crank_beater', 'Hand-crank egg beater', 1495], ['coffee_percolator', 'Stovetop percolator', 2795],
    ['pressure_cooker', 'Stovetop pressure cooker', 6990, 1939],
    ['thermos_flask', 'Vacuum flask', 1995, 1904], ['alarm_clock', 'Wind-up alarm clock', 2195],
    ['flashlight', 'Battery flashlight', 1450, 1899], ['kerosene_lantern', 'Kerosene lantern', 1890],
    ['plastic_lunch_box', 'Plastic lunch box', 950, 1958],
  ] },
  toys: { shops: ['five_and_dime'], weight: 'handheld', pawnShare: .16, stock: [2, 8], rows: [
    ['marbles', 'Bag of glass marbles', 399], ['wood_top', 'Painted wooden top', 395],
    ['rag_doll', 'Rag doll', 1390], ['rubber_ball', 'Rubber ball', 299],
    ['jump_rope', 'Skipping rope', 565], ['checkers', 'Checkers set', 899],
    ['jigsaw_puzzle', 'Cardboard jigsaw puzzle', 1090, 1905],
    ['tin_train', 'Clockwork tin train', 3825, 1895, 1968],
    ['toyrobot', 'Wind-up toy robot', 2400, 1950, 1985],
    ['modelkit', 'Model airplane kit', 2140, 1935],
    ['diecast_car', 'Die-cast toy car', 485, 1934], ['plastic_bricks', 'Box of plastic building bricks', 1590, 1950],
  ] },
  clothing: { shops: ['clothing'], weight: 'handheld', pawnShare: .15, stock: [2, 8], rows: [
    ['wool_coat', 'Wool overcoat', 13990], ['jacket', 'Wool jacket', 8950],
    ['work_boots', 'Leather work boots', 10200], ['rubber_boots', 'Rubber boots', 4190],
    ['work_shirt', 'Cotton work shirt', 2790], ['dress_shirt', 'Dress shirt', 3900],
    ['overalls', 'Denim overalls', 5490], ['denim_jeans', 'Denim jeans', 4690],
    ['scarf', 'Silk scarf', 3290], ['gloves', 'Leather gloves', 3195],
    ['wool_socks', 'Pair of wool socks', 990], ['suspenders', 'Pair of suspenders', 1790],
    ['felt_hat', 'Felt hat', 4590], ['flat_cap', 'Wool flat cap', 2900],
    ['nylon_stockings', 'Pair of nylon stockings', 799, 1940],
    ['canvas_sneakers', 'Canvas sneakers', 3395, 1917],
    ['polyester_suit', 'Polyester leisure suit', 7950, 1968, 1985],
    ['hooded_sweatshirt', 'Hooded sweatshirt', 3590, 1935],
  ] },
  electronics: { shops: ['general'], weight: 'handheld', pawnShare: .20, stock: [1, 3], rows: [
    ['radio', 'Table radio', 35235, 1922, 1970, 1922],
    ['portable_radio', 'Portable transistor radio', 4990, 1954, 2005, 1954],
    ['phonograph', 'Cabinet phonograph', 45500, 1895, 1945, 1895],
    ['record_player', 'Electric record player', 17900, 1925, undefined, 1925],
    ['pocket_calculator', 'Pocket calculator', 1295, 1971, undefined, 1971],
    ['cassette_player', 'Portable cassette player', 6995, 1979, 2005, 1979],
    ['compact_disc_player', 'Portable CD player', 7995, 1984, 2012, 1984],
    ['vhs_player', 'Videocassette player', 17995, 1977, 2006, 1977],
    ['dvd_player', 'DVD player', 6895, 1997, undefined, 1997],
    ['headphones', 'Stereo headphones', 5995, 1958, undefined, 1985],
    ['digital_camera', 'Compact digital camera', 19995, 1995, undefined, 1995],
    ['instant_camera', 'Instant camera', 11995, 1948, undefined, 1948],
    ['smartphone', 'Touchscreen phone', 59900, 2007, undefined, 2007],
    ['usb_drive', 'USB flash drive', 1090, 2000],
  ] },
  appliances: { shops: ['general'], weight: 'heavy', pawnShare: .18, stock: [1, 2], rows: [
    ['television', 'Black-and-white television', 225000, 1946, 1985, 1946],
    ['color_tv', 'Color tube television', 69000, 1954, 2008, 1954],
    ['flat_tv', 'Flat-panel television', 36900, 1998, undefined, 1998],
    ['refrigerator', 'Electric refrigerator', 105000, 1918, undefined, 1918],
    ['icebox', 'Insulated icebox', 41000, 1880, 1948],
    ['wringer_washer', 'Wringer washing machine', 56000, 1910, 1975, 1910],
    ['auto_washer', 'Automatic washing machine', 72500, 1937, undefined, 1937],
    ['microwave', 'Countertop microwave oven', 13995, 1967, undefined, 1967],
  ] },
  valuables: { shops: ['pawn'], weight: 'pocket', pawnShare: .46, stock: [1, 2], rows: [
    ['ring', 'Gold band', 23900], ['pocketwatch', 'Brass pocket watch', 14900, 1880, undefined, 1880],
    ['brooch', 'Enamel brooch', 6580], ['silver_chain', 'Silver chain', 5695],
    ['locket', 'Gold-filled locket', 7895], ['cufflinks', 'Pair of cuff links', 4995],
    ['wristwatch', 'Mechanical wristwatch', 21900, 1905, undefined, 1920],
    ['binoculars', 'Field binoculars', 10990, 1895, undefined, 1930],
  ] },
  instruments: { shops: ['pawn'], weight: 'bulky', pawnShare: .38, stock: [1, 2], rows: [
    ['guitar', 'Acoustic guitar', 28900, 1880, undefined, 1920],
    ['electric_guitar', 'Electric guitar', 38500, 1935, undefined, 1935],
    ['fiddle', 'Student fiddle', 22500], ['banjo', 'Five-string banjo', 29500],
    ['mandolin', 'Bowl-back mandolin', 22900], ['cornet', 'Brass cornet', 27900, 1880, undefined, 1900],
    ['accordion', 'Button accordion', 31500], ['typewriter', 'Portable typewriter', 18700, 1910, 1995, 1910],
  ] },
} satisfies Record<string, {
  shops: readonly ShopKind[];
  weight: Weight;
  pawnShare: number;
  stock: readonly [min: number, max: number];
  rows: readonly Row[];
}>;

export type Category = keyof typeof GROUPS;

export type Item = Readonly<{
  id: string;
  label: string;
  cents2025: number;
  from: number;
  to: number;
  serialFrom: number | null;
  category: Category;
  shops: readonly ShopKind[];
  weight: Weight;
  pawnShare: number;
  stock: readonly [min: number, max: number];
}>;

export const ITEMS: readonly Item[] = (Object.keys(GROUPS) as Category[]).flatMap(category => {
  const { rows, ...defaults } = GROUPS[category];
  return (rows as readonly Row[]).map(([id, label, cents2025, from = FIRST_YEAR, to = LAST_YEAR, serialFrom = null]) => ({
    id, label, cents2025, from, to, serialFrom, category, ...defaults,
  }));
});
