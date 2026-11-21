"""Check the shipped props, including each actual mesh and packed image."""
from io import BytesIO
from pathlib import Path
import json

import numpy as np
from PIL import Image

from glb_reader import load, acc, matrices

ROOT=Path(__file__).resolve().parent
manifest=json.loads((ROOT/"model-info.json").read_text())
assets=manifest["props"]
assert len(assets)==len({entry["id"] for entry in assets})==24
assert {a["file"] for a in assets}=={p.relative_to(ROOT).as_posix() for p in ROOT.glob("props/*.glb")}
report=[]
for asset in assets:
    gltf,buf=load(ROOT/asset["file"])
    assert len(gltf["nodes"])==1 and gltf["nodes"][0]["name"]==asset["id"],asset
    assert not gltf.get("cameras")
    assert not any("uri" in buffer for buffer in gltf["buffers"])
    dimensions=[]
    for image in gltf["images"]:
        view=gltf["bufferViews"][image["bufferView"]]
        start=view.get("byteOffset",0)
        with Image.open(BytesIO(buf[start:start+view["byteLength"]])) as img:
            assert img.format=="PNG"
            pixels=np.asarray(img.convert("RGBA"))
            assert all(np.array_equal(pixels[::2,::2],pixels[y::2,x::2]) for x,y in ((1,0),(0,1),(1,1)))
            assert all(d>0 and d&(d-1)==0 for d in img.size)
            dimensions.append(img.size)
    for sampler in gltf["samplers"]:
        assert sampler["magFilter"]==9728 and sampler["minFilter"] in (9728,9984)
        assert sampler.get("wrapS",10497)==sampler.get("wrapT",10497)==10497
    matrices_world=matrices(gltf)
    tris=[]
    max_error=0
    for i,node in enumerate(gltf["nodes"]):
        matrix=matrices_world[i]
        for prim in gltf["meshes"][node["mesh"]]["primitives"]:
            attributes=prim["attributes"]
            p=acc(gltf,buf,attributes["POSITION"])@matrix[:3,:3].T+matrix[:3,3]
            t=acc(gltf,buf,prim["indices"]).reshape(-1,3)
            uv=acc(gltf,buf,attributes["TEXCOORD_0"])
            texture=gltf["materials"][prim["material"]]["pbrMetallicRoughness"]["baseColorTexture"]["index"]
            uv=uv*np.array(dimensions[gltf["textures"][texture]["source"]])*.0388
            tris.append(p[t])
            for a,b in ((0,1),(1,2),(2,0)):
                edge=np.linalg.norm(p[t[:,a]]-p[t[:,b]],axis=1)
                textured=np.linalg.norm(uv[t[:,a]]-uv[t[:,b]],axis=1)
                max_error=max(max_error,float(np.max(np.abs(edge-textured))))
    triangles=np.concatenate(tris)
    model_min=triangles.min(axis=(0,1))
    model_max=triangles.max(axis=(0,1))
    area=np.linalg.norm(np.cross(triangles[:,1]-triangles[:,0],triangles[:,2]-triangles[:,0]),axis=1)/2
    assert area.min()>1e-9,(asset["id"],area.min())
    assert abs(model_min[1])<.00003 and np.allclose(model_max-model_min,asset["size_gltf_m"],atol=.00006),asset
    assert len(triangles)==asset["triangles"]<500
    assert len(gltf["materials"])==asset["materials"]==1
    assert max_error<.00003,(asset["id"],max_error)
    if asset["id"]=="volleyball-net":
        assert len(asset["collision_boxes"])==2
    report.append({"id":asset["id"],"triangles":len(triangles),"max_uv_error_m":round(max_error,8),
                   "packed_texture_dimensions":dimensions})
results={"props_validated":len(report),"triangle_range":[min(a["triangles"] for a in report),
          max(a["triangles"] for a in report)],"texture_texel_m":.0388,"models":report}
(ROOT/"validation.json").write_text(json.dumps(results,indent=2)+"\n")
print(f'{len(report)} props validated, {results["triangle_range"]} triangles per prop')
