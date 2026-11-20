"""Add real Timerpg buildings to the separate, same-scale street review."""
from pathlib import Path
import os
def place_reference(env,offset):
    path=Path(os.environ[env])
    before=set(bpy.data.objects);bpy.ops.import_scene.gltf(filepath=str(path))
    for ob in set(bpy.data.objects)-before:
        if ob.name.startswith('collision') or ob.get('collision_only'):ob.hide_render=True
        if not ob.parent:ob.location+=at(offset)
place_reference('KEEPER_REFERENCE_GLB',(-29,29,0))
if os.environ.get('KEEPER_LIBRARY_GLB'):
    place_reference('KEEPER_LIBRARY_GLB',(24,37,0))
shot('square',(-29,-72,6.8),(-1,2,14),39)
