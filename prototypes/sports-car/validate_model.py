"""Check GLB budgets, assembly, texture density and tyre clearance for C017."""
from io import BytesIO
import json
from math import cos, pi, sin
from pathlib import Path

import numpy as np
from PIL import Image

from glb_reader import acc, load, matrices

ROOT=Path(__file__).resolve().parent
INFO=json.loads((ROOT/"model-info.json").read_text())
ART=json.loads((ROOT/"textures/regions.json").read_text())
T=.0388


def image(doc,blob,texture):
    i=doc["textures"][texture["index"]]["source"]
    v=doc["bufferViews"][doc["images"][i]["bufferView"]]
    off=v.get("byteOffset",0)
    with Image.open(BytesIO(blob[off:off+v["byteLength"]])) as im:
        return np.asarray(im.convert("RGBA"))


def pixel_grid(a):
    return all(np.array_equal(a[::2,::2],a[y::2,x::2]) for x,y in ((1,0),(0,1),(1,1)))


def lateral_hit(triangles,y,z,low,high):
    """Trace through the tyre footprint, from the inner to the outer shoulder."""
    a,b,c=triangles.transpose(1,0,2)
    ab=b-a
    ac=c-a
    det=ab[:,1]*ac[:,2]-ac[:,1]*ab[:,2]
    with np.errstate(divide="ignore",invalid="ignore"):
        u=((y-a[:,1])*ac[:,2]-(z-a[:,2])*ac[:,1])/det
        v=(ab[:,1]*(z-a[:,2])-ab[:,2]*(y-a[:,1]))/det
        x=a[:,0]+ab[:,0]*u+ac[:,0]*v
        return np.any(np.isfinite(u)&np.isfinite(v)&(u>=0)&(v>=0)&(u+v<=1)&(x>low)&(x<high))


def desc(doc,idx):
    for i in doc["nodes"][idx].get("children",[]):
        yield i
        yield from desc(doc,i)


def check(item,model):
    doc,blob=load(ROOT/model["file"])
    transforms=matrices(doc)
    nd=doc["nodes"]
    names={n["name"]:i for i,n in enumerate(nd)}
    parent={kid:n["name"] for n in nd for kid in n.get("children",[])}
    far,near=model["lod"]=="far",model["lod"]=="near"
    materials=doc["materials"]
    assert len(materials)<=6
    assert set(model["materials"])=={m["name"] for m in materials}
    assert all(s["magFilter"]==9728 and s["minFilter"] in (9728,9984) and
               s.get("wrapS",10497)==s.get("wrapT",10497)==10497 for s in doc["samplers"])
    assert np.allclose(transforms[names[item["key"]]],np.eye(4))
    assert model["nodes"]==item["lods"][0]["nodes"],model["lod"]
    for name,described in model["nodes"].items():
        i=names[name]
        assert parent[i]==described["parent"]
        assert np.allclose(transforms[i][:3,3],described["center_gltf_m"],atol=1e-6)
        if "axis_local_gltf" in described:
            assert nd[i]["extras"]["axis_local_gltf"]==described["axis_local_gltf"]
            assert np.allclose(transforms[i][:3,:3],np.eye(3),atol=1e-6)
    for ax,fore in (("front","f"),("rear","r")):
        dims=item["wheel_axles"][ax]
        z=item["wheelbase_m"]*.5*(1 if fore=="f" else -1)
        for side,sign in (("l",1),("r",-1)):
            name="wheel_"+fore+side
            i=names[name]
            expected=np.array([sign*dims["track_m"]/2,dims["radius_m"],z])
            assert np.allclose(transforms[i][:3,3],expected,atol=1e-6)
            assert nd[i]["extras"]["tyre_width_m"]==dims["width_m"]
            assert nd[i]["extras"]["tyre_radius_m"]==dims["radius_m"]
            if fore=="f":
                assert parent[i]=="steer_f"+side
                assert np.allclose(transforms[names[parent[i]]][:3,3],expected,atol=1e-6)

    assert model["nodes"]["exit_left"]["center_gltf_m"][0]>0>model["nodes"]["exit_right"]["center_gltf_m"][0]
    assert nd[names["roof_panel"]]["extras"]["removable"]
    assert set(model["lamps"])==set(item["lods"][0]["lamps"])
    lamp_mesh={i:name for name in model["lamps"] for i in desc(doc,names[name])}
    emitted=[m for m in materials if "emissiveTexture" in m]
    assert len(emitted)==(0 if far else 1)
    mask=None
    if not far:
        assert emitted[0]["name"]=="glow_lamps"
        mask=image(doc,blob,emitted[0]["emissiveTexture"])[:,:,:3]
        wanted=np.zeros(mask.shape[:2],dtype=bool)
        for cx,cy in ART["lamps"].values():
            x,y=2*(cx//2),2*(cy//2)
            wanted[y-6:y+8,x-12:x+14]=True
            assert mask[y-6:y+8,x-12:x+14].any()
        assert not mask[~wanted].any()
        assert pixel_grid(mask)

    points=[]
    paint=[]
    dims={}
    by_mesh={}
    triangle_count=0
    max_error=0
    for i,n in enumerate(nd):
        if "mesh" not in n:
            continue
        tf=transforms[i]
        by_mesh[i]=[]
        for p in doc["meshes"][n["mesh"]]["primitives"]:
            indices=acc(doc,blob,p["indices"]).reshape(-1,3)
            xyz=acc(doc,blob,p["attributes"]["POSITION"])@tf[:3,:3].T+tf[:3,3]
            m=materials[p["material"]]
            tex=m["pbrMetallicRoughness"]["baseColorTexture"]
            key=tex["index"]
            if key not in dims:
                bitmap=image(doc,blob,tex)
                assert pixel_grid(bitmap)
                dims[key]=np.array(bitmap.shape[:2][::-1])
            texel=acc(doc,blob,p["attributes"]["TEXCOORD_0"])[indices]*dims[key]
            tris=xyz[indices]
            if m["name"]=="paint_primary":
                paint.append(tris)
            if m["name"]=="glow_lamps":
                assert i in lamp_mesh
                center=texel.mean(axis=1).astype(int)
                assert np.all(mask[center[:,1],center[:,0]].max(axis=1)>0),lamp_mesh[i]
            else:
                assert i not in lamp_mesh
            physical_edges=np.linalg.norm(np.roll(tris,1,axis=1)-tris,axis=-1)
            uv_edges=np.linalg.norm(np.roll(texel,1,axis=1)-texel,axis=-1)*T
            max_error=max(max_error,float(abs(physical_edges-uv_edges).max()))
            by_mesh[i].append(xyz)
            points.append(xyz)
            triangle_count+=len(indices)
    assert triangle_count==model["triangles"] and max_error<.001
    points=np.concatenate(points)
    assert np.allclose(np.column_stack((points.min(axis=0),points.max(axis=0))),model["bounds_gltf_m"],atol=1e-5)
    assert abs(points[:,1].min())<.001
    assert 1.10<=points[:,1].max()<=1.18

    # Removing the roof only detaches its own geometry. The windshield and targa
    # hoop originate in the static body. The deck and both doors are independent.
    for name in ("roof_panel","hood","trunk","door_l","door_r"):
        assert names[name] not in set(desc(doc,names["roof_panel"])) and parent[names[name]]==item["key"]
        if near:
            assert set(desc(doc,names[name]))&by_mesh.keys(),name
    if near:
        panels=np.concatenate(paint)
        for ax,fore in (("front","f"),("rear","r")):
            r=item["wheel_axles"][ax]["radius_m"]
            w=item["wheel_axles"][ax]["width_m"]
            for side,sign in (("l",1),("r",-1)):
                name=f"wheel_{fore}{side}"
                x,y,z=transforms[names[name]][:3,3]
                vertices=np.concatenate([np.concatenate(by_mesh[i]) for i in desc(doc,names[name])])
                assert abs(vertices[:,0].max()-vertices[:,0].min()-w) < .009
                assert abs(vertices[:,1].min())<.001 and abs(vertices[:,1].max()-2*r)<.001
                for i in range(21):
                    phi=pi*i/20
                    py=y+r*.966*sin(phi)
                    pz=z+r*.966*cos(phi)
                    a,b=sorted((x-sign*.46*w,x+sign*(w/2+.118)))
                    assert not lateral_hit(panels,py,pz,a,b),(item["key"],name,py,pz)
    return {"triangles":triangle_count,"materials":len(materials),"uv_error_m":round(max_error,7),
            "wheel_aperture_probes":84 if near else 0}


assert INFO["texel_m"]==T and len(INFO["vehicles"])==2
results={}
for item in INFO["vehicles"]:
    near,mid,far=item["lods"]
    assert near["triangles"]<=3000 and mid["triangles"]<.4*near["triangles"] and far["triangles"]<150
    assert abs(sum(item["weight_distribution"].values())-1)<1e-8
    assert .50<item["weight_distribution"]["rear"]<.65
    assert 950<=item["estimated_mass_kg"]<=1150
    assert item["estimated_0_100_kmh_s"]>0 and item["suggested_final_drive"]>0
    assert set(item["paint"]["named_presets"])=={"red","yellow","racing_green","white","orange","slate_metallic"}
    for name,path in item["paint"]["masks"].items():
        with Image.open(ROOT/path) as im:
            a=np.asarray(im)
            assert im.size==(64,64) and pixel_grid(a) and np.all(a==255),name
    results[item["key"]]={m["lod"]:check(item,m) for m in (near,mid,far)}
(ROOT/"validation.json").write_text(json.dumps(results,indent=2)+"\n")
print(json.dumps(results,indent=2))
