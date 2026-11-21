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
parts={}
uv_error=0
colliders=[] 
for i,n in enumerate(g['nodes']):
    if 'mesh' not in n:continue
    arrays=[]
    for prim in g['meshes'][n['mesh']]['primitives']:
        verts=acc(g,b,prim['attributes']['POSITION'])
        verts=verts@world[i][:3,:3].T+world[i][:3,3]
        ids=acc(g,b,prim['indices']).reshape(-1,3)
        tri=verts[ids]
        if n.get("extras",{}).get("collision_only"):
            colliders.append(tri)
            continue
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
assert abs(result['height_m']-info['height_m'])<.001
assert len(colliders)==len(info["collision"]["boxes_blender_z_up"])
for meta,box in zip(info["collision"]["boxes_blender_z_up"],colliders):
    x,y,z=meta["center"]
    w,d,h=meta["size"]
    assert np.allclose((box.max(axis=(0,1))+box.min(axis=(0,1)))/2,[x,z,-y],atol=.001)
    assert np.allclose(box.max(axis=(0,1))-box.min(axis=(0,1)),[w,h,d],atol=.001)
assert len(g["images"])==len(g["materials"])
for datum in info["doors"]+info["anchors"]:
    n=node_indices[datum["node"]]
    assert np.allclose(world[n][:3,3],datum["position_gltf_m"],atol=.001)
    assert "mesh" not in g["nodes"][n]
for datum in info["doors"]:
    x,y,z=datum["position_gltf_m"]
    wall_z=-(14.5+(-3.60 if datum["heading_deg"]==0 else 5.6))
    close=tri[(np.abs(tri[:,:,2]-wall_z).min(axis=1)<.2)&
              (np.ptp(tri[:,:,0],axis=1)>.04)&(np.ptp(tri[:,:,1],axis=1)>.055)]
    blocked=(close[:,:,0].min(axis=1)<x+.57)&(close[:,:,0].max(axis=1)>x-.57)&(
             close[:,:,1].min(axis=1)<y+2.14)&(close[:,:,1].max(axis=1)>y+.06)
    assert not blocked.any(),datum
    for local_y in ((-3.92,-3.2) if datum["heading_deg"]==0 else (5.25,5.94)):
        point=np.array([x,-(local_y+14.5)])
        supported=tri[(np.abs(tri[:,:,1]-y).max(axis=1)<.014)]
        polygon=supported[:,:,[0,2]]
        edge=np.roll(polygon,-1,axis=1)-polygon
        delta=point-polygon
        cross=edge[:,:,0]*delta[:,:,1]-edge[:,:,1]*delta[:,:,0]
        assert ((cross.min(axis=1)>=-1e-6)|(cross.max(axis=1)<=1e-6)).any(),(datum,local_y)
names=list(node_indices)
assert "lot" in names and "roof_and_rafters" in names
assert len([n for n in names if n.startswith("tree_") and "__" not in n])==17
assert any(m["name"]=="glass_lodge" and "emissiveTexture" in m for m in g["materials"])
result.update(collision_boxes=len(colliders),clear_doors=len(info["doors"]),
              woodland_trees=17,building_height_m=info["building_height_m"])
(R/"validation.json").write_text(json.dumps(result,indent=2)+"\n")
print(result)
