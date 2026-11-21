# MV Copperwake: revision notes

Strong start. Keep:
- the length (309 m) and the navy hull with copper funnels;
- the terraced stern;
- the walkable promenade under the lifeboats;
- the matte pixel-grid textures;
- the twelve stair runs and boarding points;
- the 19.5k-triangle budget;
- testing in desktop Filament.

The problem now is that it reads **empty and generic**: big bare teak planes, a flat repeating cabin strip, an empty lounge
box and dark nights. A ship people want to travel on should look alive from far away and feel full of places up
close. Revise on `codex/copperwake`, with the same origin, axes and file name.

## 1. The hull: give it a face

- **Name:** "COPPERWAKE" in raised white letters on both sides of the bow (about 1.6 m tall), and on the stern with
  a made-up home port beneath it ("COPPERWAKE / PORT MERIDIAN").
- **Plates and paint:**
  - hull plating seams as pixel lines every 2.4 m;
  - a band of slightly darker navy at the waterline;
  - a **red antifouling boot-top** below the waterline, about 1.5 m showing above the water;
  - white draft marks at the bow and stern;
  - a thin gold or copper sheer line just below the first deck.
- **Bow:**
  - an anchor in its hawse pocket on each side;
  - a bulbous-bow hint below the waterline;
  - bow thruster markings ("⊕" with arrows);
  - a mooring deck at the very front with winches, bollards and chain pipes as low geometry.
- **Portholes:** round portholes along the lower hull (crew decks), in rows, as texture, some lit at night.
- **Stern:** mooring winches and bollards on the terraced decks, and the flag staff with a made-up company flag.

## 2. The cabin decks: no more flat strips

- **The balcony face:** in the texture, each bay shows a glass balcony rail, the cabin's sliding door, a curtain
  colour that varies (cream, blue, grey), two balcony chairs as pixels, and white dividers between bays. Every few
  bays has a different curtain or an open door, so the face doesn't read as one stamped pattern.
- **Shape:** step the superstructure in **two or three setbacks** along the length (not one straight wall), with the
  lifeboat deck tucked under the first overhang as it is now.
- **The forward face** below the bridge: a sloped glass observation lounge front (several decks of angled windows),
  the signature look of a cruise ship's front.

## 3. The funnels: they read as brick chimneys

- Make them **painted steel**:
  - smooth copper-orange with vertical shading steps;
  - a **black top band** with soot;
  - a rounded aerodynamic cowl or a flared top;
  - a company emblem on each side (a made-up copper wave mark, no real line).
- Lean them slightly aft (5-8°).
- A short mast between or ahead of them, with a radar dome, radar arrays and a horn.

## 4. The decks: fill them with life

- **Pool deck:**
  - the main pool plus a kids' splash pool with a little slide;
  - two hot tubs;
  - rows of loungers (instanced), umbrellas, a towel stand;
  - a poolside bar with stools under a canopy;
  - a **big outdoor movie screen** on the funnel casing facing the pool;
  - glass windbreaks along the sides.
- **Upper deck forward:** a running track painted round the deck (lane lines), a mini-golf corner, shuffleboard
  courts, a basketball cage and deck chairs.
- **Aft:** a tiered terrace bar on the stern steps with tables, and a hot tub at the very stern.
- **Plants:** planters with palm trees as `tree_*` nodes or simple low-poly palms.
- **The promenade:** deck chairs facing the sea, life-ring stations every 20 m, and benches.

## 5. Interiors: a few great rooms instead of one empty box

Build these as clean shells with **fixed fittings** that define them; the game adds loose furniture:
1. **Grand atrium** (amidships, three decks high): a sweeping double staircase, glass lifts, gallery railings on each
   level, a reception desk, and a chandelier (glow only on its bulbs).
2. **Main dining room** (aft, two decks high): a raised centre ceiling, big windows to the wake, and a captain's table
   platform.
3. **Theatre** (forward): raked seating as stepped floor, a stage with curtains, and balcony rows.
4. **The bridge:** a wraparound window console, the helm, radar screens (glow), and the bridge wings with
   repeaters.
5. **Observation lounge** (the existing one): put the sloped glass front from section 2 here, with a bar along one
   side and tiered seating facing forward. Replace the noisy flat floor texture with carpet in a pattern.
6. **Stateroom** (keep it), plus one **suite** with a big balcony.

Mark every room's floor node, doors and stairs. Interior collision: walls, stairs as stepped boxes, railings.

## 6. Night: cruise ships glow

- **Windows:** most cabin balconies' doors glow warm, in a varied pattern (some dark). Use a separate emission mask
  that covers only the glass.
- **Deck lights:**
  - string lights over the pool;
  - lit pool water (a soft glow on the pool floor);
  - the funnel and the emblem floodlit (as `flood_*` nodes);
  - the movie screen showing a pixel image.
- **Navigation lights:**
  - a red port light;
  - a green starboard light;
  - a white masthead light;
  - a white stern light;
  - a top mast light.
- **Budget:** keep the glowing area reasonable by making only glass and lamp heads glow, not whole walls.

## 7. Eras: give us the ocean liner too

Ocean travel in our timeline starts long before modern cruise ships. Add a second ship in the same company's colours:
**SS Copperwake (1952)**, a classic ocean liner about 260 m long:
- a black hull with a white superstructure;
- **three raked copper funnels** with black tops;
- long open teak promenade decks with rows of portholes and square windows;
- lifeboats on davits along the boat deck;
- a cruiser stern;
- a sharp raked bow with the name in white;
- two masts;
- a small outdoor pool aft.

Inside: a grand staircase with wood panelling, a first-class dining saloon and a smoking room. Same rules and
budget. Then states:
- **SS Copperwake 1952-1978:** in service; a 1979+ state "laid up", with rust streaks and covered funnels, or a
  floating hotel at a pier;
- **MV Copperwake (modern), 1996+.**

## 8. Distance models

- `copperwake-mid.glb` (3,000-5,000 triangles): the hull, decks as blocks, the funnels and the mast, with lit
  windows as texture.
- `copperwake-far.glb` (under 400): the hull, superstructure steps, funnels and mast. It must read as a cruise ship
  from the shore at 32 px.
- The same for the 1952 liner.

## 9. Technical

- **Triangles:** up to 30,000 per ship (it's a vehicle and a destination). Instance loungers, lifeboats, railing posts
  and windows.
- **Materials:** at most 12 per ship.
- **Collision:** in glTF metres. Make boxes for:
  - the outer hull walls;
  - every railing line;
  - the funnels and the mast;
  - pools (as blockers at their edges, with the water as a floor below);
  - interior walls;
  - stairs as stepped boxes.

  List them all in model-info.json.
- **Anchors in model-info.json** for: the gangways (port and starboard), the pier mooring lines (bow and stern), the
  captain's spawn on the bridge, and a camera point for the "arriving in port" view.
- **Lettering:** made-up names only. No real cruise lines, colours or logos.
- **Review sheet:** big panels:
  - both ships by day and night from the side at sea level;
  - from a pier;
  - the bow name;
  - the funnels close up;
  - the pool deck at person height;
  - each interior room;
  - the bridge;
  - near, mid and far models side by side with black silhouettes at 128, 64 and 32 px.
