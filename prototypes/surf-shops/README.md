# Surf Shop Pair

- **Driftline Surf & Repair** is a 1960-onward boardwalk shop with teal weatherboards, a coral canvas awning, twin display windows, and a repair counter.
- **Tideglass Board Co.** is a 2008-onward corner shop with a rising roof, cedar fins, a broad front display, and glass wrapping onto the side sidewalk.

Both have open customer and service entrances, an interior floor and fixed counters, named collision boxes, and a removable `display_stock` under `lot`. The standing surfboards and bodyboards come from the Beach Day Prop Pack. Each shop comes as a self-contained GLB and a packed editable Blender file. All visible textures use Timerpg's 2×2 art pixels, 0.0388 m per texel and nearest/repeat sampling.

## Placement and glass

Models use glTF metres, Y up, +Z toward the street, and the front center at grade. The per-shop manifests have portal coordinates and collision boxes in the Blender coordinate format expected by the landmark loader. Window collisions are separate from the visible panes and leave the doors open.

The storefront has a genuine aperture, separate transparent overlay, and merchandise geometry behind it. Desktop glTF viewers blend the lightly tinted pane. The overlay's BODY-role material avoids the current landmark loader's opaque painted-room glass rule. Its alpha is authored so the loader's masked shader can cut out the pane and keep a few pixel highlights. The runtime storefront path and interaction still need in-game testing. Street and night lighting in the Blender renders are review context.

## Rebuild

The packed Blender source is self-contained. To regenerate, use Blender 4.3.2 with Pillow and a checkout of the Beach Day board props from [PR #35](https://github.com/LifemaxRPG/Timerpg/pull/35).

```sh
python make_art.py
BEACH_PACK_PROPS=/path/to/beach-prop-pack/props blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
for shop in driftline tideglass; do
  SURF_SHOP="$shop" blender -b --python-exit-code 1 --python render_model.py
done
python package_review.py
```
