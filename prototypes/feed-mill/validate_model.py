"""Validate actual GLB geometry, pixel scale, doors, bins, roof cut-out and collision handoff."""
from pathlib import Path
import json,struct,io
import numpy as np
from PIL import Image
R=Path(__file__).resolve().parent;info=json.loads((R/'model-info.json').read_text());raw=(R/'feed-mill.glb').read_bytes();assert struct.unpack_from('<4sII',raw)==(b'glTF',2,len(raw));n,t=struct.unpack_from('<II',raw,12);assert t==0x4e4f534a;g=json.loads(raw[20:20+n]);nb,t=struct.unpack_from('<II',raw,20+n);assert t==0x004e4942;binary=raw[28+n:28+n+nb]
assert len(g['materials'])<=12 and all('uri' not in x for k in ['images','buffers'] for x in g[k]);assert all(s['magFilter']==9728 and s.get('minFilter',9728) in [9728,9984] and s.get('wrapS',10497)==s.get('wrapT',10497)==10497 for s in g['samplers'])
images=[]
for im in g['images']:
 v=g['bufferViews'][im['bufferView']];a=v.get('byteOffset',0);p=np.array(Image.open(io.BytesIO(binary[a:a+v['byteLength']])).convert('RGBA'));h,w=p.shape[:2];assert h%2==w%2==0 and np.array_equal(p,p[::2,::2].repeat(2,0).repeat(2,1)),im['name'];images.append((w,h,p))
ct={5126:('f',4),5125:('I',4),5123:('H',2),5121:('B',1)};ws={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}
def acc(i):
 a=g['accessors'][i];v=g['bufferViews'][a['bufferView']];typ,size=ct[a['componentType']];w=ws[a['type']];offset=v.get('byteOffset',0)+a.get('byteOffset',0);stride=v.get('byteStride',size*w);return np.array([struct.unpack_from('<'+typ*w,binary,offset+j*stride) for j in range(a['count'])])
nodes={x['name']:x for x in g['nodes']}
for name in ['feed_mill','structure','lot','elevator','elevator_ladder','grain_bin_01','grain_bin_02','sign_name','sign_tower','anchor_rail_dock','collision']:assert name in nodes,name
assert nodes['collision']['extras']['collision_only'] and nodes['collision']['extras']['render'] is False
assert info['rail']['reference_only'] and nodes['anchor_rail_dock']['extras']['reference_only']
assert np.allclose(nodes['anchor_rail_dock']['translation'],[-44*.0776,0,-164*.0776])
for d in info['doors']:
 node=nodes[d['node']];assert np.allclose(node.get('translation',[0,0,0]),d['position']) and np.isclose(d['position'][1],d['floor_m']);q=node.get('rotation',[0,0,0,1]);v=np.array([0.,0.,1.]);xyz=np.array(q[:3]);out=v+2*np.cross(xyz,np.cross(xyz,v)+q[3]*v);assert np.allclose(out,d['outward'],atol=1e-6)
 assert d['panel'] in nodes and any(c.get('gate_node')==d['panel'] for c in info['collision']['boxes_blender_z_up'])
assert len(info['doors'])==4 and len(info['bins']['nodes'])==2
regions=json.loads((R/'art-regions.json').read_text());utility_rects=[v['rect_art'] for k,v in regions.items() if k in ['metal','sack','public_door','load_front','load_rear','service_door','louver','lamp']]
counts={};max_error=0;bounds=[];roof_inside_shaft=0;paving_tops=[]
for i,m in enumerate(g['meshes']):
 if i==nodes['collision']['mesh']:continue
 count=0
 for p in m['primitives']:
  pos=acc(p['attributes']['POSITION']);uv=acc(p['attributes']['TEXCOORD_0']);idx=acc(p['indices']).ravel().astype(int);assert np.isfinite(pos).all() and np.isfinite(uv).all() and len(idx)%3==0 and idx.max()<len(pos) and idx.min()>=0;bounds.extend(pos)
  ma=g['materials'][p['material']];w,h,_=images[g['textures'][ma['pbrMetallicRoughness']['baseColorTexture']['index']]['source']];pixels=uv*np.array([w,h]);physical=pixels*.0388
  for a,b,c in idx.reshape(-1,3):
   cr=np.cross(pos[b]-pos[a],pos[c]-pos[a]);assert np.linalg.norm(cr)>1e-12,(m['name'],'degenerate triangle')
   for j,k in [(a,b),(b,c),(c,a)]:max_error=max(max_error,abs(np.linalg.norm(pos[j]-pos[k])-np.linalg.norm(physical[j]-physical[k])))
   if ma['name']=='detail_equipment':
    corners=pixels[[a,b,c]]
    assert any(np.all(corners>=np.array([x,y])*2-.001) and np.all(corners<=np.array([x+ww,y+hh])*2+.001) for x,y,ww,hh in utility_rects),('Utility atlas bleed',m['name'],corners.tolist(),[w,h])
   if ma['name']=='ground_paving' and cr[1]>.001 and np.ptp(pos[[a,b,c],1])<1e-5:paving_tops.append(pos[[a,b,c]])
   if ma['name']=='roof_metal':
    mid=pos[[a,b,c]].mean(0)/.0776
    if 0.01<mid[0]<71.99 and 88.01<-mid[2]<151.99 and mid[1]<232:roof_inside_shaft+=1
  count+=len(idx)//3
 counts[i]=count
assert max_error<.001,('UV scale error',max_error)
assert roof_inside_shaft==0,('Warehouse roof continues inside elevator shaft',roof_inside_shaft)
# Positive-area coplanar overlap causes black self-shadow patches on solid paving.
def cross2(a,b):return float(a[0]*b[1]-a[1]*b[0])
def clipped_area(a,b):
 poly=[p.copy() for p in a];orientation=1 if cross2(b[1]-b[0],b[2]-b[0])>0 else -1
 for i in range(3):
  u,v=b[i],b[(i+1)%3];edge=v-u;out=[]
  for j,p in enumerate(poly):
   q=poly[(j+1)%len(poly)];fp=orientation*cross2(edge,p-u);fq=orientation*cross2(edge,q-u);pi=fp>=-1e-8;qi=fq>=-1e-8
   if pi:out.append(p)
   if pi!=qi and abs(fp-fq)>1e-12:out.append(p+(q-p)*fp/(fp-fq))
  poly=out
  if len(poly)<3:return 0
 return abs(sum(cross2(poly[i],poly[(i+1)%len(poly)]) for i in range(len(poly))))/2
for i,a in enumerate(paving_tops):
 for b in paving_tops[i+1:]:
  if abs(a[0,1]-b[0,1])<1e-5:assert clipped_area(a[:,[0,2]],b[:,[0,2]])<1e-4,'Overlapping coplanar paving tops'
triangles=sum(counts.values());assert triangles==info['triangles_visible'] and triangles<=12000
bounds=np.array(bounds);assert bounds[:,1].min()>=-1e-6
for name,key in [('sign_name','mill_name'),('sign_tower','tower_sign')]:
 m=g['meshes'][nodes[name]['mesh']];assert len(m['primitives'])==1 and g['materials'][m['primitives'][0]['material']]['name']=='sign_name';p=m['primitives'][0];pos=acc(p['attributes']['POSITION']);r=regions[key]['rect_art'];assert np.isclose(np.ptp(pos[:,0]),r[2]*.0776) and np.isclose(np.ptp(pos[:,1]),r[3]*.0776)
# Every companion box is positive, full-height and attached to a real assembly.
boxes=info['collision']['boxes_blender_z_up'];assert 0<len(boxes)<=48
for b in boxes:assert all(x>0 for x in b['size']) and np.isclose(b['center'][2]-b['size'][2]/2,0) and b['attachment'] in nodes
cp=np.concatenate([acc(p['attributes']['POSITION']) for p in g['meshes'][nodes['collision']['mesh']]['primitives']]);expected=[]
for b in boxes:
 x,y,z=b['center'];w,d,h=b['size'];expected += [(x+a*w/2,z+c*h/2,-y-s*d/2) for a,s,c in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]]
for p in expected:assert np.min(np.linalg.norm(cp-np.array(p),axis=1))<1e-5
# Bin colliders cover actual grain-bin geometry, including the front ladders and blowers.
def child_positions(name):
 out=[]
 def visit(node):
  if 'mesh' in node:out.extend(acc(p['attributes']['POSITION']) for p in g['meshes'][node['mesh']]['primitives'])
  for j in node.get('children',[]):visit(g['nodes'][j])
 visit(nodes[name]);return np.concatenate(out)
for name in info['bins']['nodes']:
 pp=child_positions(name);bb=[b for b in boxes if b['attachment']==name]
 for p in pp:
  assert any(np.all(p>=np.array([b['center'][0]-b['size'][0]/2,0,-b['center'][1]-b['size'][1]/2])-1e-5) and np.all(p<=np.array([b['center'][0]+b['size'][0]/2,b['size'][2],-b['center'][1]+b['size'][1]/2])+1e-5) for b in bb),('Uncovered bin obstruction',name,p)
props={name:sum(counts.get(g['nodes'][j].get('mesh'),0) for j in node.get('children',[])) for name,node in nodes.items() if (name.startswith('pallet_and_sacks_') or name.startswith('service_bin')) and 'mesh' not in node};assert all(0<n<500 for n in props.values())
result={'visible_triangles':triangles,'materials':len(g['materials']),'external_doors':len(info['doors']),'grain_bins':2,'collision_boxes':len(boxes),'prop_triangles':props,'uv_scale_max_edge_error_m':max_error,'textures':'All embedded PNGs are exact 2 x 2 texel art pixels; nearest/repeat sampling','roof_cutout':'No warehouse roof triangles continue inside the elevator shaft','signs':'Separate physical-scale sign_name and tower FEED panel','doors':'Anchors match floor heights and outward orientations; closed panels have companion gate boxes','collision':'Full-height boxes match the exported collider; bin geometry is covered. Architecture uses its walls/floors/ramps.','rail':'Reference anchor only; no rails or operational railway connection','ground':'All exported geometry at or above grade; no positive-area overlap between coplanar paving tops','glb_bytes':len(raw),'renders':'Blender desktop previews, not Filament or S25 captures.'}
(R/'validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
