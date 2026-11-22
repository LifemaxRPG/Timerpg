"""Check all twelve GLBs against the C001 placement, LOD and texture contract."""
from io import BytesIO
from pathlib import Path
import json

import numpy as np
from PIL import Image

from glb_reader import load, acc, matrices

ROOT=Path(__file__).resolve().parent
info=json.loads((ROOT/"model-info.json").read_text())
reference=info["states"][0]["models"][0]["anchors"]
report=[]
for state in info["states"]:
    counts=[]
    for model in state["models"]:
        doc,raw=load(ROOT/model["file"])
        names={n["name"]:i for i,n in enumerate(doc["nodes"])}
        world=matrices(doc)
        assert {"lot","collision","sign_name",*reference}<=names.keys()
        assert len(doc["materials"])<=12
        assert model["anchors"]==reference
        for door,p in reference.items():
            m=world[names[door]]
            assert "mesh" not in doc["nodes"][names[door]]
            assert np.allclose(m[:3,3],p,atol=.00002)
            assert np.allclose(m[:3,2],(0,0,1 if door=="door_main" else -1),atol=.00002)
        images=[]
        for image in doc["images"]:
            view=doc["bufferViews"][image["bufferView"]]
            start=view.get("byteOffset",0)
            with Image.open(BytesIO(raw[start:start+view["byteLength"]])) as im:
                assert im.format=="PNG"
                px=np.asarray(im.convert("RGBA"))
                assert all(np.array_equal(px[::2,::2],px[y::2,x::2]) for x,y in ((1,0),(0,1),(1,1)))
                images.append(px)
        for sampler in doc["samplers"]:
            assert sampler["magFilter"]==9728 and sampler["minFilter"] in (9728,9984)
            assert sampler.get("wrapS",10497)==sampler.get("wrapT",10497)==10497

        def picture(texture):
            return images[doc["textures"][texture["index"]]["source"]]
        glass=next(m for m in doc["materials"] if m["name"]=="glass_storefront")
        assert glass["alphaMode"]=="BLEND"
        base=picture(glass["pbrMetallicRoughness"]["baseColorTexture"])
        mask=picture(glass["emissiveTexture"])[:,:,:3].max(axis=2)
        assert not np.any(mask[base[:,:,3]>=235])
        assert bool(np.any(mask))==(state["id"]!="closed")

        visible=[]
        colliders={}
        uv_error=0.0
        for i,node in enumerate(doc["nodes"]):
            if "mesh" not in node:
                continue
            for prim in doc["meshes"][node["mesh"]]["primitives"]:
                attrs=prim["attributes"]
                p=acc(doc,raw,attrs["POSITION"])@world[i][:3,:3].T+world[i][:3,3]
                tri=acc(doc,raw,prim["indices"]).reshape(-1,3)
                mat=doc["materials"][prim["material"]]
                if node.get("extras",{}).get("collision_only"):
                    assert mat["pbrMetallicRoughness"]["baseColorFactor"][3]==0
                    colliders[node["name"]]=(p.min(axis=0),p.max(axis=0))
                    continue
                visible.append(p[tri])
                image=picture(mat["pbrMetallicRoughness"]["baseColorTexture"])
                uv=acc(doc,raw,attrs["TEXCOORD_0"])*np.array(image.shape[:2][::-1])*.0388
                for a,b in ((0,1),(1,2),(2,0)):
                    p_length=np.linalg.norm(p[tri[:,a]]-p[tri[:,b]],axis=1)
                    uv_length=np.linalg.norm(uv[tri[:,a]]-uv[tri[:,b]],axis=1)
                    uv_error=max(uv_error,float(np.abs(p_length-uv_length).max()))
        surface=np.concatenate(visible)
        assert abs(surface[:,:,1].min())<.00003
        assert np.isclose(surface[:,:,1].max(),info["height_m"],atol=.00003)
        assert np.linalg.norm(np.cross(surface[:,1]-surface[:,0],surface[:,2]-surface[:,0]),axis=1).min()>1e-9
        tris=len(surface)
        assert tris==model["triangles_visible"]<=5000 and uv_error<.00006
        # Cast through each portal at three offsets and two pedestrian heights.
        a,b,c=surface[:,0],surface[:,1],surface[:,2]
        u,v=b-a,c-a
        den=u[:,0]*v[:,1]-u[:,1]*v[:,0]
        valid=np.abs(den)>1e-9
        for name,(x,y,z) in reference.items():
            for sx in (x-.48,x,x+.48):
                for sy in (y+.68,y+1.79):
                    sx0,sy0=sx-a[:,0],sy-a[:,1]
                    w=np.divide(sx0*v[:,1]-v[:,0]*sy0,den,out=np.zeros_like(den),where=valid)
                    t=np.divide(u[:,0]*sy0-sx0*u[:,1],den,out=np.zeros_like(den),where=valid)
                    hit=valid&(w>=-1e-6)&(t>=-1e-6)&(w+t<=1.000001)
                    hit_z=a[:,2]+w*u[:,2]+t*v[:,2]
                    assert not np.any(hit&(np.abs(hit_z-z)<.85)),(model["file"],name)
        assert len(colliders)==len(model["collision_boxes_gltf_m"])
        for i,box in enumerate(model["collision_boxes_gltf_m"]):
            lo,hi=colliders[f"collider_{i:02}"]
            assert np.allclose((lo+hi)/2,box["center_gltf_m"],atol=.00003)
            assert np.allclose(hi-lo,box["size_gltf_m"],atol=.00003)
        sign=model["sign_name"]
        width=sign["rect_texels"][2]*.0388
        assert sign["width_m"]==width
        counts.append(tris)
        report.append({"asset":model["file"],"visible_triangles":tris,"materials":len(doc["materials"]),
                       "doors_checked":2,"collision_boxes_checked":len(colliders),
                       "pane_emission":bool(np.any(mask)),"max_uv_error_m":round(uv_error,8)})
    assert counts[0]>counts[1]>counts[2]
(ROOT/"validation.json").write_text(json.dumps(report,indent=2)+"\n")
print(f"{len(report)} GLBs passed placement, LOD, doorway, collision, pane mask and pixel-scale checks")
