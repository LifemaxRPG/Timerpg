"""Validate the geometry, placement and pixel grid of all 26 palm exports."""
import io
import json
from pathlib import Path

from PIL import Image
import numpy as np
from glb_reader import acc,load,matrices

ROOT=Path(__file__).resolve().parent
info=json.loads((ROOT/"model-info.json").read_text())
summary={}
assert len(info["types"])==13
for tree in info["types"]:
    for level,limit in (("near",1200),("mid",190)):
        record=tree[level]
        gltf,binary=load(ROOT/record["file"])
        assert len(gltf["materials"])==len(gltf["images"])==2
        world=matrices(gltf)
        dimensions=[]
        for image in gltf["images"]:
            v=gltf["bufferViews"][image["bufferView"]]
            offset=v.get("byteOffset",0)
            png=Image.open(io.BytesIO(binary[offset:offset+v["byteLength"]]))
            assert png.format=="PNG"
            data=np.asarray(png.convert("RGB"))
            assert all(np.array_equal(data[::2,::2],data[y::2,x::2])
                       for x,y in ((0,1),(1,0),(1,1)))
            dimensions.append(png.size)
        for s in gltf["samplers"]:
            assert s["magFilter"]==9728 and s["minFilter"] in (9728,9984)
            assert s.get("wrapS",10497)==s.get("wrapT",10497)==10497
        triangles=[]
        errors=[]
        for index,node in enumerate(gltf["nodes"]):
            if "mesh" not in node:
                continue
            for p in gltf["meshes"][node["mesh"]]["primitives"]:
                xyz=acc(gltf,binary,p["attributes"]["POSITION"])
                xyz=xyz@world[index][:3,:3].T+world[index][:3,3]
                indices=acc(gltf,binary,p["indices"]).reshape(-1,3)
                faces=xyz[indices]
                triangles.append(faces)
                material=gltf["materials"][p["material"]]["pbrMetallicRoughness"]
                tex=gltf["textures"][material["baseColorTexture"]["index"]]
                uv=acc(gltf,binary,p["attributes"]["TEXCOORD_0"])*np.asarray(dimensions[tex["source"]])*.0388
                for a,b in ((0,1),(1,2),(0,2)):
                    distance=np.linalg.norm(faces[:,a]-faces[:,b],axis=1)
                    uv_distance=np.linalg.norm(uv[indices[:,a]]-uv[indices[:,b]],axis=1)
                    errors.append(float(abs(distance-uv_distance).max()))
        shape=np.concatenate(triangles)
        low,high=shape.min(axis=(0,1)),shape.max(axis=(0,1))
        norm=np.cross(shape[:,1]-shape[:,0],shape[:,2]-shape[:,0])
        assert np.isfinite(shape).all() and np.linalg.norm(norm,axis=1).min()>1e-9
        assert len(shape)==record["triangles"]<=limit,(tree["id"],level,len(shape))
        assert abs(low[1])<1e-5 and max(errors)<.002,(tree["id"],max(errors))
        assert np.allclose(low,record["min_gltf_m"],atol=.0001)
        assert np.allclose(high,record["max_gltf_m"],atol=.0001)
        summary.setdefault(tree["id"],{})[level]={"triangles":len(shape),"height_m":round(float(high[1]),4),
                                               "max_uv_edge_error_m":round(max(errors),8)}
    near,mid=summary[tree["id"]]["near"],summary[tree["id"]]["mid"]
    assert abs(near["height_m"]-mid["height_m"])<max(.23,near["height_m"]*.075),tree["id"]
(ROOT/"validation.json").write_text(json.dumps(summary,indent=2)+"\n")
print(f"Validated {len(summary)*2} GLBs")
