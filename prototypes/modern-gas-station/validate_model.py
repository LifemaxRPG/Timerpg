"""Check the handoff contract against the actual GLB, not just its metadata."""
import io,json,struct,math
from pathlib import Path
import numpy as np
from PIL import Image
R=Path(__file__).resolve().parent;raw=(R/'modern-gas-station.glb').read_bytes()
assert struct.unpack_from('<4sII',raw)==(b'glTF',2,len(raw))
size,kind=struct.unpack_from('<II',raw,12);g=json.loads(raw[20:20+size]);offset=20+size
n,kind=struct.unpack_from('<II',raw,offset);binary=raw[offset+8:offset+8+n];assert kind==0x004e4942
info=json.loads((R/'model-info.json').read_text());assert len(g['materials'])<=12
assert all(s['magFilter']==9728 and s.get('wrapS',10497)==10497 and s.get('wrapT',10497)==10497 for s in g['samplers'])
assert all('uri' not in x for key in ('buffers','images') for x in g[key])
images=[]
for im in g['images']:
    v=g['bufferViews'][im['bufferView']];start=v.get('byteOffset',0)
    pixels=np.array(Image.open(io.BytesIO(binary[start:start+v['byteLength']])).convert('RGBA'))
    h,w=pixels.shape[:2];assert w%2==0 and h%2==0,(im['name'],w,h)
    assert np.array_equal(pixels,pixels[::2,::2].repeat(2,0).repeat(2,1)),im['name']
    images.append((w,h))
types={5126:('f',4),5125:('I',4),5123:('H',2),5121:('B',1)};counts={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}
def accessor(index):
    a=g['accessors'][index];v=g['bufferViews'][a['bufferView']];ch,size=types[a['componentType']];width=counts[a['type']];start=v.get('byteOffset',0)+a.get('byteOffset',0);stride=v.get('byteStride',width*size)
    return np.array([struct.unpack_from('<'+ch*width,binary,start+i*stride) for i in range(a['count'])])
names={n['name']:n for n in g['nodes']};assert all(n in names for n in ('lot','door_main','door_back','collision'))
assert names['collision']['extras']['collision_only'] is True
for door in info['doors']:
    assert np.allclose(names[door['node']].get('translation',[0,0,0]),door['position'])
    assert abs(door['position'][1])<1e-7
    q=names[door['node']].get('rotation',[0,0,0,1]);v=np.array([0.,0.,1.]);xyz=np.array(q[:3]);rotated=v+2*np.cross(xyz,np.cross(xyz,v)+q[3]*v)
    assert np.allclose(rotated,door['outward'],atol=1e-6)
triangles=0;max_error=0.;pump_counts={};mesh_counts={}
collision_mesh=names['collision']['mesh']
for i,mesh in enumerate(g['meshes']):
    if i==collision_mesh:continue
    count=0
    for p in mesh['primitives']:
        pos=accessor(p['attributes']['POSITION']);uv=accessor(p['attributes']['TEXCOORD_0']);idx=accessor(p['indices']).ravel().astype(int)
        assert np.isfinite(pos).all() and np.isfinite(uv).all() and idx.max()<len(pos) and len(idx)%3==0
        material=g['materials'][p['material']];texture=g['textures'][material['pbrMetallicRoughness']['baseColorTexture']['index']]
        w,h=images[texture['source']];physical_uv=uv*np.array([w,h])*.0388
        for a,b,c in idx.reshape((-1,3)):
            for j,k in [(a,b),(b,c),(c,a)]:
                length=np.linalg.norm(pos[j]-pos[k]);texlength=np.linalg.norm(physical_uv[j]-physical_uv[k])
                if length>1e-7:max_error=max(max_error,abs(length-texlength))
        count+=len(idx)//3
    triangles+=count
    mesh_counts[i]=count
def count_node(i):
    node=g['nodes'][i]
    return mesh_counts.get(node.get('mesh'),0)+sum(count_node(j) for j in node.get('children',[]))
for i,node in enumerate(g['nodes']):
    if node['name'] in ['pump_01','pump_02','pump_03','pump_04']:pump_counts[node['name']]=count_node(i)
assert len(pump_counts)==4
assert triangles==info['triangles_visible'] and triangles<=12000
assert all(n<500 for n in pump_counts.values()),pump_counts
assert max_error<.001,('UV scale mismatch, metres',max_error)
result={'glb_bytes':len(raw),'visible_triangles':triangles,'materials':len(g['materials']),'embedded_textures':len(images),'art_pixel_grid':'all texture pixels are exact 2x2 blocks','uv_scale_max_edge_error_m':max_error,'pump_triangles':pump_counts,'doors':'ground-level named nodes verified','collision':'separate collision-only mesh verified','lot':'optional lot hierarchy verified','renderer':'Blender previews; no Filament or phone benchmark'}
(R/'validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
