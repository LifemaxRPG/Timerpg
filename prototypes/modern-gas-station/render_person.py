"""Reframe the saved scene at 1.7 m without rebuilding the other previews."""
from pathlib import Path
import bpy
from mathutils import Vector
R=Path(__file__).resolve().parent
scene=bpy.context.scene;cam=scene.camera;cam.data.type='PERSP';cam.data.lens=30
cam.location=(-18,-38,1.7);cam.rotation_euler=(Vector((0,-5,2.5))-cam.location).to_track_quat('-Z','Y').to_euler()
bg=scene.world.node_tree.nodes.get('Background');bg.inputs[0].default_value=(.12,.18,.29,1);bg.inputs[1].default_value=.35
for light in bpy.data.lights:
    if light.type=='SUN':light.energy=.38;light.color=(.48,.62,1)
    elif light.type=='AREA':light.energy/= .12
for name in ('glow_station_signs_and_lamps','sign_price'):
    bpy.data.materials[name].node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=1
scene.render.filepath=str(R/'renders/05-person-height.png')
bpy.ops.render.render(write_still=True)
