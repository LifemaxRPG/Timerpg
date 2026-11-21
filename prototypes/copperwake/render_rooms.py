"""Render the ten exported rooms from within each shell."""
from pathlib import Path
import os
import json
import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
manifest=json.loads((ROOT/"interiors"/"room-manifest.json").read_text())
for key,info in manifest.items():
    choice=os.environ.get("COPPERWAKE_ROOMS","").split(",")
    if choice!=[""] and key not in choice:
        continue
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=str(ROOT/info["file"]))
    scene=bpy.context.scene
    scene.render.engine="CYCLES"
    scene.cycles.samples=14
    scene.cycles.use_denoising=False
    scene.cycles.max_bounces=2
    scene.render.resolution_x=720
    scene.render.resolution_y=460
    scene.render.resolution_percentage=100
    scene.view_settings.view_transform="AgX"
    w,h,d=info["dimensions_m"]
    world=bpy.data.worlds.new("Room review ambient")
    world.use_nodes=True
    world.node_tree.nodes.get("Background").inputs[0].default_value=(.43,.50,.62,1)
    world.node_tree.nodes.get("Background").inputs[1].default_value=.38
    scene.world=world
    for index,y in enumerate((-d*.35,0,d*.35)):
        x=(-1)**index*w*.16
        data=bpy.data.lights.new("Desktop overhead fill","AREA")
        data.energy=0.83*w*d
        data.size=min(4,w*.34)
        data.color=(1,.85,.67)
        ob=bpy.data.objects.new("Desktop overhead fill",data)
        scene.collection.objects.link(ob)
        ob.location=(x,y,h-.355)
    bpy.ops.object.camera_add()
    cam=bpy.context.object
    scene.camera=cam
    cam.data.lens=20 if w>10 else 17
    if "bridge" in key:
        cam.location=(0,3.93,1.66)
        target=(0,-2.5,.99)
    elif "lounge" in key or "smoking" in key:
        cam.location=(-w*.29,d*.42,1.83)
        target=(2,-d*.25,1.12)
    elif "theatre" in key:
        cam.location=(0,-d*.44,2.20)
        target=(0,7,2.92)
    elif "atrium" in key or "staircase" in key:
        cam.location=(-w*.16,-d*.43,1.86)
        target=(w*.08,4,h*.40)
    else:
        cam.location=(-w*.20,-d*.41,1.83)
        target=(0,d*.18,min(2.2,h*.38))
    cam.rotation_euler=(Vector(target)-cam.location).to_track_quat("-Z","Y").to_euler()
    scene.render.filepath=str(ROOT/"renders"/f"room-{key}.png")
    bpy.ops.render.render(write_still=True)
