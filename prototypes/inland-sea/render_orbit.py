"""Render a seven-second inspection orbit from the packed Blender file."""
from pathlib import Path
from math import pi,cos,sin
import bpy,sys
from mathutils import Vector
r=Path(__file__).resolve().parent
bpy.ops.wm.open_mainfile(filepath=str(r/'inland-sea.blend'))
sc=bpy.context.scene
cam=sc.camera
sc.frame_start=next((int(a.split("=")[1]) for a in sys.argv if a.startswith("--start=")),1)
sc.frame_end=next((int(a.split("=")[1]) for a in sys.argv if a.startswith("--through=")),84)
sc.render.fps=12
sc.render.resolution_x=600
sc.render.resolution_y=420
sc.render.resolution_percentage=100
sc.cycles.samples=5
sc.cycles.max_bounces=3
sc.render.use_persistent_data=True
cam.data.lens=42
sc.render.image_settings.file_format='PNG'
sc.render.filepath='/tmp/inland-orbit/frame-'
def pose(scene):
    angle=-1.34+2*pi*(scene.frame_current-1)/84
    cam.location=(163*cos(angle),50+163*sin(angle),36+14*(1+sin(angle))/2)
    cam.rotation_euler=(Vector((-2,50,31))-cam.location).to_track_quat('-Z','Y').to_euler()
bpy.app.handlers.frame_change_pre.append(pose)
pose(sc)
bpy.ops.render.render(animation=True)
