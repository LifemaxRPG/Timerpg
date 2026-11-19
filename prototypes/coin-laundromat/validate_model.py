"""Validate both actual GLBs, the clean-shell/optional-kit separation, UVs, fixtures and colliders."""
from pathlib import Path
import json,struct,io
import numpy as np
from PIL import Image
R=Path(__file__).resolve().parent;info=json.loads((R/'model-info.json').read_text());kitinfo=json.loads((R/'fixtures-info.json').read_text());reg=json.loads((R/'art-regions.json').read_text());U=.0776
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

def validate(file,meta,kit=False):
 raw=(R/file).read_bytes();assert struct.unpack_from('<4sII',raw)==(b'glTF',2,len(raw));n,t=struct.unpack_from('<II',raw,12);assert t==0x4e4f534a;g=json.loads(raw[20:20+n]);nb,t=struct.unpack_from('<II',raw,20+n);assert t==0x004e4942;binary=raw[28+n:28+n+nb]
 assert len(g['materials'])<=12 and all('uri' not in x for k in ['images','buffers'] for x in g[k]);assert all(s['magFilter']==9728 and s.get('minFilter',9728) in [9728,9984] and s.get('wrapS',10497)==s.get('wrapT',10497)==10497 for s in g['samplers'])
 images=[]
 for im in g['images']:
  v=g['bufferViews'][im['bufferView']];a=v.get('byteOffset',0);p=np.array(Image.open(io.BytesIO(binary[a:a+v['byteLength']])).convert('RGBA'));h,w=p.shape[:2];assert h%2==w%2==0 and np.array_equal(p,p[::2,::2].repeat(2,0).repeat(2,1)),im['name'];images.append((w,h,p))
 def acc(i):
  a=g['accessors'][i];v=g['bufferViews'][a['bufferView']];typ,size=ct[a['componentType']];w=ws[a['type']];offset=v.get('byteOffset',0)+a.get('byteOffset',0);stride=v.get('byteStride',size*w);return np.array([struct.unpack_from('<'+typ*w,binary,offset+j*stride) for j in range(a['count'])])
 nodes={x['name']:x for x in g['nodes']};assert nodes['collision']['extras']['collision_only'] and nodes['collision']['extras']['render'] is False
 utility_rects=[reg[k]['rect_art'] for k in ['service_door','washer_face','dryer_face','change_face','louver']];counts={};max_error=0;bounds=[];paving=[];architecture=[];stripe_area=0;front_gables=0
 for i,m in enumerate(g['meshes']):
  if i==nodes['collision']['mesh']:continue
  count=0
  for p in m['primitives']:
   pos=acc(p['attributes']['POSITION']);uv=acc(p['attributes']['TEXCOORD_0']);idx=acc(p['indices']).ravel().astype(int);assert np.isfinite(pos).all() and np.isfinite(uv).all() and len(idx)%3==0 and idx.min()>=0 and idx.max()<len(pos);bounds.extend(pos)
   ma=g['materials'][p['material']];w,h,_=images[g['textures'][ma['pbrMetallicRoughness']['baseColorTexture']['index']]['source']];pixels=uv*np.array([w,h]);physical=pixels*.0388
   for a,b,c in idx.reshape(-1,3):
    tri=pos[[a,b,c]];cr=np.cross(pos[b]-pos[a],pos[c]-pos[a]);assert np.linalg.norm(cr)>1e-12,(file,m['name'],'degenerate')
    for j,k in [(a,b),(b,c),(c,a)]:max_error=max(max_error,abs(np.linalg.norm(pos[j]-pos[k])-np.linalg.norm(physical[j]-physical[k])))
    if ma['name']=='detail_panels':
     corners=pixels[[a,b,c]];assert any(np.all(corners>=np.array([x,y])*2-.001) and np.all(corners<=np.array([x+ww,y+hh])*2+.001) for x,y,ww,hh in utility_rects),('Atlas bleed',m['name'])
    if not kit:
     if ma['name']=='ground_paving' and cr[1]>.001 and np.ptp(tri[:,1])<1e-5:paving.append(tri)
     if ma['name'] in ['wall_stucco','detail_coral','detail_enamel']:architecture.append(tri)
     if ma['name']=='detail_enamel' and cr[1]>0 and np.allclose(tri[:,1],.55*U,atol=1e-6):stripe_area+=np.linalg.norm(cr)/2
     if ma['name']=='wall_stucco' and np.allclose(tri[:,2],U,atol=1e-6) and tri[:,1].min()>=58*U-1e-6:assert cr[2]>0,'Butterfly gable faces inward';front_gables+=1
     if ma['name']=='roof_membrane' and abs(cr[1])>.5*np.linalg.norm(cr):assert cr[1]>0,'Roof top faces inward'
   count+=len(idx)//3
  counts[i]=count
 assert max_error<.001 and sum(counts.values())==meta['triangles_visible'];assert np.array(bounds)[:,1].min()>=-1e-6
 boxes=meta['collision']['boxes_blender_z_up'];assert 0<len(boxes)<=48
 for b in boxes:assert b['attachment'] in nodes and all(x>0 for x in b['size']) and np.isclose(b['center'][2]-b['size'][2]/2,0)
 cp=np.concatenate([acc(p['attributes']['POSITION']) for p in g['meshes'][nodes['collision']['mesh']]['primitives']])
 for b in boxes:
  x,y,z=b['center'];w,d,h=b['size']
  for a,s,c in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]:assert np.min(np.linalg.norm(cp-np.array([x+a*w/2,z+c*h/2,-y-s*d/2]),axis=1))<1e-5
 def descend(name):
  result=[]
  def visit(o):
   assert not any(k in o for k in ['matrix','translation','rotation','scale']),('Unexpected mesh/group transform',name)
   if 'mesh' in o:result.extend(acc(p['attributes']['POSITION']) for p in g['meshes'][o['mesh']]['primitives'])
   for j in o.get('children',[]):visit(g['nodes'][j])
  visit(nodes[name]);return np.concatenate(result)
 def node_count(name):
  def visit(o):return counts.get(o.get('mesh'),0)+sum(visit(g['nodes'][j]) for j in o.get('children',[]))
  return visit(nodes[name])
 props={}
 if kit:
  assert 'laundry_fixtures' in nodes and 'coin_laundromat' not in nodes and len(meta['machine_nodes'])==11
  for name in meta['machine_nodes']:
   assert name in nodes;props[name]=node_count(name);assert 0<props[name]<500;bb=[b for b in boxes if b['attachment']==name];assert len(bb)==1;b=bb[0];x,y,z=b['center'];w,d,h=b['size'];p=descend(name);assert np.all(p.min(0)>=np.array([x-w/2,0,-y-d/2])-1e-5) and np.all(p.max(0)<=np.array([x+w/2,h,-y+d/2])+1e-5),('Fixture not covered',name)
 else:
  assert info['clean_shell'] and 'coin_laundromat' in nodes and 'laundry_fixtures' not in nodes
  assert all(name not in nodes for name in kitinfo['machine_nodes']),'Machine leaked into clean primary model'
  assert front_gables>=4 and np.isclose(stripe_area,5*.5*64*U*U,atol=1e-5),'Duplicate or missing parking stripes'
  for d in info['doors']:
   o=nodes[d['node']];assert np.allclose(o['translation'],d['position']) and np.isclose(d['floor_m'],2*U);q=o.get('rotation',[0,0,0,1]);v=np.array([0.,0.,1.]);xyz=np.array(q[:3]);out=v+2*np.cross(xyz,np.cross(xyz,v)+q[3]*v);assert np.allclose(out,d['outward'],atol=1e-6);assert d['panel'] in nodes and any(b.get('gate_node')==d['panel'] for b in boxes)
  assert len(info['doors'])==3 and sum(d['external'] for d in info['doors'])==2
  for xc,yc,half,top in [(25,0,9,34),(48,112,7,32),(44,144,7,32)]:
   rect=np.array([[xc-half+.1,2.1],[xc+half-.1,2.1],[xc+half-.1,top-.1],[xc-half+.1,top-.1]])*U
   for tri in architecture:
    normal=np.cross(tri[1]-tri[0],tri[2]-tri[0])
    if abs(normal[2])>.99*np.linalg.norm(normal) and np.ptp(tri[:,2])<1e-5 and abs(tri[0,2]+yc*U)<1.7*U:assert clipped_area(tri[:,[0,1]],rect)<1e-4,'Architectural trim blocks doorway'
  for i,a in enumerate(paving):
   for b in paving[i+1:]:
    if abs(a[0,1]-b[0,1])<1e-5:assert clipped_area(a[:,[0,2]],b[:,[0,2]])<1e-4,'Overlapping paving tops'
  for s in info['machine_slots']:
   o=nodes[s['node']];assert o['extras']['reference_only'] and np.allclose(o['translation'],s['position']) and s['fixture_node'] in kitinfo['machine_nodes'];q=o.get('rotation',[0,0,0,1]);v=np.array([0.,0.,1.]);xyz=np.array(q[:3]);out=v+2*np.cross(xyz,np.cross(xyz,v)+q[3]*v);assert np.allclose(out,s['outward'],atol=1e-6) and np.isclose(o['translation'][1],2*U)
  assert len(info['machine_slots'])==11 and sum(s['kind']=='washer' for s in info['machine_slots'])==6 and sum(s['kind']=='dryer' for s in info['machine_slots'])==4
  material=next(m for m in g['materials'] if m['name']=='glass_storefront');assert material.get('alphaMode') in ['BLEND','MASK'];_,_,p=images[g['textures'][material['pbrMetallicRoughness']['baseColorTexture']['index']]['source']];assert p[:,:,3].min()<255 and p[:,:,3].max()==255
  p=g['meshes'][nodes['sign_name']['mesh']]['primitives'][0];assert g['materials'][p['material']]['name']=='sign_name';pos=acc(p['attributes']['POSITION']);assert np.isclose(np.ptp(pos[:,0]),112*U) and np.isclose(np.ptp(pos[:,1]),18*U)
  bays=[b['bounds_blender_art'] for b in info['parking']['bays']];assert len(bays)==4
  for i,a in enumerate(bays):
   assert a[3]<=-24 and a[3]-a[1]==64
   for b in bays[i+1:]:assert not(min(a[2],b[2])>max(a[0],b[0]) and min(a[3],b[3])>max(a[1],b[1]))
  for name in ['outside_bench','rear_bin','roadside_sign']:props[name]=node_count(name);assert 0<props[name]<500
 return {'file':file,'visible_triangles':sum(counts.values()),'materials':[m['name'] for m in g['materials']],'collision_boxes':len(boxes),'prop_triangles':props,'uv_scale_max_edge_error_m':max_error,'bytes':len(raw)}
a=validate('coin-laundromat.glb',info);b=validate('laundry-fixtures.glb',kitinfo,True);assert a['visible_triangles']+b['visible_triangles']<=12000 and len(set(a['materials']+b['materials']))<=12
result={'primary':a,'optional_kit':b,'combined_visible_triangles':a['visible_triangles']+b['visible_triangles'],'combined_materials':len(set(a['materials']+b['materials'])),'separation':'Primary GLB is a clean shell: no machine meshes. Optional kit contains six washers, four dryers and one coin cabinet.','fixtures':'All eleven static kit fixtures stay below 500 triangles and have full-height matching colliders. No operating or billing behavior.','anchors':'Slot floor heights, outward directions and kit mappings verified.','art':'All embedded PNGs use exact 2 x 2 texel art pixels, nearest/repeat sampling and physical UV density.','glazing':'Actual alpha panes exported; game importer transparency integration remains pending.','architecture':'Butterfly roof/gable normals, clear doorways, paving and four non-overlapping parking templates verified.','renders':'Blender desktop previews, not Filament/S25 captures. Views 01–06 include optional kit; 07 is the primary shell alone.'}
(R/'validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
