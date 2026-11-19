"""Reframe the two close views from the saved daylight scene."""
import bpy
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;scene=bpy.context.scene;cam=scene.camera;cam.data.type='PERSP';cam.data.lens=32
lights=[(d,d.energy/.12) for d in bpy.data.lights if d.type=='AREA'];sun=next(d for d in bpy.data.lights if d.type=='SUN')
bg=scene.world.node_tree.nodes.get('Background');glow=bpy.data.materials['glow_period_signs_and_lamps'].node_tree.nodes.get('Principled BSDF')
for name,pos,target,night in [('05-person-height',(18,-26,1.7),(0,1,2.2),True),('06-mechanical-pumps',(-1,-11.5,2.1),(-5,-5,1.65),False)]:
    cam.location=pos;cam.rotation_euler=(Vector(target)-cam.location).to_track_quat('-Z','Y').to_euler()
    bg.inputs[0].default_value=(*((.12,.18,.28) if night else (.64,.71,.75)),1);bg.inputs[1].default_value=.35 if night else .65
    sun.energy=.35 if night else 2;sun.color=(.5,.65,1) if night else (1,.88,.72)
    for d,p in lights:d.energy=p*(1 if night else .12)
    glow.inputs['Emission Strength'].default_value=1 if night else .12
    scene.render.filepath=str(R/'renders'/(name+'.png'));bpy.ops.render.render(write_still=True)
