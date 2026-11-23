"""Validate exported C011 geometry, articulation, physical texels, and LOD budgets."""
from io import BytesIO
import json
from math import cos, radians, sin
from pathlib import Path

import numpy as np
from PIL import Image

from glb_reader import acc, load, matrices

ROOT=Path(__file__).resolve().parent
meta=json.loads((ROOT/"model-info.json").read_text())
regions=json.loads((ROOT/"textures/regions.json").read_text())
T=.0388

def embedded(doc,data,texture):
    image=doc["images"][doc["textures"][texture]["source"]]
    assert image["mimeType"]=="image/png"
    view=doc["bufferViews"][image["bufferView"]]
    p=view.get("byteOffset",0)
    with Image.open(BytesIO(data[p:p+view["byteLength"]])) as bitmap:
        return np.array(bitmap.convert("RGBA"))

def pixel_grid(image):
    return all(np.array_equal(image[::2,::2],image[i::2,j::2])
               for i,j in ((0,1),(1,0),(1,1)))

def descendants(doc,node):
    for child in doc["nodes"][node].get("children",[]):
        yield child
        yield from descendants(doc,child)

def project(points,matrix):
    return points@matrix[:3,:3].T+matrix[:3,3]

reports=[]
assert meta["texel_m"]==T and len(meta["vehicles"])==3
for item in meta["vehicles"]:
    assert [a["lod"] for a in item["lods"]]==["near","mid","far"]
    assert 4<=len(item["paint"]["preset_body_colours"])<=6
    assert all(m["center_gltf_m"][1]-m["size_gltf_m"][1]/2>=0 for m in item["collision_boxes"])
    for key,mask in item["paint"]["masks"].items():
        base=Image.open(ROOT/"textures"/(item["key"]+"-"+key+".png"))
        image=np.asarray(Image.open(ROOT/mask))
        assert image.shape==base.size[::-1] and pixel_grid(image)
        assert image.min()<image.max()==255 and (image==255).mean()>.95
    for a in item["lods"]:
        doc,data=load(ROOT/a["file"])
        world=matrices(doc)
        nodes=doc["nodes"]
        by_name={n["name"]:i for i,n in enumerate(nodes)}
        parents={child:n["name"] for n in nodes for child in n.get("children",[])}
        far=a["lod"]=="far"
        near=a["lod"]=="near"
        assert len(doc["materials"])<=6
        assert {m["name"] for m in doc["materials"]}==set(a["materials"])
        assert all(s["magFilter"]==9728 and s["minFilter"] in (9728,9984)
                   and s.get("wrapS",10497)==s.get("wrapT",10497)==10497 for s in doc["samplers"])
        assert set(a["nodes"])==set(item["lods"][0]["nodes"])
        for name,info in a["nodes"].items():
            i=by_name[name]
            assert parents[i]==info["parent"]
            assert np.allclose(world[i][:3,3],info["center_gltf_m"],atol=1e-6)
            assert np.allclose(world[i][:3,3],item["lods"][0]["nodes"][name]["center_gltf_m"],atol=1e-6)
            if "axis_local_gltf" in info:
                assert nodes[i]["extras"]["axis_local_gltf"]==info["axis_local_gltf"]
                assert np.allclose(world[i][:3,:3],np.eye(3),atol=1e-6)
        for fore in ("f","r"):
            for side in ("l","r"):
                name="wheel_"+fore+side
                pivot=a["nodes"][name]["center_gltf_m"]
                expected=[(1 if side=="l" else -1)*item["track_m"]/2,item["wheel_radius_m"],
                          (1 if fore=="f" else -1)*item["wheelbase_m"]/2]
                assert np.allclose(pivot,expected,atol=1e-6)
                if fore=="f":
                    assert a["nodes"][name]["parent"]=="steer_f"+side
        assert a["nodes"]["exit_left"]["center_gltf_m"][0]>0>a["nodes"]["exit_right"]["center_gltf_m"][0]
        emissive=[m for m in doc["materials"] if "emissiveTexture" in m]
        assert len(emissive)==(0 if far else 1)
        lamp_children={i for name in a["lamps"] for i in descendants(doc,by_name[name])}
        if not far:
            assert emissive[0]["name"]=="glow_lamps"
            mask=embedded(doc,data,emissive[0]["emissiveTexture"]["index"])[:,:,:3]
            expected=np.zeros(mask.shape[:2],dtype=bool)
            for x,y in regions["lamps"].values():
                expected[y-6:y+8,x-6:x+8]=True
            assert mask[expected].any() and not mask[~expected].any()
            assert pixel_grid(mask)
        bounds=[]
        dim={}
        triangles=0
        max_error=0
        positions={}
        for node_id,node in enumerate(nodes):
            if "mesh" not in node:
                continue
            transform=world[node_id]
            positions[node_id]=[]
            for primitive in doc["meshes"][node["mesh"]]["primitives"]:
                idx=acc(doc,data,primitive["indices"]).reshape(-1,3)
                local=acc(doc,data,primitive["attributes"]["POSITION"]).astype(float)
                points=project(local,transform)
                bounds.append(points)
                positions[node_id].append(points)
                triangles+=len(idx)
                mat=doc["materials"][primitive["material"]]
                assert mat["name"]!="glow_lamps" or node_id in lamp_children
                tex=mat["pbrMetallicRoughness"]["baseColorTexture"]["index"]
                if tex not in dim:
                    image=embedded(doc,data,tex)
                    assert pixel_grid(image)
                    dim[tex]=np.array(image.shape[:2][::-1])
                uv=acc(doc,data,primitive["attributes"]["TEXCOORD_0"]).astype(float)[idx]*dim[tex]*T
                xyz=points[idx]
                edges=np.linalg.norm(np.roll(xyz,1,axis=1)-xyz,axis=-1)
                uv_edges=np.linalg.norm(np.roll(uv,1,axis=1)-uv,axis=-1)
                max_error=max(max_error,float(abs(edges-uv_edges).max()))
        coords=np.concatenate(bounds)
        assert np.allclose(np.column_stack((coords.min(axis=0),coords.max(axis=0))),a["bounds_gltf_m"],atol=1e-5)
        assert -.001<coords[:,1].min()<.001
        assert max_error<.0002
        assert triangles==a["triangles"]
        assert triangles<=3000 if near else triangles<150 if far else triangles<.40*item["lods"][0]["triangles"]
        if not far:
            for hinge,sweep in a["door_preview_degrees"].items():
                hinge_id=by_name[hinge]
                bits=[p for i in descendants(doc,hinge_id) for p in positions.get(i,[])]
                center=np.asarray(a["nodes"][hinge]["center_gltf_m"])
                points=np.concatenate(bits)-center
                theta=radians(sweep)
                rotated=points[:,0]*cos(theta)+points[:,2]*sin(theta)
                outward=rotated*(1 if center[0]>0 else -1)
                assert outward.max()>.75 and np.max(abs(points[:,0]))<.30
            assert all(any(i in positions for i in descendants(doc,by_name[name])) for name in a["lamps"])
            assert "hood" in by_name
            if near:
                assert "badge_plate" in by_name
        reports.append({"file":a["file"],"triangles":triangles,"materials":len(doc["materials"]),
                        "max_uv_edge_error_m":max_error,"nodes_checked":len(a["nodes"])})
(ROOT/"validation.json").write_text(json.dumps(reports,indent=2)+"\n")
print("Validated nine exports, wheel and door articulation, lamp and paint masks, physical texels, and distance budgets.")
