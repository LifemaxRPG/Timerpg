"""Verify C007 geometry, era parity, UV density and integration anchors."""
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
holes=meta["states"][0]["models"][0]["holes"]
assert [item["hole"] for item in holes]==list(range(1,19))
assert {h["obstacle"] for h in holes if h["obstacle"]!="banks or open run"}=={
    "windmill","lighthouse","volcano","pond bridge","covered bridge","castle"}
reports=[]
for state in meta["states"]:
    closed=state["id"]=="2006"
    previous_triangles=12001
    for asset in state["models"]:
        doc,data=load(ROOT/asset["file"])
        world=matrices(doc)
        names={node["name"]:i for i,node in enumerate(doc["nodes"])}
        all_doors=asset["door_nodes_gltf_m"]
        assert all_doors==reference
        assert {"lot","collision","statue_pad","statue_anchor","putting_lanes","windmill_blades",*all_doors}.issubset(names)
        assert asset["holes"]==holes
        rotor=world[names["windmill_blades"]]
        assert np.allclose(rotor[:3,3],meta["windmill"]["pivot_gltf_m"],atol=1e-6)
        assert np.allclose(rotor[:3,2],meta["windmill"]["axis_local_gltf"],atol=1e-6)
        assert doc["nodes"][names["windmill_blades"]]["children"]
        assert np.allclose(world[names["statue_anchor"]][:3,3],meta["statue_pad"]["center_gltf_m"],atol=1e-6)
        assert ("detail_chain_link" in {m["name"] for m in doc["materials"]})==closed
        assert len(doc["materials"]) <= 12
        assert all(s["magFilter"] == 9728 and s["minFilter"] in (9728,9984)
                   and s.get("wrapS",10497) == 10497 and s.get("wrapT",10497) == 10497
                   for s in doc["samplers"])

        glass = next(m for m in doc["materials"] if m["name"] == "glass_ticket_hut")
        mask = embedded_image(doc, data, glass["emissiveTexture"]["index"])[:,:,:3]
        assert bool(mask[2:-2,2:-2].any())==(not closed)
        assert not mask[:2].any() and not mask[-2:].any()
        assert not mask[:,:2].any() and not mask[:,-2:].any()
        assert {m["name"] for m in doc["materials"] if "emissiveTexture" in m}=={"glass_ticket_hut","sign_faces"}
        sign=next(m for m in doc["materials"] if m["name"]=="sign_faces")
        sign_mask=embedded_image(doc,data,sign["emissiveTexture"]["index"])[:,:,:3]
        x,y,w,h=asset["sign_panels"]["sign_name"]["rect_texels"]
        assert bool(sign_mask[y:y+h,x:x+w].any())==(not closed)
        assert ("sign_closed" in names)==closed
        assert set(asset["sign_panels"]).issubset(names)
        for index, node_id in enumerate(doc["nodes"][names["collision"]]["children"]):
            node = doc["nodes"][node_id]
            assert node["name"] == f"collider_{index:02d}" and node["extras"]["collision_only"]
        assert len(asset["collision_boxes_gltf_m"]) == len(doc["nodes"][names["collision"]]["children"]) <= 45

        for name, position in all_doors.items():
            transform = world[names[name]]
            assert np.allclose(transform[:3,3],position,atol=1e-6)
            axis={"door_back":(0,0,-1)}.get(name,(0,0,1))
            assert np.allclose(transform[:3,2],axis,atol=1e-6), (name,transform[:3,2])

        # Open entrance, the alley door and the full above-pad volume reserved for the statue.
        portals=np.array([((-.84,.152,-.46),(.84,2.46,.48)),
                          ((1.25,.152,-6.89),(2.84,2.46,-6.05)),
                          ((-18.37,.11,-2.99),(-8.63,5.5,8.69))])
        cup_vertices=[]
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
                matname=doc["materials"][primitive["material"]]["name"]
                if matname=="ground_putting_turf":
                    cup_vertices.extend(points)
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
        # All eighteen actual turf meshes carry eight ring vertices around their cup.
        vertices=np.unique(np.asarray(cup_vertices).round(6),axis=0)
        for hole in holes:
            x,_,z=hole["cup_gltf_m"]
            radii=np.linalg.norm(vertices[:,[0,2]]-np.array((x,z)),axis=1)
            assert np.count_nonzero(np.isclose(radii,.105,atol=1e-5))==8, hole
        previous_triangles = triangles
        reports.append({"file":asset["file"],"triangles":triangles,"materials":len(doc["materials"]),
                        "boxes":len(asset["collision_boxes_gltf_m"]),"max_uv_error_m":max_error})

(ROOT / "validation.json").write_text(json.dumps(reports,indent=2)+"\n")
print(f"{len(reports)} GLBs passed the 18 cup rings, statue clearance, windmill pivot, door, collision, emission and texel checks")
