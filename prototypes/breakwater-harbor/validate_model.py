"""Validate the delivered GLB and the geometry around berths, routes and storage."""
from pathlib import Path
from io import BytesIO
import json

import numpy as np
from PIL import Image

from glb_reader import load, acc, matrices

ROOT = Path(__file__).resolve().parent
info = json.loads((ROOT / "model-info.json").read_text())
doc, blob = load(ROOT / "breakwater-harbor.glb")
world = matrices(doc)
indices = {node["name"]: i for i, node in enumerate(doc["nodes"])}
assert len(info["materials"]) == len(doc["materials"]) <= 12
assert not doc.get("cameras") and "KHR_lights_punctual" not in doc.get("extensionsUsed", [])
assert "berth_yacht" in indices and "optional_rack_boats" in indices

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

for name,anchor in info["berths_and_anchors"].items():
    assert np.max(np.abs(world[indices[name]][:3,3] - anchor["gltf_m"])) < .00002
for name in ("door_main","door_dry_stack"):
    assert np.allclose(world[indices[name]][:3,2], (0,0,1),atol=1e-6)
    x,y,z=info["berths_and_anchors"][name]["gltf_m"]
    for collision in info["collision_boxes"]:
        center=np.array(collision["center_gltf_m"])
        half=np.array(collision["size_gltf_m"])/2
        assert np.any(np.abs(np.array((x,y+.8,z-.7))-center) > half)

docks = bounds("floating_slips")
for x in (-32,8,18):
    assert np.isclose(surface_y(docks,x,19.4).max(),-.48,atol=1e-5)
assert np.isclose(surface_y(docks,-8,7.5).max(),-.466,atol=.01)
assert np.isclose(surface_y(docks,-8,.1).max(),.014,atol=.01)
yard=bounds("launch_and_service")
ramp_heights=[surface_y(yard,23,s).max() for s in (-7.6,-5,-.5,3,6.5)]
assert all(a>b for a,b in zip(ramp_heights,ramp_heights[1:]))
assert ramp_heights[0] > info["waterline_y_m"] > ramp_heights[-1]

sails = [value for key,value in info["berths_and_anchors"].items() if key.startswith("berth_sail")]
assert len(sails) == 6
for sail in sails:
    x,y,z=sail["gltf_m"]
    for dx in (-2.2,0,2.2):
        for dz in (-6,0,6):
            assert not len(surface_y(docks,x+dx,z+dz)), sail
quay=bounds("visitor_quay")
breakwaters=bounds("armored_breakwaters")
yacht=info["berths_and_anchors"]["berth_yacht"]
x,_,z=yacht["gltf_m"]
# Check that the annotated 44 m rectangle clears the ramp, fingers, quay and riprap.
for bx in np.linspace(x-yacht["beam_m"]/2,x+yacht["beam_m"]/2,5):
    for bz in np.linspace(z-yacht["length_m"]/2,z+yacht["length_m"]/2,19):
        assert all(not len(surface_y(p,bx,bz)) for p in (docks,quay,breakwaters,yard)), (bx,bz)
for bx in np.linspace(x-4.15,x+4.15,5):
    for bz in np.linspace(54,86,14):
        assert not len(surface_y(breakwaters,bx,bz)), (bx,bz)
assert info["rack_capacity"]["slots"] == 18
rack=bounds("dry_stack_racks")
for x in (-35.89,-15.69):
    levels=surface_y(rack,x,-23)
    assert all(np.any(np.isclose(levels,z+.38,atol=1e-5)) for z in (.49,3.61,6.73))
    assert not len(surface_y(rack,-25,-23))

result={
    "triangles":triangle_count,"materials":len(doc["materials"]),"pixel_aligned_pngs":len(sizes),
    "max_uv_edge_error_m":round(uv_error,8),"dock_top_y_m":-.48,"sailboat_berths_checked":len(sails),
    "clear_yacht_envelope_m":[yacht["length_m"],yacht["beam_m"]],
    "ramp_slope_and_water_intersection":True,"rack_tiers_verified_per_side":3,
    "anchor_nodes_checked":len(info["berths_and_anchors"]),
}
(ROOT/"validation.json").write_text(json.dumps(result,indent=2)+"\n")
print(result)
