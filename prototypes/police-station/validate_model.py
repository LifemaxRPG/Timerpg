"""Test model roles, visible geometry, grid density and walkable cell thresholds."""
import io
import json
from pathlib import Path

import numpy as np
from PIL import Image

from glb_reader import acc, load, matrices

ROOT = Path(__file__).resolve().parent
INFO = json.loads((ROOT / "model-info.json").read_text())
T = INFO["meters_per_texel"]


def points(world, coords):
    return coords @ world[:3,:3].T + world[:3,3]


def rgb(image):
    return np.asarray(image.convert("RGB"))


def verify_art(im, name):
    p = np.asarray(im)
    assert p.shape[0] % 2 == p.shape[1] % 2 == 0, name
    for dy, dx in ((0,1),(1,0),(1,1)):
        assert np.array_equal(p[::2,::2], p[dy::2,dx::2]), name


for path in (ROOT / "textures").glob("*.png"):
    with Image.open(path) as image:
        verify_art(image, path.name)


def inside(box, pt):
    return np.all(np.abs(np.array(pt) - box["center_gltf_m"]) < np.array(box["size_gltf_m"])/2 - .001)


def projected_hit(triangles, x, y, z0, z1):
    """Intersect a segment parallel to glTF Z with visible triangles."""
    a, b, c = triangles.transpose(1,0,2)
    ab = b-a
    ac = c-a
    mat = ab[:,0]*ac[:,1] - ac[:,0]*ab[:,1]
    with np.errstate(divide="ignore", invalid="ignore"):
        u = ((x-a[:,0])*ac[:,1] - (y-a[:,1])*ac[:,0])/mat
        v = (ab[:,0]*(y-a[:,1]) - ab[:,1]*(x-a[:,0]))/mat
        z = a[:,2]+ab[:,2]*u+ac[:,2]*v
        return (np.isfinite(u) & np.isfinite(v) & (u >= 0) & (v >= 0) & (u+v <= 1) & (z > z0) & (z < z1))


def examine(state, lod, model):
    doc, raw = load(ROOT / model["file"])
    transforms = matrices(doc)
    nodes = {n["name"]:i for i,n in enumerate(doc["nodes"])}
    assert np.allclose(transforms[nodes["Briarford Police Station"]],np.eye(4))
    assert {"lot","collision","door_main","door_back","sign_name"} <= nodes.keys()
    assert np.allclose(transforms[nodes["lot"]][:3,3], [0,0,0])
    mats = doc["materials"]
    assert all(s.get("magFilter") == 9728 and s.get("minFilter") in (9728,9984) and
               s.get("wrapS",10497) == s.get("wrapT",10497) == 10497 for s in doc["samplers"])
    assert len(model["materials"]) <= 12
    assert {"glass_station","glass_restricted","glow_entrance"} <= {m["name"] for m in mats}
    images = {}
    for i,img in enumerate(doc["images"]):
        view=doc["bufferViews"][img["bufferView"]]
        start=view.get("byteOffset",0)
        with Image.open(io.BytesIO(raw[start:start+view["byteLength"]])) as im:
            verify_art(im, img["name"])
            images[i]=im.copy()
    for mat in mats:
        if mat["name"].startswith("glass_"):
            pbr = mat["pbrMetallicRoughness"]
            mask = images[doc["textures"][mat["emissiveTexture"]["index"]]["source"]]
            base = images[doc["textures"][pbr["baseColorTexture"]["index"]]["source"]]
            for texel in (0, 1, 8, 15):
                assert not rgb(mask)[2*texel].any() and not rgb(mask)[:,2*texel].any()
            assert rgb(mask)[8,8].any() and rgb(base)[8,8].any()
    triangles = []
    visible, hidden = 0, 0
    for n in doc["nodes"]:
        if "mesh" not in n:
            continue
        world = transforms[nodes[n["name"]]]
        for prim in doc["meshes"][n["mesh"]]["primitives"]:
            values = prim["attributes"]
            verts = points(world, acc(doc,raw,values["POSITION"]))
            assert verts[:,1].min() >= -1e-6, n["name"]
            indices = acc(doc,raw,prim["indices"]).reshape(-1,3)
            mat = mats[prim["material"]]
            if mat["name"] == "collision_invisible":
                hidden += len(indices)
                assert n.get("extras",{}).get("collision_only")
                box = state["collision_boxes"][int(n["name"].rsplit("_",1)[1])]
                low, high = verts.min(axis=0), verts.max(axis=0)
                assert np.allclose((low+high)/2,box["center_gltf_m"],atol=1e-4),n["name"]
                assert np.allclose(high-low,box["size_gltf_m"],atol=1e-4),n["name"]
                continue
            visible += len(indices)
            p = verts[indices]
            triangles.append(p)
            uv = acc(doc,raw,values["TEXCOORD_0"])
            tex = mat["pbrMetallicRoughness"]["baseColorTexture"]["index"]
            im = images[doc["textures"][tex]["source"]]
            d3 = np.linalg.norm(p[:, [1,2,0]] - p,axis=2)
            q = uv[indices]
            d2 = np.linalg.norm((q[:, [1,2,0]] - q)*np.array(im.size),axis=2)
            assert np.allclose(d2*T,d3,atol=.0011,rtol=.002), (n["name"],mat["name"],float(np.max(abs(d2*T-d3))))
    assert visible == model["triangles"] and visible < 12000, (model["file"], visible, model["triangles"])
    row = {"visible_triangles":visible, "collision_triangles":hidden, "visible_materials":len(model["materials"])}
    if lod != "near":
        assert hidden == 0
        return row
    assert hidden == len(state["collision_boxes"])*12
    for name, data in state["doors"].items():
        actual = transforms[nodes[name]]
        assert np.allclose(actual[:3,3],data["position"],atol=1e-4), name
        assert np.allclose(actual[:3,2],data["front_gltf"],atol=1e-4), name
        assert not any(inside(box,(data["position"][0],1,data["position"][2]))
                       for box in state["collision_boxes"]),name
    for name, p in state["anchors"].items():
        assert np.allclose(transforms[nodes[name]][:3,3],p,atol=1e-4), name
    p = np.concatenate(triangles)
    assert not projected_hit(p,.13,1.37,-.34,2.77).any(),"public door blocked"
    if "door_garage" in state["doors"]:
        assert not projected_hit(p,10.48,1.4,-19.41,-18.75).any(),"service garage blocked"
    for c in state["cells"]:
        i = c["id"]
        gate = doc["nodes"][nodes[c["door_node"]]]
        assert gate.get("children"),c
        cbox = c["closed_box_gltf_m"]
        hinge = [cbox["center"][0]-cbox["size"][0]/2,
                 c["room_min_gltf_m"][1],cbox["center"][2]]
        assert np.allclose(transforms[nodes[c["door_node"]]][:3,3],hinge,atol=1e-4),c
        floor = doc["nodes"][nodes[c["floor_node"]]]
        assert "mesh" in floor
        assert doc["nodes"][nodes[c["window_bars_node"]]].get("children")
        for dx in (-.29,0,.29):
            cx = c["closed_box_gltf_m"]["center"][0]+dx
            for z in (-13.25,-13.39,-13.57):
                assert not any(inside(box,(cx,1.1,z)) for box in state["collision_boxes"]),("cell threshold",i)
        assert c["bunk_anchor"] in state["anchors"]
    row["fixed_collision_boxes"] = len(state["collision_boxes"])
    row["separately_pivoted_cells"] = len(state["cells"])
    return row


verified = {}
for era, state in INFO["states"].items():
    assert len(state["cells"]) == 4
    assert state["models"]["far"]["triangles"] < state["models"]["mid"]["triangles"] < state["models"]["near"]["triangles"]
    verified[era] = {lod:examine(state,lod,model) for lod,model in state["models"].items()}
(ROOT / "validation.json").write_text(json.dumps(verified, indent=2)+"\n")
print(json.dumps(verified, indent=2))
