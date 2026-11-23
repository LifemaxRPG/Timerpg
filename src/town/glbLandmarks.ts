import { rand } from '../sim/rng';

// Hand-made landmark MODELS (glTF .glb, kotlin-street/docs/LANDMARKS_GLB.md): one-off buildings modelled by hand to
// the guide (docs/CHATGPT_MODEL_GUIDE.md) and drawn by the engine from kotlin-street/app/src/main/assets/landmarks/.
// This is the generator's side of the catalogue (the engine reads landmarks.json there; a test keeps the two in step).
// Nothing places them yet: a later change gives towns their sites (as the stores' storeSites.ts does) and records the
// business's history (opened, moved, closed, reused) in the simulation; the models never decide that themselves.

export type GlbLandmarkKind =
  | 'landmark_glb_modern_gas_station'
  | 'landmark_glb_main_street_service_station'
  | 'landmark_glb_junction_diner'
  | 'landmark_glb_starling_lanes'
  | 'landmark_glb_gazette_office'
  | 'landmark_glb_feed_mill'
  | 'landmark_glb_mirage_nightclub'
  | 'landmark_glb_pulse_nightclub'
  | 'landmark_glb_starlight_drive_in'
  | 'landmark_glb_water_tower'
  | 'landmark_glb_roadside_motel'
  | 'landmark_glb_premier_railroad_depot'
  | 'landmark_glb_coin_laundromat'
  | 'landmark_glb_foundry_nightclub'
  | 'landmark_glb_foundry_factory'
  | 'landmark_glb_funeral_home'
  | 'landmark_glb_reel_time_video_1984'
  | 'landmark_glb_reel_time_video_1996'
  | 'landmark_glb_reel_time_video_2011'
  | 'landmark_glb_reel_time_video_2020'
  | 'landmark_glb_county_courthouse_1894'
  | 'landmark_glb_county_courthouse_1962'
  | 'landmark_glb_county_courthouse_1988'
  | 'landmark_glb_county_courthouse_2008'
  | 'landmark_glb_radio_station'
  | 'landmark_glb_public_library_1912'
  | 'landmark_glb_public_library_1970s'
  | 'landmark_glb_corner_drugstore_family'
  | 'landmark_glb_corner_drugstore_retail'
  | 'landmark_glb_corner_drugstore_closed'
  | 'landmark_glb_corner_drugstore_cafe'
  | 'landmark_glb_small_town_bank_1920s'
  | 'landmark_glb_small_town_bank_drive_through'
  | 'landmark_glb_brick_church'
  | 'landmark_glb_country_church'
  | 'landmark_glb_lincoln_high_school_1925'
  | 'landmark_glb_lincoln_high_school_1955'
  | 'landmark_glb_lincoln_high_school_1975'
  | 'landmark_glb_lincoln_high_school_2000'
  | 'landmark_glb_carhop_drive_in_original'
  | 'landmark_glb_carhop_drive_in_closed'
  | 'landmark_glb_carhop_drive_in_restored'
  | 'landmark_glb_fire_station_1924'
  | 'landmark_glb_fire_station_1988'
  | 'landmark_glb_post_office_1938'
  | 'landmark_glb_post_office_1988'
  | 'landmark_glb_highway_rest_area'
  | 'landmark_glb_lantern_pine_lodge'
  | 'landmark_glb_the_eyrie'
  | 'landmark_glb_union_arch'
  | 'landmark_glb_night_keeper'
  | 'landmark_glb_beach_blvd_rex'
  | 'landmark_glb_great_meridian_1926'
  | 'landmark_glb_great_meridian_1941'
  | 'landmark_glb_great_meridian_1976'
  | 'landmark_glb_great_meridian_2000'
  | 'landmark_glb_five_and_dime_1925'
  | 'landmark_glb_five_and_dime_1970'
  | 'landmark_glb_five_and_dime_2000'
  | 'landmark_glb_five_and_dime_closed'
  | 'landmark_glb_grain_elevator_1925'
  | 'landmark_glb_grain_elevator_1950'
  | 'landmark_glb_grain_elevator_1990'
  | 'landmark_glb_baseball_park_1935'
  | 'landmark_glb_baseball_park_1950'
  | 'landmark_glb_baseball_park_1960'
  | 'landmark_glb_baseball_park_2000'
  | 'landmark_glb_mill_branch_active'
  | 'landmark_glb_mill_branch_abandoned'
  | 'landmark_glb_westmeadow_1955'
  | 'landmark_glb_westmeadow_1990'
  | 'landmark_glb_barber_1940'
  | 'landmark_glb_barber_1980'
  | 'landmark_glb_barber_2010'
  | 'landmark_glb_salon_1940'
  | 'landmark_glb_salon_1980'
  | 'landmark_glb_salon_2010'
  | 'landmark_glb_truck_stop_1960'
  | 'landmark_glb_truck_stop_1990';

export interface GlbLandmarkSpec {
  name: string;
  /** The business it houses. */
  role: 'gas_station' | 'diner' | 'bowling' | 'newspaper' | 'feed_mill' | 'nightclub' | 'drive_in' | 'water_tower' | 'motel' | 'railroad_depot' | 'laundromat' | 'factory' | 'funeral_home' | 'video_store' | 'phone_repair' | 'courthouse' | 'radio_station'
    | 'library' | 'drugstore' | 'bank' | 'church' | 'school' | 'carhop' | 'fire_station' | 'post_office' | 'rest_area' | 'lodge' | 'monument' | 'roadside_attraction'
    | 'five_and_dime' | 'grain_elevator' | 'ballpark'
    | 'elementary_school' | 'barber' | 'salon' | 'truck_stop';
  /** The design's years: built from..to (it may stand long after, as the simulation decides), and its sample year. */
  era: { from: number; to: number; built: number };
  /** The lot, metres: width along the street, depth back from it; the building's walls. */
  lot: [number, number];
  building: [number, number];
  /** The plot the whole chain of states needs, when the states differ in size (the bank's drive-through, the library's wing): the biggest lot. */
  plot?: [number, number];
}

export const GLB_LANDMARKS: Record<GlbLandmarkKind, GlbLandmarkSpec> = {
  // a highway-era station with a canopy over four pumps and a convenience store (a town's Main Street station may
  // move out to one when the simulation says so: the two may also stand together)
  landmark_glb_modern_gas_station: { name: 'Crossfield Fuel & Market', role: 'gas_station', era: { from: 2005, to: 2040, built: 2005 }, lot: [32.28, 35.08], building: [17.38, 8.69] },
  // the 1950s-60s full-service station on Main Street: office, two repair bays, two pumps
  landmark_glb_main_street_service_station: { name: 'Main Street Service', role: 'gas_station', era: { from: 1950, to: 1969, built: 1953 }, lot: [23.28, 21.73], building: [15.52, 8.07] },
  // a streamlined enamel diner; opens in the 1940s-50s
  landmark_glb_junction_diner: { name: 'Junction Diner', role: 'diner', era: { from: 1940, to: 1965, built: 1948 }, lot: [17.0, 21.0], building: [12.0, 12.3] },
  // an eight-lane 1960s bowling alley
  landmark_glb_starling_lanes: { name: 'Starling Lanes', role: 'bowling', era: { from: 1960, to: 1969, built: 1964 }, lot: [34.76, 42.21], building: [22.35, 31.04] },
  // a two-storey brick newspaper office with a sawtooth print-room wing behind; built 1905-40, stands 1950 on (Main Street)
  landmark_glb_gazette_office: { name: 'Gazette Office & Print Shop', role: 'newspaper', era: { from: 1905, to: 1940, built: 1925 }, lot: [13.66, 19.09], building: [9.93, 14.28] },
  // a timber country elevator (20 m) with a feed-store wing and two grain bins; built 1920-60, stands to the present (rail side, farmland)
  landmark_glb_feed_mill: { name: 'Hearthside Feed & Grain', role: 'feed_mill', era: { from: 1920, to: 1960, built: 1938 }, lot: [29.8, 19.87], building: [26.23, 16.68] },
  // a 1980s synthwave nightclub with a neon marquee and a rear loading dock
  landmark_glb_mirage_nightclub: { name: 'Club Mirage', role: 'nightclub', era: { from: 1980, to: 1989, built: 1985 }, lot: [27.32, 29.8], building: [18.62, 17.38] },
  // the same plot as Club Mirage in a 2020s louvre-and-LED sky-lounge style
  landmark_glb_pulse_nightclub: { name: 'Club Pulse', role: 'nightclub', era: { from: 2020, to: 2029, built: 2024 }, lot: [27.32, 29.8], building: [18.62, 17.38] },
  // a 1950s Googie drive-in: concession pavilion, widescreen tower, fenced tiered car lot
  landmark_glb_starlight_drive_in: { name: 'Starlight Drive-In', role: 'drive_in', era: { from: 1950, to: 1966, built: 1956 }, lot: [34.76, 42.21], building: [16.14, 7.45] },
  // a four-legged riveted steel municipal water tower in a fenced compound (26 m); stands 1940 on
  landmark_glb_water_tower: { name: 'Water Tower', role: 'water_tower', era: { from: 1920, to: 1965, built: 1940 }, lot: [12.73, 12.73], building: [12.73, 12.73] },
  // an L-shaped 1950s-60s motor court: twelve rooms round a parking court, a pool and a neon pylon
  landmark_glb_roadside_motel: { name: 'Sundown Motel', role: 'motel', era: { from: 1950, to: 1969, built: 1957 }, lot: [47.18, 33.52], building: [36.0, 26.07] },
  // a brick-and-timber passenger depot with a clock tower and a 52 m platform behind; built 1900-35, stands 1950 on
  landmark_glb_premier_railroad_depot: { name: 'Union Depot', role: 'railroad_depot', era: { from: 1900, to: 1935, built: 1915 }, lot: [52.15, 23.59], building: [34.76, 12.42] },
  // a 1960s coin laundry with a butterfly roof and a pylon sign (an optional washer/dryer kit for the inside ships beside it)
  landmark_glb_coin_laundromat: { name: 'Suds & Spin Coin Laundry', role: 'laundromat', era: { from: 1960, to: 1975, built: 1964 }, lot: [18.62, 21.11], building: [9.93, 11.17] },
  // a 2010s-20s club in an old sawtooth-roofed factory, with a courtyard behind a street gantry
  landmark_glb_foundry_nightclub: { name: 'The Foundry', role: 'nightclub', era: { from: 2010, to: 2029, built: 2018 }, lot: [28.56, 29.8], building: [19.87, 18.0] },
  // the works The Foundry was made from, in its working years: a tin sawtooth roof, a tall brick chimney, the gate gantry;
  // its name painted on the brick in four variants (glbSites.ts FACTORY_NAMES; opens 1900-45, on the club's plot)
  landmark_glb_foundry_factory: { name: 'Hargrove Iron Works', role: 'factory', era: { from: 1900, to: 1945, built: 1938 }, lot: [28.56, 29.8], building: [19.87, 18.0] },
  // an early-1900s house converted to a funeral home: portico, covered side arrival lane, vaulted chapel wing, rear service bay.
  // era = the conversion window (drawn from the seed; it stays one to the present). The house was built before that (the
  // design's years 1910-35, from the seed: glbSites.ts houseBuilt, data only); before the conversion the plot is open ground
  // in the export, as the other models' plots are before they open. Its family name is painted per town (glbSites.ts FUNERAL_NAMES)
  landmark_glb_funeral_home: { name: 'Hollis & Sons Funeral Home', role: 'funeral_home', era: { from: 1935, to: 1960, built: 1948 }, lot: [26.38, 22.66], building: [21.74, 17.65] },
  // a four-bay strip centre (built 1972-82) in four states on ONE shell, lot, origin and door layout (glbSites.ts VIDEO_STATES): the video
  // store in its second bay (the 1984 state stands from the strip's building, until the store takes its second bay; the store opens
  // 1980-86, data), the store with a second bay, the store closing down, and the bay reused as a phone repair shop (era = the years
  // each state begins, drawn from the seed)
  landmark_glb_reel_time_video_1984: { name: 'Reel Time Video', role: 'video_store', era: { from: 1972, to: 1982, built: 1981 }, lot: [36.01, 28.56], building: [32.28, 11.17] },
  landmark_glb_reel_time_video_1996: { name: 'Reel Time Video', role: 'video_store', era: { from: 1990, to: 1997, built: 1996 }, lot: [36.01, 28.56], building: [32.28, 11.17] },
  landmark_glb_reel_time_video_2011: { name: 'Reel Time Video', role: 'video_store', era: { from: 2008, to: 2013, built: 2011 }, lot: [36.01, 28.56], building: [32.28, 11.17] },
  // the county seat's courthouse on its square (a Romanesque Revival courthouse of 1894 with a clock tower, its lawn, the
  // soldiers' memorial and the bandstand): four states on ONE shell, lot and origin, the whole 80 m civic block of a county
  // seat (glbSites.ts COURTHOUSE_STATES; era = the years each state stands from). Its plot is the block, not glbLandmarkTiles.
  landmark_glb_county_courthouse_1894: { name: 'County Courthouse', role: 'courthouse', era: { from: 1894, to: 1950, built: 1894 }, lot: [79.46, 79.46], building: [29.8, 29.8] },
  landmark_glb_county_courthouse_1962: { name: 'County Courthouse', role: 'courthouse', era: { from: 1951, to: 1975, built: 1962 }, lot: [79.46, 79.46], building: [29.8, 29.8] },
  landmark_glb_county_courthouse_1988: { name: 'County Courthouse', role: 'courthouse', era: { from: 1976, to: 1999, built: 1988 }, lot: [79.46, 79.46], building: [29.8, 29.8] },
  landmark_glb_county_courthouse_2008: { name: 'County Courthouse', role: 'courthouse', era: { from: 2000, to: 2040, built: 2008 }, lot: [79.46, 79.46], building: [29.8, 29.8] },
  // a 1950s-60s brick broadcast house with a glazed entrance bay, a RADIO blade sign and a lattice mast in a fenced yard
  // beside it; its call letters painted per town (glbSites.ts RADIO_NAMES)
  landmark_glb_radio_station: { name: 'Valley Radio', role: 'radio_station', era: { from: 1946, to: 1960, built: 1955 }, lot: [21.73, 17.69], building: [16.1, 15.13] },

  // ---- the intake models (docs/queue/INTAKE.md, LANDMARKS_GLB.md "The intake models"): lots are the .glb's bounds, `plot` the chain's biggest
  landmark_glb_public_library_1912: { name: "Public Library", role: 'library', era: { from: 1905, to: 1920, built: 1912 }, lot: [18.62, 27.31], building: [18.0, 13.97], plot: [23.28, 36.63] },
  landmark_glb_public_library_1970s: { name: "Public Library", role: 'library', era: { from: 1965, to: 2040, built: 1975 }, lot: [23.28, 36.63], building: [18.0, 13.97], plot: [23.28, 36.63] },
  landmark_glb_corner_drugstore_family: { name: "Corner Drugstore", role: 'drugstore', era: { from: 1920, to: 1980, built: 1950 }, lot: [15.75, 27.47], building: [11.8, 19.87], plot: [15.75, 27.47] },
  landmark_glb_corner_drugstore_retail: { name: "Corner Drugstore", role: 'drugstore', era: { from: 1974, to: 2010, built: 1990 }, lot: [15.75, 27.47], building: [11.8, 19.87], plot: [15.75, 27.47] },
  landmark_glb_corner_drugstore_closed: { name: "Corner Drugstore", role: 'drugstore', era: { from: 2004, to: 2040, built: 2012 }, lot: [15.75, 27.47], building: [11.8, 19.87], plot: [15.75, 27.47] },
  landmark_glb_corner_drugstore_cafe: { name: "Corner Drugstore", role: 'drugstore', era: { from: 2012, to: 2040, built: 2016 }, lot: [15.75, 27.47], building: [11.8, 19.87], plot: [15.75, 27.47] },
  landmark_glb_small_town_bank_1920s: { name: "Town State Bank", role: 'bank', era: { from: 1900, to: 1930, built: 1925 }, lot: [16.76, 23.63], building: [13.97, 18.0], plot: [26.23, 23.63] },
  landmark_glb_small_town_bank_drive_through: { name: "Town State Bank", role: 'bank', era: { from: 1965, to: 1985, built: 1975 }, lot: [26.23, 23.63], building: [13.97, 18.0], plot: [26.23, 23.63] },
  landmark_glb_brick_church: { name: "Town Church", role: 'church', era: { from: 1880, to: 2040, built: 1905 }, lot: [46.94, 54.7], building: [13.97, 44.23] },
  landmark_glb_country_church: { name: "Country Church", role: 'church', era: { from: 1870, to: 2040, built: 1900 }, lot: [31.04, 32.59], building: [9.0, 24.21] },
  landmark_glb_lincoln_high_school_1925: { name: "Lincoln High School", role: 'school', era: { from: 1925, to: 1958, built: 1925 }, lot: [232.8, 159.86], building: [139.68, 95.92], plot: [232.8, 159.86] },
  landmark_glb_lincoln_high_school_1955: { name: "Lincoln High School", role: 'school', era: { from: 1952, to: 1978, built: 1952 }, lot: [232.8, 159.86], building: [139.68, 95.92], plot: [232.8, 159.86] },
  landmark_glb_lincoln_high_school_1975: { name: "Lincoln High School", role: 'school', era: { from: 1972, to: 2003, built: 1972 }, lot: [232.8, 159.86], building: [139.68, 95.92], plot: [232.8, 159.86] },
  landmark_glb_lincoln_high_school_2000: { name: "Lincoln High School", role: 'school', era: { from: 1998, to: 2040, built: 1998 }, lot: [232.8, 159.86], building: [139.68, 95.92], plot: [232.8, 159.86] },
  landmark_glb_carhop_drive_in_original: { name: "Milo's Drive-In", role: 'carhop', era: { from: 1952, to: 1962, built: 1954 }, lot: [43.46, 34.46], building: [14.28, 10.24], plot: [43.46, 34.46] },
  landmark_glb_carhop_drive_in_closed: { name: "Milo's Drive-In", role: 'carhop', era: { from: 1976, to: 2012, built: 1995 }, lot: [43.46, 34.46], building: [14.28, 10.24], plot: [43.46, 34.46] },
  landmark_glb_carhop_drive_in_restored: { name: "Milo's Diner", role: 'carhop', era: { from: 2000, to: 2040, built: 2005 }, lot: [43.46, 34.46], building: [14.28, 10.24], plot: [43.46, 34.46] },
  landmark_glb_fire_station_1924: { name: "Fire Station", role: 'fire_station', era: { from: 1924, to: 1924, built: 1924 }, lot: [28.03, 25.45], building: [16.82, 15.27], plot: [28.03, 25.45] },
  landmark_glb_fire_station_1988: { name: "Fire Station", role: 'fire_station', era: { from: 1984, to: 1990, built: 1985 }, lot: [28.03, 25.45], building: [16.82, 15.27], plot: [28.03, 25.45] },
  landmark_glb_post_office_1938: { name: "Post Office", role: 'post_office', era: { from: 1938, to: 1938, built: 1938 }, lot: [26.7, 24.52], building: [16.02, 14.71], plot: [26.7, 24.52] },
  landmark_glb_post_office_1988: { name: "Post Office", role: 'post_office', era: { from: 1976, to: 2040, built: 1988 }, lot: [26.7, 24.52], building: [16.02, 14.71], plot: [26.7, 24.52] },
  landmark_glb_highway_rest_area: { name: "Pine Bend Rest Area", role: 'rest_area', era: { from: 1975, to: 2040, built: 1978 }, lot: [82.0, 66.0], building: [82, 66] },
  landmark_glb_lantern_pine_lodge: { name: "Lantern Pine Lodge", role: 'lodge', era: { from: 1930, to: 2040, built: 1935 }, lot: [34.0, 29.0], building: [34, 29] },
  landmark_glb_the_eyrie: { name: "The Eyrie", role: 'monument', era: { from: 1938, to: 2040, built: 1940 }, lot: [198.0, 160.0], building: [198, 160] },
  landmark_glb_union_arch: { name: "Union Arch", role: 'monument', era: { from: 1929, to: 2040, built: 1930 }, lot: [43.0, 36.0], building: [43, 36] },
  landmark_glb_night_keeper: { name: "The Night Keeper", role: 'roadside_attraction', era: { from: 1930, to: 2040, built: 1935 }, lot: [62.0, 50.0], building: [62, 50] },
  landmark_glb_beach_blvd_rex: { name: "Beach Blvd Rex", role: 'roadside_attraction', era: { from: 1964, to: 2040, built: 1964 }, lot: [9.6, 11.4], building: [4.86, 7.26] },
  landmark_glb_great_meridian_1926: { name: "The Great Meridian", role: 'monument', era: { from: 1926, to: 1940, built: 1926 }, lot: [119.96, 120.13], building: [119.97, 119.97], plot: [119.96, 120.13] },
  landmark_glb_great_meridian_1941: { name: "The Great Meridian", role: 'monument', era: { from: 1941, to: 1975, built: 1941 }, lot: [119.96, 120.13], building: [119.97, 119.97], plot: [119.96, 120.13] },
  landmark_glb_great_meridian_1976: { name: "The Great Meridian", role: 'monument', era: { from: 1976, to: 1999, built: 1976 }, lot: [119.96, 120.13], building: [119.97, 119.97], plot: [119.96, 120.13] },
  landmark_glb_great_meridian_2000: { name: "The Great Meridian", role: 'monument', era: { from: 2000, to: 2040, built: 2000 }, lot: [119.96, 120.13], building: [119.97, 119.97], plot: [119.96, 120.13] },

  // ---- the third intake (C001-C003, 2026-11-23): the five and dime (10 x 28 m, party walls: a street-wall building mid-block downtown; the dime store,
  // the variety store, Dollar Cove, then for lease), the Lark Bend grain elevator (timber 1925, the concrete bins added 1950, the co-op 1990: the rail
  // side, its siding along its back) and the Alder Bend Ballpark (wooden park 1935, lights 1950, steel bleachers 1960, aluminium 2000; its gravel lot on the street)
  landmark_glb_five_and_dime_1925: { name: "Marlow Dime", role: 'five_and_dime', era: { from: 1925, to: 1969, built: 1927 }, lot: [10.04, 31.36], building: [10, 28], plot: [10.04, 31.36] },
  landmark_glb_five_and_dime_1970: { name: "Marlow Variety", role: 'five_and_dime', era: { from: 1970, to: 1999, built: 1975 }, lot: [10.04, 31.36], building: [10, 28], plot: [10.04, 31.36] },
  landmark_glb_five_and_dime_2000: { name: "Dollar Cove", role: 'five_and_dime', era: { from: 2000, to: 2040, built: 2005 }, lot: [10.04, 31.36], building: [10, 28], plot: [10.04, 31.36] },
  landmark_glb_five_and_dime_closed: { name: "Marlow (for lease)", role: 'five_and_dime', era: { from: 2012, to: 2040, built: 2014 }, lot: [10.04, 31.36], building: [10, 28], plot: [10.04, 31.36] },
  landmark_glb_grain_elevator_1925: { name: "Lark Bend Elevator", role: 'grain_elevator', era: { from: 1925, to: 1949, built: 1930 }, lot: [48.0, 32.68], building: [48.0, 28.8], plot: [71.0, 32.8] },
  landmark_glb_grain_elevator_1950: { name: "Lark Bend Elevator", role: 'grain_elevator', era: { from: 1950, to: 1989, built: 1955 }, lot: [71.0, 32.8], building: [71, 28.8], plot: [71.0, 32.8] },
  landmark_glb_grain_elevator_1990: { name: "Lark Bend Co-op", role: 'grain_elevator', era: { from: 1990, to: 2040, built: 1995 }, lot: [71.0, 32.8], building: [71, 28.8], plot: [71.0, 32.8] },
  landmark_glb_baseball_park_1935: { name: "Alder Bend Ballpark", role: 'ballpark', era: { from: 1935, to: 1949, built: 1937 }, lot: [144.32, 155.16], building: [144.92, 155.52], plot: [144.32, 155.16] },
  landmark_glb_baseball_park_1950: { name: "Alder Bend Ballpark", role: 'ballpark', era: { from: 1950, to: 1959, built: 1955 }, lot: [144.32, 155.16], building: [144.92, 155.52], plot: [144.32, 155.16] },
  landmark_glb_baseball_park_1960: { name: "Alder Bend Ballpark", role: 'ballpark', era: { from: 1960, to: 1999, built: 1965 }, lot: [144.32, 155.16], building: [144.92, 155.52], plot: [144.32, 155.16] },
  landmark_glb_baseball_park_2000: { name: "Alder Bend Ballpark", role: 'ballpark', era: { from: 2000, to: 2040, built: 2005 }, lot: [144.32, 155.16], building: [144.92, 155.52], plot: [144.32, 155.16] },
  // ---- the fourth intake (C004-C006, 2026-11-23): Westmeadow Elementary (1955, then 1990 with the portables) and the Mill Branch one-room school (in use to the
  // 1950s, then abandoned); Mercer Barber and Lilac Beauty, two 6 x 18 m street-wall shops in three eras each (the pole spins); the Cedar Relay truck stop
  // (1960) and the Relay Travel Center (1990): 127 x 144 m of canopies, diner, 30 truck bays, scale and service bay, catalogued but not placed in the capital (its plan has no room for it)
  landmark_glb_mill_branch_active: { name: "Mill Branch School", role: 'elementary_school', era: { from: 1890, to: 1950, built: 1895 }, lot: [22.0, 30.0], building: [22, 30], plot: [22.0, 30.0] },
  landmark_glb_mill_branch_abandoned: { name: "Mill Branch School (abandoned)", role: 'elementary_school', era: { from: 1951, to: 2040, built: 1955 }, lot: [22.0, 30.0], building: [22, 30], plot: [22.0, 30.0] },
  landmark_glb_westmeadow_1955: { name: "Westmeadow Elementary", role: 'elementary_school', era: { from: 1955, to: 1989, built: 1957 }, lot: [85.0, 66.0], building: [85, 66], plot: [85.0, 66.0] },
  landmark_glb_westmeadow_1990: { name: "Westmeadow Elementary", role: 'elementary_school', era: { from: 1990, to: 2040, built: 1992 }, lot: [85.0, 66.0], building: [85, 66], plot: [85.0, 66.0] },
  landmark_glb_barber_1940: { name: "Mercer Barber", role: 'barber', era: { from: 1940, to: 1979, built: 1945 }, lot: [6.06, 22.1], building: [6, 18], plot: [6.06, 22.1] },
  landmark_glb_barber_1980: { name: "Mercer Hair Co.", role: 'barber', era: { from: 1980, to: 2009, built: 1985 }, lot: [6.04, 22.1], building: [6, 18], plot: [6.06, 22.1] },
  landmark_glb_barber_2010: { name: "Mercer Barber Co.", role: 'barber', era: { from: 2010, to: 2040, built: 2012 }, lot: [6.04, 22.1], building: [6, 18], plot: [6.06, 22.1] },
  landmark_glb_salon_1940: { name: "Lilac Beauty", role: 'salon', era: { from: 1940, to: 1979, built: 1945 }, lot: [6.06, 22.1], building: [6, 18], plot: [6.06, 22.1] },
  landmark_glb_salon_1980: { name: "Lilac Styling", role: 'salon', era: { from: 1980, to: 2009, built: 1985 }, lot: [6.04, 22.1], building: [6, 18], plot: [6.06, 22.1] },
  landmark_glb_salon_2010: { name: "Lilac Nail Room", role: 'salon', era: { from: 2010, to: 2040, built: 2012 }, lot: [6.04, 22.1], building: [6, 18], plot: [6.06, 22.1] },
  landmark_glb_truck_stop_1960: { name: "Cedar Relay", role: 'truck_stop', era: { from: 1960, to: 1989, built: 1962 }, lot: [127.0, 144.0], building: [127, 144], plot: [127.0, 144.0] },
  landmark_glb_truck_stop_1990: { name: "Relay Travel Center", role: 'truck_stop', era: { from: 1990, to: 2040, built: 1992 }, lot: [127.0, 144.0], building: [127, 144], plot: [127.0, 144.0] },
  landmark_glb_reel_time_video_2020: { name: 'Cell Fix', role: 'phone_repair', era: { from: 2009, to: 2015, built: 2013 }, lot: [36.01, 28.56], building: [32.28, 11.17] },
};
export const GLB_LANDMARK_KINDS = Object.keys(GLB_LANDMARKS) as GlbLandmarkKind[];

/** A plot tile is 1.24 m along the street (the art's scale) and 2.10 m back (the position scale): see STORES.md. */
export const TILE_ALONG_M = 1.24;
export const TILE_BACK_M = 2.1;

/** The plot a model needs, in tiles (width along the street, depth back). */
export function glbLandmarkTiles(kind: GlbLandmarkKind): [number, number] {
  const s = GLB_LANDMARKS[kind];
  const [w, d] = s.plot ?? s.lot;
  return [Math.ceil(w / TILE_ALONG_M), Math.ceil(d / TILE_BACK_M)];
}

/** The year a town's [kind] opens, drawn from its design years (deterministic in the seed). */
export function glbLandmarkOpenYear(seed: string, kind: GlbLandmarkKind): number {
  const { from, to } = GLB_LANDMARKS[kind].era;
  return from + Math.floor(rand(seed, 'glb-landmark-open', kind) * (to - from + 1));
}

/** What a gallon of regular cost (US dollars, nominal), by year: the engine's table (LandmarkKit.kt GasPrices.TABLE). */
export const GAS_PRICES: readonly [number, number][] = [
  [1935, 0.19], [1940, 0.18], [1945, 0.21], [1950, 0.27], [1955, 0.29], [1960, 0.31], [1965, 0.31], [1970, 0.36], [1973, 0.39],
  [1974, 0.53], [1975, 0.57], [1978, 0.63], [1979, 0.86], [1980, 1.19], [1981, 1.31], [1982, 1.22], [1985, 1.2], [1986, 0.93],
  [1990, 1.16], [1995, 1.15], [1998, 1.06], [2000, 1.51], [2003, 1.59], [2005, 2.3], [2006, 2.59], [2007, 2.8], [2008, 3.27],
  [2009, 2.35], [2010, 2.79], [2011, 3.53], [2012, 3.64], [2014, 3.36], [2015, 2.45], [2016, 2.14], [2018, 2.72], [2019, 2.6],
  [2020, 2.17], [2021, 3.01], [2022, 3.95], [2023, 3.52], [2024, 3.3], [2025, 3.15],
];

/** Regular in [year] (eased between the table's years; 3% a year past its end), for NPCs' small talk and the signs. */
export function gasPrice(year: number): number {
  const t = GAS_PRICES;
  if (year <= t[0][0]) return t[0][1];
  const last = t[t.length - 1];
  if (year >= last[0]) return last[1] * Math.pow(1.03, year - last[0]);
  let i = 0;
  while (t[i + 1][0] <= year) i++;
  const [y0, p0] = t[i]; const [y1, p1] = t[i + 1];
  return p0 + ((p1 - p0) * (year - y0)) / (y1 - y0);
}
