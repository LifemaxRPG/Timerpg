"""Verify C006 geometry, era parity, UV density and integration anchors."""
from io import BytesIO
import json
from pathlib import Path

import numpy as np
from PIL import Image

from glb_reader import acc, load, matrices

ROOT = Path(__file__).resolve().parent
TEXEL_M = .0388
meta = json.loads((ROOT / "model-info.json").read_text())


def embedded_image(doc, data, texture_index):
    image = doc["images"][doc["textures"][texture_index]["source"]]
    view = doc["bufferViews"][image["bufferView"]]
    start = view.get("byteOffset", 0)
    with Image.open(BytesIO(data[start:start+view["byteLength"]])) as bitmap:
        assert bitmap.format == "PNG"
        return np.asarray(bitmap.convert("RGBA"))


def overlaps(points_min, points_max, bounds_min, bounds_max):
    return np.all(points_max > bounds_min + .001) and np.all(points_min < bounds_max - .001)


reference=meta["states"][0]["models"][0]["door_nodes_gltf_m"]
assert len(meta["routing"]["diesel_lanes"])==6
assert len(meta["routing"]["car_lanes"])==3
reports=[]
regions=json.loads((ROOT/"textures/sign-regions.json").read_text())
for state in meta["states"]:
    assert len(state["models"])==3
assert meta["truck_parking"]["bays"]==30
for state in meta["states"]:
    previous_triangles=12001
    for asset in state["models"]:
        doc,data=load(ROOT/asset["file"])
        world=matrices(doc)
        names={node["name"]:i for i,node in enumerate(doc["nodes"])}
        all_doors=asset["door_nodes_gltf_m"]
        assert all_doors==reference
        assert {"lot","collision","diesel_canopy","car_canopy","highway_sign",*all_doors}.issubset(names)
        assert len(doc["materials"]) <= 12
        assert all(s["magFilter"] == 9728 and s["minFilter"] in (9728,9984)
                   and s.get("wrapS",10497) == 10497 and s.get("wrapT",10497) == 10497
                   for s in doc["samplers"])

        glass = next(m for m in doc["materials"] if m["name"] == "glass_storefront")
        mask = embedded_image(doc, data, glass["emissiveTexture"]["index"])[:,:,:3]
        assert mask[2:-2,2:-2].any() and not mask[:2].any() and not mask[-2:].any()
        assert not mask[:,:2].any() and not mask[:,-2:].any()
        assert {m["name"] for m in doc["materials"] if "emissiveTexture" in m}=={"glass_storefront","sign_faces"}
        sign=next(m for m in doc["materials"] if m["name"]=="sign_faces")
        sign_mask=embedded_image(doc,data,sign["emissiveTexture"]["index"])[:,:,:3]
        assert sign_mask.shape==(1024,512,3)
        x,y,w,h=regions["lens"]
        lens=sign_mask[y:y+h,x:x+w]
        assert lens[2:-2,2:-2].any() and not lens[:2].any() and not lens[-2:].any()
        assert not lens[:,:2].any() and not lens[:,-2:].any()
        assert {"sign_name","sign_name_rear","sign_store","sign_diesel","sign_car","sign_scale","sign_showers"}.issubset(names)
        for key in ("sign_scale","sign_staff","sign_menu"):
            x,y,w,h=asset["sign_panels"][key]["rect_texels"]
            assert not sign_mask[y:y+h,x:x+w].any()
        assert set(asset["sign_panels"]).issubset(names)
        assert {"era_service_bay","sign_route_diesel_arrow","sign_route_trucks_arrow",
                "sign_service_bay","label_ice","label_news","label_air","label_water",
                "sign_price","sign_price_rear"}.issubset(names)
        modern=state["id"]=="1990"
        assert ("sign_atm" in names)==modern
        price=asset["sign_panels"]["sign_price"]
        x,y,w,h=price["rect_texels"]
        assert price["atlas_size_texels"]==[512,1024]
        assert [x,y,w,h]==regions["price_new" if modern else "price_old"]
        assert sign_mask[y:y+h,x:x+w].any()
        assert asset["sign_panels"]["sign_price_rear"]["rect_texels"]==[x,y,w,h]
        tree_meshes=[]
        light_nodes={source["node"] for source in asset["lights"] if source["role"]=="yard"}
        assert len(light_nodes)==6 and sum(source["role"]=="canopy" for source in asset["lights"])==12
        for number in range(1,7):
            tree=doc["nodes"][names[f"tree_{number:02d}"]]
            tree_meshes.append(sorted(doc["nodes"][i]["mesh"] for i in tree["children"]))
            node=f"flood_{number:02d}"
            assert node in light_nodes
            source=next(light for light in asset["lights"] if light["node"]==node)
            assert np.allclose(world[names[node]][:3,3]-(0,.17,0),source["position_gltf_m"])
        assert all(pair==tree_meshes[0] for pair in tree_meshes)
        for index, node_id in enumerate(doc["nodes"][names["collision"]]["children"]):
            node = doc["nodes"][node_id]
            assert node["name"] == f"collider_{index:02d}" and node["extras"]["collision_only"]
        assert len(asset["collision_boxes_gltf_m"]) == len(doc["nodes"][names["collision"]]["children"]) <= 64

        for name, position in all_doors.items():
            transform = world[names[name]]
            assert np.allclose(transform[:3,3],position,atol=1e-6)
            axis={"door_showers":(1,0,0),"door_back":(0,0,-1)}.get(name,(0,0,1))
            assert np.allclose(transform[:3,2],axis,atol=1e-6), (name,transform[:3,2])

        portals=[((-1.1,.16,-.40),(1.1,2.60,.39)),
                 ((12.11,.16,-21.1),(14.16,2.50,-20.02)),
                 ((19.51,.16,-15.49),(20.5,2.46,-13.78))]
        portals.append(((40.64,.31,-61.8),(43.77,5.8,-34.5)))
        for kind,central_half,depth in (("diesel",2.10,10.23),("car",1.77,8.23)):
            clearance=state[kind+"_clear_height_m"]-.02
            for lane in meta["routing"][kind+"_lanes"]:
                x,_,z=lane["center_gltf_m"]
                portals.append(((x-central_half,.045,z-depth),(x+central_half,clearance,z+depth)))
        portals=np.array(portals)
        dimensions = {}
        triangles = 0
        max_error = 0
        for node_id, node in enumerate(doc["nodes"]):
            if "mesh" not in node:
                continue
            transform = world[node_id]
            for primitive in doc["meshes"][node["mesh"]]["primitives"]:
                attr = primitive["attributes"]
                local = acc(doc, data, attr["POSITION"]).astype(float)
                points = local @ transform[:3,:3].T + transform[:3,3]
                indices = acc(doc,data,primitive["indices"]).reshape(-1,3)
                if node.get("extras",{}).get("collision_only"):
                    index = int(node["name"].split("_")[-1])
                    box = asset["collision_boxes_gltf_m"][index]
                    assert len(np.unique(points.round(5),axis=0)) == 8
                    assert np.allclose(np.ptp(points,axis=0),box["size_gltf_m"], atol=.0001)
                    assert np.allclose((points.min(axis=0)+points.max(axis=0))/2,box["center_gltf_m"],atol=.0001)
                    assert not any(overlaps(points.min(axis=0),points.max(axis=0),a,b) for a,b in portals)
                    continue
                triangles += len(indices)
                assert points[:,1].min() >= -.0001
                xyz = points[indices]
                for index,(a,b) in enumerate(portals):
                    intersecting=[face for face in xyz if overlaps(face.min(axis=0),face.max(axis=0),a,b)]
                    assert not intersecting,(asset["file"],node["name"],index,intersecting[0].tolist())
                ti = doc["materials"][primitive["material"]]["pbrMetallicRoughness"]["baseColorTexture"]["index"]
                if ti not in dimensions:
                    texture = embedded_image(doc,data,ti)
                    for x,y in ((1,0),(0,1),(1,1)):
                        assert np.array_equal(texture[::2,::2],texture[y::2,x::2])
                    dimensions[ti] = np.array(texture.shape[:2][::-1])
                uv = acc(doc,data,attr["TEXCOORD_0"]).astype(float)[indices] * dimensions[ti] * TEXEL_M
                edges = np.roll(xyz,1,axis=1) - xyz
                uv_edges = np.roll(uv,1,axis=1) - uv
                misfit = np.linalg.norm(edges,axis=2)-np.linalg.norm(uv_edges,axis=2)
                max_error = max(max_error, float(abs(misfit).max()))
        assert triangles == asset["triangles_visible"] < previous_triangles, (asset["file"],triangles,asset["triangles_visible"],previous_triangles)
        assert triangles <= 3964, (asset["file"],triangles)
        assert max_error < .0002, (asset["file"],max_error)
        previous_triangles = triangles
        reports.append({"file":asset["file"],"triangles":triangles,"materials":len(doc["materials"]),
                        "boxes":len(asset["collision_boxes_gltf_m"]),"max_uv_error_m":max_error})

(ROOT / "validation.json").write_text(json.dumps(reports,indent=2)+"\n")
print(f"{len(reports)} GLBs passed the era, LOD, aperture, lane and scale clearance, collisions, yard light anchors, mesh reuse, repaint panels, emission masks, texels and triangle budget")
