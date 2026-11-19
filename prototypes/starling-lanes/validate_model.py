"""Validate the exported bowling-alley asset against the Timerpg handoff contract."""
import struct,json,io,tempfile,hashlib
from pathlib import Path
from PIL import Image
import numpy as np
R=Path(__file__).resolve().parent;raw=(R/'starling-lanes.glb').read_bytes()
assert struct.unpack_from('<4sII',raw)==(b'glTF',2,len(raw))
n,t=struct.unpack_from('<II',raw,12);g=json.loads(raw[20:20+n]);offset=20+n
bn,t=struct.unpack_from('<II',raw,offset);binary=raw[offset+8:offset+8+bn];assert t==0x004e4942
info=json.loads((R/'model-info.json').read_text());assert len(g['materials'])<=12
assert all(s['magFilter']==9728 and s.get('minFilter',9728) in (9728,9984) and s.get('wrapS',10497)==10497 and s.get('wrapT',10497)==10497 for s in g['samplers'])
assert all('uri' not in x for k in ('buffers','images') for x in g[k])
images=[]
for image in g['images']:
    view=g['bufferViews'][image['bufferView']];start=view.get('byteOffset',0)
    pixels=np.array(Image.open(io.BytesIO(binary[start:start+view['byteLength']])).convert('RGBA'));h,w=pixels.shape[:2]
    assert h%2==0 and w%2==0 and np.array_equal(pixels,pixels[::2,::2].repeat(2,0).repeat(2,1)),image['name'];images.append((w,h))
types={5126:('f',4),5125:('I',4),5123:('H',2),5121:('B',1)};widths={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}
def access(i):
    a=g['accessors'][i];v=g['bufferViews'][a['bufferView']];c,size=types[a['componentType']];w=widths[a['type']];start=v.get('byteOffset',0)+a.get('byteOffset',0);stride=v.get('byteStride',size*w)
    return np.array([struct.unpack_from('<'+c*w,binary,start+j*stride) for j in range(a['count'])])
names={n['name']:n for n in g['nodes']}
for name in ['lot','giant_pin','service_yard','door_main','door_back','door_side','door_panel_main','door_panel_back','door_panel_side','collision']:assert name in names,name
assert names['collision']['extras']['collision_only'] is True
assert len(info['collision']['boxes_blender_z_up'])<=36
for name,expected,forward_expected in [('door_main',[0,0,2*.0776],[0,0,1]),('door_back',[104*.0776,0,-402*.0776],[0,0,-1]),('door_side',[-146*.0776,0,-240*.0776],[-1,0,0])]:
 node=names[name];assert np.allclose(node.get('translation',[0,0,0]),expected)
 q=node.get('rotation',[0,0,0,1]);v=np.array([0.,0.,1.]);xyz=np.array(q[:3]);forward=v+2*np.cross(xyz,np.cross(xyz,v)+q[3]*v)
 assert np.allclose(forward,forward_expected,atol=1e-6)
for panel in info['door_panels']:assert any(c.get('gate_node')==panel for c in info['collision']['boxes_blender_z_up'])
max_error=0;counts={}
for i,mesh in enumerate(g['meshes']):
    if i==names['collision']['mesh']:continue
    count=0
    for p in mesh['primitives']:
        pos=access(p['attributes']['POSITION']);uv=access(p['attributes']['TEXCOORD_0']);idx=access(p['indices']).ravel().astype(int)
        assert np.isfinite(pos).all() and np.isfinite(uv).all() and idx.max()<len(pos) and len(idx)%3==0
        m=g['materials'][p['material']];texture=g['textures'][m['pbrMetallicRoughness']['baseColorTexture']['index']];w,h=images[texture['source']];uv=uv*np.array([w,h])*.0388
        for a,b,c in idx.reshape(-1,3):
            assert np.linalg.norm(np.cross(pos[b]-pos[a],pos[c]-pos[a]))>1e-12,mesh.get('name')
            for j,k in [(a,b),(b,c),(c,a)]:max_error=max(max_error,abs(np.linalg.norm(pos[j]-pos[k])-np.linalg.norm(uv[j]-uv[k])))
        count+=len(idx)//3
    counts[i]=count
assert sum(counts.values())==info['triangles_visible'] and sum(counts.values())<=12000
assert max_error<.001,max_error
def node_count(i):
    n=g['nodes'][i];return counts.get(n.get('mesh'),0)+sum(node_count(j) for j in n.get('children',[]))
props={n['name']:node_count(i) for i,n in enumerate(g['nodes']) if n['name'] in ('giant_pin','service_yard')}
assert all(0<c<500 for c in props.values()),props
result={'visible_triangles':sum(counts.values()),'materials':len(g['materials']),'glb_bytes':len(raw),'prop_triangles':props,'collision_boxes':len(info['collision']['boxes_blender_z_up']),'art_grid':'All embedded textures are exact 2x2 texel blocks','uv_scale_max_edge_error_m':max_error,'doors':'All three ground-level outward-facing nodes and gated panels verified','renderer':'Blender desktop previews, not Filament or S25 captures'}
(R/'validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
