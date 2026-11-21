"""Rasterize actual GLB profiles in black at 128, 64 and 32 pixels wide."""
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from glb_reader import acc,load,matrices

ROOT=Path(__file__).resolve().parent


def profile(path):
    gltf,binary=load(path)
    world=matrices(gltf)
    triangles=[]
    for i,node in enumerate(gltf["nodes"]):
        if "mesh" not in node:
            continue
        for primitive in gltf["meshes"][node["mesh"]]["primitives"]:
            verts=acc(gltf,binary,primitive["attributes"]["POSITION"])
            points=verts@world[i][:3,:3].T+world[i][:3,3]
            ids=acc(gltf,binary,primitive["indices"]).reshape(-1,3)
            triangles.append(points[ids][:,:,[2,1]])
    return np.concatenate(triangles)


def silhouette(points, width):
    lo=points.min(axis=(0,1))
    hi=points.max(axis=(0,1))
    scale=(width-2)/(hi[0]-lo[0])
    height=round((hi[1]-lo[1])*scale)+3
    image=Image.new("RGB",(width,height),"#e9e3d7")
    draw=ImageDraw.Draw(image)
    for triangle in points:
        draw.polygon([(1+(x-lo[0])*scale,1+(hi[1]-y)*scale) for x,y in triangle],fill="#161a1c")
    return image


board=Image.new("RGB",(1080,680),"#263438")
draw=ImageDraw.Draw(board)
title=ImageFont.truetype("DejaVuSans.ttf",22)
label=ImageFont.truetype("DejaVuSans.ttf",16)
for row,(version,display) in enumerate((("copperwake","MV Copperwake, 1996+"),("ss-copperwake","SS Copperwake, 1952"))):
    draw.text((29,16+row*335),display,font=title,fill="#eddfbf")
    for col,(suffix,stage) in enumerate((("","Near"),("-mid","Mid"),("-far","Far"))):
        x=col*350+28
        y=row*335+64
        draw.text((x,y-8),stage,font=label,fill="#eddfbf")
        points=profile(ROOT/f"{version}{suffix}.glb")
        for i,width in enumerate((128,64,32)):
            target=silhouette(points,width)
            draw.text((x,y+45+66*i),f"{width} px",fill="#eddfbf",font=label)
            board.paste(target,(x+87,y+41+66*i))
board.save(ROOT/"lod-review.png")
