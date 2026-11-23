"""Validate exported C012 geometry, articulation, physical texels, and LOD budgets."""
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
assert meta["texel_m"]==T and len(meta["vehicles"])==4
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
        pickup=item["key"]=="tallmere-1972"
        liftback=item["key"] in ("pinelatch-1993","stoneweir-2015")
        cargo="tailgate" if pickup else "trunk"
        assert cargo in by_name and a["nodes"][cargo]["axis_local_gltf"]==[1,0,0]
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
            wayfarer=item["key"]=="stoneweir-2015"
            if wayfarer:
                for zone,(x,y) in json.loads((ROOT/"textures/stoneweir-2015-regions.json").read_text())["lamps"].items():
                    if zone in ("drl","tail_bar"):
                        w=18 if zone=="drl" else 26
                        expected[y:y+4,x-w:x+w+2]=True
                        assert mask[y:y+4,x-w:x+w+2].max()>0
            for x,y in regions["lamps"].values():
                width=10 if item["key"]=="stoneweir-2015" and x in (16,42) else 6
                expected[y-6:y+8,x-width:x+width+2]=True
            assert mask[expected].any() and not mask[~expected].any()
            assert pixel_grid(mask)
        paint_faces=[]
        bounds=[]
        dim={}
        triangles=0
        max_error=0
        positions={}
        bed_clear=True
        moving_cargo=set(descendants(doc,by_name[cargo]))
        cargo_glazing=False
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
                if item["key"]=="stoneweir-2015" and mat["name"]=="paint_primary":
                    paint_faces.extend(xyz)
                if liftback and node_id in moving_cargo and mat["name"]=="glass_vehicle":
                    cargo_glazing=True
                if pickup and not far:
                    lo,hi=xyz.min(axis=1),xyz.max(axis=1)
                    hit_axes=(hi>[-.38,.738,-2.52])&(lo<[.38,1.003,-.976])
                    bed_clear=bed_clear and not np.all(hit_axes,axis=1).any()
                edges=np.linalg.norm(np.roll(xyz,1,axis=1)-xyz,axis=-1)
                uv_edges=np.linalg.norm(np.roll(uv,1,axis=1)-uv,axis=-1)
                max_error=max(max_error,float(abs(edges-uv_edges).max()))
        coords=np.concatenate(bounds)
        assert np.allclose(np.column_stack((coords.min(axis=0),coords.max(axis=0))),a["bounds_gltf_m"],atol=1e-5)
        assert -.001<coords[:,1].min()<.001
        if item["key"]=="stoneweir-2015":
            assert 1.65<=coords[:,1].max()<=1.70+.002
            assert .70<=2*item["wheel_radius_m"]<=.75
            assert parents[by_name["light_tail_bar"]]=="trunk"
            assert {"light_drl_l","light_drl_r","light_tail_bar"}.issubset(a["lamps"])
            wheels={i for key in ("wheel_fl","wheel_fr","wheel_rl","wheel_rr")
                    for i in descendants(doc,by_name[key])}
            min_chassis=min(p[:,1].min() for i,ps in positions.items() if i not in wheels for p in ps)
            assert .197<=min_chassis<=.212,(a["file"],min_chassis)
            if not far:
                faces=np.asarray(paint_faces)
                a0,b0,c0=np.moveaxis(faces,1,0)
                u,v=b0-a0,c0-a0
                det=u[:,1]*v[:,2]-u[:,2]*v[:,1]
                valid=abs(det)>1.e-9
                for axle_z in (-1.35,1.35):
                    for delta in (-.12,0,.12):
                        dy=.647-a0[:,1]
                        dz=axle_z+delta-a0[:,2]
                        a_weight=np.divide(dy*v[:,2]-dz*v[:,1],det,where=valid,out=np.zeros_like(det))
                        b_weight=np.divide(u[:,1]*dz-u[:,2]*dy,det,where=valid,out=np.zeros_like(det))
                        inside=valid&(a_weight>=0)&(b_weight>=0)&(a_weight+b_weight<=1)
                        x=a0[:,0]+a_weight*u[:,0]+b_weight*v[:,0]
                        assert not (inside&(abs(x)>.85)).any(),(a["file"],axle_z,delta)
                for light in ("light_drl_l","light_drl_r","light_tail_bar"):
                    for i in descendants(doc,by_name[light]):
                        for p in doc["meshes"][nodes[i]["mesh"]]["primitives"]:
                            uv=acc(doc,data,p["attributes"]["TEXCOORD_0"])
                            center=(uv.mean(axis=0)*256).astype(int).clip(0,255)
                            assert mask[center[1]-1:center[1]+2,center[0]-1:center[0]+2].any(),light
        assert max_error<.0002
        assert triangles==a["triangles"]
        assert triangles<=3000 if near else triangles<150 if far else triangles<.40*item["lods"][0]["triangles"]
        if not far:
            angle=radians(a["cargo_preview_degrees"][cargo])
            center=np.asarray(a["nodes"][cargo]["center_gltf_m"])
            panels=np.concatenate([p for i in moving_cargo for p in positions.get(i,[])])-center
            yy=panels[:,1]*cos(angle)-panels[:,2]*sin(angle)
            zz=panels[:,1]*sin(angle)+panels[:,2]*cos(angle)
            assert zz.min()<panels[:,2].min()-.24 if pickup else yy.max()>panels[:,1].max()+.40
            assert bed_clear and (cargo_glazing if liftback else not cargo_glazing)
            for stripe in item["optional_livery_nodes"]:
                assert nodes[by_name[stripe]]["extras"]["optional_livery"]
                assert parents[by_name[stripe]] in ("hood","trunk")
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
print("Validated 12 exports, wheel and cargo pivots, door sweep, open pickup bed, liftback glazing, livery, masks and LOD budgets.")
