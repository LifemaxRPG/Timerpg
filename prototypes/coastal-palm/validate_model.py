"""Validate both coastal palm exports and their shared pixel grid."""
import io
import json

import numpy as np
from PIL import Image
from glb_reader import R,acc,load,matrices

info=json.loads((R/"model-info.json").read_text())
summary={}
for level,budget in (("near",1200),("mid",190)):
    record=info[level]
    gltf,binary=load(R/record["file"])
    world=matrices(gltf)
    assert len(gltf["materials"])==2 and len(gltf["images"])==2
    assert all(m["pbrMetallicRoughness"].get("roughnessFactor",1)>=.9 for m in gltf["materials"])
    dimensions=[]
    for image in gltf["images"]:
        view=gltf["bufferViews"][image["bufferView"]]
        offset=view.get("byteOffset",0)
        png=Image.open(io.BytesIO(binary[offset:offset+view["byteLength"]]))
        pixels=np.array(png.convert("RGBA"))
        assert png.format=="PNG" and all(d%2==0 for d in png.size)
        assert all(np.array_equal(pixels[::2,::2],pixels[y::2,x::2])
                   for x,y in ((0,1),(1,0),(1,1)))
        dimensions.append(png.size)
    for sampler in gltf["samplers"]:
        assert sampler["magFilter"]==9728 and sampler["minFilter"] in (9728,9984)
        assert sampler.get("wrapS",10497)==sampler.get("wrapT",10497)==10497
    triangles=[]
    errors=[]
    for i,node in enumerate(gltf["nodes"]):
        if "mesh" not in node:
            continue
        for prim in gltf["meshes"][node["mesh"]]["primitives"]:
            attr=prim["attributes"]
            positions=acc(gltf,binary,attr["POSITION"])
            positions=positions@world[i][:3,:3].T+world[i][:3,3]
            ids=acc(gltf,binary,prim["indices"]).reshape(-1,3)
            mesh=positions[ids]
            triangles.append(mesh)
            mat=gltf["materials"][prim["material"]]
            texture=mat["pbrMetallicRoughness"]["baseColorTexture"]["index"]
            size=dimensions[gltf["textures"][texture]["source"]]
            uv=acc(gltf,binary,attr["TEXCOORD_0"])*np.array(size)*.0388
            for a,b in ((0,1),(1,2),(0,2)):
                errors.append(float(abs(np.linalg.norm(mesh[:,a]-mesh[:,b],axis=1)-
                                        np.linalg.norm(uv[ids[:,a]]-uv[ids[:,b]],axis=1)).max()))
    data=np.concatenate(triangles)
    lo,hi=data.min(axis=(0,1)),data.max(axis=(0,1))
    area=np.linalg.norm(np.cross(data[:,1]-data[:,0],data[:,2]-data[:,0]),axis=1)
    assert np.isfinite(data).all() and area.min()>1e-9
    assert len(data)==record["triangles"]<=budget
    assert max(errors)<.002 and abs(lo[1])<.0001
    assert np.allclose(lo,record["min_gltf_m"],atol=.0001)
    assert np.allclose(hi,record["max_gltf_m"],atol=.0001)
    assert {"trunk","crown"}<={n["name"] for n in gltf["nodes"]}
    summary[level]={"triangles":len(data),"height_m":round(float(hi[1]),4),
                    "max_uv_edge_error_m":round(max(errors),8),"materials":2}
for bound in ("min_gltf_m","max_gltf_m"):
    assert np.allclose(info["near"][bound],info["mid"][bound],atol=.35)
(R/"validation.json").write_text(json.dumps(summary,indent=2)+"\n")
print(summary)
