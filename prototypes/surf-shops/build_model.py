"""Build two coastal shops with open entrances and real display-window sight lines."""
from io import BytesIO
from math import pi
from pathlib import Path
import json
import os
import sys

import bpy
from PIL import Image

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from glb_reader import load
from sculpture_tools import group,material,mesh,loft,oval,box,beam,plaque
from revision_common import batch,export_glb,save_source

PROP_ROOT=Path(os.environ.get("BEACH_PACK_PROPS",ROOT.parents[2]/"timerpg-beach-prop-pack"/
                             "prototypes"/"beach-prop-pack"/"props"))
BOARD_NAMES=("surf-longboard","surf-fish","surf-shortboard","bodyboard")
atlas=Image.new("RGB",(512,512))
board_regions={}
for i,name in enumerate(BOARD_NAMES):
    gltf,binary=load(PROP_ROOT/f"{name}.glb")
    view=gltf["bufferViews"][gltf["images"][0]["bufferView"]]
    start=view.get("byteOffset",0)
    with Image.open(BytesIO(binary[start:start+view["byteLength"]])) as image:
        x,y=(i%2)*256,(i//2)*256
        atlas.paste(image.convert("RGB"),(x,y))
        board_regions[name]=(x,y,*image.size)
atlas.save(ROOT/"textures"/"display-boards.png")

def slab(name,parent,x0,x1,y0,y1,z0,z1,mat):
    return box(name,parent,((x0+x1)/2,(y0+y1)/2,(z0+z1)/2),(x1-x0,y1-y0,z1-z0),mat)

def plane_roof(name,parent,x0,x1,y0,y1,low,high,thick,mat):
    return loft(name,parent,[[(x0,y0,low-d),(x1,y0,high-d),(x1,y1,high-d),(x0,y1,low-d)]
                             for d in (thick,0)],mat)

def pole(name,parent,x,y,z0,z1,r,mat):
    return oval(name,parent,[(x,y,z0,r,r),(x,y,z1,r,r)],mat,n=8)

def glass_material():
    mat=material("detail_storefront_overlay","clear-display")
    tree=mat.node_tree
    shader=tree.nodes.get("Principled BSDF")
    texture=next(n for n in tree.nodes if n.type=="TEX_IMAGE")
    tree.links.new(texture.outputs["Alpha"],shader.inputs["Alpha"])
    shader.inputs["Roughness"].default_value=.37
    mat.surface_render_method="BLENDED"
    return mat

def boards(parent,material,placements):
    for name,x,y,z,lean,angle in placements:
        old=set(bpy.data.objects)
        bpy.ops.import_scene.gltf(filepath=str(PROP_ROOT/f"{name}.glb"))
        imported=[ob for ob in bpy.data.objects if ob not in old]
        assert len(imported)==1 and imported[0].type=="MESH"
        ob=imported[0]
        ob.name="Stock "+name
        ob.parent=parent
        ob.location=(x,y,z)
        ob.rotation_mode="XYZ"
        ob.rotation_euler=(pi/2+lean[0],lean[1],angle)
        xx,yy,width,height=board_regions[name]
        for loop in ob.data.uv_layers.active.data:
            u,v=loop.uv
            loop.uv=((xx+u*width)/512,(512-yy-height+v*height)/512)
        ob.data.materials.clear()
        ob.data.materials.append(material)

for style in ("driftline","tideglass"):
    modern=style=="tideglass"
    title="Tideglass Board Co." if modern else "Driftline Surf & Repair"
    bpy.ops.wm.read_factory_settings(use_empty=True)
    root=group(title)
    shell=group("retail_shell",root)
    rooftop=group("shop_roof",root)
    windows=group("display_window_panes",shell)
    interior=group("built_in_fixtures",shell)
    lot=group("lot",root)
    stock=group("display_stock",lot)
    collision=group("collision",root)
    wall_mat=material("wall_shop","concrete" if modern else "teal-siding")
    band=material("wall_detail","cedar" if modern else "coral")
    trim=material("detail_ivory","ivory")
    frame=material("detail_frames","navy")
    wood=material("floor_timber","floorwood")
    top=material("roof_shop","roof-blue" if modern else "roof-brown")
    paving=material("ground_sidewalk","sidewalk")
    signmat=material("sign_name","sign",.09)
    lamp=material("glow_shop","warm",.52)
    pane=glass_material()
    boardmat=material("detail_stock","display-boards")
    invisible=bpy.data.materials.new("collision_invisible")
    invisible.use_nodes=True
    invisible.node_tree.nodes.get("Principled BSDF").inputs["Alpha"].default_value=0
    invisible.surface_render_method="BLENDED"
    collision_boxes=[]
    anchors={}
    pane_specs=[]

    def box_collision(key,x0,x1,y0,y1,z0,z1,attachment=None):
        index=len(collision_boxes)
        ob=slab(f"collider_{index:02}",collision,x0,x1,y0,y1,z0,z1,trim)
        ob.data.materials.clear()
        ob.data.materials.append(invisible)
        ob["collision_only"]=True
        data={"name":key,"center":[(x0+x1)/2,(y0+y1)/2,(z0+z1)/2],
              "size":[x1-x0,y1-y0,z1-z0]}
        if attachment:
            data["group"]="lot"
            data["attachment"]=attachment
        collision_boxes.append(data)

    def marker(name,x,y,z,heading=0):
        entry=group(name,root)
        entry.location=(x,y,z)
        entry.rotation_euler.z=heading
        anchors[name]=[x,z,-y]

    def sign(name,parent,x,y,z,row,width,height,back=False):
        w,h=width*.0776,height*.0776
        a,b=(x+w/2,x-w/2) if back else (x-w/2,x+w/2)
        ob=plaque(name,parent,[(a,y,z),(b,y,z),(b,y,z+h),(a,y,z+h)],
                  signmat,(0,row*2,width*2,height*2),(256,256))
        ob["keep"]=True

    def bay(name,axis,fixed,start,end,bottom,upper,split=()):
        if axis=="front":
            mesh(name,windows,[(start,fixed,bottom),(end,fixed,bottom),(end,fixed,upper),
                               (start,fixed,upper)],[(0,1,2,3)],pane)
            for x in (start,*split,end):
                slab("Storefront vertical mullion",shell,x-.031,x+.031,fixed-.083,fixed+.083,bottom-.06,upper+.07,frame)
            for z in (bottom-.035,upper):
                slab("Storefront sash rail",shell,start-.035,end+.035,fixed-.09,fixed+.09,z,z+.041,frame)
            box_collision(name,start,end,fixed-.06,fixed+.06,bottom,upper)
        else:
            mesh(name,windows,[(fixed,start,bottom),(fixed,end,bottom),(fixed,end,upper),
                                (fixed,start,upper)],[(0,1,2,3)],pane)
            for y in (start,*split,end):
                slab("Corner return vertical mullion",shell,fixed-.083,fixed+.083,y-.031,y+.031,bottom-.06,upper+.07,frame)
            for z in (bottom-.035,upper):
                slab("Corner return sash rail",shell,fixed-.09,fixed+.09,start-.035,end+.035,z,z+.041,frame)
            box_collision(name,fixed-.06,fixed+.06,start,end,bottom,upper)
        pane_specs.append({"name":name,"axis":axis,"fixed_m":fixed,"span_m":[start,end],"heights_m":[bottom,upper]})

    def riser(x0,x1,y0,y1,h=.36,part=stock):
        slab("Window display plinth",part,x0,x1,y0,y1,.16,h,band)
        slab("Plinth face cap",part,x0-.02,x1+.02,y0-.02,y1+.02,h,h+.042,wood)

    def display_rack(x0,x1,y,z0,z1):
        for x in (x0,x1):
            slab("Board rack upright",stock,x-.029,x+.029,y-.033,y+.033,z0,z1,frame)
        for z in (z0+.13,z1-.15):
            slab("Board rack backing bar",stock,x0-.08,x1+.08,y-.065,y+.065,z,z+.039,wood)

    def steps(x0,x1,y0,y1):
        slab("Sidewalk threshold",shell,x0,x1,y0,y1,.069,.162,trim)

    if not modern:
        width,depth=12.4,9.66
        x0,x1=-6.2,6.2
        slab("Timber sales floor",shell,x0,x1,.045,depth,.071,.162,wood)
        slab("Front boardwalk landing",root,-7.2,7.2,-2.65,.06,.002,.07,paving)
        slab("Rear service stoop",root,1.50,4.13,depth-.16,10.47,.023,.10,paving)
        steps(-1.15,1.15,-.31,.93)
        for x in (x0,x1):
            slab("Long weatherboard side",shell,x-.126,x+.126,.042,depth,.16,3.91,wall_mat)
            slab("Painted skirting",shell,x-.136,x+.136,.055,depth,.16,.43,band)
            slab("Side window shutter relief",shell,x-.153,x+.153,4.1,6.18,1.19,2.46,frame)
            slab("Side storm panel",shell,x-.161,x+.161,4.21,6.07,1.23,2.4,wall_mat)
            box_collision("Side masonry wall",x-.128,x+.128,.057,depth,.16,3.91)
        for a,b in [(-6.18,2.05),(3.43,6.18)]:
            slab("Rear weatherboard wall",shell,a,b,9.40,9.62,.16,3.91,wall_mat)
            box_collision("Rear wall",a,b,9.4,9.62,.16,3.91)
        slab("Rear service lintel",shell,2.05,3.43,9.40,9.62,2.37,3.91,wall_mat)
        marker("door_back",2.74,9.76,.162,pi)
        for a,b in [(-6.2,-5.64),(-1.34,-1.04),(1.04,1.34),(5.64,6.2)]:
            slab("Painted façade pier",shell,a,b,-.060,.25,.16,3.75,wall_mat)
            box_collision("Front facade pier",a,b,-.06,.25,.16,3.75)
        for a,b in [(-5.64,-1.34),(1.34,5.64)]:
            slab("Coral bulkhead below shop window",shell,a,b,-.055,.22,.16,.61,band)
            slab("Capped shop sill",shell,a-.01,b+.01,-.112,.25,.61,.688,trim)
            bay("Main display glass","front",.075,a,b,.69,3.13,((a+b)/2,))
        for a,b in [(-1.18,-.996),(.996,1.18)]:
            slab("Recessed entry return wall",shell,a,b,.10,.76,.163,3.39,band)
            box_collision("Door return",a,b,.10,.76,.163,3.39)
        slab("Recessed entrance head",shell,-1.06,1.06,.57,.77,2.47,3.72,trim)
        for x in (-.995,.995):
            slab("Entrance jamb",shell,x-.041,x+.041,.57,.80,.16,2.48,frame)
        marker("door_main",0,.37,.162)
        slab("Front frieze",shell,-6.22,6.22,-.08,.235,3.12,4.23,wall_mat)
        slab("Frieze cap",rooftop,-6.34,6.34,-.155,.30,4.18,4.33,trim)
        slab("Parapet central rise",rooftop,-4.7,4.7,-.106,.25,4.28,5.25,wall_mat)
        slab("Parapet top coping",rooftop,-4.79,4.79,-.18,.35,5.21,5.36,trim)
        slab("Sign frieze strip",shell,-4.67,4.67,-.187,-.075,3.68,5.27,frame)
        sign("sign_name_driftline",shell,0,-.195,3.69,0,116,20)
        sign("sign_surf_repair",shell,0,-.194,3.02,21,70,11)
        # Fabric drops toward the street. The transom sign is raised above its leading edge.
        for i in range(16):
            a=-6.13+i*12.26/16
            b=a+12.26/16
            c=band if i%2==0 else trim
            front,back=-1.65,-.05
            loft("Striped canvas awning",rooftop,[[(a,front,2.83-t),(b,front,2.83-t),(b,back,3.63-t),(a,back,3.63-t)]
                  for t in (.055,0)],c)
            slab("Canvas valance",rooftop,a,b,front-.034,front+.02,2.66,2.835,c)
        for x in (-5.85,-2.01,2.01,5.85):
            beam("Awning diagonal brace",rooftop,(x,.01,2.52),(x,-1.49,2.85),(.029,.035),(.029,.035),frame,4)
        slab("Low roll roof",rooftop,-6.33,6.33,.20,9.77,3.87,4.06,top)
        for x in (-6.22,6.22):
            slab("Low roof edge trim",rooftop,x-.11,x+.11,.15,9.75,4.03,4.18,frame)
        slab("Rear roof coping",rooftop,-6.23,6.23,9.56,9.79,4.03,4.17,frame)
        for x in (-3.50,3.55):
            riser(x-1.96,x+1.96,.40,1.57,.285)
            display_rack(x-1.34,x+1.34,1.25,.33,3.13)
        boards(stock,boardmat,[("surf-longboard",-4.49,.86,1.82,(-.065,.09),.04),
                ("surf-fish",-3.64,1.01,1.64,(-.062,-.12),.14),
                ("surf-shortboard",-2.63,.88,1.59,(-.035,.11),-.05),
                ("surf-longboard",2.28,.99,1.81,(-.085,-.09),.12),
                ("surf-shortboard",3.22,.87,1.60,(-.04,.13),.03),
                ("bodyboard",4.47,.88,1.26,(-.03,-.02),-.18)])
        slab("Back repair counter",interior,-5.80,-2.92,6.87,8.94,.164,1.04,wall_mat)
        slab("Wood repair bench top",interior,-5.84,-2.84,6.8,8.99,1.04,1.128,wood)
        slab("Fixed check-out counter",interior,3.83,5.79,4.76,6.22,.163,1.03,wall_mat)
        slab("Check-out top edge",interior,3.76,5.87,4.69,6.28,1.03,1.12,wood)
        box_collision("Fixed front counter",3.76,5.87,4.69,6.28,.163,1.12)
        box_collision("Repair bench", -5.84,-2.84,6.8,8.99,.163,1.128)
        height=5.36
        era="1960 onward"
    else:
        width,depth=13,10.35
        x0,x1=-6.5,6.5
        slab("Modern sales floor",shell,x0,x1,.042,depth,.065,.16,paving)
        slab("Concrete pedestrian landing",root,-7.6,7.6,-2.64,.12,.0,.068,paving)
        slab("Corner walk return",root,6.35,7.6,0,6.84,.0,.068,paving)
        steps(-3.72,-1.52,-.27,.89)
        # Transparent display window wraps around the right-hand corner.
        for a,b in [(-6.5,-5.95),(-4.12,-3.76),(-1.47,-1.17),(6.08,6.5)]:
            slab("Concrete front support",shell,a,b,-.083,.225,.16,4.94,wall_mat)
            box_collision("Concrete facade pier",a,b,-.083,.225,.16,4.94)
        for a,b in [(-5.95,-4.12),(-1.17,6.08)]:
            slab("Low window parapet",shell,a,b,-.081,.16,.16,.44,wall_mat)
            slab("Precast window sill cap",shell,a,b,-.128,.25,.43,.49,trim)
            split=tuple(x for x in (-.20,1.91,3.95) if a<x<b)
            bay("Modern window frontage","front",.035,a,b,.49,3.82,split)
        for a,b in [(-3.84,-3.66),(-1.57,-1.39)]:
            slab("Recessed cedar door jamb",shell,a,b,.12,.75,.159,3.34,band)
            box_collision("Return of entrance",a,b,.12,.75,.16,3.34)
        for x in (-3.66,-1.57):
            slab("Recessed entrance metal jamb",shell,x-.043,x+.043,.608,.787,.16,2.53,frame)
        slab("Open portal lintel",shell,-3.69,-1.54,.61,.792,2.47,3.89,trim)
        marker("door_main",-2.62,.36,.162)
        slab("Long west wall",shell,-6.58,-6.37,.057,10.33,.16,4.04,wall_mat)
        box_collision("West structure",-6.58,-6.37,.057,10.33,.16,4.04)
        slab("Eastern wrapping window base",shell,6.378,6.61,.17,5.22,.16,.43,wall_mat)
        bay("East wraparound display","side",6.54,.17,5.22,.44,3.8,(1.88,3.55))
        slab("East rear enclosed service wall",shell,6.39,6.63,5.22,10.33,.16,4.84,band)
        box_collision("East service wall",6.39,6.63,5.22,10.33,.16,4.84)
        for a,b in [(-6.55,3.93),(5.46,6.52)]:
            slab("Rear service wall",shell,a,b,10.12,10.35,.16,4.8,wall_mat)
            box_collision("Rear service wall",a,b,10.12,10.35,.16,4.8)
        slab("Rear service header",shell,3.93,5.46,10.12,10.35,2.38,4.80,band)
        slab("Rear drainage stoop",root,3.77,5.62,10.13,10.87,.021,.16,paving)
        marker("door_back",4.70,10.52,.16,pi)
        slab("Clerestory backing",shell,-6.54,6.53,-.097,.14,3.81,4.5,band)
        slab("Projected roof strip lower edge",rooftop,-6.66,6.75,-1.12,.19,4.06,4.25,trim)
        slab("Projected roof strip",rooftop,-6.66,6.75,-1.15,.27,4.23,4.61,top)
        slab("Modern brand plaque footing",shell,-4.49,4.36,-1.149,-.97,3.76,5.24,frame)
        sign("sign_name_tideglass",shell,-.06,-1.173,3.79,33,110,19)
        sign("sign_board_co",shell,-.32,-1.174,3.27,53,62,11)
        slab("Board Co hanging plaque",rooftop,-2.78,2.14,-1.16,-.98,3.25,4.13,frame)
        plane_roof("Swept east roof",rooftop,-1.44,6.81,.09,10.55,4.41,6.05,.24,top)
        plane_roof("Low west roof",rooftop,-6.73,-1.44,.08,10.56,4.27,4.41,.20,top)
        for y in (.14,10.34):
            mesh("Closure above clerestory",shell,[(-1.46,y,4.14),(6.59,y,4.14),(6.59,y,5.80),
                 (-1.46,y,4.30)],[(0,1,2,3) if y<1 else (3,2,1,0)],band)
        slab("East man-high curtain wall header",shell,6.39,6.62,.13,5.23,3.78,5.83,band)
        slab("East upper rear band",shell,6.39,6.62,5.23,10.33,4.78,5.83,band)
        slab("Front canopy end cap",rooftop,6.62,6.80,-1.16,10.54,5.82,6.06,trim)
        for y in (5.43,5.80,6.18,6.56,6.94,7.32,7.70,8.08,8.46,8.84,9.22,9.60):
            slab("Cedar sunscreen fin",rooftop,6.62,6.76,y-.048,y+.047,.6,3.69,trim)
        for x in (-4.72,1.0,4.86):
            slab("Canopy light blade",rooftop,x-.55,x+.55,-.82,-.70,4.025,4.075,lamp)
        riser(-.98,5.93,.37,1.55,.30)
        riser(5.02,6.31,1.59,4.87,.28)
        display_rack(-.62,5.71,1.41,.35,3.52)
        boards(stock,boardmat,[("surf-longboard",.18,.83,1.87,(-.05,-.13),.05),
               ("surf-fish",1.46,.87,1.60,(-.06,.08),.06),
               ("surf-shortboard",2.63,.90,1.60,(-.04,-.10),-.04),
               ("surf-longboard",3.87,.86,1.88,(-.07,.11),.07),
               ("bodyboard",5.27,.85,1.16,(-.03,.02),.14),
               ("surf-shortboard",5.77,2.47,1.67,(-.055,-.07),pi/2),
               ("surf-longboard",5.75,3.81,1.91,(-.052,.09),pi/2)])
        slab("Island service counter",interior,-5.77,-3.53,4.12,6.3,.162,1.02,band)
        slab("Island counter laminate",interior,-5.86,-3.45,4.05,6.38,1.02,1.102,trim)
        box_collision("Fixed island counter",-5.86,-3.45,4.05,6.38,.16,1.102)
        height=6.06
        era="2008 onward"

    # Permanent fixtures are inside the clean shell. Merchandise and its racks can be hidden together.
    for x in (-3.4,0,3.4):
        for y in (2.05,5.0,7.6):
            z=3.55 if not modern else (3.85 if x<0 else 4.20)
            slab("Suspended strip shade",interior,x-.51,x+.51,y-.053,y+.053,z,z+.061,lamp)
    for a,b in ((-5.73,-4.15),(.55,2.14)):
        slab("Undersill light rail",interior,a,b,.94,1.075,3.017,3.072,lamp)
    for x,y in ((-6.61,-.59),(5.80,-.70)):
        slab("Porch utility crate",lot,x-.19,x+.19,y-.233,y+.233,.069,.45,wood)
        slab("Crate metal edging",lot,x-.196,x+.196,y-.242,y-.203,.40,.46,frame)
        box_collision("Utility crate",x-.197,x+.197,y-.243,y+.24,.069,.46,"lot")
    for x in ((-6.35,6.51) if modern else (-6.10,5.98)):
        slab("Rear drainpipe",shell,x-.066,x+.066,depth-.036,depth+.135,.16,3.81,frame)
    if modern:
        sign("sign_service",shell,4.45,10.368,2.57,77,51,11,back=True)
    else:
        sign("sign_repairs",shell,-3.96,9.637,2.45,100,53,12,back=True)

    for parent in (shell,rooftop,interior,windows,stock,lot):
        batch(parent)
    bpy.context.view_layer.update()
    displayed=[ob for ob in bpy.context.scene.objects if ob.type=="MESH" and ob.parent!=collision]
    for ob in displayed:
        ob.data.calc_loop_triangles()
    tris=sum(len(ob.data.loop_triangles) for ob in displayed)
    info={"name":title,"id":style,"era":era,"file":f"{style}.glb",
          "dimensions_m":{"width":width,"depth":depth,"height":height},
          "axes":"glTF metres, Y up and +Z toward the sidewalk. Front center at grade.",
          "triangles":tris,"materials":sorted({m.name for ob in displayed for m in ob.data.materials}),
          "anchors":anchors,"storefront_panes":pane_specs,
          "collision":{"boxes_blender_z_up":collision_boxes},
          "optional_nodes":["lot","display_stock"],
          "swatches":["pavement","plank_horizontal"]+(["roof_tin_brown"] if not modern else []),
          "display_board_source":"Beach Day Prop Pack, PR #35. Display copies preserve original geometry and texel density.",
          "glazing":"The storefront uses an alpha overlay under a BODY-role name so the current loader's painted-room glass rule does not replace the see-through view."}
    (ROOT/f"model-info-{style}.json").write_text(json.dumps(info,indent=2)+"\n")
    export_glb(ROOT/f"{style}.glb")
    save_source(ROOT/f"{style}.blend")
    print(style,tris,len(info["materials"]),"visible materials and",len(collision_boxes),"boxes")
