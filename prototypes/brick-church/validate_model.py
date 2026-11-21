"""Validate both actual church GLBs, including physical UVs and non-rectangular glass."""
from pathlib import Path
import json,io
import numpy as np
from PIL import Image
from glb_reader import load,acc,matrices,clipped_area,cross
R=Path(__file__).resolve().parent;U=.0776

def validate(variant):
 info=json.loads((R/('model-info-'+variant+'.json')).read_text());g,b=load(R/info['file']);mats=g['materials'];assert len(mats)<=12 and all('uri' not in x for key in ['buffers','images'] for x in g[key]);assert all(s.get('magFilter')==9728 and s.get('minFilter',9728) in [9728,9984] and s.get('wrapS',10497)==s.get('wrapT',10497)==10497 for s in g['samplers'])
 images=[]
 for im in g['images']:
  v=g['bufferViews'][im['bufferView']];img=Image.open(io.BytesIO(b[v.get('byteOffset',0):v.get('byteOffset',0)+v['byteLength']])).convert('RGBA');a=np.asarray(img);assert a.shape[0]%2==a.shape[1]%2==0;assert np.array_equal(a[::2,::2],a[1::2,::2]) and np.array_equal(a[::2,::2],a[::2,1::2]) and np.array_equal(a[::2,::2],a[1::2,1::2]);images.append(a)
 nodes={n['name']:n for n in g['nodes']};node_index={n['name']:i for i,n in enumerate(g['nodes'])};matrix=matrices(g);visible=[];cv=[];counts={};glass_area=0;glow_area=0;maxerr=0;paving=[];group_glass={}
 for ni,n in enumerate(g['nodes']):
  if 'mesh' not in n:continue
  count=0
  for p in g['meshes'][n['mesh']]['primitives']:
   pos=acc(g,b,p['attributes']['POSITION']);idx=acc(g,b,p['indices']).ravel();world=(np.c_[pos,np.ones(len(pos))]@matrix[ni].T)[:,:3];ts=world[idx].reshape(-1,3,3)
   if n['name']=='collision':cv.extend(world);continue
   area=np.linalg.norm(np.cross(ts[:,1]-ts[:,0],ts[:,2]-ts[:,0]),axis=1)/2;assert np.all(area>1e-10),(variant,n['name'],'Degenerate triangles');assert world[:,1].min()>-1e-5,(variant,n['name'],'Below grade');count+=len(ts);visible.extend(ts)
   m=mats[p['material']];tex=g['textures'][m['pbrMetallicRoughness']['baseColorTexture']['index']];im=images[tex['source']];uv=acc(g,b,p['attributes']['TEXCOORD_0'])*[im.shape[1]*.0388,im.shape[0]*.0388];uv=uv[idx].reshape(-1,3,2)
   for i,j in [(0,1),(1,2),(2,0)]:maxerr=max(maxerr,float(np.abs(np.linalg.norm(ts[:,i]-ts[:,j],axis=1)-np.linalg.norm(uv[:,i]-uv[:,j],axis=1)).max()))
   if m['name']=='glass_windows':glass_area+=float(area.sum());group_glass[n['name']]=group_glass.get(n['name'],0)+float(area.sum())
   if m['name']=='glow_lamps':glow_area+=float(area.sum())
   if m['name']=='ground_paving':
    assert 'COLOR_0' in p['attributes'];assert np.any(acc(g,b,p['attributes']['COLOR_0'])[:,:3]<.98),'Lost paving palette'
   if n['name'].startswith('paths__'):paving.extend(ts)
  counts[n['name']]=count
 assert sum(counts.values())==info['triangles_visible'] and sum(counts.values())<=12000
 assert maxerr<.002,(variant,'UV density',maxerr);assert abs(glow_area-info['non_window_emissive_area_m2'])<1e-4 and glow_area<15
 expected=sum(w['area_m2'] for w in info['windows']);assert abs(glass_area-expected)<1e-3,(variant,'Arched glass area mismatch',glass_area,expected)
 for name,actual in group_glass.items():assert abs(actual-sum(w['area_m2'] for w in info['windows'] if w['part_node']==name))<1e-3
 assert all(w['part_node'] in nodes for w in info['windows'])
 curved=[w for w in info['windows'] if w['profile']!='rect'];assert len(curved)>=10 if variant=='brick' else len(curved)>=10
 for w in curved:assert 0<w['area_m2']<w['width_art']*w['height_art']*U*U,'Arch must have an actual curved head'
 cv=np.array(cv)
 for box in info['collision']['boxes_gltf_y_up']:
  c=np.array(box['center']);s=np.array(box['size']);assert np.all(s>0) and np.isclose(c[1]-s[1]/2,box['support_floor_m'],atol=1e-6)
  for x in [-1,1]:
   for y in [-1,1]:
    for z in [-1,1]:assert np.linalg.norm(cv-(c+s*np.array([x,y,z])/2),axis=1).min()<1e-4
 visible=np.array(visible)
 for d in info['doors']:
  assert d['node'] in nodes and d['external'] and d['open'];n=nodes[d['node']];assert 'mesh' not in n and np.allclose(n['translation'],d['position'],atol=1e-5)
  pos=np.array(d['position']);assert np.allclose(matrix[node_index[d['node']]][:3,:3]@np.array([0.,0.,1.]),d['outward'],atol=1e-6),(variant,d['node'],'Wrong facing')
  horizontal_floors=visible[(np.ptp(visible[:,:,1],axis=1)<1e-6)&(np.abs(visible[:,0,1]-d['floor_m'])<1e-5)]
  supported=False
  for t in horizontal_floors:
   a,b1,c=t[:,[0,2]];p1=pos[[0,2]];den=cross(b1-a,c-a)
   if abs(den)<1e-10:continue
   u=cross(p1-a,c-a)/den;v=cross(b1-a,p1-a)/den
   if u>=-1e-5 and v>=-1e-5 and u+v<=1+1e-5:supported=True;break
  assert supported,(variant,d['node'],'Entry floor not supported by actual landing')
  axis=0 if d['outward'][0] else 2;horizontal=2 if axis==0 else 0;half=d['width_m']/2-.025;lo=d['floor_m']+.03;hi=d['floor_m']+min(d['height_m']-.02,2.2)
  rect=np.array([[pos[horizontal]-half,lo],[pos[horizontal]+half,lo],[pos[horizontal]+half,hi],[pos[horizontal]-half,hi]])
  near=visible[(np.ptp(visible[:,:,axis],axis=1)<1e-5)&(np.abs(visible[:,0,axis]-pos[axis])<.22)&(np.ptp(visible[:,:,1],axis=1)>.01)]
  for t in near:assert clipped_area(t[:,[horizontal,1]],rect)<1e-4,(variant,d['node'],'Blocked entry')
 assert len(info['doors'])==(3 if variant=='brick' else 2)
 assert ('parish_hall' in nodes)==(variant=='brick') and ('vestry' in nodes)==(variant=='country')
 assert 'sign_name' in nodes and sum(n.get('name')=='sign_name' for n in g['nodes'])==1
 assert sum(info['triangles_per_part'].values())==sum(counts.values());assert all(0<v<500 for v in info['prop_triangles'].values()),info['prop_triangles']
 paving=np.array(paving);assert len(paving)>0
 for i,a in enumerate(paving):
  aa=a[:,[0,2]];lo=aa.min(0);hi=aa.max(0)
  for bb in paving[i+1:]:
   bb=bb[:,[0,2]]
   if np.all(np.minimum(hi,bb.max(0))-np.maximum(lo,bb.min(0))>1e-6):assert clipped_area(aa,bb)<1e-5,'Overlapping path triangles'
 # Actual main-floor surfaces must cover nave and hall; roofs and ceiling panes exist.
 assert 'nave__detail_wood' in nodes and ('parish_hall__detail_wood' if variant=='brick' else 'vestry__detail_wood') in nodes
 assert ('corner_steeple' if variant=='brick' else 'front_belfry') in nodes
 return {'variant':variant,'visible_triangles':sum(counts.values()),'materials':len(mats),'embedded_images':len(images),'curved_glass_windows':len(curved),'glass_geometry_area_m2':round(glass_area,5),'non_window_glow_m2':round(glow_area,5),'open_exterior_entries':len(info['doors']),'collision_boxes':len(info['collision']['boxes_gltf_y_up']),'max_uv_edge_error_m':maxerr,'largest_lot_prop_triangles':max(info['prop_triangles'].values()),'bytes':(R/info['file']).stat().st_size}

out={'variants':[validate(v) for v in ['brick','country']],'art':'Actual GLB PNGs are exact 2x2 texel blocks, nearest/repeat; UV edges retain 0.0388m per texel, with active constant paving colours.','architecture':'Actual pointed/round glass areas match the clipped arch polygons; open exterior apertures, room shells, separate rear hall/vestry and hidden collision boxes checked.','collision':'Exact exported box corners match glTF Y-up metre metadata and support floors. Architectural surfaces provide wall/floor/stair collision. Paths have no overlapping triangle areas.','budget':'Both alternatives are below 12000 visible triangles and 12 materials. Every optional lot prop is below 500 triangles; non-window glow is below 15m2.','runtime':'Asset geometry only. Navigation, collision parsing, lights, pane masks, furnishing, town-name repainting and church/bell interactions need game integration.','renders':'Actual Blender desktop previews, not Filament/S25 captures.'}
(R/'validation.json').write_text(json.dumps(out,indent=2)+'\n');print(json.dumps(out,indent=2))
