"""Check both exported towers against their physical asset manifests."""
from pathlib import Path
from io import BytesIO
import json

import numpy as np
from PIL import Image

from glb_reader import load, acc, matrices

ROOT=Path(__file__).resolve().parent

def surface_y(tris, x, z):
    """Return all triangle heights hit by a vertical ray at floating-point coordinates."""
    a,b,c = tris[:,0],tris[:,1],tris[:,2]
    dx1,dz1 = b[:,0]-a[:,0],b[:,2]-a[:,2]
    dx2,dz2 = c[:,0]-a[:,0],c[:,2]-a[:,2]
    det = dx1*dz2-dz1*dx2
    mask = np.abs(det) > 1e-9
    dx,dz = x-a[:,0],z-a[:,2]
    first = np.divide(dx*dz2-dz*dx2,det,where=mask,out=np.zeros_like(det))
    second = np.divide(dx1*dz-dz1*dx,det,where=mask,out=np.zeros_like(det))
    valid = mask & (first>=-1e-6) & (second>=-1e-6) & (first+second<=1.000001)
    return (a[:,1]+first*(b[:,1]-a[:,1])+second*(c[:,1]-a[:,1]))[valid]


def validate(state):
    info=json.loads((ROOT/f"model-info-{state}.json").read_text())
    gltf,data=load(ROOT/info["file"])
    assert not gltf.get("cameras") and "KHR_lights_punctual" not in gltf.get("extensionsUsed",[])
    assert len(gltf["materials"])==len(info["materials"])<=12
    names={node["name"]:i for i,node in enumerate(gltf["nodes"])}
    world=matrices(gltf)
    assert "lot" in names and "optional_headland" in names
    assert "mesh" not in gltf["nodes"][names["beacon_beam"]]
    assert np.isclose(world[names["beacon_beam"]][1,3],info["focal_height_above_grade_m"])
    sizes=[]
    for image in gltf["images"]:
        bv=gltf["bufferViews"][image["bufferView"]]
        start=bv.get("byteOffset",0)
        with Image.open(BytesIO(data[start:start+bv["byteLength"]])) as img:
            assert img.format=="PNG"
            pix=np.asarray(img.convert("RGBA"))
            assert all(np.array_equal(pix[::2,::2],pix[j::2,i::2]) for i,j in ((1,0),(0,1),(1,1)))
            sizes.append(img.size)
    for sampler in gltf["samplers"]:
        assert sampler["magFilter"]==9728 and sampler["minFilter"] in (9728,9984)
        assert sampler.get("wrapS",10497)==sampler.get("wrapT",10497)==10497
    per_node={}
    uv_error=0
    for i,node in enumerate(gltf["nodes"]):
        if "mesh" not in node:
            continue
        pieces=[]
        for prim in gltf["meshes"][node["mesh"]]["primitives"]:
            att=prim["attributes"]
            p=acc(gltf,data,att["POSITION"])@world[i][:3,:3].T+world[i][:3,3]
            tri=acc(gltf,data,prim["indices"]).reshape(-1,3)
            uv=acc(gltf,data,att["TEXCOORD_0"])
            tex=gltf["materials"][prim["material"]]["pbrMetallicRoughness"]["baseColorTexture"]["index"]
            uv=uv*np.array(sizes[gltf["textures"][tex]["source"]])*.0388
            for a,b in ((0,1),(1,2),(2,0)):
                distance=np.linalg.norm(p[tri[:,a]]-p[tri[:,b]],axis=1)
                sampled=np.linalg.norm(uv[tri[:,a]]-uv[tri[:,b]],axis=1)
                uv_error=max(uv_error,float(np.abs(distance-sampled).max()))
            pieces.append(p[tri])
        per_node[i]=np.concatenate(pieces)
    all_tri=np.concatenate(list(per_node.values()))
    area=np.linalg.norm(np.cross(all_tri[:,1]-all_tri[:,0],all_tri[:,2]-all_tri[:,0]),axis=1)/2
    assert area.min()>1e-10,(state,area.min())
    assert len(all_tri)==info["triangles"]<=12000 and uv_error<.00007,(state,uv_error)
    assert np.isclose(all_tri[:,:,1].max(),info["height_above_grade_m"],atol=.005)
    for x in (-.8,0,.8):
        for z in (-.15,-6.5,-13.5,-20.5):
            heights=surface_y(all_tri,x,z)
            assert np.any(np.isclose(heights,.05,atol=.0001)),(state,x,z)
    assert np.allclose(world[names["door_main"]][:2,3],(0,.16),atol=.00002)
    for name,anchor in info["anchors"].items():
        assert np.allclose(world[names[name]][:3,3],anchor["gltf_m"],atol=.00002)
    stair_ids=gltf["nodes"][names["maintenance_stairs"]]["children"]
    stairs=np.concatenate([per_node[i] for i in stair_ids if i in per_node])
    for x,y,z in info["stair_tread_samples_gltf_m"]:
        assert np.any(np.isclose(surface_y(stairs,x,z),y,atol=.00003)),(state,x,y,z)
    for name,anchor in info["anchors"].items():
        if not name.startswith("door_"):
            continue
        assert np.allclose(world[names[name]][:3,2],(0,0,1),atol=.00002)
        x,y,z=anchor["gltf_m"]
        for offset in (-.33,0,.33):
            # Check the entire opening at waist and shoulder height, casting along world Z.
            for h in (.80,1.52,1.92):
                hits=surface_y(all_tri[:,:,[0,2,1]],x+offset,y+h)
                assert not ((hits<=z+.04)&(hits>=z-.87)).any(),(state,name,offset,h)
    return {"state":state,"triangles":len(all_tri),"materials":len(gltf["materials"]),
            "embedded_pixel_textures":len(sizes),"max_uv_edge_error_m":round(uv_error,8),
            "anchors_checked":len(info["anchors"]),"treads_checked":len(info["stair_tread_samples_gltf_m"]),
            "focal_height_m":info["focal_height_above_grade_m"]}

report=[validate(state) for state in ("historic","modern")]
(ROOT/"validation.json").write_text(json.dumps(report,indent=2)+"\n")
print(report)
