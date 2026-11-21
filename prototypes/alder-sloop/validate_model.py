"""Inspect the published GLB, including its embedded pixels and physical UV scale."""
from glb_reader import R,load,acc,matrices
import io,json
import numpy as np
from PIL import Image

info=json.loads((R/'model-info.json').read_text())
g,b=load(R/"alder-sloop.glb")
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
parts={}
uv_error=0
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
assert len(tri)==info['triangles_visible']<=8500,(len(tri),info['triangles_visible'])
assert uv_error<.002,uv_error
assert tri[:,:,1].min()>=-.0001
result={'visible_triangles':len(tri),'materials':len(g['materials']),'embedded_pixel_aligned_images':len(sizes),'max_uv_edge_error_m':round(uv_error,7),'min_triangle_area_m2':round(float(areas.min()),9),'height_m':round(float(tri[:,:,1].max()),4)}
expected=info["bounding_size_gltf_m"]
actual=tri.max(axis=(0,1))-tri.min(axis=(0,1))
assert np.allclose(actual,expected,atol=.0001)
assert abs(info["waterline_above_keel_m"]-info["draft_m"])<.001
assert len(g["materials"])==len(g["images"])==11
for anchor in info["anchors"]:
    idx=node_indices[anchor["node"]]
    assert "mesh" not in g["nodes"][idx]
    assert np.allclose(world[idx][:3,3],anchor["position_gltf_m"],atol=.001)
assert np.allclose(world[node_indices["rudder_pivot"]][:3,3],[0,1.44,-3.2],atol=.001)
for key in ("sail_main","sail_jib","mast_and_standing_rig","hull","deck_and_cockpit"):
    assert g["nodes"][node_indices[key]]["children"]


def horizontal_faces_at(x,z,lo,hi):
    sample=np.array([x,z])
    planar=tri[(tri[:,:,1].min(axis=1)>=lo)&(tri[:,:,1].max(axis=1)<=hi)]
    points=planar[:,:,[0,2]]
    edges=np.roll(points,-1,axis=1)-points
    diff=sample-points
    cross=edges[:,:,0]*diff[:,:,1]-edges[:,:,1]*diff[:,:,0]
    area=(edges[:,0,0]*edges[:,1,1]-edges[:,0,1]*edges[:,1,0])
    inside=((cross.min(axis=1)>=-1e-6)|(cross.max(axis=1)<=1e-6))&(np.abs(area)>1e-7)
    return int(inside.sum())


for x,depth in ((.32,-1.80),(-.28,-2.30),(.41,-3.02)):
    assert horizontal_faces_at(x,depth,1.877,1.879)>0
    assert horizontal_faces_at(x,depth,2.12,2.245)==0
assert horizontal_faces_at(.35,3.6,2.25,2.43)>0
result.update(named_anchors=len(info["anchors"]),open_cockpit_samples=3,
              steering_pivot_gltf_m=[0,1.44,-3.2],draft_m=info["draft_m"],length_m=info["length_overall_m"])
(R/"validation.json").write_text(json.dumps(result,indent=2)+"\n")
print(result)
