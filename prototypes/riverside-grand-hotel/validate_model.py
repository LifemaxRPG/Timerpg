"""Inspect the published GLB, including its embedded pixels and physical UV scale."""
from glb_reader import R,load,acc,matrices,clipped_area
import io,json
import numpy as np
from PIL import Image

info=json.loads((R/'model-info.json').read_text())
g,b=load(next(R.glob('*.glb')))
assert len(g['materials'])<=12 and not g.get('cameras')
assert not any('uri' in buffer for buffer in g['buffers'])
sizes=[]
for im in g['images']:
    v=g['bufferViews'][im['bufferView']]
    payload=b[v.get('byteOffset',0):v.get('byteOffset',0)+v['byteLength']]
    image=Image.open(io.BytesIO(payload))
    assert image.format=='PNG'
    pix=np.array(image.convert('RGBA'))
    assert all(np.array_equal(pix[::2,::2],pix[y::2,x::2]) for x,y in [(1,0),(0,1),(1,1)])
    sizes.append(image.size)
for s in g['samplers']:
    assert s['magFilter']==9728 and s['minFilter'] in [9728,9984]
    assert s.get('wrapS',10497)==s.get('wrapT',10497)==10497
world=matrices(g)
node_indices={n['name']:i for i,n in enumerate(g['nodes'])}
parts={}; uv_error=0; collision=[]; by_material={}
for i,n in enumerate(g['nodes']):
    if 'mesh' not in n:continue
    arrays=[]
    for prim in g['meshes'][n['mesh']]['primitives']:
        verts=acc(g,b,prim['attributes']['POSITION'])
        verts=verts@world[i][:3,:3].T+world[i][:3,3]
        ids=acc(g,b,prim['indices']).reshape(-1,3)
        tri=verts[ids]
        if n['name']=='collision':collision.extend(verts);continue
        mat=g['materials'][prim['material']]
        by_material.setdefault(mat['name'],[]).append(tri)
        tex=mat['pbrMetallicRoughness']['baseColorTexture']['index']
        uv=acc(g,b,prim['attributes']['TEXCOORD_0'])*np.array(sizes[g['textures'][tex]['source']])*.0388
        for k,j in [(0,1),(1,2),(2,0)]:
            e=np.linalg.norm(tri[:,k]-tri[:,j],axis=-1)-np.linalg.norm(uv[ids[:,k]]-uv[ids[:,j]],axis=-1)
            uv_error=max(uv_error,float(np.max(np.abs(e))))
        arrays.append(tri)
    if arrays:parts[n['name']]=np.concatenate(arrays)
tri=np.concatenate(list(parts.values()))
crosses=np.cross(tri[:,1]-tri[:,0],tri[:,2]-tri[:,0]);areas=np.linalg.norm(crosses,axis=1)/2
assert areas.min()>1e-11 and np.isfinite(tri).all(), (areas.min(),(areas<=1e-11).sum())
assert len(tri)==info['triangles_visible']<=12000
assert uv_error<.002,uv_error
assert tri[:,:,1].min()>=-.0001
result={'visible_triangles':len(tri),'materials':len(g['materials']),'embedded_pixel_aligned_images':len(sizes),'max_uv_edge_error_m':round(uv_error,7),'min_triangle_area_m2':round(float(areas.min()),9),'height_m':round(float(tri[:,:,1].max()),4)}
def surface(x,y,z):
    rows=tri[np.max(np.abs(tri[:,:,1]-y),axis=1)<.0001]
    t=rows[:,:,[0,2]]
    edges=np.roll(t,-1,axis=1)-t
    rel=np.array([x,z])-t
    cc=edges[:,:,0]*rel[:,:,1]-edges[:,:,1]*rel[:,:,0]
    return ((cc.min(axis=1)>=-.0001)|(cc.max(axis=1)<=.0001)).any()
for door in info['doors']:
    idx=node_indices[door['node']]
    x,y,z=door['position']
    assert np.allclose(world[idx][:3,3],[x,y,z])
    assert surface(x,y,z) and 'mesh' not in g['nodes'][idx]
    w=door['width_m']/2-.03
    rect=np.array([[x-w,y+.03],[x+w,y+.03],[x+w,y+2.1],[x-w,y+2.1]])
    near=tri[np.max(np.abs(tri[:,:,2]-z),axis=1)<.16]
    assert sum(clipped_area(t[:,[0,1]],rect) for t in near)<.001
step_count=0
for stair in info['stairs']:
    x,y,z=stair['start_gltf_m']
    for i in range(1,1+stair['risers']):
        assert surface(x,y+i*stair['rise_m'],z+stair['direction_z']*(i-.5)*stair['run_m']), (stair,i)
        step_count+=1
u=.0776
assert not surface(0,58*u,-152*u)
for x,y,z in [(0,32,-193),(44,58,-124),(-238,58,-275),(-238,110,-275),(-238,162,-275),(271,6,-210)]:
    assert surface(x*u,y*u,z*u),(x,y,z)
boxes=info['collision']['boxes_gltf_y_up']
corners={tuple(np.round(p,4)) for p in collision}
expected={tuple(np.round(np.array(c['center'])+np.array(c['size'])*np.array([x,y,z])/2,4)) for c in boxes for x in [-1,1] for y in [-1,1] for z in [-1,1]}
from scipy.spatial import cKDTree
assert cKDTree(list(corners)).query(list(expected))[0].max()<.0002 and cKDTree(list(expected)).query(list(corners))[0].max()<.0002
assert max(info['prop_triangles'].values())<500
assert abs(result['height_m']-info['tower_height_m'])<.001
glow=np.concatenate(by_material['glow_lamps'])
glow_area=np.linalg.norm(np.cross(glow[:,1]-glow[:,0],glow[:,2]-glow[:,0]),axis=1).sum()/2
assert np.isclose(glow_area,info['non_window_emissive_area_m2'],atol=1e-4)
result.update(door_apertures=len(info['doors']),treads_supported=step_count,collision_boxes=len(boxes),glow_area_m2=round(float(glow_area),5))
(R/'validation.json').write_text(json.dumps(result,indent=2)+'\n')
print(result)
