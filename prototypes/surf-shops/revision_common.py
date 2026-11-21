"""Shared Blender export and material helpers."""
from pathlib import Path
import bpy

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

