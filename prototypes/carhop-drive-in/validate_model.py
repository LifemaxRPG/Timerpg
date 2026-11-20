"""Validate the delivered GLBs, not estimates from the construction script."""
from pathlib import Path
import json,io
import numpy as np
from PIL import Image
from glb_reader import load,acc,matrices,clipped_area,cross
R=Path(__file__).resolve().parent;U=.0776

def validate(state):
 info=json.loads((R/('model-info-'+state+'.json')).read_text());g,b=load(R/info['file']);mats=g['materials'];matrix=matrices(g)
 assert len(mats)<=12 and all('uri' not in x for k in ['buffers','images'] for x in g[k])
 assert all(s.get('magFilter')==9728 and s.get('minFilter',9728) in [9728,9984] and s.get('wrapS',10497)==s.get('wrapT',10497)==10497 for s in g['samplers'])
 images=[]
 for im in g['images']:
  v=g['bufferViews'][im['bufferView']];a=np.asarray(Image.open(io.BytesIO(b[v.get('byteOffset',0):v.get('byteOffset',0)+v['byteLength']])).convert('RGBA'));assert a.shape[0]%2==a.shape[1]%2==0
  assert all(np.array_equal(a[::2,::2],q) for q in [a[1::2,::2],a[::2,1::2],a[1::2,1::2]]),'Not exact 2x2 art pixels';images.append(a)
 ni={n['name']:i for i,n in enumerate(g['nodes'])};nodes={n['name']:n for n in g['nodes']};visible=[];cv=[];count=0;uv_error=0;glow_area=0;glass_area=0;props={};materials_by_node={}
 for i,n in enumerate(g['nodes']):
  if 'mesh' not in n:continue
  nc=0
  for p in g['meshes'][n['mesh']]['primitives']:
   pos=acc(g,b,p['attributes']['POSITION']);world=(np.c_[pos,np.ones(len(pos))]@matrix[i].T)[:,:3];idx=acc(g,b,p['indices']).ravel();ts=world[idx].reshape(-1,3,3)
   if n['name']=='collision':cv.extend(world);continue
   area=np.linalg.norm(np.cross(ts[:,1]-ts[:,0],ts[:,2]-ts[:,0]),axis=1)/2;assert np.all(area>1e-10),(state,n['name'],'Degenerate');assert world[:,1].min()>-1e-5,(state,n['name'],'Below grade')
   m=mats[p['material']];materials_by_node.setdefault(n['name'],set()).add(m['name']);img=images[g['textures'][m['pbrMetallicRoughness']['baseColorTexture']['index']]['source']];uv=acc(g,b,p['attributes']['TEXCOORD_0'])*[img.shape[1]*.0388,img.shape[0]*.0388];uv=uv[idx].reshape(-1,3,2)
   for a,c in [(0,1),(1,2),(2,0)]:uv_error=max(uv_error,float(np.abs(np.linalg.norm(ts[:,a]-ts[:,c],axis=1)-np.linalg.norm(uv[:,a]-uv[:,c],axis=1)).max()))
   if m['name']=='glow_lamps':glow_area+=float(area.sum())
   if m['name']=='glass_windows':glass_area+=float(area.sum())
   if m['name']=='ground_paving':assert 'COLOR_0' in p['attributes'] and np.any(acc(g,b,p['attributes']['COLOR_0'])[:,:3]<.98),'Lost paving colour'
   visible.extend(ts);count+=len(ts);nc+=len(ts)
  props[n['name']]=nc
 assert count==info['triangles_visible'] and count<=12000 and sum(info['triangles_per_part'].values())==count
 assert uv_error<.002,(state,uv_error);assert np.isclose(glow_area,info['non_window_emissive_area_m2'],atol=1e-4) and glow_area<15
 assert len(info['parking_bays'])==8 and all(d['menu_node'] in nodes for d in info['parking_bays'])
 assert len([n for n in nodes if n.startswith('menu_speaker_') and '__' not in n])==8
 assert all(0<t<500 for t in info['prop_triangles'].values()),info['prop_triangles']
 # All same-frame collision corners must really exist, with supported full-height boxes.
 cv=np.asarray(cv)
 for box in info['collision']['boxes_gltf_y_up']:
  c=np.array(box['center']);s=np.array(box['size']);assert np.all(s>0) and np.isclose(c[1]-s[1]/2,0,atol=1e-6) and box['attachment'] in nodes
  for x in [-1,1]:
   for y in [-1,1]:
    for z in [-1,1]:assert np.linalg.norm(cv-(c+s*np.array([x,y,z])/2),axis=1).min()<1e-4
 # Bay slots avoid every menu case and canopy column with a 1.8m-wide, 4.8m-long reference car.
 for bay in info['parking_bays']:
  x,_,z=bay['centre_gltf_m']
  for box in info['collision']['boxes_gltf_y_up']:
   if box['source'] not in ['Menu post and case','Canopy column','Canopy rear column']:continue
   c=np.array(box['center']);s=np.array(box['size']);assert not(abs(x-c[0])<.9+s[0]/2 and abs(z-c[2])<2.4+s[2]/2),(state,'Car slot obstructed',bay['index'],box['source'])
 # The 1.2m central pedestrian route from drive aisle to the door stays unobstructed.
 for box in info['collision']['boxes_gltf_y_up']:
  if box['source'] not in ['Menu post and case','Canopy column','Canopy rear column']:continue
  c=np.array(box['center']);s=np.array(box['size']);assert abs(c[0])>=.6+s[0]/2,'Blocked centre approach'
 visible=np.asarray(visible)
 for d in info['doors']:
  assert d['node'] in nodes and d['open'] and d['external'];p=np.array(d['position']);m=matrix[ni[d['node']]];assert np.allclose(m[:3,3],p,atol=1e-5) and np.allclose(m[:3,:3]@np.array([0,0,1]),d['outward'],atol=1e-5)
  floors=visible[(np.ptp(visible[:,:,1],axis=1)<1e-6)&(np.abs(visible[:,0,1]-d['floor_m'])<1e-5)];support=False
  for t in floors:
   a,c,e=t[:,[0,2]];q=p[[0,2]];den=cross(c-a,e-a)
   if abs(den)<1e-10:continue
   u=cross(q-a,e-a)/den;v=cross(c-a,q-a)/den
   if u>=-1e-5 and v>=-1e-5 and u+v<=1+1e-5:support=True;break
  assert support,(state,'Unsupported entry',d['node'])
  rect=np.array([[p[0]-d['width_m']/2+.04,d['floor_m']+.03],[p[0]+d['width_m']/2-.04,d['floor_m']+.03],[p[0]+d['width_m']/2-.04,d['floor_m']+d['height_m']-.03],[p[0]-d['width_m']/2+.04,d['floor_m']+d['height_m']-.03]])
  near=visible[(np.ptp(visible[:,:,2],axis=1)<1e-5)&(np.abs(visible[:,0,2]-p[2])<.22)&(np.ptp(visible[:,:,1],axis=1)>.01)]
  for t in near:assert clipped_area(t[:,[0,1]],rect)<1e-4,(state,'Blocked door',d['node'])
 assert len(info['doors'])==2 and 'door_prep_inside' in nodes and 'sign_name' in nodes and 'lot' in nodes
 tables=[n for n in nodes if n.startswith('picnic_table_') and '__' not in n];assert len(tables)==(4 if state=='restored' else 0)
 if state=='closed':assert glass_area==0 and all(max(m.get('emissiveFactor',[0]))==0 for m in mats)
 else:assert glass_area>0
 return {'state':state,'visible_triangles':count,'materials':len(mats),'embedded_images':len(images),'max_uv_edge_error_m':uv_error,'glow_geometry_area_m2':round(glow_area,5),'glass_area_m2':round(glass_area,5),'collision_boxes':len(info['collision']['boxes_gltf_y_up']),'largest_optional_prop_triangles':max(info['prop_triangles'].values()),'picnic_tables':len(tables),'menu_speakers':8,'open_exterior_doors':2}

if __name__=='__main__':
 out={'states':[validate(s) for s in ['original','closed','restored']],'checks':['Actual GLB triangles, embedded images, nearest/repeat samplers and exact 2x2 art blocks.','UV edge distances retain 0.0388m/texel; paving vertex colours survive export.','Grade, all collision corners, full-height boxes, clear and supported same-frame door anchors.','Reference car slots clear menu cases and canopy columns; eight speaker nodes exist in each state.','Closed has no glass geometry or emission; four removable picnic-table props occur only in restored.'],'renders':'Blender desktop previews, not Filament/S25 captures.','integration':'Asset validation only; game state selection, runtime collisions, furniture, navigation and lighting need integration.'}
 infos=[json.loads((R/('model-info-'+s+'.json')).read_text()) for s in ['original','closed','restored']];assert all(i['doors']==infos[0]['doors'] and i['building_footprint_m']==infos[0]['building_footprint_m'] for i in infos)
 (R/'validation.json').write_text(json.dumps(out,indent=2)+'\n');print(json.dumps(out,indent=2))
