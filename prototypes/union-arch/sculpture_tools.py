"""Mesh construction in metres with flat normals and physical, orthonormal UVs."""
from pathlib import Path
from math import cos,sin,pi
from mathutils import Vector as V
import bpy
R=Path(__file__).resolve().parent
T=.0388
Y0=18.
def group(name,parent=None):
    ob=bpy.data.objects.new(name,None);bpy.context.collection.objects.link(ob);ob.parent=parent
    return ob
def material(name,texture,emission=0):
    m=bpy.data.materials.new(name);m.use_nodes=True
    p=m.node_tree.nodes.get('Principled BSDF')
    p.inputs['Roughness'].default_value=.96
    p.inputs['Specular IOR Level'].default_value=.13
    tex=m.node_tree.nodes.new('ShaderNodeTexImage')
    tex.image=bpy.data.images.load(str(R/'textures'/(texture+'.png')),check_existing=True)
    tex.image.pack();tex.interpolation='Closest';tex.extension='REPEAT'
    m.node_tree.links.new(tex.outputs['Color'],p.inputs['Base Color'])
    if emission:
        m.node_tree.links.new(tex.outputs['Color'],p.inputs['Emission Color'])
        p.inputs['Emission Strength'].default_value=emission
    return m
def mesh(name,parent,verts,polys,mat,uv=None):
    verts=[V(v) for v in verts]
    faces=[];ids=[]
    for i,f in enumerate(polys):
        if len(f)>3 and uv is None:
            n=(verts[f[1]]-verts[f[0]]).cross(verts[f[2]]-verts[f[0]]).normalized()
            if any(abs(n.dot(verts[j]-verts[f[0]]))>1.e-6 for j in f[3:]):
                faces.extend([[f[0],f[j],f[j+1]] for j in range(1,len(f)-1)])
                ids.extend([i]*(len(f)-2));continue
        faces.append(f);ids.append(i)
    me=bpy.data.meshes.new(name);me.from_pydata([v+V((0,Y0,0)) for v in verts],[],faces);me.update()
    ob=bpy.data.objects.new(name,me);bpy.context.collection.objects.link(ob);ob.parent=parent
    mats=mat if isinstance(mat,list) else [mat]*len(polys)
    slots=list(dict.fromkeys(mats))
    for m in slots:me.materials.append(m)
    layer=me.uv_layers.new(name='World texel grid')
    for p,i in zip(me.polygons,ids):
        m=mats[i];p.material_index=slots.index(m)
        im=next(n.image for n in m.node_tree.nodes if n.type=='TEX_IMAGE')
        u=V((0,0,1)).cross(p.normal) if abs(p.normal.z)<.95 else V((0,1,0)).cross(p.normal)
        u.normalize();v=p.normal.cross(u)
        for j,li in enumerate(p.loop_indices):
            pt=verts[me.loops[li].vertex_index]
            layer.data[li].uv=uv[j] if uv else (pt.dot(u)/(T*im.size[0]),pt.dot(v)/(T*im.size[1]))
    return ob
def loft(name,g,rings,m,ends=True):
    n=len(rings[0]);vs=sum(rings,[]);fs=[];ms=[]
    for k in range(len(rings)-1):
        for j in range(n):
            fs.append((k*n+j,k*n+(j+1)%n,(k+1)*n+(j+1)%n,(k+1)*n+j))
            ms.append(m(k,j) if callable(m) else m)
    if ends:
        fs.extend([tuple(reversed(range(n))),tuple(range(len(vs)-n,len(vs)))]);ms.extend([ms[0],ms[-1]])
    return mesh(name,g,vs,fs,ms)
def oval(name,g,levels,m,n=12,transform=None):
    rings=[]
    for x,y,z,rx,ry in levels:
        ring=[(x+rx*cos((j+.5)*2*pi/n),y+ry*sin((j+.5)*2*pi/n),z) for j in range(n)]
        rings.append([transform(*p) for p in ring] if transform else ring)
    return loft(name,g,rings,m)
def box(name,g,c,d,m):
    x,y,z=c;w,l,h=d
    return loft(name,g,[[(x+a*w/2,y+b*l/2,z+q*h/2) for a,b in [(-1,-1),(1,-1),(1,1),(-1,1)]] for q in (-1,1)],m)
def beam(name,g,a,b,r1,r2,m,n=8):
    a,b=V(a),V(b);d=(b-a).normalized()
    ref=V((0,1,0)) if abs(d.y)<.85 else V((1,0,0))
    u=ref.cross(d).normalized();v=d.cross(u)
    return loft(name,g,[[pt+u*(rx*cos((j+.5)*2*pi/n))+v*(ry*sin((j+.5)*2*pi/n)) for j in range(n)] for pt,(rx,ry) in [(a,r1),(b,r2)]],m)
def plaque(name,g,vs,mat,rect,size):
    x,y,w,h=rect;tw,th=size
    uvs=[(x/tw,1-(y+h)/th),((x+w)/tw,1-(y+h)/th),((x+w)/tw,1-y/th),(x/tw,1-y/th)]
    return mesh(name,g,vs,[(0,1,2,3)],mat,uvs)
