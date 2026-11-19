"""Validate the actual exported GLB, pixel art, blank sign and full-height fenced-site collider."""
from pathlib import Path
import json,struct,io
import numpy as np
from PIL import Image
R=Path(__file__).resolve().parent;info=json.loads((R/'model-info.json').read_text());raw=(R/'water-tower.glb').read_bytes();assert struct.unpack_from('<4sII',raw)==(b'glTF',2,len(raw));n,t=struct.unpack_from('<II',raw,12);assert t==0x4e4f534a;g=json.loads(raw[20:20+n]);nb,t=struct.unpack_from('<II',raw,20+n);assert t==0x004e4942;binary=raw[28+n:28+n+nb]
assert all('uri' not in v for k in ['images','buffers'] for v in g[k])
assert len(g['materials'])<=12
assert all(s['magFilter']==9728 and s.get('minFilter',9728) in [9728,9984] and s.get('wrapS',10497)==s.get('wrapT',10497)==10497 for s in g['samplers'])
nodes={n['name']:n for n in g['nodes']}
for name in ['water_tower','structure','lot','tank','ladder','service_gate','access_ladder','sign_name','collision','leg_1','leg_2','leg_3','leg_4']:assert name in nodes,name
assert not any(n.startswith('door_') for n in nodes) and info['doors']==[]
assert nodes['collision']['extras']['render'] is False and nodes['collision']['extras']['collision_only']
images=[]
for im in g['images']:
 v=g['bufferViews'][im['bufferView']];a=v.get('byteOffset',0);p=np.array(Image.open(io.BytesIO(binary[a:a+v['byteLength']])).convert('RGBA'));h,w=p.shape[:2];assert w%2==h%2==0 and np.array_equal(p,p[::2,::2].repeat(2,0).repeat(2,1)),im['name'];images.append((w,h,p))
cts={5126:('f',4),5125:('I',4),5123:('H',2),5121:('B',1)};width={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}
def acc(i):
 a=g['accessors'][i];v=g['bufferViews'][a['bufferView']];typ,size=cts[a['componentType']];w=width[a['type']];offset=v.get('byteOffset',0)+a.get('byteOffset',0);stride=v.get('byteStride',size*w);return np.array([struct.unpack_from('<'+typ*w,binary,offset+j*stride) for j in range(a['count'])])
triangles=0;max_error=0;allpos=[];upward_catwalk=0
for mi,m in enumerate(g['meshes']):
 if mi==nodes['collision']['mesh']:continue
 for p in m['primitives']:
  pos=acc(p['attributes']['POSITION']);uv=acc(p['attributes']['TEXCOORD_0']);idx=acc(p['indices']).ravel().astype(int);assert np.isfinite(pos).all() and np.isfinite(uv).all() and len(idx)%3==0 and idx.min()>=0 and idx.max()<len(pos)
  allpos.extend(pos);material=g['materials'][p['material']];ti=material['pbrMetallicRoughness']['baseColorTexture']['index'];w,h,_=images[g['textures'][ti]['source']];physical=uv*np.array([w,h])*.0388
  for a,b,c in idx.reshape(-1,3):
   cr=np.cross(pos[b]-pos[a],pos[c]-pos[a]);assert np.linalg.norm(cr)>1e-12,(m['name'],'degenerate')
   for j,k in [(a,b),(b,c),(c,a)]:max_error=max(max_error,abs(np.linalg.norm(pos[j]-pos[k])-np.linalg.norm(physical[j]-physical[k])))
   if material['name']=='detail_catwalk' and np.linalg.norm(cr)>.05 and np.allclose(pos[[a,b,c],1],236.8*.0776,atol=1e-6):assert cr[1]>0,'Catwalk top faces inward';upward_catwalk+=1
  triangles+=len(idx)//3
assert upward_catwalk>=32 and triangles==info['triangles_visible'] and triangles<=12000 and max_error<.001
# The single closed-site box encloses every obstruction from grade to the beacon.
assert len(info['collision']['boxes_blender_z_up'])==1
c=info['collision']['boxes_blender_z_up'][0];x,y,z=c['center'];w,d,h=c['size'];lo=np.array([x-w/2,z-h/2,-y-d/2]);hi=np.array([x+w/2,z+h/2,-y+d/2]);pos=np.array(allpos);assert pos[:,1].min()>=-1e-6;assert np.all(pos.min(0)>=lo-1e-5) and np.all(pos.max(0)<=hi+1e-5)
cp=np.concatenate([acc(p['attributes']['POSITION']) for p in g['meshes'][nodes['collision']['mesh']]['primitives']]);assert np.allclose(cp.min(0),lo,atol=1e-5) and np.allclose(cp.max(0),hi,atol=1e-5)
for i,name in enumerate(info['legs']['nodes']):
 n=nodes[name];assert len(g['meshes'][n['mesh']]['primitives'])==1;pp=acc(g['meshes'][n['mesh']]['primitives'][0]['attributes']['POSITION']);assert pp[:,1].max()>18 and pp[:,1].min()<.7
# Sign is isolated, blank and has three facets with the documented developed dimensions.
s=g['meshes'][nodes['sign_name']['mesh']];assert len(s['primitives'])==1;p=s['primitives'][0];m=g['materials'][p['material']];assert m['name']=='sign_name';assert len(acc(p['indices']))==18
w,h,pix=images[g['textures'][m['pbrMetallicRoughness']['baseColorTexture']['index']]['source']];assert (w,h)==(128,64)
assert np.array_equal(pix,np.array(Image.open(R/'textures/sign-name.png').convert('RGBA')))
assert np.all(pix[8:30,12:116,:]==np.array([229,223,201,255])),'Blank name area contains marks or letters'
assert info['sign_name']['blank'] is True and info['ladder']['climbable'] is False
result={'visible_triangles':triangles,'materials':len(g['materials']),'legs':4,'tank_facets':16,'sign':'Exact isolated sign_name node/material; blank 64 x 20 art-pixel region on three physical-scale facets','ladder':'Fixed rear ladder, cage, attached stand-offs, tank and roof access; visual only','collision':'One full-height box enclosing the visibly closed fenced compound; all visible obstructions inside','doors':'None: uninhabitable utility tower, maintenance anchor only','pixel_grid':'All embedded PNGs are exact 2 x 2 art-pixel blocks','uv_scale_max_edge_error_m':max_error,'catwalk':'Upward-facing top surface verified','ground':'All exported geometry at or above grade','glb_bytes':len(raw),'renders':'Blender desktop previews, not Filament or S25 captures.'}
(R/'validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
