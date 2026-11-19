# Willow House Funeral Home -> the town's funeral home

`source/funeral-home.glb` is the modeller's file, copied unchanged from the modeller's branch with `git show` (prototypes/funeral-home/funeral-home.glb)
(its model-info.json is in the assets folder). `python3 make_variants.py` (in this folder) paints a town's family name on the two sign faces
(`sign_name`, `sign_name_back`: one shared atlas region [0,0,48,24]) and writes `funeral-home-<name>.glb` for six made-up surnames
(HOLLIS, PRUITT, MARLOW, WHELAN, ASHBY, KESSLER, each "& SONS" / "FUNERAL HOME") into
`app/src/main/assets/landmarks/funeral-home/`; only the sign's picture is replaced, the geometry is the modeller's.
`src/town/glbSites.ts` FUNERAL_FAMILIES lists the surnames in the same order as the catalogue's `variants`.
Shared swatch credit (LimeZu stucco, brick, slate, pavement) as in the model's licence note: Timerpg only.
