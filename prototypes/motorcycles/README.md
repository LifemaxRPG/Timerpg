# Timerpg Motorcycles

Three original road bikes with real wheel, steering, and kickstand pivots.

| Motorcycle | Era | Details |
| --- | --- | --- |
| Brackenton Quarry Twin | 1941–1949 | Heavy V-twin cruiser with deep valanced fenders, wire spokes, sprung rider saddle, pillion pad, and footboards |
| Marrowfield Standard 550 | 1971–1978 | Naked standard with a round headlamp, upright bars, twin rear shocks, and a striped fuel tank |
| Kelveran Slipstream | 2002–2008 | Faired sport bike with a raked windscreen, clip-ons, angled graphics, and raised pillion seat |

Each has near, mid and far GLBs and a packed editable Blender source for the near model.

## Game handoff

- glTF coordinates are in metres, with +Y up, +Z forward, +X the rider’s left, and the origin on the ground midway between axles. The wheel centerlines share a track of zero. Front and rear tyre widths and radii are listed separately.
- `handlebar` pivots at the steering head about the raked local axis in the manifest. The fork, `wheel_f` and grip anchors move with it. Both wheels spin around local +X. The older headlamps steer with the front assembly, and the sport bike’s headlamps are mounted to its fairing.
- `kickstand` is exported folded. Rotate it 72° in the negative direction about local +X to deploy it. Deployed upright contact was checked against the model plane. Balance and lean are runtime work.
- Use `seat_rider`, `seat_pillion`, grip, peg and dismount anchors for the mounting and riding poses. The manifest includes coarse chassis boxes and estimated mass and speed for the physics handoff.
- Body and secondary paint have independent PNG masks. The badge is independently replaceable on the near model. Head, tail, brake and turn lenses have individually named anchors with a dedicated emissive mask. Bind material instances to control them independently.
- Far models retain the fitment and control nodes and simplify their visible geometry. Riding, balance, pose transitions, lamp controls and Android performance remain integration work.

## Rebuild

With Blender 4.3, Pillow and NumPy, run from this directory.

```bash
python make_art.py
blender -b --python-exit-code 1 --python build_model.py
python validate_model.py
for bike in brackenton-1947 marrowfield-1974 kelveran-2005; do
  BIKE_MODEL="$bike" blender -b --python-exit-code 1 --python render_model.py
done
python package_review.py
```

`review.jpg` shows the exported bikes in Blender Cycles desktop renders. It includes a close rider-control view, the deployed kickstand and a 1.75 m preview mannequin.
