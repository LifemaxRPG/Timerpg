"""Render the shipped GLBs at actual scale and as a labeled-catalog source."""
from pathlib import Path
import json
import os
from math import radians, sin

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
DATA=json.loads((ROOT/"model-info.json").read_text())["props"]

bpy.ops.wm.read_factory_settings(use_empty=True)
scene=bpy.context.scene
scene.render.engine="CYCLES"
scene.cycles.samples=12
scene.cycles.use_denoising=False
scene.cycles.max_bounces=2
scene.view_settings.view_transform="AgX"
scene.view_settings.look="AgX - Medium High Contrast"
scene.render.image_settings.file_format="PNG"
scene.render.image_settings.color_mode="RGBA"
world=bpy.data.worlds.new("Coastal sky")
world.use_nodes=True
world.node_tree.nodes["Background"].inputs[0].default_value=(.66,.77,.84,1)
world.node_tree.nodes["Background"].inputs[1].default_value=.61
scene.world=world

bpy.ops.object.light_add(type="SUN")
sun=bpy.context.object
sun.rotation_euler=Vector((.59,.29,-.70)).to_track_quat("-Z","Y").to_euler()
sun.data.energy=2.7
sun.data.color=(1,.90,.76)
sun.data.angle=.14
bpy.ops.object.camera_add()
camera=bpy.context.object
scene.camera=camera
camera.data.type="ORTHO"
camera.data.clip_end=600

def point_camera(eye, target, scale):
    camera.location=eye
    camera.rotation_euler=(Vector(target)-camera.location).to_track_quat("-Z","Y").to_euler()
    camera.data.ortho_scale=scale

def load(entry):
    before=set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=str(ROOT/entry["file"]))
    parts=set(bpy.data.objects)-before
    root=next(o for o in parts if o.parent not in parts)
    root.name="Preview "+entry["id"]
    return root,parts

def srgb(rgb):
    return tuple(x/12.92 if x<=.04045 else ((x+.055)/1.055)**2.4 for x in [c/255 for c in rgb])

def surface(name, shades, scale):
    mat=bpy.data.materials.new(name)
    mat.use_nodes=True
    nodes,links=mat.node_tree.nodes,mat.node_tree.links
    bsdf=nodes["Principled BSDF"]
    bsdf.inputs["Roughness"].default_value=.97
    if len(shades)==1:
        bsdf.inputs["Base Color"].default_value=(*srgb(shades[0]),1)
        return mat
    pos=nodes.new("ShaderNodeNewGeometry")
    size=nodes.new("ShaderNodeVectorMath")
    size.operation="MULTIPLY"
    size.inputs[1].default_value=scale
    pix=nodes.new("ShaderNodeVectorMath")
    pix.operation="FLOOR"
    noise=nodes.new("ShaderNodeTexWhiteNoise")
    ramp=nodes.new("ShaderNodeValToRGB")
    ramp.color_ramp.interpolation="CONSTANT"
    stops=ramp.color_ramp.elements
    stops[0].color=(*srgb(shades[0]),1)
    stops[-1].position=.79
    stops[-1].color=(*srgb(shades[-1]),1)
    for i,color in enumerate(shades[1:-1],1):
        stops.new(i/len(shades)).color=(*srgb(color),1)
    links.new(pos.outputs["Position"],size.inputs[0])
    links.new(size.outputs[0],pix.inputs[0])
    links.new(pix.outputs[0],noise.inputs[0])
    links.new(noise.outputs["Value"],ramp.inputs[0])
    links.new(ramp.outputs[0],bsdf.inputs["Base Color"])
    return mat

def polygon(name,coords,faces,mat):
    mesh=bpy.data.meshes.new(name)
    mesh.from_pydata(coords,[],faces)
    obj=bpy.data.objects.new(name,mesh)
    scene.collection.objects.link(obj)
    mesh.materials.append(mat)

def backdrop():
    sand=surface("Beach review sand",[(212,187,140),(217,193,148),(221,197,155),(228,202,155)],(12.89,12.89,12.89))
    damp=surface("Damp edge",[(189,160,113),(194,171,124),(205,181,133)],(12.89,12.89,12.89))
    water=surface("Blue-green shallows",[(88,152,147),(93,161,152),(108,176,163),(119,182,164)],(6.44,12.9,12.89))
    foam=surface("Shore lace",[(175,199,170),(185,205,181),(208,213,185)],(12.89,12.89,12.89))
    start=-35
    xs=[start+i*.19 for i in range(370)]
    border=[3.94+.24*sin(x*.58)+.11*sin(x*1.63) for x in xs]
    def strip(name,a,b,z,mat):
        coords=[(x,y,z) for x,l,h in zip(xs,a,b) for y in (l,h)]
        faces=[(2*i,2*i+2,2*i+3,2*i+1) for i in range(len(xs)-1)]
        polygon(name,coords,faces,mat)
    strip("Beach floor",[-100]*len(xs),[y-.33 for y in border],-.012,sand)
    strip("Tide line",[y-.331 for y in border],[y+.018 for y in border],-.012,damp)
    strip("Review water",border,[150]*len(xs),-.013,water)
    widths=[.065+.05*sin(x*3.4)+.025*sin(x*7) for x in xs]
    strip("Wave crest",[y+.04 for y in border],[y+.09+max(.022,w) for y,w in zip(border,widths)],-.009,foam)

layout={
 "umbrella-coral":(-2.14,.10,10),"umbrella-teal":(1.78,.54,-8),
 "towel-coral":(-2.92,-.88,-16),"towel-teal":(2.66,-.26,16),
 "surf-longboard":(4.03,-.72,-24),"surf-fish":(3.41,-1.08,-21),"surf-shortboard":(2.81,-1.73,-31),
 "bodyboard":(3.23,.96,31),"ball-beach":(.76,-1.43,11),"ball-volleyball":(-1.18,2.13,0),
 "bucket":(-.61,-1.8,12),"shovel":(-.58,-2.16,-73),"rake":(-.27,-1.86,48),"star-mold":(-.12,-2.2,20),
 "sandcastle":(-1.39,-2.32,-20),"flying-disc":(.43,-2.18,0),
 "cooler":(-1.45,-.41,-23),"tote":(-2.54,-1.58,5),
 "folding-chair":(-3.33,.15,-33),"lounger":(1.08,-.34,-11),
 "swim-ring":(-.49,3.64,12),"air-mattress":(2.75,4.85,-42),
 "flip-flops":(2.16,-1.23,12),"volleyball-net":(-.17,2.53,6),
}
view=os.environ.get("BEACH_VIEW","day")
if view=="catalog":
    point_camera((6,-10,7),(0,0,0),18)
    right=camera.rotation_euler.to_matrix()@Vector((1,0,0))
    up=camera.rotation_euler.to_matrix()@Vector((0,1,0))
    bpy.context.view_layer.update()
    for i,entry in enumerate(DATA):
        obj,parts=load(entry)
        corners=[part.matrix_world@v.co for part in parts if part.type=="MESH" for v in part.data.vertices]
        us=[right.dot(p) for p in corners]
        vs=[up.dot(p) for p in corners]
        scale=min(2.17/(max(us)-min(us)),1.57/(max(vs)-min(vs)))
        x=((i%6)-2.5)*3
        y=(1.5-i//6)*2.58+.23
        obj.scale=(scale,)*3
        obj.location=right*(x-scale*(max(us)+min(us))/2)+up*(y-scale*(max(vs)+min(vs))/2)
    scene.render.film_transparent=True
    scene.render.resolution_x=1680
    scene.render.resolution_y=964
    scene.cycles.samples=14
else:
    backdrop()
    for entry in DATA:
        obj,_=load(entry)
        x,y,yaw=layout[entry["id"]]
        obj.location=(x,y,0)
        obj.rotation_euler.z=radians(yaw)
    scene.render.resolution_x=1440 if view=="day" else 1200
    scene.render.resolution_y=1020 if view=="day" else 830
    views={
        "day":((6.27,-8.3,7.43),(.05,.97,.03),12.15),
        "toys":((1.58,-4.94,3.20),(-1.15,-1.25,.27),5.0),
        "surf":((6.2,-4.55,4.05),(2.4,-.13,.44),5.5),
    }
    point_camera(*views[view])

scene.render.filepath=str(ROOT/"renders"/f"{view}.png")
bpy.ops.render.render(write_still=True)
