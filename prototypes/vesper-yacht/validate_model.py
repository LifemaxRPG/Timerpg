"""Inspect the published GLB, including its embedded pixels and physical UV scale."""
from glb_reader import R,load,acc,matrices
import io,json
import numpy as np
from PIL import Image

info=json.loads((R/'model-info.json').read_text())
g,b=load(R/"vesper-yacht.glb")
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
assert len(tri)==info['triangles_visible']<=12000,(len(tri),info['triangles_visible'])
assert uv_error<.002,uv_error
assert tri[:,:,1].min()>=-.0001
result={'visible_triangles':len(tri),'materials':len(g['materials']),'embedded_pixel_aligned_images':len(sizes),'max_uv_edge_error_m':round(uv_error,7),'min_triangle_area_m2':round(float(areas.min()),9),'height_m':round(float(tri[:,:,1].max()),4)}
extents=tri.max(axis=(0,1))-tri.min(axis=(0,1))
assert np.allclose(extents,[info["beam_m"],info["height_m"],info["length_overall_m"]],atol=.001)
assert len(g["materials"])==len(g["images"])==11
for marker in info["anchors"]:
    idx=node_indices[marker["node"]]
    assert "mesh" not in g["nodes"][idx]
    assert np.allclose(world[idx][:3,3],marker["position_gltf_m"],atol=.001)


def support(x,z,height):
    planar=tri[np.max(np.abs(tri[:,:,1]-height),axis=1)<.0002]
    poly=planar[:,:,[0,2]]
    e=np.roll(poly,-1,axis=1)-poly
    q=np.array([x,z])-poly
    cross=e[:,:,0]*q[:,:,1]-e[:,:,1]*q[:,:,0]
    area=e[:,0,0]*e[:,1,1]-e[:,0,1]*e[:,1,0]
    return (((cross.min(axis=1)>=-1e-6)|(cross.max(axis=1)<=1e-6))&(np.abs(area)>1e-7)).any()


steps=0
for stair in info["stairs"]:
    (x,height,z0),(_,high,z1)=stair["foot_gltf_m"],stair["landing_gltf_m"]
    count=stair["treads"]
    for n in range(count):
        z=z0+(z1-z0)*(n+.5)/count
        y=height+(high-height)*(n+1)/count
        assert support(x,z,y),(stair["name"],n,z,y)
        steps+=1
for x,z,floor in ((0,-19.97,2.72),(.92,-8.6,4.46),(0,-5.8,4.46),(0,-1.65,7.15),(1.2,2.4,9.81)):
    assert support(x,z,floor),(x,z,floor)
for z,y,half in ((-6.14,4.46,1.16),(-1.82,7.15,.76)):
    candidates=tri[(np.abs(tri[:,:,2]-z).min(axis=1)<.15)&
                   (np.ptp(tri[:,:,0],axis=1)>.045)&(np.ptp(tri[:,:,1],axis=1)>.055)]
    inside=(candidates[:,:,0].min(axis=1)<half)&(candidates[:,:,0].max(axis=1)>-half)&(
            candidates[:,:,1].min(axis=1)<y+2.0)&(candidates[:,:,1].max(axis=1)>y+.11)
    assert not inside.any(),(z,y)
# The open stern between both steps must stay clear of the sloping hull skin.
clearance=(tri[:,:,0].min(axis=1)<.32)&(tri[:,:,0].max(axis=1)>-.32)&(
    tri[:,:,2].min(axis=1)<-16.10)&(tri[:,:,2].max(axis=1)>-17.12)&(
    tri[:,:,1].max(axis=1)>2.84)&(tri[:,:,1].min(axis=1)<4.43)
assert not clearance.any()
assert support(0,-16.4,2.72)
result["open_lower_stern"]=True
result.update(verified_stair_treads=steps,room_portals=2,named_anchors=len(info["anchors"]),
              length_m=info["length_overall_m"],draft_m=info["waterline_above_keel_m"])
(R/"validation.json").write_text(json.dumps(result,indent=2)+"\n")
print(result)
