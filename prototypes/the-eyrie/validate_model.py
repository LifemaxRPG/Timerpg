"""Inspect the published GLB, including its embedded pixels and physical UV scale."""
from glb_reader import R,load,acc,matrices
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
parts={}; uv_error=0; 
for i,n in enumerate(g['nodes']):
    if 'mesh' not in n:continue
    arrays=[]
    for prim in g['meshes'][n['mesh']]['primitives']:
        verts=acc(g,b,prim['attributes']['POSITION'])
        verts=verts@world[i][:3,:3].T+world[i][:3,3]
        ids=acc(g,b,prim['indices']).reshape(-1,3)
        tri=verts[ids]
        mat=g['materials'][prim['material']]
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
assert abs(result['height_m']-info['height_m'])<.001
assert np.allclose(tri.min(axis=(0,1)),[-99,0,-160],atol=.0001)
assert np.allclose(tri.max(axis=(0,1)),[99,info['height_m'],0],atol=.0001)
# Check the actual rising tread geometry from both approaches.
for side in (-1,1):
    for i in range(20):
        depth=-80-side*(38.1-i*.8)
        surface=.16+(i+1)*.176
        planar=tri[np.max(np.abs(tri[:,:,1]-surface),axis=1)<.0001]
        covered=planar[(planar[:,:,0].min(axis=1)<0)&(planar[:,:,0].max(axis=1)>0)&
                       (planar[:,:,2].min(axis=1)<depth)&(planar[:,:,2].max(axis=1)>depth)]
        assert len(covered)>0,(side,i,depth,surface)
lot = g['nodes'][node_indices['lot']]
counts = [sum(len(parts[g['nodes'][c]['name']]) for c in g['nodes'][i]['children'])
          for i in lot['children']]
assert len(counts)==20 and max(counts)<500
assert len(g['images'])==len(info['materials'])==12
result.update(stair_treads=40, removable_props=len(counts), largest_prop_triangles=max(counts))
(R/'validation.json').write_text(json.dumps(result,indent=2)+'\n')
print(result)
