"""Validate the actual funeral-home GLB, physical UVs, apertures, normals and colliders."""
from pathlib import Path
import json,struct,io
import numpy as np
from PIL import Image
R=Path(__file__).resolve().parent;info=json.loads((R/'model-info.json').read_text());reg=json.loads((R/'art-regions.json').read_text());U=.0776
ct={5126:('f',4),5125:('I',4),5123:('H',2),5121:('B',1)};ws={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}
def cross2(a,b):return float(a[0]*b[1]-a[1]*b[0])
def clipped_area(a,b):
 poly=[p.copy() for p in a];orientation=1 if cross2(b[1]-b[0],b[2]-b[0])>0 else -1
 for i in range(len(b)):
  u,v=b[i],b[(i+1)%len(b)];edge=v-u;out=[]
  for j,p in enumerate(poly):
   q=poly[(j+1)%len(poly)];fp=orientation*cross2(edge,p-u);fq=orientation*cross2(edge,q-u);pi=fp>=-1e-8;qi=fq>=-1e-8
   if pi:out.append(p)
   if pi!=qi and abs(fp-fq)>1e-12:out.append(p+(q-p)*fp/(fp-fq))
  poly=out
  if len(poly)<3:return 0
 return abs(sum(cross2(poly[i],poly[(i+1)%len(poly)]) for i in range(len(poly))))/2

raw=(R/'funeral-home.glb').read_bytes();assert struct.unpack_from('<4sII',raw)==(b'glTF',2,len(raw));n,t=struct.unpack_from('<II',raw,12);assert t==0x4e4f534a;g=json.loads(raw[20:20+n]);nb,t=struct.unpack_from('<II',raw,20+n);assert t==0x004e4942;binary=raw[28+n:28+n+nb]
assert len(g['materials'])<=12 and all('uri' not in x for k in ['images','buffers'] for x in g[k]);assert all(s['magFilter']==9728 and s.get('minFilter',9728) in [9728,9984] and s.get('wrapS',10497)==s.get('wrapT',10497)==10497 for s in g['samplers'])
images=[]
for im in g['images']:
 v=g['bufferViews'][im['bufferView']];a=v.get('byteOffset',0);p=np.array(Image.open(io.BytesIO(binary[a:a+v['byteLength']])).convert('RGBA'));h,w=p.shape[:2];assert h%2==w%2==0 and np.array_equal(p,p[::2,::2].repeat(2,0).repeat(2,1)),im['name'];images.append((w,h,p))
def acc(i):
 a=g['accessors'][i];v=g['bufferViews'][a['bufferView']];typ,size=ct[a['componentType']];w=ws[a['type']];offset=v.get('byteOffset',0)+a.get('byteOffset',0);stride=v.get('byteStride',size*w);return np.array([struct.unpack_from('<'+typ*w,binary,offset+j*stride) for j in range(a['count'])])
nodes={x['name']:x for x in g['nodes']};assert nodes['collision']['extras']['collision_only'] and nodes['collision']['extras']['render'] is False
counts={};max_error=0;bounds=[];paving=[];architecture=[];roofs=0;panel_tris=0
rects=[v['rect_art'] for k,v in reg.items() if k!='nameplate']
for i,m in enumerate(g['meshes']):
 if i==nodes['collision']['mesh']:continue
 count=0
 for p in m['primitives']:
  pos=acc(p['attributes']['POSITION']);uv=acc(p['attributes']['TEXCOORD_0']);idx=acc(p['indices']).ravel().astype(int);assert np.isfinite(pos).all() and np.isfinite(uv).all() and len(idx)%3==0 and idx.min()>=0 and idx.max()<len(pos);bounds.extend(pos)
  ma=g['materials'][p['material']];w,h,_=images[g['textures'][ma['pbrMetallicRoughness']['baseColorTexture']['index']]['source']];pixels=uv*np.array([w,h]);physical=pixels*.0388
  for a,b,c in idx.reshape(-1,3):
   tri=pos[[a,b,c]];cr=np.cross(pos[b]-pos[a],pos[c]-pos[a]);assert np.linalg.norm(cr)>1e-12,(m['name'],'degenerate')
   for j,k in [(a,b),(b,c),(c,a)]:max_error=max(max_error,abs(np.linalg.norm(pos[j]-pos[k])-np.linalg.norm(physical[j]-physical[k])))
   if ma['name']=='glass_home':
    corners=pixels[[a,b,c]];assert any(np.all(corners>=np.array([x,y])*2-.001) and np.all(corners<=np.array([x+ww,y+hh])*2+.001) for x,y,ww,hh in rects),('Atlas bleed',m['name']);panel_tris+=1
   if ma['name']=='roof_slate' and abs(cr[1])>.5*np.linalg.norm(cr):assert cr[1]>0,'Roof top faces inward';roofs+=1
   if ma['name']=='ground_paving' and cr[1]>.001 and np.ptp(tri[:,1])<1e-5:paving.append(tri)
   if ma['name'] in ['wall_siding','detail_trim','wall_brick_base']:architecture.append(tri)
  count+=len(idx)//3
 counts[i]=count
assert max_error<.001 and sum(counts.values())==info['triangles_visible']<=12000;assert np.array(bounds)[:,1].min()>=-1e-6 and roofs>=12 and panel_tris>=40
boxes=info['collision']['boxes_blender_z_up'];assert 0<len(boxes)<=48
for b in boxes:assert b['attachment'] in nodes and all(x>0 for x in b['size']) and np.isclose(b['center'][2]-b['size'][2]/2,0)
cp=np.concatenate([acc(p['attributes']['POSITION']) for p in g['meshes'][nodes['collision']['mesh']]['primitives']])
for b in boxes:
 x,y,z=b['center'];w,d,h=b['size']
 for a,s,c in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]:assert np.min(np.linalg.norm(cp-np.array([x+a*w/2,z+c*h/2,-y-s*d/2]),axis=1))<1e-5
for d in info['doors']:
 o=nodes[d['node']];assert np.allclose(o['translation'],d['position']);assert np.isclose(d['floor_m'],(2 if d['node']=='door_service_bay' else 6)*U);q=o.get('rotation',[0,0,0,1]);v=np.array([0.,0.,1.]);xyz=np.array(q[:3]);out=v+2*np.cross(xyz,np.cross(xyz,v)+q[3]*v);assert np.allclose(out,d['outward'],atol=1e-6);assert d['panel'] in nodes and any(b.get('gate_node')==d['panel'] for b in boxes)
assert len(info['doors'])==7 and sum(d['external'] for d in info['doors'])==5
# Check structural surfaces intersecting the face of every door, excluding the intended panel.
for xc,yc,half,bottom,top,axis in [(0,0,12,6,40,'x'),(-64,52,9,6,38,'y'),(64,52,12,6,38,'y'),(32,104,8,6,38,'x'),(-32,104,8,6,38,'x'),(96,120,8,6,38,'x'),(-32,184,24,2,40,'x')]:
 center=xc if axis=='x' else yc;fixed=-yc if axis=='x' else xc;plane=2 if axis=='x' else 0;other=0 if axis=='x' else 2;rect=np.array([[center-half+.15,bottom+.15],[center+half-.15,bottom+.15],[center+half-.15,top-.15],[center-half+.15,top-.15]])*U
 if axis=='y':rect[:,0]*=-1
 for tri in architecture:
  normal=np.cross(tri[1]-tri[0],tri[2]-tri[0])
  if abs(normal[plane])>.99*np.linalg.norm(normal) and np.ptp(tri[:,plane])<1e-5 and abs(tri[0,plane]-fixed*U)<1.7*U:assert clipped_area(tri[:,[other,1]],rect)<1e-4,('Trim blocks doorway',xc,yc)
for i,a in enumerate(paving):
 for b in paving[i+1:]:
  if abs(a[0,1]-b[0,1])<1e-5:assert clipped_area(a[:,[0,2]],b[:,[0,2]])<1e-4,'Overlapping paving tops'
def node_count(o):return counts.get(o.get('mesh'),0)+sum(node_count(g['nodes'][j]) for j in o.get('children',[]))
props={}
for o in g['nodes'][g['nodes'].index(nodes['lot'])].get('children',[]):
 node=g['nodes'][o]
 if node.get('children'):props[node['name']]=node_count(node);assert 0<props[node['name']]<500
for name in ['sign_name','sign_name_back']:
 p=g['meshes'][nodes[name]['mesh']]['primitives'][0];assert g['materials'][p['material']]['name']=='sign_name';pos=acc(p['attributes']['POSITION']);assert np.isclose(np.ptp(pos[:,0]),48*U) and np.isclose(np.ptp(pos[:,1]),24*U)
def structural_positions(o):
 points=[]
 if 'mesh' in o:points.extend(acc(p['attributes']['POSITION']) for p in g['meshes'][o['mesh']]['primitives'])
 for j in o.get('children',[]):points.extend(structural_positions(g['nodes'][j]))
 return points
bp=np.concatenate(structural_positions(nodes['structure']));src=bp[:,[0,2,1]].copy();src[:,1]*=-1
assert np.allclose(src.min(0),info['built_bounds_blender_m']['min'],atol=1e-5) and np.allclose(src.max(0),info['built_bounds_blender_m']['max'],atol=1e-5)
assert np.allclose(np.ptp(src,axis=0)[:2],info['building_m'],atol=1e-5)
ma=next(m for m in g['materials'] if m['name']=='glass_home');assert 'emissiveTexture' in ma,'Separate pane-only mask missing'
result={'file':'funeral-home.glb','visible_triangles':sum(counts.values()),'materials':len(g['materials']),'collision_boxes':len(boxes),'doors':len(info['doors']),'external_doors':sum(d['external'] for d in info['doors']),'prop_triangles':props,'uv_scale_max_edge_error_m':max_error,'bytes':len(raw),'art':'Every embedded PNG has exact 2x2 texel art pixels; nearest/repeat samplers and physical UV density verified. Door/window atlas containment and separate pane-only emission mask verified.','architecture':'Upward roof normals, clear seven door apertures, non-overlapping coplanar paving and grade-safe geometry verified.','collision':'Full-height box corners match actual exported collision mesh. Door translations, outward normals, floors and gate bindings verified.','runtime':'Clean architectural shells only. Navigation, doors, lighting, business history and simulation integration remain pending.','renders':'Blender desktop previews, not Filament/S25 captures.'}
(R/'validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
