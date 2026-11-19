"""Actual exported buffers, room/parking layout, pool cutout and isolated sign states."""
from pathlib import Path
import struct,json,io,tempfile,hashlib
from PIL import Image
import numpy as np
from sign_art import write_name,write_vacancy
R=Path(__file__).resolve().parent;raw=(R/'roadside-motel.glb').read_bytes();info=json.loads((R/'model-info.json').read_text());reg=json.loads((R/'art-regions.json').read_text())
assert struct.unpack_from('<4sII',raw)==(b'glTF',2,len(raw));n,t=struct.unpack_from('<II',raw,12);g=json.loads(raw[20:20+n]);o=20+n;nb,t=struct.unpack_from('<II',raw,o);binary=raw[o+8:o+8+nb];assert t==0x004e4942
assert len(g['materials'])<=12 and all('uri' not in v for k in ('buffers','images') for v in g[k]);assert all(s['magFilter']==9728 and s.get('minFilter',9728) in (9728,9984) and s.get('wrapS',10497)==10497 and s.get('wrapT',10497)==10497 for s in g['samplers'])
images=[]
for im in g['images']:
 v=g['bufferViews'][im['bufferView']];a=v.get('byteOffset',0);pixels=np.array(Image.open(io.BytesIO(binary[a:a+v['byteLength']])).convert('RGBA'));h,w=pixels.shape[:2];assert h%2==w%2==0 and np.array_equal(pixels,pixels[::2,::2].repeat(2,0).repeat(2,1)),im['name'];images.append((w,h))
types={5126:('f',4),5125:('I',4),5123:('H',2),5121:('B',1)};widths={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}
def access(i):
 a=g['accessors'][i];v=g['bufferViews'][a['bufferView']];c,size=types[a['componentType']];w=widths[a['type']];offset=v.get('byteOffset',0)+a.get('byteOffset',0);stride=v.get('byteStride',size*w);return np.array([struct.unpack_from('<'+c*w,binary,offset+j*stride) for j in range(a['count'])])
names={n['name']:n for n in g['nodes']}
for name in ['lot','pool','pool_gate','ice_machine','roadside_sign','sign_name','sign_name_back','glow_vacancy_panel','glow_vacancy_panel_back','collision']:assert name in names,name
assert names['collision']['extras']['collision_only'] and names['collision']['extras']['render'] is False
assert info['collision']['use_the_walls'] and len(info['collision']['boxes_blender_z_up'])<=48
rooms=info['rooms'];assert len(rooms)==info['room_count']==12 and {r['number'] for r in rooms}==set(range(1,13))
for room in rooms:assert room['door'] in names and room['window'] in names
for d in info['doors']:
 node=names[d['node']];assert np.allclose(node.get('translation',[0,0,0]),d['position']);q=node.get('rotation',[0,0,0,1]);v=np.array([0.,0.,1.]);xyz=np.array(q[:3]);forward=v+2*np.cross(xyz,np.cross(xyz,v)+q[3]*v);assert np.allclose(forward,d['outward'],atol=1e-6)
 if d['node'].startswith('door_room_'):assert np.dot(np.array([232*.0776,2*.0776,-150*.0776])-np.array(d['position']),np.array(d['outward']))>0
for panel in info['door_panels']:assert panel in names and any(c.get('gate_node')==panel for c in info['collision']['boxes_blender_z_up'])
bays=[p['bounds_blender_art'] for p in info['parking']['bays']];assert len(bays)==12
for i,a in enumerate(bays):
 assert a[0]<a[2] and a[1]<a[3]
 for b in bays[i+1:]:assert not (min(a[2],b[2])-max(a[0],b[0])>1e-6 and min(a[3],b[3])-max(a[1],b[1])>1e-6),'Overlapping parking bays'
 assert not(min(a[2],320)-max(a[0],176)>0 and min(a[3],128)-max(a[1],24)>0),'Parking crosses fenced pool'
for c in info['collision']['boxes_blender_z_up']:
 assert all(x>0 for x in c['size'])
 if c['source']!='Pool ladder':assert c['center'][2]-c['size'][2]/2>=-1e-6
regions=[reg[n]['rect_art'] for n in ['metal','rubber','coral','cream','pool_tile','carpet','guest_door','ice_machine','ac_grille']]
counts={};max_error=0;ground_faces=0;pool_center=np.array([248*.0776,-76*.0776])
def inside_triangle(p,tri):
 a,b,c=tri;v0=b-a;v1=c-a;v2=p-a;det=v0[0]*v1[1]-v0[1]*v1[0]
 if abs(det)<1e-9:return False
 u=(v2[0]*v1[1]-v2[1]*v1[0])/det;v=(v0[0]*v2[1]-v0[1]*v2[0])/det;return u>=-1e-7 and v>=-1e-7 and u+v<=1+1e-7
for i,m in enumerate(g['meshes']):
 if i==names['collision']['mesh']:continue
 count=0
 for p in m['primitives']:
  pos=access(p['attributes']['POSITION']);uv=access(p['attributes']['TEXCOORD_0']);idx=access(p['indices']).ravel().astype(int);assert np.isfinite(pos).all() and np.isfinite(uv).all() and len(idx)%3==0 and idx.max()<len(pos)
  material=g['materials'][p['material']];tex=g['textures'][material['pbrMetallicRoughness']['baseColorTexture']['index']];w,h=images[tex['source']];pixels=uv*np.array([w,h]);physical=pixels*.0388
  for a,b,c in idx.reshape(-1,3):
   cross=np.cross(pos[b]-pos[a],pos[c]-pos[a]);area=np.linalg.norm(cross);assert area>1e-12,(m['name'],a,b,c)
   for j,k in [(a,b),(b,c),(c,a)]:max_error=max(max_error,abs(np.linalg.norm(pos[j]-pos[k])-np.linalg.norm(physical[j]-physical[k])))
   if material['name']=='detail_utility':
    corners=pixels[[a,b,c]];assert any(np.all(corners>=np.array([x,y])*2-.001) and np.all(corners<=np.array([x+ww,y+hh])*2+.001) for x,y,ww,hh in regions),('Utility atlas bleed',m['name'])
   if material['name'].startswith('ground_') and cross[1]/area>.9 and area/2>1:
    ground_faces+=1;assert pos[[a,b,c],1].min()>=-1e-6
    assert not inside_triangle(pool_center,pos[[a,b,c]][:,[0,2]]),'A ground face fills the pool opening'
  count+=len(idx)//3
 counts[i]=count
assert sum(counts.values())==info['triangles_visible'] and sum(counts.values())<=12000 and max_error<.001
assert ground_faces>0
for name,material_name in [('sign_name','sign_name'),('sign_name_back','sign_name'),('glow_vacancy_panel','glow_vacancy'),('glow_vacancy_panel_back','glow_vacancy')]:
 assert all(g['materials'][p['material']]['name']==material_name for p in g['meshes'][names[name]['mesh']]['primitives'])
def node_count(i):
 n=g['nodes'][i];return counts.get(n.get('mesh'),0)+sum(node_count(c) for c in n.get('children',[]))
props={n['name']:node_count(i) for i,n in enumerate(g['nodes']) if n['name'] in ['ice_machine','pool_ladder','pool_lounger_01','pool_lounger_02','service_bin_01','service_bin_02','roadside_sign']};assert all(0<c<500 for c in props.values()),props
name_before=hashlib.sha256((R/'textures/sign-name.png').read_bytes()).hexdigest()
with tempfile.TemporaryDirectory() as td:
 td=Path(td);write_vacancy(td/'yes.png',False);write_vacancy(td/'no.png',True);a=np.array(Image.open(td/'yes.png'));b=np.array(Image.open(td/'no.png'));changed=np.any(a!=b,axis=2);assert changed.any();ys,xs=np.where(changed);assert xs.min()>=6 and xs.max()<28 and ys.min()>=6 and ys.max()<20,'Vacancy state changed pixels outside NO'
 write_name(td/'name.png',info['sign_name']['preview_name']+' MOTEL');assert (td/'name.png').read_bytes()==(R/'textures/sign-name.png').read_bytes()
 try:write_name(td/'long.png','THIS NAME IS TOO LONG FOR THE PANEL')
 except ValueError:pass
 else:raise AssertionError('Names must not be silently truncated or stretched')
assert hashlib.sha256((R/'textures/sign-name.png').read_bytes()).hexdigest()==name_before
result={'visible_triangles':sum(counts.values()),'materials':len(g['materials']),'rooms':12,'court_facing_guest_doors':12,'doors':len(info['doors']),'parking_bays':12,'parking_overlap':'None; pool fence excluded','collision_boxes':len(info['collision']['boxes_blender_z_up']),'architectural_collision':'Use the walls and closed door meshes; explicit boxes cover exterior obstructions','prop_triangles':props,'glb_bytes':len(raw),'art_grid':'All embedded images are exact 2x2 texel art pixels','uv_scale_max_edge_error_m':max_error,'pool':'Water below grade; asset ground has a true opening. Runtime terrain cutout/swimming pending.','signs':'Isolated sign_name and glow_vacancy panels; tested name writer and NO-only state change','renderer':'Blender desktop previews, not Filament/S25 captures'}
(R/'validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
