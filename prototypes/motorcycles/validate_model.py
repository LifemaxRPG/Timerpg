"""Validate exported C013 geometry, articulation, physical texels, and LOD budgets."""
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
        required={"kickstand","handlebar","wheel_f","wheel_r","seat_rider",
                  "seat_pillion","exit_left","exit_right","grip_left","grip_right","peg_left","peg_right"}
        assert required.issubset(by_name)
        assert a["nodes"]["kickstand"]["axis_local_gltf"]==[1,0,0]
        for name,info in a["nodes"].items():
            i=by_name[name]
            assert parents[i]==info["parent"]
            assert np.allclose(world[i][:3,3],info["center_gltf_m"],atol=1e-6)
            assert np.allclose(world[i][:3,3],item["lods"][0]["nodes"][name]["center_gltf_m"],atol=1e-6)
            if "axis_local_gltf" in info:
                assert np.allclose(nodes[i]["extras"]["axis_local_gltf"],info["axis_local_gltf"],atol=1.e-7)
                assert np.allclose(world[i][:3,:3],np.eye(3),atol=1e-6)
        for end,sign in (("front",1),("rear",-1)):
            name="wheel_"+end[0]
            pivot=a["nodes"][name]["center_gltf_m"]
            assert np.allclose(pivot,[0,item["wheel_radius_m"][end],sign*item["wheelbase_m"]/2],atol=1.e-6)
            assert a["nodes"][name]["axis_local_gltf"]==[1,0,0]
        assert a["nodes"]["wheel_f"]["parent"]=="handlebar"
        assert parents[by_name["grip_left"]]==parents[by_name["grip_right"]]=="handlebar"
        head=np.array(a["nodes"]["handlebar"]["center_gltf_m"])
        axis=np.array(a["nodes"]["handlebar"]["axis_local_gltf"])
        assert abs(np.linalg.norm(axis)-1)<1.e-6 and axis[1]>.75 and axis[2]<-.15
        hub=np.array(a["nodes"]["wheel_f"]["center_gltf_m"])-head
        assert np.linalg.norm(np.cross(axis,hub))<1.e-6
        theta=radians(26)
        hand=np.array(a["nodes"]["grip_left"]["center_gltf_m"])-head
        swung=hand*cos(theta)+np.cross(axis,hand)*sin(theta)+axis*np.dot(axis,hand)*(1-cos(theta))
        assert np.linalg.norm(swung-hand)>.12
        assert a["nodes"]["exit_left"]["center_gltf_m"][0]>0>a["nodes"]["exit_right"]["center_gltf_m"][0]
        emissive=[m for m in doc["materials"] if "emissiveTexture" in m]
        assert len(emissive)==(0 if far else 1)
        lamp_children={i for name in a["lamps"] for i in descendants(doc,by_name[name])}
        if not far:
            assert emissive[0]["name"]=="glow_lamps"
            mask=embedded(doc,data,emissive[0]["emissiveTexture"]["index"])[:,:,:3]
            expected=np.zeros(mask.shape[:2],dtype=bool)
            for x,y in regions["lamps"].values():
                width=10 if item["key"]=="kelveran-2005" and x in (16,42) else 6
                expected[y-6:y+8,x-width:x+width+2]=True
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
        assert np.max(abs(coords[:,2])) < item["wheelbase_m"]/2 + .47, (a["file"],coords[:,2].min(),coords[:,2].max())
        assert max_error<.0002
        assert triangles==a["triangles"]
        assert triangles<=2000 if near else triangles<150 if far else triangles<.40*item["lods"][0]["triangles"]
        for key in ("wheel_f","wheel_r"):
            points=np.concatenate([p for i in descendants(doc,by_name[key]) for p in positions.get(i,[])])
            assert abs(points[:,1].min())<1.e-5
        if not far:
            angle=radians(a["kickstand_deploy_degrees"])
            pivot=np.array(a["nodes"]["kickstand"]["center_gltf_m"])
            stand=np.concatenate([p for i in descendants(doc,by_name["kickstand"]) for p in positions.get(i,[])])-pivot
            folded_min=np.min(stand[:,1]+pivot[1])
            deployed_y=stand[:,1]*cos(angle)-stand[:,2]*sin(angle)+pivot[1]
            assert folded_min>.30
            assert -.01<deployed_y.min()<.04,(a["file"],deployed_y.min())
            assert stand[np.argmin(deployed_y),0]+pivot[0]>.24
            assert all(any(i in positions for i in descendants(doc,by_name[name])) for name in a["lamps"])
            assert not any(spec["role"]=="reverse" for spec in a["lamps"].values())
            if near:
                assert "badge_plate" in by_name
        reports.append({"file":a["file"],"triangles":triangles,"materials":len(doc["materials"]),
                        "max_uv_edge_error_m":max_error,"nodes_checked":len(a["nodes"])})
(ROOT/"validation.json").write_text(json.dumps(reports,indent=2)+"\n")
print("Validated nine exports, steering rake and parenting, wheel contacts, folded/deployed kickstands, masks and LOD budgets.")
