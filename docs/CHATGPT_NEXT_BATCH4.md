# ChatGPT: batch 4 (and two follow-ups)

Follow docs/CHATGPT_MODEL_GUIDE.md. The library, drugstore and bank were excellent: keep that standard.
- Open exterior doorways.
- Pane-only emission masks.
- One restrained sign, in a documented region the game can repaint per town.
- Collision in glTF metres.
- Separate .glb files per state, sharing one shell, origin and doors.

Make one branch per model, `codex/<name>`.

## Follow-ups first

1. **The brick church** from batch 3 hasn't arrived. Please finish it, with the white country-church variant.
2. **Revise Lincoln High School** (`codex/lincoln-high-school`):
   - The 1925 block needs to read as a school, not an apartment block:
     - a taller central entrance tower with a stone surround and steps;
     - rows of tall paired classroom windows with stone sills;
     - a stone cornice and a parapet with the name;
     - more brick detail.
   - The campus is mostly bare green from above. Add:
     - paths from the entrance to the street, the bus loop, the gym and the field;
     - trees as `tree_*` nodes;
     - fences with gates round the fields;
     - a concession stand and ticket booth at the field;
     - the parking lots;
     - bike racks.
   - Keep the four states, interiors and budgets.

## Batch 4: five new models

### 1. Car dealership through the decades
One lot about 40 x 45 m. Cars are not part of the model: the game parks its own, so leave marked spaces and display pads.

**States:**
- **1950-1969:** a showroom with a curved glass corner and a tall pylon sign, a service garage with two bays behind, and a small used-car lot with pennant strings.
- **1970-1999:** a flat-roofed brick-and-glass showroom refaced with a big fascia sign, and a bigger used lot with light poles.
- **2000+:** either a glassy modern showroom with an entry portal, or the closed dealership reused as a used-car lot with a small sales trailer.

Use one made-up name, "<SURNAME> MOTORS", and no real car brands.

### 2. Carhop drive-in burger stand
A 1950s roadside stand: a low building with a wide flat canopy over the parking bays, menu-and-speaker posts at each bay, and a tall sign with an arrow.

**States:**
- **1952-1975:** carhop service.
- **1976-1999:** walk-up windows and picnic tables, with the canopy kept.
- **2000+:** closed and faded, or restored as a nostalgic diner.

Use a made-up name, "<NAME>'S DRIVE-IN". No chain trade dress.

### 3. Main Street hardware store
A two-storey brick store of about 1905, about 9 x 30 m, which fits in a street wall: party walls on both sides, with a finished back.
- A recessed entrance between big display windows, a painted name band, and upstairs windows.
- A back loading door and a side yard for lumber.
- **Interior:** a long sales floor with a wooden counter and a wall of small-parts drawers as fixed geometry.

**States:**
- **1905-1979:** the family store.
- **1980-2009:** "TRUE VALUE"-style co-op signage, made up and not a real brand.
- **2010+:** an antique mall or an empty storefront "FOR LEASE".

### 4. Veterans' hall / lodge
A one-storey 1920s-50s brick hall about 15 x 25 m: a gabled front with a plain portico, a flagpole and a small memorial stone with a decommissioned cannon or a plain obelisk.

**Interior:** one big hall with a stage at one end, a kitchen and a bar room off the side.

Use a made-up post name and number on a sign board: "POST 214". No real organisation names or emblems.

### 5. Town swimming pool and park pavilion
A city park block corner, about 50 x 40 m:
- a 1950s outdoor pool with a diving board, a lifeguard chair and a fence;
- a bathhouse with changing rooms and a snack window;
- an open-sided picnic pavilion with tables, separate within the lot.

**States:**
- **1950-1989:** open.
- **1990-2009:** a newer splash slide added.
- **2010+:** the pool filled in as a splash pad, with the bathhouse kept.

The water uses a `water_pool` material at y 0, with the basin below.

## For each model
- Under the guide's budgets.
- model-info.json listing the states and years, and the sign region.
- One review sheet: per state, day, night, back and above; plus person-height views of the entrance and one interior.
- A short README.

No zips, pycache or duplicate images.
