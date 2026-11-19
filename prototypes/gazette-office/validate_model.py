"""Actual GLB validation: art/UV scale, floors, doors, stair opening and collision handoff."""
from pathlib import Path
import json,struct,io
import numpy as np
from PIL import Image
R=Path(__file__).resolve().parent;info=json.loads((R/'model-info.json').read_text());raw=(R/'gazette-office.glb').read_bytes();assert struct.unpack_from('<4sII',raw)==(b'glTF',2,len(raw));n,t=struct.unpack_from('<II',raw,12);assert t==0x4e4f534a;g=json.loads(raw[20:20+n]);nb,t=struct.unpack_from('<II',raw,20+n);assert t==0x004e4942;binary=raw[28+n:28+n+nb]
assert len(g['materials'])<=12 and all('uri' not in x for k in ['images','buffers'] for x in g[k]);assert all(s['magFilter']==9728 and s.get('minFilter',9728) in [9728,9984] and s.get('wrapS',10497)==s.get('wrapT',10497)==10497 for s in g['samplers'])
images=[]
for im in g['images']:
 v=g['bufferViews'][im['bufferView']];a=v.get('byteOffset',0);p=np.array(Image.open(io.BytesIO(binary[a:a+v['byteLength']])).convert('RGBA'));h,w=p.shape[:2];assert h%2==w%2==0 and np.array_equal(p,p[::2,::2].repeat(2,0).repeat(2,1)),im['name'];images.append((w,h,p))
ct={5126:('f',4),5125:('I',4),5123:('H',2),5121:('B',1)};ws={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}
def acc(i):
 a=g['accessors'][i];v=g['bufferViews'][a['bufferView']];typ,size=ct[a['componentType']];w=ws[a['type']];offset=v.get('byteOffset',0)+a.get('byteOffset',0);stride=v.get('byteStride',size*w);return np.array([struct.unpack_from('<'+typ*w,binary,offset+j*stride) for j in range(a['count'])])
nodes={x['name']:x for x in g['nodes']}
for name in ['gazette_office','structure','stairs','lot','projecting_sign','sign_name','anchor_printing_press','collision']:assert name in nodes,name
assert nodes['collision']['extras']['collision_only'] and nodes['collision']['extras']['render'] is False
assert nodes['anchor_printing_press']['extras']['reference_only'];assert np.allclose(nodes['anchor_printing_press']['translation'],[-12*.0776,3*.0776,-136*.0776])
for d in info['doors']:
 node=nodes[d['node']];assert np.allclose(node.get('translation',[0,0,0]),d['position']) and np.isclose(d['position'][1],d['floor_m']);q=node.get('rotation',[0,0,0,1]);v=np.array([0.,0.,1.]);xyz=np.array(q[:3]);out=v+2*np.cross(xyz,np.cross(xyz,v)+q[3]*v);assert np.allclose(out,d['outward'],atol=1e-6)
 assert d['panel'] in nodes and any(c.get('gate_node')==d['panel'] for c in info['collision']['boxes_blender_z_up'])
assert len(info['doors'])==3
reg=json.loads((R/'art-regions.json').read_text());utility_rects=[reg[k]['rect_art'] for k in ['main_door','side_door','loading_leaf','paper','vent']]
# Convex polygon clipping for coplanar overlaps and aperture checks.
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
stair_meshes={g['nodes'][j].get('mesh') for j in nodes['stairs'].get('children',[])}
counts={};max_error=0;bounds=[];paving=[];floors=[];architecture=[];stair_tops=set()
for i,m in enumerate(g['meshes']):
 if i==nodes['collision']['mesh']:continue
 count=0
 for p in m['primitives']:
  pos=acc(p['attributes']['POSITION']);uv=acc(p['attributes']['TEXCOORD_0']);idx=acc(p['indices']).ravel().astype(int);assert np.isfinite(pos).all() and np.isfinite(uv).all() and len(idx)%3==0 and idx.min()>=0 and idx.max()<len(pos);bounds.extend(pos)
  ma=g['materials'][p['material']];w,h,_=images[g['textures'][ma['pbrMetallicRoughness']['baseColorTexture']['index']]['source']];pixels=uv*np.array([w,h]);physical=pixels*.0388
  for a,b,c in idx.reshape(-1,3):
   tri=pos[[a,b,c]];cr=np.cross(pos[b]-pos[a],pos[c]-pos[a]);assert np.linalg.norm(cr)>1e-12,(m['name'],'degenerate')
   for j,k in [(a,b),(b,c),(c,a)]:max_error=max(max_error,abs(np.linalg.norm(pos[j]-pos[k])-np.linalg.norm(physical[j]-physical[k])))
   if i in stair_meshes and ma['name']=='wall_interior' and cr[1]>.001 and np.ptp(tri[:,1])<1e-5:stair_tops.add(round(float(tri[0,1])/.0776,3))
   if ma['name']=='detail_panels':
    corners=pixels[[a,b,c]];assert any(np.all(corners>=np.array([x,y])*2-.001) and np.all(corners<=np.array([x+ww,y+hh])*2+.001) for x,y,ww,hh in utility_rects),('Atlas bleed',m['name'])
   if ma['name']=='ground_paving' and cr[1]>.001 and np.ptp(tri[:,1])<1e-5:paving.append(tri)
   if i not in stair_meshes and ma['name']=='wall_interior' and cr[1]>.001 and np.allclose(tri[:,1],50*.0776,atol=1e-6):floors.append(tri)
   if ma['name'] in ['wall_brick','wall_interior','detail_stone']:architecture.append(tri)
  count+=len(idx)//3
 counts[i]=count
assert max_error<.001,('UV scale error',max_error)
triangles=sum(counts.values());assert triangles==info['triangles_visible'] and triangles<=12000
assert np.array(bounds)[:,1].min()>=-1e-6
for i,a in enumerate(paving):
 for b in paving[i+1:]:
  if abs(a[0,1]-b[0,1])<1e-5:assert clipped_area(a[:,[0,2]],b[:,[0,2]])<1e-4,'Overlapping paving tops'
# Front/rear plinths must not block either ground-floor passage.
for xc,yc,half in [(-40,0,8),(0,88,12)]:
 rect=np.array([[xc-half+.1,2.1],[xc+half-.1,2.1],[xc+half-.1,33.9],[xc-half+.1,33.9]])*.0776
 for tri in architecture:
  normal=np.cross(tri[1]-tri[0],tri[2]-tri[0])
  if abs(normal[2])>.99*np.linalg.norm(normal) and np.ptp(tri[:,2])<1e-5 and abs(tri[0,2]+yc*.0776)<1.7*.0776:assert clipped_area(tri[:,[0,1]],rect)<1e-4,'Masonry blocks doorway'
# Upper floor stays cut out above the staircase, except its intended top landing.
hole=np.array([[-61.9,-85.9],[-30.1,-85.9],[-30.1,-40.1],[-61.9,-40.1]])*.0776
assert floors
for tri in floors:assert clipped_area(tri[:,[0,2]],hole)<1e-4,'Upper floor fills stairwell'
assert stair_tops==set(range(4,51,2)),('Actual stair heights',stair_tops)
assert info['stairs']['risers']==24 and np.isclose(info['stairs']['lower_floor_m']+24*info['stairs']['rise_m'],info['stairs']['upper_floor_m'])
s=nodes['sign_name'];m=g['meshes'][s['mesh']];assert len(m['primitives'])==1;p=m['primitives'][0];assert g['materials'][p['material']]['name']=='sign_name';v=acc(p['attributes']['POSITION']);assert np.isclose(np.ptp(v[:,0]),100*.0776) and np.isclose(np.ptp(v[:,1]),14*.0776)
boxes=info['collision']['boxes_blender_z_up'];assert 0<len(boxes)<=48
for b in boxes:assert b['attachment'] in nodes and all(x>0 for x in b['size']) and np.isclose(b['center'][2]-b['size'][2]/2,0)
cp=np.concatenate([acc(p['attributes']['POSITION']) for p in g['meshes'][nodes['collision']['mesh']]['primitives']])
for b in boxes:
 x,y,z=b['center'];w,d,h=b['size']
 for a,s,c in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]:assert np.min(np.linalg.norm(cp-np.array([x+a*w/2,z+c*h/2,-y-s*d/2]),axis=1))<1e-5
props={name:sum(counts.get(g['nodes'][j].get('mesh'),0) for j in node.get('children',[])) for name,node in nodes.items() if (name.startswith('news_box') or name.startswith('service_bin') or name=='paper_roll_cart') and 'mesh' not in node};assert all(0<n<500 for n in props.values())
result={'visible_triangles':triangles,'materials':len(g['materials']),'external_doors':len(info['doors']),'collision_boxes':len(boxes),'prop_triangles':props,'uv_scale_max_edge_error_m':max_error,'textures':'Embedded PNGs use exact 2 x 2 texel art pixels, nearest/repeat sampling','stairs':'24 risers connect ground/upper floors; upper floor aperture verified','doorways':'Outward anchors and floor heights match; plinths leave front and internal passages clear','paving':'No positive-area coplanar top overlap; all exported geometry at or above grade','sign':'Separate physical-scale sign_name mesh and documented masthead region','collision':'Full-height boxes match exported collider; architecture uses its walls/floors/stairs','press':'Foundation/reference only; no printing or archive simulation implemented','glb_bytes':len(raw),'renders':'Blender desktop previews, not Filament/S25 captures.'}
(R/'validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
