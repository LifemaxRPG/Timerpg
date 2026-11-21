"""Validate the delivered GLB and the ship fit, stairs and the boarding path."""
from pathlib import Path
from io import BytesIO
import json
import argparse

import numpy as np
from PIL import Image

from glb_reader import load, acc, matrices

ROOT = Path(__file__).resolve().parent
info = json.loads((ROOT / "model-info.json").read_text())
doc, blob = load(ROOT / "port-meridian.glb")
world = matrices(doc)
indices = {node["name"]: i for i, node in enumerate(doc["nodes"])}
assert len(info["materials"]) == len(doc["materials"]) <= 12
assert not doc.get("cameras") and "KHR_lights_punctual" not in doc.get("extensionsUsed", [])
assert "berth_01_mv_copperwake" in indices and "deployable_gangway_leaf" in indices

sizes = []
for image in doc["images"]:
    view = doc["bufferViews"][image["bufferView"]]
    start = view.get("byteOffset", 0)
    with Image.open(BytesIO(blob[start:start+view["byteLength"]])) as image:
        assert image.format == "PNG"
        pixels = np.asarray(image.convert("RGBA"))
        assert all(np.array_equal(pixels[::2, ::2], pixels[y::2, x::2])
                   for x,y in ((1,0),(0,1),(1,1)))
        sizes.append(image.size)
for sampler in doc["samplers"]:
    assert sampler["magFilter"] == 9728 and sampler["minFilter"] in (9728,9984)
    assert sampler.get("wrapS",10497) == sampler.get("wrapT",10497) == 10497

def bounds(node_name):
    index = indices[node_name]
    stack = [index]
    points = []
    while stack:
        child = doc["nodes"][stack.pop()]
        stack.extend(child.get("children", []))
        if child["name"] in geometry:
            points.append(geometry[child["name"]])
    return np.concatenate(points)

geometry = {}
uv_error = 0
triangle_count = 0
for idx,node in enumerate(doc["nodes"]):
    if "mesh" not in node:
        continue
    mesh = doc["meshes"][node["mesh"]]
    triangles = []
    for primitive in mesh["primitives"]:
        attrs = primitive["attributes"]
        p = acc(doc,blob,attrs["POSITION"])
        p = p @ world[idx][:3,:3].T + world[idx][:3,3]
        uv = acc(doc,blob,attrs["TEXCOORD_0"])
        t = acc(doc,blob,primitive["indices"]).reshape(-1,3)
        texture = doc["materials"][primitive["material"]]["pbrMetallicRoughness"]["baseColorTexture"]["index"]
        uv = uv * sizes[doc["textures"][texture]["source"]] * .0388
        for a,b in ((0,1),(1,2),(2,0)):
            error = np.abs(np.linalg.norm(p[t[:,a]]-p[t[:,b]],axis=1)-
                           np.linalg.norm(uv[t[:,a]]-uv[t[:,b]],axis=1)).max()
            uv_error = max(uv_error, float(error))
        triangles.append(p[t])
        triangle_count += len(t)
    geometry[node["name"]] = np.concatenate(triangles)
assert triangle_count == info["triangles"] and triangle_count <= 12000
assert uv_error < .00008, uv_error

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

def support(tris,x,z,h):
    heights=surface_y(tris,x,z)
    return np.any(np.isclose(heights,h,atol=.0001))

all_tri=np.concatenate(list(geometry.values()))
assert np.isfinite(all_tri).all()
area=np.linalg.norm(np.cross(all_tri[:,1]-all_tri[:,0],all_tri[:,2]-all_tri[:,0]),axis=1)/2
assert area.min()>1e-8, area.min()
for name,anchor in info["anchors"].items():
    assert np.allclose(world[indices[name]][:3,3],anchor["xyz_gltf_m"],atol=.00002),name
for name,normal in (("door_main",(-1,0,0)),("door_arrivals",(-1,0,0)),
                    ("door_baggage",(1,0,0)),("door_gate_01",(1,0,0)),("door_service",(1,0,0))):
    assert np.allclose(world[indices[name]][:3,2],normal,atol=1e-6)
    x,y,z=info["anchors"][name]["xyz_gltf_m"]
    if name=="door_gate_01":
        continue
    for bx in (x-.7,x,x+.7):
        hits=surface_y(all_tri,bx,z)
        assert not ((hits>y+.1)&(hits<y+2.2)).any(),name

stairs=bounds("terminal_stairs")
treads=0
for run in info["stair_runs"]:
    for i in range(run["treads"]):
        z=-(run["start_blender_y"]+run["direction"]*run["run_m"]*(i+.5)/run["treads"])
        h=run["from_y_m"]+(run["to_y_m"]-run["from_y_m"])*(i+1)/run["treads"]
        assert support(stairs,run["x_center"],z,h),(run["name"],i)
        treads+=1
for x,z in [(-67,50),(-60,50),(-67,-33),(-60,-33)]:
    assert not support(bounds("departure_hall"),x,z,8.20),(x,z)

fixed=bounds("fixed_passenger_link")
leaf=bounds("deployable_gangway_leaf")
path=info["boarding_path_gltf_m"]
slopes=[]
for pieces,(x0,h0,z0),(x1,h1,z1) in zip((fixed,leaf,leaf),path,path[1:]):
    slopes.append((h1-h0)/np.hypot(x1-x0,z1-z0))
    assert abs(slopes[-1])<1/12
    for t in (.02,.24,.54,.77,.98):
        x=x0+(x1-x0)*t
        h=h0+(h1-h0)*t
        z=z0+(z1-z0)*t
        for offset in (-.5,0,.5):
            hs=surface_y(all_tri,x,z+offset)
            assert np.any(np.isclose(hs,h,atol=.0001)),(x,z,h)
            assert not ((hs>h+.08)&(hs<h+2.1)).any(),(x,z,h)
    assert support(pieces,x1,z1,h1)

fit=info["berth"]
origin=np.array(fit["vessel_origin_gltf_m"])
expected=origin+fit["ship_port_anchor_local_gltf_m"]
assert np.allclose(expected,info["onboard_transfer_gltf_m"][-1])
assert np.allclose(path[-1], info["onboard_transfer_gltf_m"][0])
assert np.isclose(origin[1]+fit["vessel_waterline_above_keel_m"],info["waterline_gltf_y"])
assert info["berth"]["required_design_depth_m"]>fit["vessel_waterline_above_keel_m"]
assert np.allclose(path[-1],info["anchors"]["gangway_ship_end"]["xyz_gltf_m"])
assert np.allclose(expected,info["anchors"]["ship_port_transfer"]["xyz_gltf_m"])

# Inspect the original exported Copperwake when supplied, including the real port opening.
parser=argparse.ArgumentParser()
parser.add_argument("--copperwake",type=Path,help="Path to the MV Copperwake near GLB")
args=parser.parse_args()
reference=None
if args.copperwake:
    ship,ship_blob=load(args.copperwake)
    transforms=matrices(ship)
    pieces=[]
    for idx,node in enumerate(ship["nodes"]):
        if node["name"]=="gangway_port":
            assert np.allclose(transforms[idx][:3,3]+origin,expected,atol=1e-5)
        if "mesh" not in node or node["name"].startswith("collider_"):
            continue
        for primitive in ship["meshes"][node["mesh"]]["primitives"]:
            xyz=acc(ship,ship_blob,primitive["attributes"]["POSITION"])
            xyz=xyz @ transforms[idx][:3,:3].T+transforms[idx][:3,3]+origin
            pieces.append(xyz[acc(ship,ship_blob,primitive["indices"]).reshape(-1,3)])
    ship_tri=np.concatenate(pieces)
    low=ship_tri.min(axis=(0,1))
    high=ship_tri.max(axis=(0,1))
    quay=bounds("berth_01_quay")
    tip=quay[:,:,0].max()
    assert low[0]>tip
    assert low[2]>fit["quay_limits_gltf_z_m"][0] and high[2]<fit["quay_limits_gltf_z_m"][1]
    for z in np.linspace(expected[2],path[-1][2],6):
        assert support(ship_tri,expected[0],z,expected[1])
        hits=surface_y(ship_tri,expected[0],z)
        assert not ((hits>expected[1]+.08)&(hits<expected[1]+2.1)).any(),z
    # Probe the outboard rail gap from outside to just inside the deck edge at head and torso height.
    # Reorder components to cast along X so vertical rail panels remain detectable.
    ship_cross=ship_tri[:,:,[1,0,2]]
    for passenger_y in (10.02,10.6,11.28):
        for z in np.linspace(path[-1][2]-.50,path[-1][2]+.50,5):
            obstruction=surface_y(ship_cross,passenger_y,z)
            assert not ((obstruction>=2.36)&(obstruction<=4.805)).any(),(passenger_y,z)
    reference={
        "ship_mesh_length_m":round(float(high[2]-low[2]),3),
        "minimum_quay_to_ship_envelope_gap_m":round(float(low[0]-tip),3),
        "quay_margin_aft_m":round(float(low[2]-fit["quay_limits_gltf_z_m"][0]),3),
        "quay_margin_forward_m":round(float(fit["quay_limits_gltf_z_m"][1]-high[2]),3),
        "ship_port_anchor_matches":True,"ship_outboard_passage_samples_clear":15,
    }

result={
    "triangles":triangle_count,"materials":len(doc["materials"]),"pixel_aligned_pngs":len(sizes),
    "max_uv_edge_error_m":round(uv_error,8),"minimum_triangle_area_m2":round(float(area.min()),9),
    "verified_stair_treads":treads,"anchors_checked":len(info["anchors"]),
    "gangway_segment_grades_pct":[round(s*100,3) for s in slopes],
}
if reference:
    result["copperwake_reference"]=reference
(ROOT/"validation.json").write_text(json.dumps(result,indent=2)+"\n")
print(result)
