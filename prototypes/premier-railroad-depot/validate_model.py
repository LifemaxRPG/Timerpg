"""Validate actual exported depot geometry, textures, entrances and modular handoff."""
from pathlib import Path
import struct,json,io
import numpy as np
from PIL import Image
R=Path(__file__).resolve().parent;raw=(R/'premier-railroad-depot.glb').read_bytes();info=json.loads((R/'model-info.json').read_text());reg=json.loads((R/'art-regions.json').read_text())
assert struct.unpack_from('<4sII',raw)==(b'glTF',2,len(raw));n,t=struct.unpack_from('<II',raw,12);g=json.loads(raw[20:20+n]);o=20+n;nb,t=struct.unpack_from('<II',raw,o);binary=raw[o+8:o+8+nb];assert t==0x004e4942
assert all('uri' not in x for k in ('buffers','images') for x in g[k]);assert len(g['materials'])<=12
assert all(s['magFilter']==9728 and s.get('minFilter',9728) in (9728,9984) and s.get('wrapS',10497)==10497 and s.get('wrapT',10497)==10497 for s in g['samplers'])
images=[]
for im in g['images']:
 v=g['bufferViews'][im['bufferView']];a=v.get('byteOffset',0);p=np.array(Image.open(io.BytesIO(binary[a:a+v['byteLength']])).convert('RGBA'));h,w=p.shape[:2]
 assert w%2==h%2==0 and np.array_equal(p,p[::2,::2].repeat(2,0).repeat(2,1)),im['name'];images.append((w,h))
types={5126:('f',4),5125:('I',4),5123:('H',2),5121:('B',1)};widths={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}
def access(i):
 a=g['accessors'][i];v=g['bufferViews'][a['bufferView']];c,size=types[a['componentType']];w=widths[a['type']];start=v.get('byteOffset',0)+a.get('byteOffset',0);stride=v.get('byteStride',size*w)
 return np.array([struct.unpack_from('<'+c*w,binary,start+j*stride) for j in range(a['count'])])
names={n['name']:n for n in g['nodes']}
for n in ['lot','rail_platform','platform_surface','platform_west_end','platform_east_end','boarding_west','boarding_east','collision']+info['station_name']['nodes']+info['clock']['hands']:assert n in names,n
assert not any(n.startswith('Reference ') for n in names),'Presentation track must never enter the asset'
assert names['collision']['extras']['collision_only'] and names['collision']['extras']['render'] is False
assert len(info['collision']['boxes_blender_z_up'])<=48
for box in info['collision']['boxes_blender_z_up']:
 assert all(v>0 for v in box['size'])
 assert box['center'][2]-box['size'][2]/2>=-1e-6
for d in info['doors']:
 node=names[d['node']];assert np.allclose(node.get('translation',[0,0,0]),d['position'])
 assert np.isclose(d['position'][1],info['entrance_floor_m'])
 q=node.get('rotation',[0,0,0,1]);v=np.array([0.,0.,1.]);xyz=np.array(q[:3]);forward=v+2*np.cross(xyz,np.cross(xyz,v)+q[3]*v);assert np.allclose(forward,d['outward'],atol=1e-6)
for panel in info['door_panels']:assert panel in names and any(c.get('gate_node')==panel for c in info['collision']['boxes_blender_z_up'])
for n in info['clock']['hands']:
 assert names[n]['extras']['clock_hand'] in ('hour','minute')
 assert len(names[n]['extras']['outward_axis_gltf'])==3
max_error=0;counts={};up_ground=[]
regions=[reg[n]['rect_art'] for n in ['metal','rubber','brass','foliage','freight_door']]
for i,m in enumerate(g['meshes']):
 if i==names['collision']['mesh']:continue
 count=0
 for p in m['primitives']:
  pos=access(p['attributes']['POSITION']);uv=access(p['attributes']['TEXCOORD_0']);idx=access(p['indices']).ravel().astype(int)
  assert np.isfinite(pos).all() and np.isfinite(uv).all() and len(idx)%3==0 and idx.max()<len(pos)
  material=g['materials'][p['material']];tex=g['textures'][material['pbrMetallicRoughness']['baseColorTexture']['index']];w,h=images[tex['source']];pixels=uv*np.array([w,h]);physical=pixels*.0388
  for a,b,c in idx.reshape(-1,3):
   cross=np.cross(pos[b]-pos[a],pos[c]-pos[a]);area=np.linalg.norm(cross);assert area>1e-12,(m['name'],a,b,c)
   for j,k in [(a,b),(b,c),(c,a)]:max_error=max(max_error,abs(np.linalg.norm(pos[j]-pos[k])-np.linalg.norm(physical[j]-physical[k])))
   if material['name']=='detail_utility':
    corners=pixels[[a,b,c]];assert any(np.all(corners>=np.array([x,y])*2-.001) and np.all(corners<=np.array([x+ww,y+hh])*2+.001) for x,y,ww,hh in regions),('Atlas bleed',m['name'])
   if material['name'].startswith('ground_') and cross[1]/area>.9 and area/2>1:
    up_ground.append(float(pos[[a,b,c],1].min()));assert pos[[a,b,c],1].min()>=-1e-6
  count+=len(idx)//3
 counts[i]=count
assert sum(counts.values())==info['triangles_visible'] and sum(counts.values())<=12000
assert max_error<.001,max_error
assert up_ground
for name in ('platform_surface','boarding_west','boarding_east'):assert np.isclose(names[name]['translation'][1],info['entrance_floor_m'])
def node_count(i):
 n=g['nodes'][i];return counts.get(n.get('mesh'),0)+sum(node_count(c) for c in n.get('children',[]))
props={n['name']:node_count(i) for i,n in enumerate(g['nodes']) if '__' not in n['name'] and (n['name'].startswith(('platform_bench_','forecourt_bin_')) or n['name']=='baggage_cart')}
assert all(0<c<500 for c in props.values()),props
def node_points(i):
 node=g['nodes'][i];arrays=[]
 if 'mesh' in node:
  for primitive in g['meshes'][node['mesh']]['primitives']:arrays.append(access(primitive['attributes']['POSITION']))
 for child in node.get('children',[]):arrays+=node_points(child)
 return arrays
for prop in props:
 node_index=next(i for i,n in enumerate(g['nodes']) if n['name']==prop)
 points=np.concatenate(node_points(node_index));lo=points.min(axis=0);hi=points.max(axis=0)
 boxes=[c for c in info['collision']['boxes_blender_z_up'] if c['attachment']==prop]
 assert boxes,prop
 covered=False
 for box in boxes:
  x,y,z=box['center'];w,d,h=box['size'];center=np.array([x,z,-y]);size=np.array([w,h,d])
  covered |= bool(np.all(lo>=center-size/2-.00001) and np.all(hi<=center+size/2+.00001))
 assert covered,('Prop collision does not cover actual mesh',prop,lo,hi)

result={'visible_triangles':sum(counts.values()),'materials':len(g['materials']),'collision_boxes':len(info['collision']['boxes_blender_z_up']),'doors':len(info['doors']),'clock_hands':len(info['clock']['hands']),'prop_triangles':props,'glb_bytes':len(raw),'art_grid':'Every embedded image is exact 2x2 texel art pixels','uv_scale_max_edge_error_m':max_error,'ground':'Broad paving and asphalt top surfaces are at/above zero','rail_interface':'Station/platform anchors verified; railway contract pending; reference track absent from GLB','renderer':'Blender desktop previews, not Filament/S25 captures'}
(R/'validation.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
