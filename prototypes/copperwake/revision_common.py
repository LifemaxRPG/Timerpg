"""Reusable helpers for both Copperwake vessels and interior rooms."""
from pathlib import Path
import bpy
from sculpture_tools import T, box

ROOT=Path(__file__).resolve().parent


def emitter(material, filename, strength):
    nodes=material.node_tree.nodes
    bsdf=nodes.get("Principled BSDF")
    tex=nodes.new("ShaderNodeTexImage")
    tex.image=bpy.data.images.load(str(ROOT/"textures"/f"{filename}.png"),check_existing=True)
    tex.image.pack()
    tex.interpolation="Closest"
    tex.extension="REPEAT"
    material.node_tree.links.new(tex.outputs["Color"],bsdf.inputs["Emission Color"])
    bsdf.inputs["Emission Strength"].default_value=strength


def export_glb(path, objects=None):
    if objects is not None:
        bpy.ops.object.select_all(action="DESELECT")
        for ob in objects:
            ob.select_set(True)
    bpy.ops.export_scene.gltf(filepath=str(path),export_format="GLB",
                              export_extras=True,use_selection=objects is not None)


def save_source(path):
    bpy.ops.file.pack_all()
    bpy.context.preferences.filepaths.save_version=0
    bpy.ops.wm.save_as_mainfile(filepath=str(path))


def collider(name, group, center, size):
    x,y,z=center
    w,d,h=size
    return {"name":name,"group":group,"center":[x,z,-y],"size":[w,h,d]}


def marker(parent,name,position,outward=(0,0,1)):
    from sculpture_tools import group
    ob=group(name,parent)
    x,y,z=position
    ob.location=position
    ob["outward_gltf"]=outward
    return {"node":ob.name,"position_gltf_m":[x,z,-y],"outward_gltf":outward}


def batch(parent):
    bins={}
    for ob in parent.children:
        if ob.type=="MESH" and not ob.get("keep") and not ob.name.startswith(("sign_name","floor_")):
            bins.setdefault(tuple(m.name for m in ob.data.materials),[]).append(ob)
    for i,objects in enumerate(bins.values()):
        bpy.ops.object.select_all(action="DESELECT")
        for ob in objects:
            ob.select_set(True)
        bpy.context.view_layer.objects.active=objects[0]
        if len(objects)>1:
            bpy.ops.object.join()
        objects[0].name=f"{parent.name}__{i:02d}"


def block(name,parent,location,size,material):
    x,y,z=location
    w,d,h=size
    return box(name,parent,(x,y,z+h/2),(w,d,h),material)

def share_translated(objects):
    """Deduplicate authored repeats as shared meshes and translated glTF nodes."""
    from mathutils import Vector

    templates={}
    for ob in objects:
        data=ob.data
        if not data.vertices:
            continue
        anchor=data.vertices[0].co.copy()
        geometry=tuple(tuple(round(v,4) for v in (vertex.co-anchor)) for vertex in data.vertices)
        faces=tuple(tuple(p.vertices) for p in data.polygons)
        key=(tuple(m.name for m in data.materials),geometry,faces)
        if key in templates:
            ob.data=templates[key]
        else:
            for vertex in data.vertices:
                vertex.co-=anchor
            data.update()
            templates[key]=data
        ob.location+=anchor
        ob["keep"]=True
    return len(templates)
