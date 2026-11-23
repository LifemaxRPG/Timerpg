"""Verify C004 geometry, era parity, UV density and integration anchors."""
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


rural_doors = meta["states"][0]["models"][0]["door_nodes_gltf_m"]
campus_doors = meta["states"][2]["models"][0]["door_nodes_gltf_m"]
reports = []
for state in meta["states"]:
    rural=state["id"].startswith("mill")
    closed=state["id"].endswith("abandoned")
    modern=state["id"].endswith("1990")
    reference=rural_doors if rural else campus_doors
    previous_triangles = 12001
    for asset in state["models"]:
        doc, data = load(ROOT / asset["file"])
        world = matrices(doc)
        names = {node["name"]: i for i, node in enumerate(doc["nodes"])}
        assert {"lot", "collision", *reference}.issubset(names)
        all_doors=asset["door_nodes_gltf_m"]
        assert {name:all_doors[name] for name in reference} == reference
        assert set(all_doors)-set(reference) == ({"door_portable_a","door_portable_b"} if modern else set())
        assert len(doc["materials"]) <= 12
        assert all(s["magFilter"] == 9728 and s["minFilter"] in (9728,9984)
                   and s.get("wrapS",10497) == 10497 and s.get("wrapT",10497) == 10497
                   for s in doc["samplers"])

        glass = next(m for m in doc["materials"] if m["name"] == "glass_school")
        mask = embedded_image(doc, data, glass["emissiveTexture"]["index"])[:,:,:3]
        assert bool(mask[2:-2,2:-2].any()) == (not closed)
        assert not mask[:2].any() and not mask[-2:].any()
        assert not mask[:,:2].any() and not mask[:,-2:].any()
        assert all(m["name"]=="glass_school" for m in doc["materials"] if "emissiveTexture" in m)
        assert "sign_name" in names
        assert ("sign_gym" in names)==(not rural)
        assert ("sign_portable_a" in names)==modern and ("sign_portable_b" in names)==modern
        assert set(asset["sign_panels"]).issubset(names)
        for index, node_id in enumerate(doc["nodes"][names["collision"]]["children"]):
            node = doc["nodes"][node_id]
            assert node["name"] == f"collider_{index:02d}" and node["extras"]["collision_only"]
        assert len(asset["collision_boxes_gltf_m"]) == len(doc["nodes"][names["collision"]]["children"]) <= 48

        for name, position in all_doors.items():
            transform = world[names[name]]
            assert np.allclose(transform[:3,3],position,atol=1e-6)
            axis={"door_service":(1,0,0),"door_back":(0,0,-1)}.get(name,(0,0,1))
            assert np.allclose(transform[:3,2],axis,atol=1e-6), (name,transform[:3,2])

        portals=[]
        for name,(x,y,z) in all_doors.items():
            if name=="door_service":
                portals.append(((x-.65,y+.03,z-.82),(x+.1,2.35,z+.82)))
            elif name=="door_back":
                portals.append(((x-.60,y+.03,z-.14),(x+.60,2.35,z+2.16)))
            else:
                dx=.56 if rural or name.startswith("door_portable") else .91
                depth=.81 if name.startswith("door_portable") else .65
                portals.append(((x-dx,y+.03,z-depth),(x+dx,2.35,z+.12)))
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
                assert not any(overlaps(face.min(axis=0),face.max(axis=0),a,b)
                               for face in xyz for a,b in portals)
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
        assert max_error < .0002, (asset["file"],max_error)
        previous_triangles = triangles
        reports.append({"file":asset["file"],"triangles":triangles,"materials":len(doc["materials"]),
                        "boxes":len(asset["collision_boxes_gltf_m"]),"max_uv_error_m":max_error})

(ROOT / "validation.json").write_text(json.dumps(reports,indent=2)+"\n")
print(f"{len(reports)} GLBs passed the era, LOD, aperture, collision, pane mask and texel checks")
