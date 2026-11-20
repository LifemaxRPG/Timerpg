"""Validate both actual library states, open portals, basement stairs, UV density and glTF collision coordinates."""
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

def validate(state):
 info=json.loads((R/('state-'+state+'.json')).read_text());raw=(R/info['file']).read_bytes();assert struct.unpack_from('<4sII',raw)==(b'glTF',2,len(raw));n,t=struct.unpack_from('<II',raw,12);assert t==0x4e4f534a;g=json.loads(raw[20:20+n]);nb,t=struct.unpack_from('<II',raw,20+n);assert t==0x004e4942;binary=raw[28+n:28+n+nb]
 assert len(g['materials'])<=12 and all('uri' not in x for k in ['images','buffers'] for x in g[k]);assert all(s['magFilter']==9728 and s.get('minFilter',9728) in [9728,9984] and s.get('wrapS',10497)==s.get('wrapT',10497)==10497 for s in g['samplers'])
 images=[]
 for im in g['images']:
  v=g['bufferViews'][im['bufferView']];a=v.get('byteOffset',0);p=np.array(Image.open(io.BytesIO(binary[a:a+v['byteLength']])).convert('RGBA'));h,w=p.shape[:2];assert h%2==w%2==0 and np.array_equal(p,p[::2,::2].repeat(2,0).repeat(2,1)),im['name'];images.append((w,h,p))
 def acc(i):
  a=g['accessors'][i];v=g['bufferViews'][a['bufferView']];typ,size=ct[a['componentType']];w=ws[a['type']];offset=v.get('byteOffset',0)+a.get('byteOffset',0);stride=v.get('byteStride',size*w);return np.array([struct.unpack_from('<'+typ*w,binary,offset+j*stride) for j in range(a['count'])])
 nodes={x['name']:x for x in g['nodes']};assert nodes['collision']['extras']['collision_only'] and nodes['collision']['extras']['render'] is False
 counts={};max_error=0;bounds=[];architecture=[];paving=[];roofs=0;arch_area=0;rects=[v['rect_art'] for k,v in reg.items() if k in ['arch','modern','ribbon','link','basement']];stair_tops=[]
 for i,m in enumerate(g['meshes']):
  if i==nodes['collision']['mesh']:continue
  count=0
  for p in m['primitives']:
   pos=acc(p['attributes']['POSITION']);uv=acc(p['attributes']['TEXCOORD_0']);idx=acc(p['indices']).ravel().astype(int);assert np.isfinite(pos).all() and np.isfinite(uv).all() and len(idx)%3==0 and idx.min()>=0 and idx.max()<len(pos);bounds.extend(pos)
   ma=g['materials'][p['material']];w,h,_=images[g['textures'][ma['pbrMetallicRoughness']['baseColorTexture']['index']]['source']];pixels=uv*np.array([w,h]);physical=pixels*.0388
   for a,b,c in idx.reshape(-1,3):
    tri=pos[[a,b,c]];cr=np.cross(pos[b]-pos[a],pos[c]-pos[a]);assert np.linalg.norm(cr)>1e-12,(state,m['name'],'degenerate')
    for j,k in [(a,b),(b,c),(c,a)]:max_error=max(max_error,abs(np.linalg.norm(pos[j]-pos[k])-np.linalg.norm(physical[j]-physical[k])))
    if ma['name']=='glass_reading':
     corners=pixels[[a,b,c]];assert any(np.all(corners>=np.array([x,y])*2-.001) and np.all(corners<=np.array([x+ww,y+hh])*2+.001) for x,y,ww,hh in rects),('Glass atlas bleed',m['name'])
    if ma['name']=='glass_reading' and np.all(pixels[[a,b,c]]>=-.001) and np.all(pixels[[a,b,c]]<=np.array([56,80])+.001):
     pu=pixels[[a,b,c]]/2;arch_area+=abs(cross2(pu[1]-pu[0],pu[2]-pu[0]))/2
     for u,vv in pu:assert vv>=14-np.sqrt(max(0,14*14-(u-14)**2))-.001,'Rectangular geometry protrudes above arch'
    if ma['name']=='roof_slate' and abs(cr[1])>.5*np.linalg.norm(cr):assert cr[1]>0,'Roof pitch inverted';roofs+=1
    if ma['name'] in ['wall_brick','detail_stone','wall_interior']:architecture.append(tri)
    if ma['name']=='ground_paving' and cr[1]>.001 and np.ptp(tri[:,1])<1e-5:paving.append(tri)
    if i==nodes['basement_stairs__detail_wood']['mesh'] and ma['name']=='detail_wood' and cr[1]>.001 and np.ptp(tri[:,1])<1e-5:stair_tops.append(float(tri[0,1])/U)
   count+=len(idx)//3
  counts[i]=count
 assert max_error<.001 and sum(counts.values())==info['triangles_visible']<=12000;assert np.array(bounds)[:,1].min()>=-18*U-1e-6 and roofs>=6
 assert np.isclose(arch_area,10*(28*26+4*14*14*np.sin(np.pi/8)),atol=.05),'Ten faceted arched pane areas do not match export'
 assert set(round(z,3) for z in stair_tops)==set(range(-14,25,2)),'Basement does not have twenty distinct riser elevations'
 # Floor height and the real stair aperture must survive export.
 wood_tris=[]
 for m in g['meshes']:
  if m is not g['meshes'][nodes['structure__detail_wood']['mesh']]:continue
  for p in m['primitives']:
   if g['materials'][p['material']]['name']!='detail_wood':continue
   po=acc(p['attributes']['POSITION']);ix=acc(p['indices']).ravel().astype(int)
   for tri in po[ix.reshape(-1,3)]:
    cr=np.cross(tri[1]-tri[0],tri[2]-tri[0])
    if cr[1]>0 and np.allclose(tri[:,1],24*U,atol=1e-6):wood_tris.append(tri)
 hole=np.array([[-31,139],[31,139],[31,179],[-31,179]])*U;hole[:,1]*=-1
 assert all(clipped_area(t[:,[0,2]],hole)<1e-4 for t in wood_tris),'Main floor seals stairwell'
 boxes=info['collision']['boxes_gltf_y_up'];assert info['collision']['coordinate_frame']=='glTF metres: Y up, +Z front' and len(boxes)<=48
 cp=np.concatenate([acc(p['attributes']['POSITION']) for p in g['meshes'][nodes['collision']['mesh']]['primitives']])
 for b in boxes:
  assert b['attachment'] in nodes and all(x>0 for x in b['size']) and 'gate_node' not in b
  x,y,z=b['center'];w,h,d=b['size'];assert np.isclose(y-h/2,b.get('support_floor_m',0)),b
  for a,s,c in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]:assert np.min(np.linalg.norm(cp-np.array([x+a*w/2,y+s*h/2,z+c*d/2]),axis=1))<1e-5
 assert not any(n.startswith('panel_door') for n in nodes),'Exterior door panels are forbidden by batch-3 brief'
 for d in info['doors']:
  o=nodes[d['node']];assert 'mesh' not in o and d['open'] and np.allclose(o['translation'],d['position']) and np.isclose(d['floor_m'],24*U);q=o.get('rotation',[0,0,0,1]);v=np.array([0.,0.,1.]);xyz=np.array(q[:3]);out=v+2*np.cross(xyz,np.cross(xyz,v)+q[3]*v);assert np.allclose(out,d['outward'],atol=1e-6)
 # Test every actual threshold against architectural surfaces. Source faces are axis-aligned.
 apertures=[(0,0,12,24,62,'x'),(76,180,10,24,60,'x')]+([(-116,254,10,24,60,'y'),(0,300,10,24,60,'x')] if state=='1970s' else [])
 for xc,yc,half,bottom,top,axis in apertures:
  center=xc if axis=='x' else yc;fixed=-yc if axis=='x' else xc;plane=2 if axis=='x' else 0;other=0 if axis=='x' else 2;rect=np.array([[center-half+.2,bottom+.2],[center+half-.2,bottom+.2],[center+half-.2,top-.2],[center-half+.2,top-.2]])*U
  if axis=='y':rect[:,0]*=-1
  for tri in architecture:
   normal=np.cross(tri[1]-tri[0],tri[2]-tri[0])
   if abs(normal[plane])>.99*np.linalg.norm(normal) and np.ptp(tri[:,plane])<1e-5 and abs(tri[0,plane]-fixed*U)<1.7*U:assert clipped_area(tri[:,[other,1]],rect)<1e-4,('Blocked doorway',state,xc,yc)
 for i,a in enumerate(paving):
  for b in paving[i+1:]:
   if abs(a[0,1]-b[0,1])<1e-5:assert clipped_area(a[:,[0,2]],b[:,[0,2]])<1e-4,'Overlapping paving/stair tops'
 def node_count(o):return counts.get(o.get('mesh'),0)+sum(node_count(g['nodes'][j]) for j in o.get('children',[]))
 props={}
 for j in nodes['lot'].get('children',[]):
  o=g['nodes'][j]
  if o.get('children'):props[o['name']]=node_count(o);assert 0<props[o['name']]<500
 # Intentional basement below grade is permitted; optional exterior grounds are never sunken.
 def points(o):
  a=[]
  if 'mesh' in o:a.extend(acc(p['attributes']['POSITION']) for p in g['meshes'][o['mesh']]['primitives'])
  for j in o.get('children',[]):a.extend(points(g['nodes'][j]))
  return a
 assert np.concatenate(points(nodes['lot']))[:,1].min()>=-1e-6
 pp=g['meshes'][nodes['sign_name']['mesh']]['primitives'][0];assert g['materials'][pp['material']]['name']=='sign_name';po=acc(pp['attributes']['POSITION']);assert np.isclose(np.ptp(po[:,0]),100*U) and np.isclose(np.ptp(po[:,1]),16*U)
 ma=next(m for m in g['materials'] if m['name']=='glass_reading');assert ma.get('alphaMode') in ['BLEND','MASK'] and 'emissiveTexture' in ma
 return {'state':state,'file':info['file'],'triangles':sum(counts.values()),'materials':len(g['materials']),'doors':len(info['doors']),'collision_boxes':len(boxes),'props':props,'uv_max_edge_error_m':max_error,'bytes':len(raw)}
results=[validate(s) for s in ['1912','1970s']];base=json.loads((R/'state-1912.json').read_text());later=json.loads((R/'state-1970s.json').read_text())
for d in base['doors']:
 q=next(x for x in later['doors'] if x['node']==d['node']);assert q['position']==d['position'] and q['outward']==d['outward']
result={'states':results,'art':'Exact 2x2 texel blocks, nearest/repeat samplers, physical 0.0388m/texel UVs and atlas containment verified.','architecture':'Real arched panes, pitched roof orientation, twenty basement riser elevations, genuine floor aperture and clear exterior portals validated.','collision':'All metadata boxes are in glTF Y-up metres; actual exported collider corners match them. Upstairs counter starts at its support floor. Optional lot remains at/above grade.','variants':'Original door coordinates and outward directions are identical across both states. Exterior doors have no closed panels or gate boxes.','runtime':'Terrain cutout, basement/stair navigation, glTF collision metadata parsing, state choice and lighting require importer/game integration.','renders':'Blender desktop renders, not Filament/S25 captures.'}
(R/'validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
