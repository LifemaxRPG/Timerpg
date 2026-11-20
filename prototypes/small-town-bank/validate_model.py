"""Validate both actual bank GLBs, open entries, fixed vault, teller panes, lane clearances and glTF collision data."""
from pathlib import Path
import json,struct,io
import numpy as np
from PIL import Image
R=Path(__file__).resolve().parent;reg=json.loads((R/'art-regions.json').read_text());U=.0776
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

COMMON={}
def validate(state):
 info=json.loads((R/('state-'+state+'.json')).read_text());raw=(R/info['file']).read_bytes();assert struct.unpack_from('<4sII',raw)==(b'glTF',2,len(raw));n,t=struct.unpack_from('<II',raw,12);assert t==0x4e4f534a;g=json.loads(raw[20:20+n]);nb,t=struct.unpack_from('<II',raw,20+n);assert t==0x004e4942;binary=raw[28+n:28+n+nb]
 assert len(g['materials'])<=12 and all('uri' not in x for k in ['images','buffers'] for x in g[k]);assert all(s['magFilter']==9728 and s.get('minFilter',9728) in [9728,9984] and s.get('wrapS',10497)==s.get('wrapT',10497)==10497 for s in g['samplers'])
 images=[]
 for im in g['images']:
  v=g['bufferViews'][im['bufferView']];a=v.get('byteOffset',0);p=np.array(Image.open(io.BytesIO(binary[a:a+v['byteLength']])).convert('RGBA'));h,w=p.shape[:2];assert h%2==w%2==0 and np.array_equal(p,p[::2,::2].repeat(2,0).repeat(2,1)),im['name'];images.append((w,h,p))
 def acc(i):
  a=g['accessors'][i];v=g['bufferViews'][a['bufferView']];typ,size=ct[a['componentType']];w=ws[a['type']];offset=v.get('byteOffset',0)+a.get('byteOffset',0);stride=v.get('byteStride',size*w);return np.array([struct.unpack_from('<'+typ*w,binary,offset+j*stride) for j in range(a['count'])])
 nodes={x['name']:x for x in g['nodes']};assert nodes['collision']['extras']['collision_only'] and nodes['collision']['extras']['render'] is False
 counts={};max_error=0;bounds=[];alltris=[];paving=[];floor_area=0;ceil_area=0;arch_area=0;teller_area=0;common=[]
 glass_rects=[reg[k]['rect_art'] for k in ['arch','tall','side','rear','sidelight','teller']];utility_rects=[reg[k]['rect_art'] for k in ['wood','terminal','night_drop','tray','louver','lane_1','lane_2']]
 for i,m in enumerate(g['meshes']):
  if i==nodes['collision']['mesh']:continue
  count=0
  for p in m['primitives']:
   pos=acc(p['attributes']['POSITION']);uv=acc(p['attributes']['TEXCOORD_0']);idx=acc(p['indices']).ravel().astype(int);assert np.isfinite(pos).all() and np.isfinite(uv).all() and len(idx)%3==0 and idx.min()>=0 and idx.max()<len(pos);bounds.extend(pos)
   ma=g['materials'][p['material']];w,h,_=images[g['textures'][ma['pbrMetallicRoughness']['baseColorTexture']['index']]['source']];pixels=uv*np.array([w,h]);physical=pixels*.0388
   for a,b,c in idx.reshape(-1,3):
    tri=pos[[a,b,c]];cr=np.cross(pos[b]-pos[a],pos[c]-pos[a]);area=np.linalg.norm(cr)/2;assert area>1e-12,(state,m['name'],'degenerate')
    for j,k in [(a,b),(b,c),(c,a)]:max_error=max(max_error,abs(np.linalg.norm(pos[j]-pos[k])-np.linalg.norm(physical[j]-physical[k])))
    rects=glass_rects if ma['name']=='glass_bank' else utility_rects if ma['name']=='detail_panels' else None
    if rects:
     corners=pixels[[a,b,c]];assert any(np.all(corners>=np.array([x,y])*2-.001) and np.all(corners<=np.array([x+ww,y+hh])*2+.001) for x,y,ww,hh in rects),('Atlas bleed',state,m['name'])
    if ma['name']=='glass_bank':
     corners=pixels[[a,b,c]]/2
     if np.all(corners>=-.001) and np.all(corners<=np.array([44,36])+.001):
      arch_area+=abs(cross2(corners[1]-corners[0],corners[2]-corners[0]))/2
      for u,vv in corners:assert vv>=22-np.sqrt(max(0,22**2-(u-22)**2))-.001,'Rectangle protrudes above real arch'
     if np.all(corners>=np.array([136,0])-.001) and np.all(corners<=np.array([166,22])+.001):teller_area+=area
    else:alltris.append(tri)
    if ma['name']=='ground_paving' and cr[1]>.001 and np.ptp(tri[:,1])<1e-5:paving.append(tri)
    if ma['name']=='ground_floor' and cr[1]>.001 and np.allclose(tri[:,1],4*U,atol=1e-6):floor_area+=area
    if ma['name']=='wall_interior' and cr[1]<-.001 and np.allclose(tri[:,1],86*U,atol=1e-6):ceil_area+=area
   count+=len(idx)//3
  counts[i]=count
 assert max_error<.001 and sum(counts.values())==info['triangles_visible']<=12000 and np.array(bounds)[:,1].min()>=-1e-6
 assert np.isclose(floor_area,180*232*U*U,atol=1e-4) and np.isclose(ceil_area,180*232*U*U,atol=1e-4)
 assert np.isclose(arch_area,44*14+4*22**2*np.sin(np.pi/8),atol=.01) and np.isclose(teller_area,4*30*22*U*U,atol=1e-4)
 boxes=info['collision']['boxes_gltf_y_up'];assert info['collision']['coordinate_frame']=='glTF metres: Y up, +Z front' and len(boxes)<=48
 cp=np.concatenate([acc(p['attributes']['POSITION']) for p in g['meshes'][nodes['collision']['mesh']]['primitives']])
 for b in boxes:
  assert b['attachment'] in nodes and all(x>0 for x in b['size']) and 'gate_node' not in b
  x,y,z=b['center'];w,h,d=b['size'];assert np.isclose(y-h/2,b.get('support_floor_m',0)),b
  for a,s,c in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]:assert np.min(np.linalg.norm(cp-np.array([x+a*w/2,y+s*h/2,z+c*d/2]),axis=1))<1e-5
 assert len(info['doors'])==2 and not any(n.startswith('panel_door') for n in nodes)
 for d in info['doors']:
  o=nodes[d['node']];assert 'mesh' not in o and d['open'] and np.allclose(o['translation'],d['position']) and np.isclose(d['floor_m'],4*U);q=o.get('rotation',[0,0,0,1]);v=np.array([0.,0.,1.]);xyz=np.array(q[:3]);out=v+2*np.cross(xyz,np.cross(xyz,v)+q[3]*v);assert np.allclose(out,d['outward'],atol=1e-6)
 for x,y,half,lo,hi in [(0,0,12,4,42),(-68,232,8,4,38)]:
  rect=np.array([[x-half+.2,lo+.2],[x+half-.2,lo+.2],[x+half-.2,hi-.2],[x-half+.2,hi-.2]])*U
  for t in alltris:
   normal=np.cross(t[1]-t[0],t[2]-t[0])
   if abs(normal[2])>.99*np.linalg.norm(normal) and np.ptp(t[:,2])<1e-5 and abs(t[0,2]+y*U)<1.7*U:assert clipped_area(t[:,[0,1]],rect)<1e-4,('Blocked exterior threshold',state,x)
 for i,a in enumerate(paving):
  for b in paving[i+1:]:
   if abs(a[0,1]-b[0,1])<1e-5:assert clipped_area(a[:,[0,2]],b[:,[0,2]])<1e-4,'Coplanar paving overlaps'
 def collect(o):
  a=[]
  if 'mesh' in o:
   for p in g['meshes'][o['mesh']]['primitives']:
    po=acc(p['attributes']['POSITION']);ix=acc(p['indices']).ravel().astype(int);a.extend(po[ix.reshape(-1,3)])
  for j in o.get('children',[]):a.extend(collect(g['nodes'][j]))
  return a
 def count(o):return counts.get(o.get('mesh'),0)+sum(count(g['nodes'][j]) for j in o.get('children',[]))
 # All original structural groups and the fixed vault are identical across states.
 for j in nodes['structure'].get('children',[]):
  o=g['nodes'][j]
  if o['name']=='drive_through':continue
  common.extend(tuple(np.round(t.ravel(),6)) for t in collect(o))
 COMMON[state]=sorted(common)
 assert nodes['vault_door'] and info['vault']['fixed_closed'];vault=np.concatenate(collect(nodes['vault_door']));b=next(b for b in boxes if b['source']=='Fixed vault front');ce=np.array(b['center']);sz=np.array(b['size']);assert np.all(vault.min(0)>=ce-sz/2-1e-5) and np.all(vault.max(0)<=ce+sz/2+1e-5),'Vault collider misses hardware'
 later=state=='drive-through';drive=info['drive_through'];assert drive['present']==later and ('drive_through' in nodes)==later and ('night_deposit' in nodes)==later and len(drive['lanes'])==(2 if later else 0)
 props={}
 if later:
  assert len(drive['terminals'])==2
  for name in drive['terminals']:props[name]=count(nodes[name]);assert 0<props[name]<500
  a,b=drive['lanes'];assert a['bounds_x_m'][1]<b['bounds_x_m'][0]
  for lane in drive['lanes']:
   centre=lane['service'][0];car_min=np.array([centre-12*U,.5*U,-264*U]);car_max=np.array([centre+12*U,.5*U+1.8,28*U])
   for box in boxes:
    ce=np.array(box['center']);sz=np.array(box['size']);assert not np.all(np.minimum(car_max,ce+sz/2)-np.maximum(car_min,ce-sz/2)>1e-5),('Reference vehicle corridor blocked',box['source'])
  # Check the actual overhead surfaces over both lane reference corridors.
  overhead=[]
  for t in collect(nodes['drive_through']):
   cr=np.cross(t[1]-t[0],t[2]-t[0])
   if cr[1]>=-.001 or np.ptp(t[:,1])>1e-5:continue
   for lane in drive['lanes']:
    c=lane['service'][0];r=np.array([[c-12*U,-264*U],[c+12*U,-264*U],[c+12*U,28*U],[c-12*U,28*U]])
    if clipped_area(t[:,[0,2]],r)>1e-4:overhead.append(t[0,1])
  assert np.isclose(min(overhead)-.5*U,info['heights_m']['canopy_nominal_clearance'],atol=1e-5)
 for j in nodes['lot'].get('children',[]):
  o=g['nodes'][j]
  if o.get('children'):props[o['name']]=count(o);assert props[o['name']]<500
 pp=g['meshes'][nodes['sign_name']['mesh']]['primitives'][0];po=acc(pp['attributes']['POSITION']);assert g['materials'][pp['material']]['name']=='sign_name' and np.isclose(np.ptp(po[:,0]),128*U) and np.isclose(np.ptp(po[:,1]),18*U)
 ma=next(m for m in g['materials'] if m['name']=='glass_bank');assert ma.get('alphaMode') in ['BLEND','MASK'] and 'emissiveTexture' in ma
 tex=g['textures'][ma['emissiveTexture']['index']];mask=images[tex['source']][2];assert not mask[0:44,272:332,:3].any(),'Teller glass should not glow'
 name=next(m for m in g['materials'] if m['name']=='sign_name');assert 'emissiveTexture' not in name and np.allclose(name.get('emissiveFactor',[0,0,0]),[0,0,0])
 return {'state':state,'triangles':sum(counts.values()),'materials':len(g['materials']),'open_exterior_doors':2,'collision_boxes':len(boxes),'props':props,'uv_max_edge_error_m':max_error,'bytes':len(raw)}
results=[validate(s) for s in ['1920s','drive-through']];a=json.loads((R/'state-1920s.json').read_text());b=json.loads((R/'state-drive-through.json').read_text());assert a['doors']==b['doors'] and COMMON['1920s']==COMMON['drive-through'],'Original bank/vault shell changed in later state'
r={'states':results,'art':'Exact 2x2 texel blocks, nearest/repeat PNGs, physical 0.0388m/texel UVs and atlas containment validated. Real faceted arch area and four clear teller pane areas verified.','architecture':'Original floor/ceiling footprints, open exterior apertures, fixed vault hardware coverage, identical original structural groups and door anchors checked.','drive_through':'Two separate reference lanes, terminal budgets, clear 1.8624m-wide/1.8m-high reference vehicle corridors and actual overhead clearance verified geometrically. No driving or banking behavior is implemented.','collision':'All metadata boxes are already glTF Y-up metres, with exact corresponding exported corners and support floors.','lighting':'Teller pane emission mask is black; carved name frieze has zero emission. Exterior panes/fixtures alone receive staged light.','runtime':'State choice, per-town name repainting, glTF collision parsing, lighting, navigation and vehicle/transaction interactions remain game integration work.','renders':'Blender desktop previews, not Filament/S25 captures.'}
(R/'validation.json').write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r,indent=2))
