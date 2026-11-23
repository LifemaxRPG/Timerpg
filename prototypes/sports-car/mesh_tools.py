"""Mesh and material helpers for glTF-oriented physical UVs."""
from math import cos, pi, sin
from pathlib import Path
import json
import sys

import bpy
from mathutils import Vector

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from revision_common import batch, emitter, export_glb, save_source

T=.0388
ATLAS=json.loads((ROOT/"textures/regions.json").read_text())
PBR="Principled BSDF"


def bl(point):
    """Convert glTF-oriented working coordinates to Blender coordinates."""
    x,y,z=point
    return Vector((x,-z,y))


def node(name,parent=None,point=(0,0,0)):
    obj=bpy.data.objects.new(name,None)
    bpy.context.collection.objects.link(obj)
    obj.parent=parent
    obj.location=bl(point)
    return obj


def material(name,texture,alpha=False):
    m=bpy.data.materials.new(name)
    m.use_nodes=True
    shader=m.node_tree.nodes[PBR]
    shader.inputs["Roughness"].default_value=.88 if alpha else .79
    shader.inputs["Specular IOR Level"].default_value=.19
    tex=m.node_tree.nodes.new("ShaderNodeTexImage")
    tex.image=bpy.data.images.load(str(ROOT/"textures"/f"{texture}.png"),check_existing=True)
    tex.image.pack()
    tex.interpolation="Closest"
    tex.extension="REPEAT"
    m.node_tree.links.new(tex.outputs["Color"],shader.inputs["Base Color"])
    if alpha:
        m.node_tree.links.new(tex.outputs["Alpha"],shader.inputs["Alpha"])
        m.surface_render_method="BLENDED"
    return m


def surface(name,parent,points,faces,mats,zone=None):
    """Keep every planar UV orthonormal, splitting bent quads at the fold."""
    coords=[bl(p) for p in points]
    if not isinstance(mats,(list,tuple)):
        mats=[mats]*len(faces)
    if not isinstance(zone,(list,tuple)):
        zone=[zone]*len(faces)
    polys,materials,regions=[],[],[]
    for face,mat,region in zip(faces,mats,zone):
        p,q,r=[coords[i] for i in face[:3]]
        n=(q-p).cross(r-p).normalized()
        pieces=[face]
        if len(face)>3 and any(abs(n.dot(coords[i]-p))>3.e-6 for i in face[3:]):
            pieces=[(face[0],face[i],face[i+1]) for i in range(1,len(face)-1)]
        for piece in pieces:
            polys.append(piece)
            materials.append(mat)
            regions.append(region)
    data=bpy.data.meshes.new(name)
    data.from_pydata(coords,[],polys)
    data.update()
    obj=bpy.data.objects.new(name,data)
    bpy.context.collection.objects.link(obj)
    obj.parent=parent
    slots=list(dict.fromkeys(materials))
    for slot in slots:
        data.materials.append(slot)
    uv=data.uv_layers.new(name="One texel per 0.0388 m")
    center=sum(coords,Vector((0,0,0)))/len(coords)
    for poly,mat,region in zip(data.polygons,materials,regions):
        poly.material_index=slots.index(mat)
        image=next(n.image for n in mat.node_tree.nodes if n.type=="TEX_IMAGE")
        atlas=ATLAS
        if region and mat.name=="glow_lamps":
            x,y=atlas["lamps"][region]
        else:
            x,y=atlas["finish"].get(region,(0,0))
        n=poly.normal
        u=(Vector((0,0,1)).cross(n) if abs(n.z)<.95 else Vector((0,1,0)).cross(n)).normalized()
        v=n.cross(u)
        if region in ("dash","badge","grille") and u.x<-.9:
            u=-u
            v=-v
        for i in poly.loop_indices:
            p=coords[data.loops[i].vertex_index]-(center if region else Vector((0,0,0)))
            uv.data[i].uv=((p.dot(u)/T+x)/image.size[0],
                           (p.dot(v)/T+(image.size[1]-y if region else 0))/image.size[1])
    return obj


def loft(name,parent,rings,mat,zone=None,cap=True,skip=()):
    n=len(rings[0])
    faces=[]
    for k in range(len(rings)-1):
        for i in range(n):
            if i not in skip:
                j=(i+1)%n
                faces.append((k*n+i,k*n+j,(k+1)*n+j,(k+1)*n+i))
    if cap:
        faces.extend((tuple(range(n-1,-1,-1)),tuple(range((len(rings)-1)*n,len(rings)*n))))
    return surface(name,parent,sum(rings,[]),faces,mat,zone)


def box(name,parent,center,size,mat,zone=None):
    cx,cy,cz=center
    w,h,l=[dim/2 for dim in size]
    return loft(name,parent,[[(cx+x*w,cy+y*h,cz+s*l) for x,y in ((-1,-1),(1,-1),(1,1),(-1,1))]
                             for s in (-1,1)],mat,zone)


def rod(name,parent,a,b,r,mat,zone=None,n=6):
    a,b=Vector(a),Vector(b)
    axis=(b-a).normalized()
    ref=Vector((0,1,0)) if abs(axis.y)<.92 else Vector((0,0,1))
    u=ref.cross(axis).normalized()
    v=axis.cross(u)
    rings=[[tuple(p+r*(cos(i*2*pi/n)*u+sin(i*2*pi/n)*v)) for i in range(n)] for p in (a,b)]
    return loft(name,parent,rings,mat,zone)
