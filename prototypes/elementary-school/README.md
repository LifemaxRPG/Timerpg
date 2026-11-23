# Mill Branch School and Westmeadow Elementary

Two original schools for Timerpg with separate state assets. Each state comes with a near, mid and far GLB and a packed Blender source for the near view.

| GLB prefix | Years and state |
| --- | --- |
| mill-branch-active | About 1890–1950, in use |
| mill-branch-abandoned | After the school closes, with peeling clapboard, broken and boarded sashes, roof openings, missing bell and unlit windows |
| westmeadow-1955 | 1955–1989, brick campus with a tar-and-gravel roof, steel playground and dirt ball field |
| westmeadow-1990 | 1990 onward, projecting canopy, marquee, patched roof, fenced playground and two portable classrooms |

Mill Branch is a one-room white clapboard school with an open belfry, woodstove, slate, pair of outhouses and a hand pump. Westmeadow has ribbon windows, a classroom wing and a gym-lunchroom with a fixed service counter and half court. The campus lot has a bus loop, a flagpole, bicycle racks, swings, a slide, overhead bars and a four-square court. The later campus has a locker-lined hallway, classroom vision panes and a molded play tower beside the retained swings.

## Put the schools in town

- glTF metres, +Y up and +Z toward the street. Each family keeps its own grade origin on the middle of its street-facing entrance across states. Reserve about 22 × 30 m for Mill Branch or 85 × 66 m for Westmeadow. The Westmeadow lot is offset to the east for its playground. Preserve access paving if furnishing a replacement lot.
- `model-info.json` lists door positions, extents, repaintable signs, and box colliders for each LOD. The existing door nodes are shared within each family. The 1990 campus adds two distinct portable entry nodes. The abandoned school retains the two existing open apertures. The game can disable their interaction based on the closure state.
- The outdoor props, paving and turf are under `lot`. Portables are under `portables`. The outhouses are closed visual props. The playground equipment has conservative enclosing colliders. The roof and bell are visual features. Gate control, bell playback, swing motion, bus routing, school simulation and Android integration remain engine work.
- Change town names using the `sign_name` atlas rectangles and preserve nearest sampling and the pixel margin. Emission masks illuminate the panes only. The abandoned state has an all-black pane mask.

## Rebuild

Run with Blender 4.3 and Python with Pillow and NumPy.

```bash
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
for state in mill-branch-active mill-branch-abandoned westmeadow-1955 westmeadow-1990; do
  SCHOOL_STATE="$state" blender -b --python-exit-code 1 --python render_model.py
done
python package_review.py
```

`review.jpg` compares all four states at day and night, from the back and above, at the entrance and schoolyard, and inside. The previews are Blender Cycles desktop renders.
