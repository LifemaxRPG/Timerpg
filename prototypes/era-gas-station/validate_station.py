"""Check station geometry, price and name panels, and refuelling anchors."""
from io import BytesIO
from pathlib import Path
import json

import numpy as np
from PIL import Image

from glb_reader import acc, load, matrices

ROOT=Path(__file__).resolve().parent
MODEL=json.loads((ROOT/"model-info.json").read_text())


def grid(a):
    return all(np.array_equal(a[::2,::2],a[y::2,x::2]) for x,y in ((1,0),(0,1),(1,1)))


def hit(vertices,origin,direction,lo,hi):
    """Intersect a short horizontal path with any exported surface."""
    a,b,c=(vertices-origin).transpose(1,0,2)
    axis=int(np.argmax(abs(direction)))
    ab,ac=b-a,c-a
    u,v=[i for i in range(3) if i!=axis]
    det=ab[:,u]*ac[:,v]-ab[:,v]*ac[:,u]
    with np.errstate(divide="ignore",invalid="ignore"):
        s=(-a[:,u]*ac[:,v]+a[:,v]*ac[:,u])/det
        t=(-ab[:,u]*a[:,v]+ab[:,v]*a[:,u])/det
        ray=(a[:,axis]+s*ab[:,axis]+t*ac[:,axis])*direction[axis]
        return np.any(np.isfinite(s)&np.isfinite(t)&(s>=0)&(t>=0)&(s+t<=1)&(ray>lo)&(ray<hi))


def examine(era,st,lod,asset):
    g,blob=load(ROOT/asset["file"])
    w=matrices(g)
    nd=g["nodes"]
    names={n["name"]:i for i,n in enumerate(nd)}
    parents={child:n["name"] for n in nd for child in n.get("children",[])}
    assert {"Vale Lantern Fuel","lot","collision","door_main","door_back",
            "sign_name_street","sign_name_exit"}<=names.keys()
    assert np.allclose(w[names["Vale Lantern Fuel"]],np.eye(4))
    assert all(s["magFilter"]==9728 and s["minFilter"] in (9728,9984) and
               s.get("wrapS",10497)==s.get("wrapT",10497)==10497 for s in g["samplers"])
    pngs={}
    for i,image in enumerate(g["images"]):
        view=g["bufferViews"][image["bufferView"]]
        off=view.get("byteOffset",0)
        with Image.open(BytesIO(blob[off:off+view["byteLength"]])) as im:
            a=np.asarray(im.convert("RGBA"))
            assert grid(a)
            pngs[i]=a

    def tex(field):
        return pngs[g["textures"][field["index"]]["source"]]

    mats=g["materials"]
    by_mat={m["name"]:m for m in mats}
    for name in ("glass_shop","sign_station","glow_price"):
        bitmap=tex(by_mat[name]["emissiveTexture"])[:,:,:3]
        assert bitmap.any()
        if name=="glass_shop":
            for v in (0,1,8,14,15):
                assert not bitmap[2*v,:,:].any() and not bitmap[:,2*v,:].any()
        else:
            x,y,width,height=(st["price_panels"][0] if name=="glow_price" else st["signs"][0])["rect"]
            assert bitmap[y:y+height,x:x+width,:].any()
    assert parents[names["price_panel"]]=="roadside_sign"
    assert {"cashier_counter","cashier_clerk","cashier_guest"}<=names.keys()
    pumps=[p for key,p in st["features"].items() if key.startswith("pump_")]
    assert len(pumps)==(6 if era=="1975" else 2)
    for pump in pumps:
        assert parents[names[pump["node"]]]=="fuel_pumps"
        assert pump["nozzle"] in names and pump["use"] in names
        assert any("mesh" in n for n in nd if parents.get(names[n["name"]])==pump["node"])
        target=np.array(st["anchors"][pump["use"]])+[0,1,0]
        for b in st["collision_boxes"]:
            assert not np.all(abs(target-b["center_gltf_m"])<np.asarray(b["size_gltf_m"])/2-.003)
    if era=="1925":
        assert by_mat["glass_gravity_pump"]["alphaMode"]=="BLEND"
    elif era=="1945":
        assert {"service_bay_1","service_bay_2","door_service_1","door_service_2","pay_phone"}<=names.keys()
    else:
        assert {"air_machine","ice_chest"}<=names.keys()

    visible,hidden=0,0
    all_faces=[]
    for i,n in enumerate(nd):
        if "mesh" not in n:
            continue
        tf=w[i]
        for p in g["meshes"][n["mesh"]]["primitives"]:
            xyz=acc(g,blob,p["attributes"]["POSITION"])@tf[:3,:3].T+tf[:3,3]
            assert xyz[:,1].min()>=-1e-6,n["name"]
            indices=acc(g,blob,p["indices"]).reshape(-1,3)
            mat=mats[p["material"]]
            if mat["name"]=="collision_invisible":
                hidden+=len(indices)
                assert n["extras"]["collision_only"]
                index=int(n["name"].rsplit("_",1)[1])
                box=st["collision_boxes"][index]
                a,b=xyz.min(axis=0),xyz.max(axis=0)
                assert np.allclose((a+b)/2,box["center_gltf_m"],atol=.00006)
                assert np.allclose(b-a,box["size_gltf_m"],atol=.00006)
                continue
            visible+=len(indices)
            shape=tex(mat["pbrMetallicRoughness"]["baseColorTexture"]).shape
            uv=acc(g,blob,p["attributes"]["TEXCOORD_0"])[indices]*np.array(shape[:2][::-1])*MODEL["meters_per_texel"]
            tris=xyz[indices]
            all_faces.append(tris)
            a=np.linalg.norm(tris-np.roll(tris,1,axis=1),axis=2)
            b=np.linalg.norm(uv-np.roll(uv,1,axis=1),axis=2)
            assert np.allclose(a,b,atol=.0011,rtol=.002),n["name"]

    assert visible==asset["triangles"] and visible<=12000
    assert len(asset["materials"])<=12
    if lod=="near":
        assert hidden==len(st["collision_boxes"])*12
        faces=np.concatenate(all_faces)
    else:
        assert hidden==0
    for name,a in st["doors"].items():
        tf=w[names[name]]
        facing=np.array(a["facing_gltf"])
        center=np.array(a["position_gltf_m"])
        assert np.allclose(tf[:3,3],center,atol=.00008),name
        assert np.allclose(tf[:3,2],facing,atol=.00008),name
        if lod=="near":
            across=np.cross([0,1,0],facing)
            for x in (-.27,0,.27):
                for y in (.88,1.63):
                    probe=center+across*x+[0,y,0]
                    assert not hit(faces,probe,facing,-.52,.20),(era,name,x,y)
                    samples=[probe+facing*r for r in np.linspace(-.46,.20,8)]
                    for b in st["collision_boxes"]:
                        delta=np.asarray(samples)-np.asarray(b["center_gltf_m"])
                        assert not np.any(np.all(abs(delta)<np.asarray(b["size_gltf_m"])/2-.002,axis=1)),(name,b["name"])
    for name,a in st["anchors"].items():
        assert np.allclose(w[names[name]][:3,3],a,atol=.00008),name
    for item in ("signs","price_panels"):
        a=st[item][0]
        children=a["nodes"] if item=="signs" else ["price_face_street","price_face_exit"]
        for name in children:
            n=nd[names[name]]
            p=g["meshes"][n["mesh"]]["primitives"][0]
            x,y,width,height=a["rect"]
            uv=acc(g,blob,p["attributes"]["TEXCOORD_0"])
            board_w,board_h=(512,256) if item=="signs" else (width,height)
            assert np.allclose(uv.min(axis=0),(x/board_w,y/board_h))
            assert np.allclose(uv.max(axis=0),((x+width)/board_w,(y+height)/board_h))
            if item=="price_panels":
                assert p["material"]==mats.index(by_mat["glow_price"])
    assert (np.asarray(st["price"]["art"])*2==st["price_panels"][0]["rect"][2:]).all()
    assert st["price"]["node"]=="price_face_street"
    return {"visible_triangles":visible,"visible_materials":len(asset["materials"]),
            "tested_thresholds":len(st["doors"]) if lod=="near" else 0,"collision_boxes":hidden//12}


assert MODEL["task"]=="C015" and MODEL["naming"]=="owner"
result={}
first=MODEL["states"]["1925"]
for era,state in MODEL["states"].items():
    assert all(d==state["doors"][name] for name,d in first["doors"].items())
    lods=state["models"]
    assert lods["near"]["triangles"]>lods["mid"]["triangles"]>lods["far"]["triangles"]
    result[era]={lod:examine(era,state,lod,a) for lod,a in lods.items()}
(ROOT/"validation.json").write_text(json.dumps(result,indent=2)+"\n")
print(json.dumps(result,indent=2))
