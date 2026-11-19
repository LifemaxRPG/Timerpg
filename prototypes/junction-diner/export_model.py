"""Export material-batched copies; preserve the individually editable source scene."""
from pathlib import Path
import bpy, json, sys

def export_model(root):
    root = Path(root)
    batch = bpy.data.collections.new('Temporary export batches')
    bpy.context.scene.collection.children.link(batch)
    groups = {}
    for collection in bpy.data.collections:
        if collection.name in ('Presentation', batch.name):
            continue
        for ob in list(collection.objects):
            if ob.type != 'MESH':
                continue
            material = ob.data.materials[0]
            duplicate = ob.copy()
            duplicate.data = ob.data.copy()
            batch.objects.link(duplicate)
            groups.setdefault(material.name, []).append(duplicate)
    for material_name, objects in groups.items():
        bpy.ops.object.select_all(action='DESELECT')
        for ob in objects:
            ob.select_set(True)
        bpy.context.view_layer.objects.active = objects[0]
        if len(objects) > 1:
            bpy.ops.object.join()
        objects[0].name = 'Junction Diner | ' + material_name
    bpy.ops.object.select_all(action='DESELECT')
    for ob in batch.objects:
        ob.select_set(True)
    triangles = sum(len(p.vertices) - 2 for ob in batch.objects for p in ob.data.polygons)
    bpy.ops.export_scene.gltf(
        filepath=str(root / 'export/junction-diner.glb'), use_selection=True,
        export_format='GLB', export_yup=True, export_texcoords=True,
        export_normals=True, export_materials='EXPORT')
    info_path = root / 'export/model-info.json'
    info = json.loads(info_path.read_text()) if info_path.exists() else {}
    info.update(export_meshes=len(batch.objects), export_triangles=triangles,
                export_note='Meshes batched by material. Source .blend retains individually editable parts. No mobile performance claim.')
    info_path.write_text(json.dumps(info, indent=2) + '\n')
    for ob in list(batch.objects):
        bpy.data.objects.remove(ob, do_unlink=True)
    bpy.data.collections.remove(batch)
    print('Material-batched GLB:', info['export_meshes'], 'meshes;', triangles, 'triangles')

if __name__ == '__main__':
    export_model(Path(__file__).resolve().parent)
