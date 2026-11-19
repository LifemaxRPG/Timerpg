# Courthouse: revision notes for Gemini

Good work. You delivered all four states, the far model (130 triangles), the interior over three floors, the moving clock
hands and bell, a separate glow mask, and a clean package, all within budget. Please revise the points below on the same
branch (`codex/county-courthouse`). Keep everything else: the same origin, the same door nodes, the same lot, the same
states.

1. **The building's shape.** From outside it reads as a squat, lumpy octagon under one heap of roof. The brief asks for
   a **cross plan**: a central block with **four entrance pavilions projecting about 4-5 m** on each side.
   - Each pavilion has **its own steep hipped or gabled roof** meeting the main roof in clear valleys, and gabled
     **dormers** on the main roof.
   - The tower should rise from the centre on a visible square base, not out of the roof pile.
   - The arched windows must read from the street: deep reveals (0.25-0.35 m), a stone arch ring with a keystone,
     and paired arches on the third floor.
   - Check the silhouette from the four corners and from street height. It should look like a Romanesque courthouse
     at a glance, not an octagon.
2. **The memorial.** It reads as a dark obelisk or a little lighthouse. Make it a **stepped granite base, a short
   column or pedestal, and a soldier standing on top**: a simple low-poly figure (about 1.8 m, cap and rifle at rest)
   in weathered bronze-green with pixel shading. Add a plain inscription panel on the base ("1861 - 1865"), with no
   names.
3. **The main entrance.**
   - The "RESTORED 2004" plaque is the size of a door. Make it a small bronze plaque, about 0.6 x 0.45 m, beside the
     door at eye height.
   - Make the 1894 datestone a single stone in the wall, about 0.8 x 0.4 m.
   - Leave the doorway **open**: no closed panel or box filling it. The game puts "Go inside" there.
   - Keep the carved "HARLAN COUNTY COURTHOUSE" over the arch, but cut into the stone band, not on a raised sign
     plate.
4. **Glow.** 1894 has about 58 m² of glowing surface, which is far too much.
   - Only the lamp heads (gas lamps about 0.3 m each), the four clock dials, and lit window glass at night should
     glow.
   - Check what else is in `glow_lamps_mask.png` (whole lamp posts, the bandstand, steps?) and take it out.
   - Aim for under 15 m² outside of the window glass.
5. **The stair hall.** The view up to the skylight is a confusing tangle of beams.
   - Make it read as **a staircase with a landing** going up to **a gallery with a railing** on floors 2 and 3,
     round an open well, under **one clear skylight** (a simple framed grid of glass).
   - Remove beams that cross the well.
   - Use the same plaster, wainscot and wood as the courtroom so the interior feels like one building.

**Deliver:**
- The same files.
- An updated review sheet showing:
  - the same four rows;
  - a new row of street-height views from each corner (to judge the cross plan);
  - close-ups of the main entrance, the memorial and the stair hall.
- A short note in the README listing what changed.
