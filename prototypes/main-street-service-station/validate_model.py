"""Validate the exported period-station asset against the Timerpg handoff contract."""
import struct,json,io,tempfile,hashlib
from pathlib import Path
from PIL import Image
import numpy as np
R=Path(__file__).resolve().parent;raw=(R/'main-street-service-station.glb').read_bytes()
assert struct.unpack_from('<4sII',raw)==(b'glTF',2,len(raw))
n,t=struct.unpack_from('<II',raw,12);g=json.loads(raw[20:20+n]);offset=20+n
bn,t=struct.unpack_from('<II',raw,offset);binary=raw[offset+8:offset+8+bn];assert t==0x004e4942
info=json.loads((R/'model-info.json').read_text());assert len(g['materials'])<=12
assert all(s['magFilter']==9728 and s.get('wrapS',10497)==10497 and s.get('wrapT',10497)==10497 for s in g['samplers'])
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
for name in ['lot','sign_price','garage_door_open','garage_door_closed','pump_01','pump_02','collision']:assert name in names,name
assert names['collision']['extras']['collision_only'] is True
assert len(info['collision']['boxes_blender_z_up'])<=48
hide=info['history_handoff']['optional_repair_only_hide']
assert all(any(c.get('attachment')==node for c in info['collision']['boxes_blender_z_up']) for node in hide)
assert any(c.get('gate_node')=='garage_door_closed' for c in info['collision']['boxes_blender_z_up'])
for d in info['doors']:
    node=names[d['node']];assert np.allclose(node.get('translation',[0,0,0]),d['position']);assert d['position'][1]==0
    q=node.get('rotation',[0,0,0,1]);v=np.array([0.,0.,1.]);xyz=np.array(q[:3]);forward=v+2*np.cross(xyz,np.cross(xyz,v)+q[3]*v)
    assert np.allclose(forward,d['outward'],atol=1e-6)
max_error=0;counts={}
for i,mesh in enumerate(g['meshes']):
    if i==names['collision']['mesh']:continue
    count=0
    for p in mesh['primitives']:
        pos=access(p['attributes']['POSITION']);uv=access(p['attributes']['TEXCOORD_0']);idx=access(p['indices']).ravel().astype(int)
        assert np.isfinite(pos).all() and np.isfinite(uv).all() and idx.max()<len(pos) and len(idx)%3==0
        m=g['materials'][p['material']];texture=g['textures'][m['pbrMetallicRoughness']['baseColorTexture']['index']];w,h=images[texture['source']];uv=uv*np.array([w,h])*.0388
        for a,b,c in idx.reshape(-1,3):
            for j,k in [(a,b),(b,c),(c,a)]:max_error=max(max_error,abs(np.linalg.norm(pos[j]-pos[k])-np.linalg.norm(uv[j]-uv[k])))
        count+=len(idx)//3
    counts[i]=count
assert sum(counts.values())==info['triangles_visible'] and sum(counts.values())<=5000
assert max_error<.001,max_error
def node_count(i):
    n=g['nodes'][i];return counts.get(n.get('mesh'),0)+sum(node_count(j) for j in n.get('children',[]))
pumps={n['name']:node_count(i) for i,n in enumerate(g['nodes']) if n['name'] in ('pump_01','pump_02')}
assert len(pumps)==2 and all(0<c<500 for c in pumps.values())
for p in g['meshes'][names['sign_price']['mesh']]['primitives']:
    assert g['materials'][p['material']]['name']=='sign_price'
    uv=access(p['attributes']['TEXCOORD_0']);assert np.allclose(uv.min(axis=0),[0,0]) and np.allclose(uv.max(axis=0),[1,1])
from price_art import write_prices
before=hashlib.sha256((R/'textures/utility.png').read_bytes()).hexdigest()
with tempfile.TemporaryDirectory() as d:
    a=Path(d)/'1959.png';b=Path(d)/'1965.png';write_prices(a,1959,'0.29','0.32');write_prices(b,1965,'0.31','0.35')
    assert not np.array_equal(np.array(Image.open(a)),np.array(Image.open(b)))
assert hashlib.sha256((R/'textures/utility.png').read_bytes()).hexdigest()==before
result={'visible_triangles':sum(counts.values()),'materials':len(g['materials']),'glb_bytes':len(raw),'pump_triangles':pumps,'art_grid':'All embedded textures are exact 2x2 texel blocks','uv_scale_max_edge_error_m':max_error,'doors':'All four ground-level outward-facing nodes verified','garage':'Open bay 1; closed bay 2 has separate state node and gated collision','yearly_prices':'Independent full-UV sign_price and two-year writer checked','history':'Relocation/reuse metadata requires simulation facts; no automatic removal by year','renderer':'Blender desktop previews, not Filament or S25 captures'}
(R/'validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
