"""Validate the delivered geometry, storefronts, collision and pixel sampling."""
from io import BytesIO
from pathlib import Path
import json

import numpy as np
from PIL import Image

from glb_reader import load, acc, matrices

ROOT=Path(__file__).resolve().parent

def ray_heights(tris, x, z):
    a,b,c=tris[:,0],tris[:,1],tris[:,2]
    dx1,dz1=b[:,0]-a[:,0],b[:,2]-a[:,2]
    dx2,dz2=c[:,0]-a[:,0],c[:,2]-a[:,2]
    det=dx1*dz2-dx2*dz1
    mask=np.abs(det)>1e-9
    dx,dz=x-a[:,0],z-a[:,2]
    u=np.divide(dx*dz2-dx2*dz,det,where=mask,out=np.zeros_like(det))
    v=np.divide(dx1*dz-dx*dz1,det,where=mask,out=np.zeros_like(det))
    hit=mask&(u>=-1e-6)&(v>=-1e-6)&(u+v<=1.000001)
    return (a[:,1]+u*(b[:,1]-a[:,1])+v*(c[:,1]-a[:,1]))[hit]

def validate(style):
    info=json.loads((ROOT/f"model-info-{style}.json").read_text())
    doc,raw=load(ROOT/info["file"])
    nodes=doc["nodes"]
    names={node["name"]:i for i,node in enumerate(nodes)}
    m=matrices(doc)
    assert len(doc["materials"])<=12
    assert not doc.get("cameras") and "KHR_lights_punctual" not in doc.get("extensionsUsed",[])
    sizes=[]
    images=[]
    for image in doc["images"]:
        v=doc["bufferViews"][image["bufferView"]]
        start=v.get("byteOffset",0)
        with Image.open(BytesIO(raw[start:start+v["byteLength"]])) as im:
            assert im.format=="PNG"
            px=np.asarray(im.convert("RGBA"))
            assert all(np.array_equal(px[::2,::2],px[y::2,x::2]) for x,y in ((1,0),(0,1),(1,1)))
            sizes.append(im.size)
            images.append(px)
    for sampler in doc["samplers"]:
        assert sampler["magFilter"]==9728 and sampler["minFilter"] in (9728,9984)
        assert sampler.get("wrapS",10497)==sampler.get("wrapT",10497)==10497

    meshes={}
    opaque=[]
    stock=[]
    visible=[]
    errors=[]
    for i,node in enumerate(nodes):
        if "mesh" not in node:
            continue
        triangles=[]
        coll=node.get("extras",{}).get("collision_only",False)
        for p in doc["meshes"][node["mesh"]]["primitives"]:
            attr=p["attributes"]
            pts=acc(doc,raw,attr["POSITION"])@m[i][:3,:3].T+m[i][:3,3]
            idx=acc(doc,raw,p["indices"]).reshape(-1,3)
            tri=pts[idx]
            triangles.append(tri)
            mat=doc["materials"][p["material"]]
            if coll:
                assert mat["pbrMetallicRoughness"]["baseColorFactor"][3]==0.0
                continue
            visible.append(tri)
            name=mat["name"]
            if name=="detail_stock":
                stock.append(tri)
            if name!="detail_storefront_overlay":
                opaque.append(tri)
            t=mat["pbrMetallicRoughness"]["baseColorTexture"]["index"]
            source=doc["textures"][t]["source"]
            uv=acc(doc,raw,attr["TEXCOORD_0"])*np.array(sizes[source])*.0388
            for a,b in ((0,1),(1,2),(2,0)):
                d=np.linalg.norm(pts[idx[:,a]]-pts[idx[:,b]],axis=1)
                s=np.linalg.norm(uv[idx[:,a]]-uv[idx[:,b]],axis=1)
                errors.append(np.abs(d-s).max())
        meshes[i]=np.concatenate(triangles)
    visible=np.concatenate(visible)
    opaque=np.concatenate(opaque)
    stock=np.concatenate(stock)
    assert len(visible)==info["triangles"]<=5000
    assert .0<=visible[:,:,1].min()<=.005
    normal=np.cross(visible[:,1]-visible[:,0],visible[:,2]-visible[:,0])
    assert np.isfinite(visible).all() and np.linalg.norm(normal,axis=1).min()>1e-10
    assert max(errors)<.00004,(style,max(errors))
    assert np.isclose(visible[:,:,1].max(),info["dimensions_m"]["height"],atol=.005)
    rear_sign=meshes[names["sign_repairs" if style=="driftline" else "sign_service"]]
    assert np.max(np.abs(rear_sign[:,:,0]))<info["dimensions_m"]["width"]/2

    overlay=next(mat for mat in doc["materials"] if mat["name"]=="detail_storefront_overlay")
    assert overlay["alphaMode"]=="BLEND"
    px=images[doc["textures"][overlay["pbrMetallicRoughness"]["baseColorTexture"]["index"]]["source"]]
    clear=float(np.mean(px[:,:,3]<128))
    assert clear>.98

    solid_z=opaque[:,:,[0,2,1]]
    solid_x=opaque[:,:,[2,0,1]]
    for name,pos in info["anchors"].items():
        node=names[name]
        assert np.allclose(m[node][:3,3],pos,atol=.00002)
        expected=(0,0,1) if name=="door_main" else (0,0,-1)
        assert np.allclose(m[node][:3,2],expected,atol=.00002)
        for d in (-.46,0,.46):
            for height in (.48,1.4,1.98):
                hits=ray_heights(solid_z,pos[0]+d,pos[1]+height)
                assert not np.any((hits>pos[2]-.6)&(hits<pos[2]+.6)),(style,name,d,height,hits)
    candidate=names["collision"]
    boxes=info["collision"]["boxes_blender_z_up"]
    assert len(boxes)==len(nodes[candidate]["children"])
    for i,box in enumerate(boxes):
        tri=meshes[names[f"collider_{i:02}"]]
        lo,hi=tri.min(axis=(0,1)),tri.max(axis=(0,1))
        x,y,z=box["center"]
        w,d,h=box["size"]
        assert np.allclose(.5*(lo+hi),(x,z,-y),atol=.00002)
        assert np.allclose(hi-lo,(w,h,d),atol=.00002)

    sightlines=0
    stock_z=stock[:,:,[0,2,1]]
    stock_x=stock[:,:,[2,0,1]]
    for pane in info["storefront_panes"]:
        front=pane["axis"]=="front"
        cut=solid_z if front else solid_x
        goods=stock_z if front else stock_x
        lo,hi=pane["span_m"]
        fixed=-pane["fixed_m"] if front else pane["fixed_m"]
        sample_y=1.8 if not modern_window(pane) else 1.9
        clear_stock=0
        for width in np.linspace(lo+.12,hi-.12,49):
            test_x=width if front else -width
            blocking=ray_heights(cut,test_x,sample_y)
            if np.any(np.abs(blocking-fixed)<.16):
                continue
            products=ray_heights(goods,test_x,sample_y)
            if np.any(((fixed-products)>.2)&((fixed-products)<1.58)):
                clear_stock+=1
        # Tideglass's narrow display window is deliberately an empty sight line into the shop.
        if hi-lo>2.2:
            assert clear_stock>2,(style,pane,clear_stock)
            sightlines+=clear_stock
    return {"shop":style,"visible_triangles":len(visible),"collision_boxes":len(boxes),
            "materials_including_collision":len(doc["materials"]),"max_uv_edge_error_m":round(float(max(errors)),8),
            "open_doors_checked":len(info["anchors"]),"display_sightlines_sampled":sightlines,
            "overlay_clear_fraction_for_masked_loader":round(clear,5)}

def modern_window(pane):
    return pane["heights_m"][1]>3.5

report=[validate(style) for style in ("driftline","tideglass")]
(ROOT/"validation.json").write_text(json.dumps(report,indent=2)+"\n")
print(report)
