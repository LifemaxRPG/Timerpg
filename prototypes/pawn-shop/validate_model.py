"""Validate geometry, repaint regions, collision and gameplay hooks in the GLBs."""
import io
import json
from pathlib import Path

import numpy as np
from PIL import Image

from glb_reader import acc, load, matrices

ROOT=Path(__file__).resolve().parent
INFO=json.loads((ROOT/"model-info.json").read_text())
T=INFO["meters_per_texel"]


def verify_art(im):
    a=np.asarray(im)
    assert all(n%2==0 for n in im.size)
    assert all(np.array_equal(a[::2,::2],a[y::2,x::2]) for x,y in ((1,0),(0,1),(1,1)))


def hit(p,x,y,limits):
    """Intersect a door-clearance segment parallel to model forward."""
    a,b,c=p.transpose(1,0,2)
    ab,ac=b-a,c-a
    det=ab[:,0]*ac[:,1]-ac[:,0]*ab[:,1]
    with np.errstate(divide="ignore",invalid="ignore"):
        u=((x-a[:,0])*ac[:,1]-(y-a[:,1])*ac[:,0])/det
        v=(ab[:,0]*(y-a[:,1])-ab[:,1]*(x-a[:,0]))/det
        z=a[:,2]+ab[:,2]*u+ac[:,2]*v
        return np.any(np.isfinite(u)&np.isfinite(v)&(u>=0)&(v>=0)&
                      (u+v<=1)&(z>limits[0])&(z<limits[1]))


def excludes(pts,bounds):
    center,size=np.asarray(bounds["center_gltf_m"]),np.asarray(bounds["size_gltf_m"])
    return not np.any(np.all(np.abs(np.asarray(pts)-center)<size/2-.002,axis=1))


def check(era,state,lod,model):
    g,blob=load(ROOT/model["file"])
    world=matrices(g)
    nodes={n["name"]:i for i,n in enumerate(g["nodes"])}
    assert {"lot","counter","collision","door_main","door_back","door_store","sign_name"}<=nodes.keys()
    assert np.allclose(world[nodes["Latch & Lyre"]],np.eye(4))
    images={}
    for i,entry in enumerate(g["images"]):
        view=g["bufferViews"][entry["bufferView"]]
        start=view.get("byteOffset",0)
        with Image.open(io.BytesIO(blob[start:start+view["byteLength"]])) as im:
            verify_art(im)
            images[i]=im.copy()
    for s in g["samplers"]:
        assert s["magFilter"]==9728 and s["minFilter"] in (9728,9984)
        assert s.get("wrapS",10497)==s.get("wrapT",10497)==10497

    def image(texture):
        return images[g["textures"][texture["index"]]["source"]]

    mats=g["materials"]
    for m in mats:
        if m["name"]=="glass_display":
            base=np.asarray(image(m["pbrMetallicRoughness"]["baseColorTexture"]).convert("RGBA"))
            mask=np.asarray(image(m["emissiveTexture"]).convert("RGB"))
            assert np.any(mask>0) and np.any(base[:,:,3]<255)
            assert not np.any(mask[base[:,:,3]==255]),"pane mask leaks onto frame or opaque letters"
        if m["name"]=="glass_inner":
            assert m["alphaMode"]=="BLEND"
            assert "emissiveTexture" not in m
        if m["name"]=="sign_paint":
            mask=np.asarray(image(m["emissiveTexture"]).convert("RGB"))
            x,y,w,h=state["signs"][0]["rect"]
            assert not np.any(mask[y:y+h,x:x+w]),"repaintable owner lettering should not emit"
            x,y,w,h=json.loads((ROOT/"textures/regions.json").read_text())["buygold" if era=="2000" else "pawn"]
            assert np.any(mask[y:y+h,x:x+w])

    nvis=nhidden=0
    triangles=[]
    for name,i in nodes.items():
        node=g["nodes"][i]
        if "mesh" not in node:
            continue
        tf=world[i]
        for prim in g["meshes"][node["mesh"]]["primitives"]:
            attrib=prim["attributes"]
            p=acc(g,blob,attrib["POSITION"])@tf[:3,:3].T+tf[:3,3]
            assert p[:,1].min()>=-1e-6,name
            indices=acc(g,blob,prim["indices"]).reshape(-1,3)
            m=mats[prim["material"]]
            if m["name"]=="collision_invisible":
                nhidden+=len(indices)
                assert lod=="near" and node["extras"]["collision_only"]
                expected=state["collision_boxes"][int(name.rsplit("_",1)[1])]
                high,low=p.max(axis=0),p.min(axis=0)
                assert np.allclose((high+low)/2,expected["center_gltf_m"],atol=.00012),name
                assert np.allclose(high-low,expected["size_gltf_m"],atol=.00012),name
                continue
            nvis+=len(indices)
            tri=p[indices]
            triangles.append(tri)
            uv=acc(g,blob,attrib["TEXCOORD_0"])
            size=image(m["pbrMetallicRoughness"]["baseColorTexture"]).size
            uv_tri=uv[indices]*np.asarray(size)*T
            side=np.linalg.norm(tri[:,[1,2,0]]-tri,axis=2)
            uv_side=np.linalg.norm(uv_tri[:,[1,2,0]]-uv_tri,axis=2)
            assert np.allclose(side,uv_side,atol=.0011,rtol=.002),name
    assert nvis==model["triangles"],(model["file"],nvis,model["triangles"])
    assert nvis<=5000 and len(model["materials"])<=12
    row={"visible_triangles":nvis,"visible_materials":len(model["materials"]),"collision_triangles":nhidden}
    if lod!="near":
        assert nhidden==0
        return row

    assert nhidden==len(state["collision_boxes"])*12
    assert {"safe","display_west","display_east","display_jewellery","display_stockroom","display_stock"}<=nodes.keys()
    for name,feature in state["features"].items():
        assert feature["node"] in nodes,name
        if name==feature["node"]:
            assert np.allclose(world[nodes[name]][:3,3],feature["position_gltf_m"],atol=.0001),name
    for name,pt in state["anchors"].items():
        assert np.allclose(world[nodes[name]][:3,3],pt,atol=.0001),name
    for name,door in state["doors"].items():
        p=door["position_gltf_m"]
        assert np.allclose(world[nodes[name]][:3,3],p,atol=.0001)
        assert np.allclose(world[nodes[name]][:3,2],[0,0,1 if door["facing"]=="+Z" else -1],atol=.0001)
        limits=(p[2]-.49,p[2]+.35)
        probes=[(p[0]+dx,1.28,z) for dx in (-.30,0,.30) for z in np.linspace(*limits,9)]
        assert all(excludes(probes,box) for box in state["collision_boxes"]),name
        mesh=np.concatenate(triangles)
        assert all(not hit(mesh,p[0]+dx,1.28,limits) for dx in (-.30,0,.30)),name

    sign=g["nodes"][nodes["sign_name"]]
    primitive=g["meshes"][sign["mesh"]]["primitives"][0]
    x,y,w,h=state["signs"][0]["rect"]
    uv=acc(g,blob,primitive["attributes"]["TEXCOORD_0"])
    assert np.allclose(uv.min(axis=0),[x/512,y/512])
    assert np.allclose(uv.max(axis=0),[(x+w)/512,(y+h)/512])
    row["collision_boxes"]=len(state["collision_boxes"])
    return row


out={}
door_sets=[]
for era,state in INFO["states"].items():
    models=state["models"]
    assert models["near"]["triangles"]>models["mid"]["triangles"]>models["far"]["triangles"]
    door_sets.append(state["doors"])
    out[era]={lod:check(era,state,lod,m) for lod,m in models.items()}
assert all(d==door_sets[0] for d in door_sets)
(ROOT/"validation.json").write_text(json.dumps(out,indent=2)+"\n")
print(json.dumps(out,indent=2))
