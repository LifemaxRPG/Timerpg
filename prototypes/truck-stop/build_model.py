"""Build Cedar Relay and its modern travel-center successor with three LODs each."""
import json
from math import pi
from pathlib import Path
import sys

import bpy

ROOT=Path(__file__).resolve().parent
sys.path.insert(0,str(ROOT))
from sculpture_tools import beam, box, group, material, mesh, plaque, oval
from revision_common import batch, emitter, export_glb, save_source

T=.0388
REGIONS=json.loads((ROOT/"textures/sign-regions.json").read_text())
STATES={"1960":("Cedar Relay",1960,1989),"1990":("Relay Travel Center",1990,None)}
GRADE=.14
DIESEL_ISLANDS=[-60.0+5.45*i for i in range(7)]
AUTO_ISLANDS=[26+4.8*i for i in range(4)]


def slab(parent,label,bounds,mat):
    a,b,c,d,e,f=bounds
    return box(label,parent,((a+b)/2,(c+d)/2,(e+f)/2),(b-a,d-c,f-e),mat)


def plane(parent,label,bounds,z,mat):
    a,b,c,d=bounds
    return mesh(label,parent,[(a,c,z),(b,c,z),(b,d,z),(a,d,z)],[(0,1,2,3)],mat)


def build(year,lod):
    bpy.ops.wm.read_factory_settings(use_empty=True)
    modern=year=="1990"
    near,far=lod=="near",lod=="far"
    name,_,_=STATES[year]
    root=group(name)
    shell,inside,lot,diesel,cars,tower,services,col=[group(n,root) for n in
        ("building_shell","fixed_interior","lot","diesel_canopy","car_canopy","highway_sign","era_service_bay","collision")]
    brick=material("wall_brick","brick")
    trim=material("trim_dark_steel","trim")
    colour=material("trim_brand","teal" if modern else "rust")
    cream=material("detail_cream","white" if modern else "white-vintage")
    safety=cream
    floor=material("floor_tile","floor" if modern else "wood")
    paving=cream
    grass=material("ground_verge","grass")
    asphalt=material("ground_asphalt","asphalt")
    roof=material("roof_membrane","roof")
    glass=material("glass_storefront","glass")
    g=glass.node_tree
    g.links.new(next(node for node in g.nodes if node.type=="TEX_IMAGE").outputs["Alpha"],
                g.nodes["Principled BSDF"].inputs["Alpha"])
    emitter(glass,"pane-mask",.15)
    chain=None
    if modern:
        chain=material("detail_chain_link","chain")
        nodes=chain.node_tree.nodes
        tex=next(node for node in nodes if node.type=="TEX_IMAGE")
        chain.node_tree.links.new(tex.outputs["Alpha"],nodes["Principled BSDF"].inputs["Alpha"])
    signs=material("sign_faces","signs")
    emitter(signs,"sign-light-mask",.18)
    hidden=bpy.data.materials.new("collision_invisible")
    hidden.use_nodes=True
    hidden.surface_render_method="BLENDED"
    hidden.node_tree.nodes["Principled BSDF"].inputs["Alpha"].default_value=0
    boxes,doors,panels=[],{},{}
    lamp_specs=[]
    instances={}

    def repeated(parent,key,label,location,size,mat):
        source=instances.get(key)
        if source is None:
            source=box(label,parent,(0,0,0),size,mat)
            source["keep"]=True
            instances[key]=source
            item=source
        else:
            item=bpy.data.objects.new(label,source.data)
            bpy.context.collection.objects.link(item)
            item.parent=parent
            item["keep"]=True
        item.location=location
        return item

    def lens(parent,x,y,z):
        a,b,wide,depth=REGIONS["lens"]
        w,h=wide*T/2,depth*T/2
        return plaque("Lamp lens only",parent,[(x-w,y+h,z),(x+w,y+h,z),
                      (x+w,y-h,z),(x-w,y-h,z)],signs,(a,b,wide,depth),(512,1024))


    def collide(label,bounds,attachment=""):
        a,b,c,d,e,f=bounds
        ob=slab(col,f"collider_{len(boxes):02d}",bounds,asphalt)
        ob.data.materials.clear()
        ob.data.materials.append(hidden)
        ob["collision_only"]=True
        boxes.append({"name":label,"center_gltf_m":[(a+b)/2,(e+f)/2,-(c+d)/2],
                      "size_gltf_m":[b-a,f-e,d-c],"group":"lot" if attachment=="lot" else "building",
                      "attachment":attachment})

    def barrier(parent,label,bounds,mat):
        slab(parent,label,bounds,mat)
        collide(label,bounds,"lot" if parent==lot else "")

    def marker(label,x,y,yaw=0):
        node=group(label,root)
        node.location=(x,y,GRADE)
        node.rotation_euler.z=yaw
        doors[label]=[x,GRADE,-y]

    def plate(parent,label,key,x,y,z,reverse=False):
        rect=REGIONS[key]
        w,h=rect[2]*T,rect[3]*T
        a,b=(x+w/2,x-w/2) if reverse else (x-w/2,x+w/2)
        item=plaque(label,parent,[(a,y,z),(b,y,z),(b,y,z+h),(a,y,z+h)],signs,rect,(512,1024))
        item["keep"]=True
        panels[label]={"texture":"signs.png","emission_texture":"sign-light-mask.png","atlas_size_texels":[512,1024],
                       "rect_texels":rect,"bottom_left_gltf_m":[a,z,-y],"width_m":w,"height_m":h}

    def facade(label,edge,v,top,wallmat,windows=(),entries=(),axis="x",thick=.28,collision_range=None):
        def bounds(a,b,z0,z1,extra=0):
            return (a,b,v-extra,v+thick+extra,z0,z1) if axis=="x" else (v-extra,v+thick+extra,a,b,z0,z1)
        def casing(name,x,half,z0,z1,offset):
            for index,depth in enumerate((v+offset,v+thick-offset)):
                coords=[(x-half,depth,z0),(x+half,depth,z0),
                        (x+half,depth,z1),(x-half,depth,z1)]
                if axis=="y":
                    coords=[(y,x,z) for x,y,z in coords]
                face=(0,1,2,3) if index==0 else (3,2,1,0)
                mesh(label+" "+name,shell,coords,[face],trim)

        def pane(a,b,z0,z1):
            coords=[(a,v+thick*.48,z0),(b,v+thick*.48,z0),(b,v+thick*.48,z1),(a,v+thick*.48,z1)]
            if axis=="y":
                coords=[(y,x,z) for x,y,z in coords]
            mesh(label+" glazing",shell,coords,[(0,1,2,3)],glass)
            if not far:
                for x in (a,b):
                    casing("vertical mullion",x,.047,z0,z1,-.009)
                if near and label in ("Dining room ribbon","Market storefront"):
                    casing("mid mullion",(a+b)/2,.035,z0,z1,.055)

        # Adjacent sections without a doorway can use the same wall collider.
        if collision_range is not False:
            collision_edge=collision_range or edge
            prev=collision_edge[0]
            for a,b,_ in sorted(entries)+[(collision_edge[1],collision_edge[1],top)]:
                if a>prev+.001:
                    collide(label,bounds(prev,a,GRADE,top))
                prev=b
        openings=[(a,b,z0,z1,True) for a,b,z0,z1 in windows]
        openings.extend((a,b,GRADE,z,False) for a,b,z in entries)
        vertices,faces=[],[]
        def quad(coords):
            start=len(vertices)
            vertices.extend((u,w,z) if axis=="x" else (w,u,z) for u,w,z in coords)
            face=tuple(range(start,start+4))
            faces.append(face if axis=="x" else face[::-1])
        q=v+thick
        def skin(a,b,z0,z1):
            if min(b-a,z1-z0)>.001:
                quad(((a,v,z0),(b,v,z0),(b,v,z1),(a,v,z1)))
                quad(((b,q,z0),(a,q,z0),(a,q,z1),(b,q,z1)))
        def reveal(u,low,high,left):
            rect=((u,v,low),(u,q,low),(u,q,high),(u,v,high))
            quad(rect if left else rect[::-1])
        def across(a,b,z,top_face):
            rect=((a,v,z),(b,v,z),(b,q,z),(a,q,z))
            quad(rect if top_face else rect[::-1])
        prev=edge[0]
        for a,b,z0,z1,is_window in sorted(openings):
            skin(prev,a,GRADE,top)
            skin(a,b,GRADE,z0)
            skin(a,b,z1,top)
            reveal(a,z0,z1,True)
            reveal(b,z0,z1,False)
            across(a,b,z1,False)
            if is_window:
                across(a,b,z0,True)
                pane(a,b,z0,z1)
            elif not far:
                for x in (a,b):
                    casing("door casing",x,.061,GRADE,z1,-.055)
            prev=b
        skin(prev,edge[1],GRADE,top)
        across(*edge,top,True)
        reveal(edge[0],GRADE,top,False)
        reveal(edge[1],GRADE,top,True)
        mesh(label+" continuous wall",shell,vertices,faces,wallmat)

    # Ground at grade. The main walk and two pads sit above the single asphalt base.
    slab(lot,"Continuous asphalt parcel",(-64,63,-28,116,0,.028),asphalt)
    slab(lot,"Front walk",(-22,21,-2.63,0,.028,GRADE),paving)
    slab(lot,"Back delivery apron",(-2,22,20.68,24.8,.028,GRADE),paving)
    slab(lot,"Shower walk", (20.0,22.17,12.4,17.58,.028,GRADE),paving)
    for a,b in ((-20,-13),(-10,-3),(3,10),(13,20)):
        plane(lot,"Parking approach edge",(a,b,-8.65,-8.494),.037,cream)
    for x in (-19,-15.7,-12.4,-9.1,8.7,12.0,15.3,18.6):
        plane(lot,"Diner customer stall",(x-.046,x+.046,-8.65,-3.1),.036,cream)
    # Two rows of truck parking. Each bay is 4 m wide and 20 m deep.
    for q in (55.0,75.1):
        for i in range(16):
            x=-30+4.0*i
            plane(lot,"Truck parking stall line",(x-.052,x+.052,q,q+18.7),.037,cream)
    plane(lot,"Truck parking shared head stripe",(-30,30,74.58,74.76),.038,cream)
    # Pass-through platform scale in a distinct inspection lane.
    scale_x=42.2
    slab(lot,"Truck weighbridge frame",(40.3,44.1,37.65,58.7,.029,.22),trim)
    slab(lot,"Truck weighbridge deck",(40.44,43.96,37.71,58.64,.22,.28),paving)
    for low,high,bottom,top in ((34.7,37.65,.030,.245),(58.7,61.65,.245,.030)):
        verts=[(40.3,low,bottom),(44.1,low,bottom),(44.1,high,top),(40.3,high,top)]
        mesh("Truck scale approach ramp",lot,verts,[(0,1,2,3)],paving)
    for x in (39.92,44.49):
        for y in (37.8,58.6):
            barrier(lot,"Scale curb guard",(x-.12,x+.12,y-.12,y+.12,.028,1.19),safety)
    barrier(lot,"Scale reader pedestal",(44.63,45.43,52.0,52.62,.028,1.85),trim)
    slab(lot,"Scale reader sign backing",(44.29,48.87,51.98,52.10,1.76,2.77),trim)
    plate(lot,"sign_scale","scale",46.58,51.965,1.80)

    # Bricked road lodge. The doors and walkable rooms are shared by both eras.
    slab(shell,"Sealed common floor",(-20,20,0,20.7,.029,GRADE),floor)
    facade("Dining room ribbon",(-20,-3.8),0,4.36,brick,
           [(-18.6,-14.35,.90,3.05),(-13.75,-9.45,.90,3.05),(-8.82,-4.7,.90,3.05)],collision_range=False)
    facade("Main entrance storefront",(-3.8,3.8),0,4.36,trim if modern else brick,
           [(-3.28,-1.40,.61,3.1),(1.39,3.33,.61,3.1)],[(-1.20,1.20,2.81)],collision_range=(-20,20))
    facade("Market storefront",(3.8,20),0,4.36,brick,
           [(4.54,8.83,.89,3.16),(9.48,13.62,.89,3.16),(14.24,18.8,.89,3.16)],collision_range=False)
    facade("West dining wall",(0,20.7),-20,4.36,brick,[(2.06,6.35,.91,3.05),(7.23,11.44,.91,3.05)],axis="y")
    facade("East shower wall",(0,20.7),19.72,4.36,brick,
           [(2.34,6.37,.91,3.05)],[(13.60,15.68,2.66)],axis="y")
    facade("Back receiving elevation",(-20,20),20.42,4.36,brick,
           [(-17.23,-12.36,1.21,3.18)],[(12.0,14.28,2.68)])
    marker("door_main",0,-.34)
    marker("door_back",13.14,21.04,pi)
    marker("door_showers",20.40,14.64,pi/2)
    plate(shell,"sign_staff","service",13.14,20.732,2.75,True)
    rect=REGIONS["showers"]
    w,h=rect[2]*T,rect[3]*T
    slab(shell,"East shower sign panel",(19.98,20.10,14.54-w/2-.08,14.54+w/2+.08,2.67,3.72),colour)
    obj=plaque("sign_showers",shell,[(20.112,14.54-w/2,2.69),(20.112,14.54+w/2,2.69),
          (20.112,14.54+w/2,2.69+h),(20.112,14.54-w/2,2.69+h)],signs,rect,(512,1024))
    obj["keep"]=True
    # Long roadside fascia, separate canopy geometry and a stepped roof skyline.
    if modern:
        slab(shell,"Modern access canopy",(-11.13,11.13,-2.60,.30,3.33,3.61),roof)
        slab(shell,"Modern canopy edge",(-11.15,11.15,-2.70,-2.52,3.25,3.74),colour)
        for x in (-9.65,9.65):
            barrier(shell,"Main canopy column",(x-.146,x+.146,-2.37,-2.05,GRADE,3.46),cream)
        slab(shell,"Raised entrance branding",(-10.05,10.05,-.29,.40,4.07,6.21),colour)
        plate(shell,"sign_store", "store_new",0,-.310,4.18)
    else:
        slab(shell,"Roadside diner cornice",(-20.22,20.22,-.62,.35,3.49,3.80),colour)
        slab(shell,"Cream stripe",(-20.22,20.22,-.64,-.56,3.55,3.69),cream)
        slab(shell,"Diner name fascia",(-10.08,10.08,-.24,.33,3.79,5.76),colour)
        plate(shell,"sign_store","store_old",0,-.258,3.84)
    slab(shell,"Road lodge flat roof",(-20.17,20.17,-.08,20.85,4.31,4.52),roof)
    for a,b,c,d in ((-20.22,-20.,-.08,20.90),(20,20.22,-.08,20.90),
                    (-20.12,20.12,-.08,.13),(-20.12,20.12,20.66,20.88)):
        slab(shell,"Dark coping",(a,b,c,d,4.50,4.73),trim)
    for x,y in ((-14,16.1),(1.7,15),(14.3,17.2)):
        d=.65 if modern else .44
        slab(shell,"Roof air handler",(x-d,x+d,y-.51,y+.51,4.52,5.16),trim)
        if not far:
            slab(shell,"Air handler lid",(x-d-.071,x+d+.071,y-.56,y+.56,5.12,5.21),cream)
    if not far:
        for x in (-18.9,18.8):
            slab(shell,"Alley drain pipe",(x-.095,x+.095,20.63,20.84,.14,4.43),trim)

    # Walkable diner and market. Fixtures are architectural geometry with ground-height boxes.
    slab(inside,"Continuous finished ceiling",(-19.7,19.7,.27,20.41,3.58,3.76),cream)
    for a,b in ((-19.72,-7.4),(-4.96,.13),(3.18,10.68),(13.2,19.72)):
        barrier(inside,"Back-of-house wall",(a,b,12.84,13.09,GRADE,3.59),cream)
    for x in (-6.8,-.18,9.90):
        barrier(inside,"Rear room partition",(x-.13,x+.13,13.09,20.42,GRADE,3.59),cream)
    # Trading counter with a staff gap at the west end.
    barrier(inside,"Fixed cash and service counter",(3.70,11.6,8.77,9.85,GRADE,1.01),colour)
    slab(inside,"Counter rounded cap",(3.63,11.69,8.68,9.94,1.01,1.10),cream)
    for y in (2.36,5.12,7.88,10.65):
        for xa,xb,back in ((-19.30,-18.50,-19.30),(-16.80,-16.00,-16.17)):
            bounds=(xa,xb,y-.72,y+.72,GRADE,1.19)
            slab(inside,"Booth pedestal",(xa+.093,xb-.093,y-.59,y+.59,GRADE,.54),trim)
            slab(inside,"Upholstered bench",(xa,xb,y-.72,y+.72,.53,.68),colour)
            slab(inside,"Upholstered back",(back,back+.17,y-.72,y+.72,.64,1.19),colour)
        slab(inside,"Booth table",(-18.26,-17.10,y-.65,y+.65,.88,1.02),cream)
        slab(inside,"Diner table pedestal",(-17.83,-17.50,y-.19,y+.19,GRADE,.9),trim)
        collide("Fixed booth and table",(-19.30,-16.00,y-.72,y+.72,GRADE,1.19))
    if near:
        for x,y in ((-16,5.3),(-11,5.8),(-6,5.8),(0,5.8),(6,5.8),(12,5.8),(17,5.8),(-10,10.8),(6,10.8)):
            mesh("Ceiling lens",inside,[(x-.73,y+.19,3.55),(x+.73,y+.19,3.55),(x+.73,y-.19,3.55),(x-.73,y-.19,3.55)],[(0,1,2,3)],cream)
    plate(inside,"sign_menu","menu",6.4,12.828,1.71)
    # Two permanent shower stalls and thresholds behind the store.
    for x in (14.8,16.86,19.34):
        barrier(inside,"Tiled shower stall divider",(x-.09,x+.09,17.30,20.42,GRADE,2.52),paving)
    for x in (15.83,18.10):
        slab(inside,"Shower tray",(x-.87,x+.87,17.41,20.30,GRADE,GRADE+.035),paving)
        if not far:
            beam("Fixed shower plumbing",inside,(x,20.13,.65),(x,20.13,2.19),(.041,.041),(.041,.041),trim,4)
            beam("Shower head stem",inside,(x,20.14,2.18),(x,19.90,2.20),(.052,.052),(.044,.044),trim,4)
    slab(inside,"Shower bench", (18.88,19.62,16.02,17.01,GRADE,.49),colour)
    collide("Shower bench",(18.88,19.62,16.02,17.01,GRADE,.49))

    # Seven diesel islands define exactly six parallel truck lanes. Columns sit only on islands.
    def canopy(parent,category,islands,y0,y1,clear,key):
        x0,x1=islands[0]-2.18,islands[-1]+2.18
        height=.49 if modern else .37
        slab(parent,category+" canopy platform",(x0,x1,y0,y1,clear,clear+.22),cream)
        slab(parent,category+" light membrane",(x0-.06,x1+.06,y0-.06,y1+.05,clear+.22,clear+height),roof)
        for a,b,c,d in ((x0,x1,y0-.12,y0+.11),(x0,x1,y1-.10,y1+.10),
                        (x0-.11,x0+.12,y0,y1),(x1-.12,x1+.11,y0,y1)):
            slab(parent,category+" fascia",(a,b,c,d,clear-.08,clear+height),colour)
        for fraction in (.20,.50,.80):
            x=x0+(x1-x0)*fraction
            for y in (y0+4,y1-4):
                lens(parent,x,y,clear-.008)
                lamp_specs.append({"node":None,"position_gltf_m":[x,clear-.02,-y],"role":"canopy"})
        w=REGIONS[key][2]*T
        # Fascia lettering bridges an actual shallow name-plate housing.
        z=clear+.09
        slab(parent,category+" sign housing",((x0+x1-w)/2-.12,(x0+x1+w)/2+.12,y0-.22,y0+.015,z-.06,z+1.30),trim)
        plate(parent,"sign_"+category.lower(),key,(x0+x1)/2,y0-.236,z)
        mid=(y0+y1)/2
        for i,x in enumerate(islands):
            column=i in (0,len(islands)//2,len(islands)-1)
            slab(parent,category+" concrete island",(x-.54,x+.54,mid-4.05,mid+4.05,.029,.24),paving)
            if column:
                for y in (mid-3.3,mid+3.3):
                    slab(parent,category+" canopy load-bearing post",(x-.19,x+.19,y-.21,y+.21,.24,clear),cream)
            if i<len(islands)-1:
                repeated(parent,"pump",category+" pump case",(x,mid,.99),(.68,1.30,1.50),colour)
                mesh(category+" pump face",parent,
                     [(x-.30,mid-.654,.99),(x+.30,mid-.654,.99),(x+.30,mid-.654,1.53),(x-.30,mid-.654,1.53)],
                     [(0,1,2,3)],trim)
                if not far:
                    repeated(parent,"trash",category+" trash can",(x,mid+1.40,.52),(.50,.52,.56),trim)
                    repeated(parent,"squeegee",category+" squeegee bucket",(x,mid-1.58,.43),(.45,.40,.38),cream)
            for y in (mid-3.9,mid+3.9):
                slab(parent,category+" yellow bollard",(x-.109,x+.109,y-.109,y+.109,.24,1.25),safety)
            collide(category+" equipment island",(x-.56,x+.56,mid-4.06,mid+4.06,.029,clear if column else 1.74))
        for a,b in zip(islands,islands[1:]):
            xx=(a+b)/2
            plane(lot,category+" lane center dash",(xx-.044,xx+.044,y0-5,y0-2.75),.039,cream)
            plane(lot,category+" exit dash",(xx-.044,xx+.044,y1+1.02,y1+3.25),.039,cream)

    diesel_clear=6.87 if modern else 6.33
    car_clear=4.70 if modern else 4.16
    canopy(diesel,"Diesel",DIESEL_ISLANDS,25,45,diesel_clear,"diesel_new" if modern else "diesel_old")
    canopy(cars,"Car",AUTO_ISLANDS,2,18,car_clear,"car_new" if modern else "car_old")

    # The independent sign has a broad arrow. Its replacement has twin braced posts.
    sx,sy=44.4,-19.15
    sign_key="pole_new" if modern else "pole_old"
    w,h=(n*T for n in REGIONS[sign_key][2:])
    z=12.10 if modern else 11.70
    for x in ((sx-2.23,sx+2.23) if modern else (sx,)):
        slab(tower,"Raised sign pier",(x-.48,x+.48,sy-.46,sy+.46,.029,.53),paving)
        slab(tower,"Highway sign pole",(x-.23,x+.23,sy-.22,sy+.22,.53,z+.54),trim)
        collide("Highway sign pole",(x-.48,x+.48,sy-.46,sy+.46,.029,z+.54))
    slab(tower,"Road sign cabinet",(sx-w/2-.18,sx+w/2+.18,sy-.31,sy+.31,z-.13,z+h+.14),colour)
    plate(tower,"sign_name",sign_key,sx,sy-.327,z)
    plate(tower,"sign_name_rear",sign_key,sx,sy+.327,z,True)
    if not modern:
        outline=[(sx-5.84,sy-.33,z-.6),(sx+4.55,sy-.33,z-.6),(sx+4.55,sy-.33,z-1.17),
                 (sx+7.03,sy-.33,z-.18),(sx+4.55,sy-.33,z+.85),(sx+4.55,sy-.33,z+.28),
                 (sx-5.84,sy-.33,z+.28)]
        mesh("Independent highway arrow",tower,outline,[tuple(range(7))],safety)
    else:
        slab(tower,"Pylon upper crest",(sx-w/2-.21,sx+w/2+.21,sy-.33,sy+.33,z+h+.115,z+h+.39),cream)

    price_key="price_new" if modern else "price_old"
    price_width=REGIONS[price_key][2]*T
    slab(tower,"Price panel casing",(sx-price_width/2-.10,sx+price_width/2+.10,sy-.277,sy+.277,8.04,11.06),trim)
    plate(tower,"sign_price",price_key,sx,sy-.292,8.12)
    plate(tower,"sign_price_rear",price_key,sx,sy+.292,8.12,True)

    # Low curbs divide the highway verge from the truck park without reducing maneuvering lanes.
    for a,b,c,d in ((-61,-41,-25.8,-21.0),(-61,-39,65.0,69.3),(50.3,61.4,43.0,63.0),
                    (-63,62,108.7,115.6)):
        slab(lot,"Pale curb and grass bed",(a,b,c,d,.028,.18),cream)
        plane(lot,"Trimmed grass island",(a+.20,b-.20,c+.20,d-.20),.184,grass)
    tree=None
    for i,(x,y) in enumerate(((-54,-23.37),(-51,67.14),(54.8,52.0),(55.2,52.71),
                              (-37,112.34),(36.72,112.34))):
        anchor=group(f"tree_{i+1:02d}",lot)
        anchor.location=(x,y,.184)
        repeated(anchor,"trunk","Tree trunk",(0,0,.85),(.25,.25,1.70),trim)
        if tree is None:
            tree=oval("Faceted roadside crown",anchor,[(0,0,z,r,r) for z,r in
                      ((1.32,.72),(1.82,1.23),(3.56,.27))],grass,5)
            tree["keep"]=True
        else:
            copied=bpy.data.objects.new("Faceted roadside crown",tree.data)
            bpy.context.collection.objects.link(copied)
            copied.parent=anchor
            copied["keep"]=True
        if i<2:
            collide("Tree trunk",(x-.15,x+.15,y-.15,y+.15,.029,1.88),lot.name)
    collide("Paired tree trunks",(54.65,55.35,51.85,52.86,.029,1.88),lot.name)
    for x in (-59,-39,-19,1,21,41,61):
        repeated(lot,"back_fence_post","Back fence wood post",(x,113.0,.85),(.115,.14,1.50),trim)
    for z in (.54,.99):
        slab(lot,"Back fence continuous rail",(-61.2,61.4,112.93,113.07,z,z+.10),trim)
    collide("Back fence",(-61.2,61.4,112.18,113.11,.03,1.88),lot.name)

    # Yard fixtures, signs and six flood heads. Shared meshes keep repeated parts cheap to export.
    for i,(x,y) in enumerate(((-33.7,55.6),(34.1,56.3),(-33.7,78),(34.1,78),(-33.7,98.8),(34.1,98.8))):
        high=10.2 if modern else 9.2
        slab(lot,"Yard light standard",(x-.112,x+.112,y-.115,y+.115,.028,high+.30),trim)
        collide("Flood light standard",(x-.128,x+.128,y-.13,y+.13,.028,high+.30),lot.name)
        anchor=group(f"flood_{i+1:02d}",lot)
        anchor.location=(x+(.63 if x<0 else -.63),y,high)
        repeated(anchor,"flood_case","Flood head housing",(0,0,.145),(1.59,1.01,.30),trim)
        lens(anchor,0,0,-.009)
        lamp_specs.append({"node":anchor.name,"position_gltf_m":[anchor.location.x,high-.17,-y],"role":"yard"})
    for x,y,z,w,d,h,label,key in ((-4.8,-.85,GRADE,2.8,1.1,1.84,"Ice machine","ice"),
                                 (-7.92,-.74,GRADE,3.0,.77,1.45,"Newspaper box","news"),
                                 (42.9,22.4,.029,2.75,.85,1.83,"Air station","air"),
                                 (46.21,22.4,.029,3.16,.85,1.83,"Water station","water")):
        slab(lot,label,(x-w/2,x+w/2,y-d/2,y+d/2,z,z+h),cream if key in ("ice","water") else colour)
        plate(lot,"label_"+key,key,x,y-d/2-.012,z+h-1.04)
    collide("Ice and newspaper dispensers",(-9.42,-3.40,-1.40,-.30,GRADE,1.98),lot.name)
    collide("Air and water service posts",(41.525,47.79,21.975,22.825,.029,1.86),lot.name)
    # The fenced back-of-house pen keeps the dumpster and bottled gas off the delivery approach.
    for a,b,c,d in ((-19.6,-19.42,21.2,25.15),(-19.6,-12.2,24.96,25.14),(-12.39,-12.21,22.2,25.15)):
        slab(lot,"Dumpster corral board screen",(a,b,c,d,.029,2.03),colour)
    slab(lot,"Dump body",(-18.90,-15.39,22.18,24.2,.029,1.28),trim)
    slab(lot,"Propane cage body",(-14.96,-12.95,22.3,24.32,.029,1.75),trim)
    for x in (-14.8,-14.4,-14.0,-13.6,-13.2):
        repeated(lot,"cage_bar","Cage front bar",(x,22.284,.91),(.068,.096,1.37),cream)
    collide("Back service corral",(-19.6,-12.2,21.2,25.15,.029,2.03),lot.name)
    for x,y,key in ((-22.27,18.9,"diesel_arrow"),(25.1,39.2,"trucks_arrow")):
        w=REGIONS[key][2]*T
        slab(lot,"Directional sign stem",(x-.091,x+.091,y-.08,y+.08,.029,2.08),trim)
        slab(lot,"Directional plate", (x-w/2-.055,x+w/2+.055,y-.063,y+.060,1.92,3.21),colour)
        plate(lot,"sign_route_"+key,key,x,y-.075,1.95)
        collide("Directional sign stem",(x-.09,x+.09,y-.08,y+.08,.029,3.21),lot.name)
    # Irregular patches stay below the travel clearance, alongside route arrows and six numbers.
    pixels={1:((-.10,.11,.05,1.31),),2:((-.4,.4,1.16,1.31),(-.4,.4,.05,.20),
             (-.4,.4,.62,.76),(.24,.4,.76,1.16),(-.4,-.24,.20,.62)),
            3:((-.4,.4,1.16,1.31),(-.4,.4,.05,.20),(-.4,.4,.62,.76),(.24,.4,.20,.62),(.24,.4,.76,1.16)),
            4:((-.4,-.25,.76,1.31),(.24,.4,.05,1.31),(-.4,.24,.61,.76)),
            5:((-.4,.4,1.16,1.31),(-.4,.4,.05,.20),(-.4,.4,.62,.76),(-.4,-.24,.76,1.16),(.24,.4,.20,.62)),
            6:((-.4,.4,.05,.20),(-.4,.4,.62,.76),(-.4,.4,1.16,1.31),(-.4,-.24,.20,.62),(-.4,-.24,.76,1.16),(.24,.4,.20,.62))}
    for i,(a,b) in enumerate(zip(DIESEL_ISLANDS,DIESEL_ISLANDS[1:]),1):
        x=(a+b)/2
        for u,v,m,n in pixels[i]:
            plane(lot,"Painted diesel lane number",(x+u,x+v,23.35+m,23.35+n),.040,cream)
        tip_y=19.0
        outline=[(x-.16,16.85),(x+.16,16.85),(x+.16,18.17),(x+.65,18.17),
                 (x,tip_y),(x-.65,18.17),(x-.16,18.17)]
        mesh("Painted approach arrow",lot,[(u,v,.0385) for u,v in outline],[tuple(range(7))],cream)
        y=31.47 if i%2 else 37.96
        stain=[(x-.7,y-.80),(x+.24,y-.92),(x+.63,y-.36),(x+.53,y+.72),(x-.13,y+1.03),(x-.62,y+.61)]
        mesh("Asphalt wear and oil patch",lot,[(u,v,.036) for u,v in stain],[tuple(range(6))],trim)

    # Detached service bay shares the forecourt position. Older trucks climb a pit platform;
    # the later wash has a flat through-deck and a separate reefer parking head row.
    bay=(-6.3,8.6,28.8,44.6)
    a,b,c,d=bay
    slab(services,"Service shop roof",(a-.17,b+.17,c-.21,d+.21,5.34,5.67),roof)
    for index,x in enumerate((a,b-.27)):
        slab(services,"Service bay side wall",(x,x+.27,c,d,.029,5.48),brick)
        # The wash frame seats against the side walls, so each side has one envelope.
        left,right=x,x+.27
        if modern:
            left,right=(a,a+.49) if index==0 else (b-.49,b)
        collide("Service bay side wall",(left,right,c,d,.029,5.48),services.name)
    slab(services,"Service bay branded header",(a,b,c-.13,c+.17,4.37,5.62),colour)
    plate(services,"sign_service_bay","wash" if modern else "repair",(a+b)/2,c-.145,4.27)
    # Support a clear vehicle entrance under the high canopy.
    if modern:
        slab(services,"Wash through deck",(a+.27,b-.27,c,d,.029,.13),paving)
        for x in (a+.33,b-.33):
            repeated(services,"wash_upright","Wash gantry column",(x,36.5,2.30),(.28,.37,4.34),colour)
        slab(services,"Wash gantry top",(a+.19,b-.19,36.31,36.69,4.34,4.62),colour)
        for x in (-25.8,-13.8,-1.8,10.2,22.2):
            repeated(lot,"power","Reefer power pedestal",(x,74.54,.67),(.52,.51,1.29),cream)
        for x0,x1,y0,y1 in ((-31,30.75,74.97,74.97),(-31,-31,74.97,96.15),(30.75,30.75,74.97,96.15)):
            mesh("Reefer stall fence",lot,[(x0,y0,.035),(x1,y1,.035),(x1,y1,1.52),(x0,y0,1.52)],
                 [(0,1,2,3)],chain)
            collide("Reefer fence",(x0-.06,x1+.06,y0-(.70 if y0==y1 else .06),y1+.06,.029,1.57),lot.name)
        for x,y in ((-31,74.97),(30.75,74.97),(-31,96.15),(30.75,96.15),(0,74.97)):
            repeated(lot,"reef_fence_post","Reefer tension post",(x,y,.78),(.09,.09,1.50),trim)
        slab(lot,"Exterior ATM case",(5.97,8.71,-.70,.056,GRADE,2.00),trim)
        plate(lot,"sign_atm","atm",7.32,-.714,.91)
        collide("Exterior ATM",(5.97,8.71,-.70,.056,GRADE,2.00),lot.name)
    else:
        for x0,x1,y0,y1 in ((a+.27,.19,c,d),(1.28,b-.27,c,d),(.19,1.28,c,34.33),(.19,1.28,40.74,d)):
            slab(services,"Raised repair deck with pit opening",(x0,x1,y0,y1,.029,.68),paving)
        plane(services,"Dark lube pit bottom",(.19,1.28,34.33,40.74),.032,trim)
        mesh("Vehicle ramp to lube platform",services,[(a+.27,c-3.05,.032),(b-.27,c-3.05,.032),
             (b-.27,c,.68),(a+.27,c,.68)],[(0,1,2,3)],paving)
        slab(services,"Workshop rear tool wall",(a+.27,b-.27,d-.26,d,.029,5.38),brick)
        collide("Workshop rear wall",(a+.27,b-.27,d-.26,d,.029,5.38),services.name)
        beam("CB whip antenna",shell,(-15.8,17.1,4.52),(-15.48,17.1,7.72),(.035,.035),(.019,.019),trim,4)
        beam("CB antenna crosspiece",shell,(-16.36,17.1,6.12),(-15.07,17.1,6.12),(.022,.022),(.022,.022),trim,4)
    for parent in (shell,inside,lot,diesel,cars,tower,services):
        batch(parent)
    file="cedar-relay-"+year+("" if near else "-"+lod)
    draw=[o for o in bpy.data.objects if o.type=="MESH" and not o.get("collision_only")]
    triangles=sum(len(p.vertices)-2 for o in draw for p in o.data.polygons)
    export_glb(ROOT/f"{file}.glb")
    if near:
        save_source(ROOT/f"{file}.blend")
    return {"file":f"{file}.glb","lod":lod,"triangles_visible":triangles,"door_nodes_gltf_m":doors,
            "collision_boxes_gltf_m":boxes,"sign_panels":panels,
            "lights":lamp_specs,
            "materials":sorted({m.name for o in draw for m in o.data.materials})}


meta={"task":"C006","name":"Cedar Relay Truck Stop","footprint_m":[127,144],"height_m":16.22,
      "units":"glTF metres, +Y up and +Z toward the highway. Common grade origin at the main entrance midpoint.",
      "lot_bounds_gltf_m":{"x":[-64,63],"y":[0,16.22],"z":[-116,28]},
      "truck_parking":{"bays":30,"bay_size_m":[4.0,18.7],"rows_gltf_z_m":[-55.0,-75.1],"head_to_head":True},
      "routing":{"diesel_lanes":[{"center_gltf_m":[(a+b)/2,0,-35],"clear_width_m":round(b-a-1.12,2)}
                  for a,b in zip(DIESEL_ISLANDS,DIESEL_ISLANDS[1:])],
                 "car_lanes":[{"center_gltf_m":[(a+b)/2,0,-10],"clear_width_m":round(b-a-1.12,2)}
                  for a,b in zip(AUTO_ISLANDS,AUTO_ISLANDS[1:])],
                 "scale_center_gltf_m":[42.2,.28,-48.18],"scale_entry_gltf_m":[42.2,.028,-34.7],
                 "scale_exit_gltf_m":[42.2,.028,-61.65],"travel_axis_gltf":[0,0,-1]},
      "swatches":["brick_red","plank_horizontal","pavement","asphalt_worn","roof_membrane_grey","roof_membrane_green","roof_membrane_tan","roof_tin_red"],
      "lod_distances_suggested_m":{"mid":90,"far":220},"states":[]}
for year,(name,first,last) in STATES.items():
    models=[build(year,lod) for lod in ("near","mid","far")]
    meta["states"].append({"id":year,"name":name,"era":[first,last],
                           "diesel_clear_height_m":6.79 if year=="1990" else 6.25,
                           "car_clear_height_m":4.62 if year=="1990" else 4.08,"models":models})
    print(year,[m["triangles_visible"] for m in models])
(ROOT/"model-info.json").write_text(json.dumps(meta,indent=2)+"\n")
