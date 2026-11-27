"""Render actual near and distance GLBs for the collection's visual catalogue."""
import json
import os
from pathlib import Path

import bpy
from mathutils import Vector as V

ROOT=Path(__file__).resolve().parent
coconut=Path(os.environ.get("COCONUT_GLB","/workspace/work/timerpg-coastal-palm/prototypes/coastal-palm/coastal-palm.glb"))
records=json.loads((ROOT/"model-info.json").read_text())["types"]
views=[(r["id"],r["near"]) for r in records]
views.extend((r["id"]+"-mid",r["mid"]) for r in records if r["id"] in ("royal","california-fan","mediterranean-fan","areca"))
if coconut.is_file():
    views.append(("coconut",{"file":str(coconut),"min_gltf_m":[-2.32,0,-4.54],"max_gltf_m":[5.53,9.92,3.87]}))


def matte(name,c):
    m=bpy.data.materials.new(name)
    m.diffuse_color=(*c,1)
    return m


def block(name,p,d,material):
    bpy.ops.mesh.primitive_cube_add(size=1,location=p)
    ob=bpy.context.object
    ob.name=name
    ob.dimensions=d
    ob.data.materials.append(material)


for key,data in views:
    requested=os.environ.get("PALM_VIEWS","")
    if requested and key not in requested.split(","):
        continue
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(ROOT/data["file"]))
    scene=bpy.context.scene
    scene.render.engine="CYCLES"
    scene.cycles.samples=10
    scene.cycles.use_denoising=False
    scene.cycles.max_bounces=2
    scene.render.resolution_x=500
    scene.render.resolution_y=560
    scene.view_settings.view_transform="AgX"
    world=bpy.data.worlds.new("Soft daylight")
    world.use_nodes=True
    scene.world=world
    bg=world.node_tree.nodes.get("Background")
    bg.inputs[0].default_value=(.67,.78,.89,1)
    bg.inputs[1].default_value=.84
    bpy.ops.mesh.primitive_plane_add(size=600,location=(0,0,-.023))
    bpy.context.object.data.materials.append(matte("Sand and pavement",(.29,.302,.24)))
    dark=matte("Scale navy",(.12,.17,.20))
    ochre=matte("Scale coat",(.32,.16,.08))
    skin=matte("Scale person",(.45,.30,.17))
    a,b=data["min_gltf_m"],data["max_gltf_m"]
    height=b[1]
    radius=max((b[0]-a[0])/2,(b[2]-a[2])/2)
    px=-min(3.55,radius*.80+.26)
    py=-min(2.8,radius*.68)
    for dx in (-.088,.088):
        block("Scale legs",(px+dx,py,.395),(.14,.20,.79),dark)
    block("Scale torso",(px,py,1.056),(.36,.23,.52),ochre)
    block("Scale head",(px,py,1.47),(.22,.21,.31),skin)
    bpy.ops.object.light_add(type="SUN")
    sun=bpy.context.object
    sun.rotation_euler=V((.54,.38,-.73)).to_track_quat("-Z","Y").to_euler()
    sun.data.energy=2.16
    sun.data.color=(1,.92,.78)
    sun.data.angle=.16
    bpy.ops.object.camera_add()
    cam=bpy.context.object
    scene.camera=cam
    cam.data.type="ORTHO"
    cam.data.ortho_scale=max(height*1.18,(radius*2+.48)*1.18)
    focus=V(((a[0]+b[0])/2,-(a[2]+b[2])/2,height*.49))
    cam.location=focus+V((-5.7,-9.6,2.18))
    cam.rotation_euler=(focus-cam.location).to_track_quat("-Z","Y").to_euler()
    scene.render.filepath=str(ROOT/"renders"/f"{key}.png")
    bpy.ops.render.render(write_still=True)
