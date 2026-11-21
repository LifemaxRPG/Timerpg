"""Check ship variants and room handoff metadata against exported GLBs."""
import io
import json

import numpy as np
from PIL import Image
from glb_reader import R,acc,load,matrices


def check_file(path):
    gltf,binary=load(path)
    assert len(gltf["materials"])<=12
    assert not any("uri" in item for item in gltf["buffers"])
    for rec in gltf["images"]:
        view=gltf["bufferViews"][rec["bufferView"]]
        offset=view.get("byteOffset",0)
        im=Image.open(io.BytesIO(binary[offset:offset+view["byteLength"]]))
        pixels=np.array(im.convert("RGBA"))
        assert im.format=="PNG" and pixels.shape[0]%2==pixels.shape[1]%2==0
        assert all(np.array_equal(pixels[::2,::2],pixels[y::2,x::2]) for y,x in ((0,1),(1,0),(1,1)))
    for sampler in gltf["samplers"]:
        assert sampler["magFilter"]==9728 and sampler["minFilter"] in (9728,9984)
        assert sampler.get("wrapS",10497)==sampler.get("wrapT",10497)==10497
    count=0
    lo=np.full(3,np.inf)
    hi=-lo
    transform=matrices(gltf)
    for i,node in enumerate(gltf["nodes"]):
        if "mesh" not in node:
            continue
        for prim in gltf["meshes"][node["mesh"]]["primitives"]:
            ids=acc(gltf,binary,prim["indices"])
            count+=len(ids)//3
            local=acc(gltf,binary,prim["attributes"]["POSITION"])
            world=local@transform[i][:3,:3].T+transform[i][:3,3]
            assert np.isfinite(world).all()
            lo=np.minimum(lo,world.min(axis=0))
            hi=np.maximum(hi,world.max(axis=0))
    return gltf,transform,count,lo,hi


catalog=json.loads((R/"liner-model-info.json").read_text())
rooms=json.loads((R/"interiors"/"room-manifest.json").read_text())
results={}
for key,info in catalog.items():
    gltf,world,count,low,high=check_file(R/f"{key}.glb")
    assert count==info["triangles"] and len(gltf["materials"])==info["materials"],key
    assert abs((high-low)[2]-info["length_m"])<.30 and abs(low[1])<.001,key
    if key.endswith("-mid"):
        assert 3000<=count<=5000,key
    elif key.endswith("-far"):
        assert count<400,key
    for anchor in info["anchors"]:
        i=next(i for i,node in enumerate(gltf["nodes"]) if node["name"]==anchor["node"])
        assert np.allclose(world[i][:3,3],anchor["position_gltf_m"],atol=.001),key
    results[key]=count
for key,room in rooms.items():
    gltf,world,count,low,high=check_file(R/room["file"])
    nodes={node["name"]:i for i,node in enumerate(gltf["nodes"])}
    floor=room["floor_node"]
    assert count==room["triangles"] and "mesh" in gltf["nodes"][nodes[floor]],key
    assert len(room["collision_boxes_gltf_m"])>6 and low[1]>=-.001,key
    stairs=[point for stair in room["stairs"] for point in (stair["start"],stair["end"])]
    for marker in (*room["doors"],*stairs):
        index=nodes[marker["node"]]
        assert np.allclose(world[index][:3,3],marker["position_gltf_m"],atol=.001),key
    results[key]=count
modern=json.loads((R/"model-info.json").read_text())
gltf,_,count,low,high=check_file(R/"copperwake.glb")
assert count==modern["triangles_visible"]<=30000
assert len(modern["collision"]["boxes_gltf_m"])>216
linked={}
for node in gltf["nodes"]:
    if "mesh" in node:
        linked[node["mesh"]]=linked.get(node["mesh"],0)+1
assert max(linked.values())>20
assert sum("emissiveTexture" in m for m in gltf["materials"])>=2
results["copperwake"]=count
print(json.dumps(results,indent=2))
(R/"revision-validation.json").write_text(json.dumps({
    "verified_triangles":results,"room_count":len(rooms),"largest_shared_mesh_node_count":max(linked.values()),
    "modern_collision_boxes":len(modern["collision"]["boxes_gltf_m"])
},indent=2)+"\n")
