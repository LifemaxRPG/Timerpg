"""Build and export independently placeable Timerpg beach props."""
from dataclasses import dataclass, field
from math import cos, sin, pi
from pathlib import Path
import json
from tempfile import TemporaryDirectory

from PIL import Image

import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parent
TEXEL = .0388
TILES = json.loads((ROOT/"textures"/"tiles.json").read_text())
PATTERN_TILES = {"towel_coral", "towel_teal", "longboard", "fishboard", "shortboard", "bodyboard"}

bpy.ops.wm.read_factory_settings(use_empty=True)

def material(name, filename):
    mat = bpy.data.materials.new(name)
    mat.use_nodes = True
    tree = mat.node_tree
    shader = tree.nodes.get("Principled BSDF")
    shader.inputs["Roughness"].default_value = .88
    shader.inputs["Specular IOR Level"].default_value = .13
    tex = tree.nodes.new("ShaderNodeTexImage")
    tex.image = bpy.data.images.load(str(filename))
    tex.image.pack()
    tex.interpolation = "Closest"
    tex.extension = "REPEAT"
    tree.links.new(tex.outputs["Color"], shader.inputs["Base Color"])
    return mat

source_atlas = Image.open(ROOT/"textures"/"beach-atlas.png").convert("RGB")
material_cache = {}
catalog = []

def prop_atlas(tags):
    key=tuple(sorted(set(tags)))
    if key in material_cache:
        return material_cache[key]
    columns=1 if len(key)==1 else 2 if len(key)<=4 else 4
    rows=1
    while rows*columns<len(key):
        rows*=2
    width,height=columns*128,rows*128
    pixels=Image.new("RGB",(width,height))
    local_tiles={}
    for i,name in enumerate(key):
        x,y,w,h=TILES[name]
        offset=((i%columns)*128,(i//columns)*128)
        pixels.paste(source_atlas.crop((x,y,x+w,y+h)),offset)
        local_tiles[name]=offset
    with TemporaryDirectory(prefix="beach-texture-") as folder:
        crop=Path(folder)/("beach-"+"-".join(key)+".png")
        pixels.save(crop)
        mat=material("detail_beach_atlas",crop)
    result=mat,local_tiles,width,height
    material_cache[key]=result
    return result

@dataclass
class Prop:
    slug: str
    title: str
    category: str
    era: str = "1950 onward"
    pose: str = "Resting"
    collision: str = "decoration"
    verts: list = field(default_factory=list)
    faces: list = field(default_factory=list)
    swatches: list = field(default_factory=list)

    def add(self, verts, faces, colors):
        start = len(self.verts)
        self.verts.extend(verts)
        self.faces.extend(tuple(i+start for i in face) for face in faces)
        self.swatches.extend([colors]*len(faces) if isinstance(colors,str) else colors)

    def rings(self, rings, color, cap_bottom=True, cap_top=True):
        n = len(rings[0])
        assert all(len(ring)==n for ring in rings)
        faces = []
        tags = []
        for row in range(len(rings)-1):
            for i in range(n):
                j=(i+1)%n
                faces.append((row*n+i,row*n+j,(row+1)*n+j,(row+1)*n+i))
                tags.append(color(row,i) if callable(color) else color)
        if cap_bottom:
            faces.append(tuple(reversed(range(n))))
            tags.append(color(-1,0) if callable(color) else color)
        if cap_top:
            faces.append(tuple((len(rings)-1)*n+i for i in range(n)))
            tags.append(color(len(rings)-1,0) if callable(color) else color)
        self.add([v for ring in rings for v in ring],faces,tags)

    def box(self, xyz, size, color):
        x,y,z=xyz
        w,d,h=size
        self.rings([[(x+a*w/2,y+b*d/2,z+c*h/2) for a,b in ((-1,-1),(1,-1),(1,1),(-1,1))]
                    for c in (-1,1)],color)

    def oval(self, levels, color, n=10, **options):
        rings=[[(x+rx*cos(2*pi*i/n),y+ry*sin(2*pi*i/n),z) for i in range(n)]
               for x,y,z,rx,ry in levels]
        self.rings(rings,color,**options)

    def rod(self, start, end, r, color, n=6, r_end=None):
        a,b = Vector(start), Vector(end)
        v=(b-a).normalized()
        u=v.cross(Vector((0,0,1) if abs(v.z)<.9 else (0,1,0))).normalized()
        side=v.cross(u)
        radii=(r,r_end if r_end is not None else r)
        rings=[[(point + radius*(cos(2*pi*i/n)*u+sin(2*pi*i/n)*side))[:]
                for i in range(n)] for point,radius in zip((a,b),radii)]
        self.rings(rings,color)

    def curved_fabric(self, sections, width, color):
        """Thin continuous fabric folded along its length with a finished underside."""
        n=len(sections)
        verts=[(x,y,z+dz) for dz in (0,-.034) for y,z in sections for x in (-width/2,width/2)]
        faces=[]
        tags=[]
        for i in range(n-1):
            a,b=2*i,2*(i+1)
            faces.extend(((a,a+1,b+1,b),(a+2*n,b+2*n,b+1+2*n,a+1+2*n),
                          (a,b,b+2*n,a+2*n),(a+1,a+1+2*n,b+1+2*n,b+1)))
            tags.extend((color,"canvas","canvas","canvas"))
        faces.extend(((0,2*n,2*n+1,1),(2*n-2,2*n-1,4*n-1,4*n-2)))
        self.add(verts,faces,tags+["canvas","canvas"])

    def annulus(self, center, radius, thickness, swatches, steps=12, sides=6, elliptical=1):
        x,y,z=center
        verts=[]
        for i in range(steps):
            theta=2*pi*i/steps
            for j in range(sides):
                phi=2*pi*j/sides
                r=radius+thickness*cos(phi)
                verts.append((x+r*cos(theta),y+elliptical*r*sin(theta),z+thickness*sin(phi)))
        faces=[]; tags=[]
        for i in range(steps):
            for j in range(sides):
                nxt=(i+1)%steps
                k=(j+1)%sides
                faces.append((i*sides+j,nxt*sides+j,nxt*sides+k,i*sides+k))
                tags.append(swatches[i%len(swatches)])
        self.add(verts,faces,tags)

def new(slug,title,category,**kwargs):
    obj=Prop(slug,title,category,**kwargs)
    catalog.append(obj)
    return obj

def umbrella(slug,title,primary):
    p=new(slug,title,"Shade",pose="Open",collision="pole")
    p.rod((0,0,.025),(0,0,2.15),.036,"metal")
    p.oval([(0,0,.01,.118,.118),(0,0,.09,.13,.13),(0,0,.135,.083,.083)],"rope",8)
    n=16
    outer=[]; mid=[]
    for i in range(n):
        a=2*pi*i/n
        outer.append((1.09*cos(a),1.09*sin(a),1.76 if i%2==0 else 1.66))
        mid.append((.61*cos(a),.61*sin(a),2.02 if i%2==0 else 2.0))
    verts=outer+mid+[(0,0,2.14)]
    faces=[]; colors=[]
    for i in range(n):
        j=(i+1)%n
        faces.extend(((i,j,n+j,n+i),(n+i,n+j,2*n)))
        swatch=primary if (i//2)%2==0 else "cream"
        colors.extend((swatch,swatch))
    p.add(verts,faces,colors)
    p.oval([(0,0,2.13,.07,.07),(0,0,2.205,.043,.043)],"cream",8)
    for i in range(0,n,2):
        x,y,z=outer[i]
        mx,my,mz=mid[i]
        p.rod((0,0,2.11),(mx,my,mz-.039),.012,"metal",4)
        p.rod((mx,my,mz-.039),(x,y,z-.039),.012,"metal",4)
    # A short valance gives the scalloped edge its own narrow shadow band.
    p.rings([[(x,y,z-.053) for x,y,z in outer],outer],
            lambda row,i:primary if (i//2)%2==0 else "cream",False,False)

umbrella("umbrella-coral","Coral Stripe Umbrella","coral")
umbrella("umbrella-teal","Teal Stripe Umbrella","teal")

def towel(slug,title,pattern,edge):
    p=new(slug,title,"Shade",pose="Laid flat")
    x0,x1=-.445,.445
    y0,y1=-.85,.85
    def corner(h,scale):
        return [(x*scale,y*scale,h) for x,y in [(x0+.07,y0),(x1-.07,y0),(x1,y0+.075),(x1,y1-.05),
                  (x1-.075,y1),(x0+.04,y1),(x0,y1-.06),(x0,y0+.055)]]
    p.rings([corner(.004,1),corner(.024,1),corner(.034,.95)],
            lambda row,i:pattern if row==2 else edge)
    for y in (y0,y1):
        for i in range(10):
            x=-.385+i*.084
            p.box((x,y,.014),(.04,.09 if i%2 else .115,.02),"cream")

towel("towel-coral","Coral Beach Towel","towel_coral","coral")
towel("towel-teal","Teal Beach Towel","towel_teal","teal")

def board(slug,title,coords,depth,pattern,edge,fins,era):
    p=new(slug,title,"Surf",era=era,pose="Fin-up on sand")
    def ring(z,k):
        return [(x*k,y,z+.08*max(0,abs(y)-.55)**2) for x,y in coords]
    n=len(coords)
    p.rings([ring(.02,.88),ring(.041,1),ring(depth,1),ring(depth+.013,.88)],
            lambda row,k:pattern if row in (-1,3) else edge)
    for x,y,height,depth_y in fins:
        z=depth+.011
        section=[(y-depth_y*.50,z),(y+depth_y*.5,z),(y+depth_y*.58,z+height*.43),
                 (y+depth_y*.34,z+height),(y+depth_y*.05,z+height*.91)]
        around=[(x+dx,t,h) for dx in (-.018,.018) for t,h in section]
        sides=[(i,(i+1)%5,(i+1)%5+5,i+5) for i in range(5)]
        p.add(around,[tuple(reversed(range(5))),tuple(range(5,10))]+sides,"navy")
    return p

board("surf-longboard","Classic Stringer Longboard",
      [(-.13,-1.4),(.13,-1.4),(.238,-1.13),(.276,-.64),(.286,.12),(.23,.83),
       (.147,1.31),(.068,1.435),(-.068,1.435),(-.147,1.31),(-.23,.83),(-.286,.12),
       (-.276,-.64),(-.238,-1.13)],.079,"longboard","teal",[(0,-1.02,.19,.29)],"1950 onward")
board("surf-fish","Swallowtail Fish Surfboard",
      [(-.225,-.91),(0,-.73),(.225,-.91),(.265,-.6),(.281,-.04),(.228,.5),(.134,.835),
       (0,.975),(-.134,.835),(-.228,.5),(-.281,-.04),(-.265,-.6)],.068,
      "fishboard","sage",[(-.135,-.58,.139,.2),(.135,-.58,.139,.2)],"1970 onward")
board("surf-shortboard","Sunset Shortboard",
      [(-.085,-.97),(.085,-.97),(.176,-.73),(.237,-.31),(.242,.15),(.185,.53),(.09,.844),
       (0,1.025),(-.09,.844),(-.185,.53),(-.242,.15),(-.237,-.31),(-.176,-.73)],.066,
      "shortboard","coral",[(-.133,-.54,.109,.14),(.133,-.54,.109,.14),(0,-.77,.119,.15)],"1980 onward")
p=new("bodyboard","Striped Bodyboard","Surf",era="1975 onward",pose="Deck-up")
outline=[(-.24,-.51),(.24,-.51),(.288,-.37),(.293,.29),(.21,.49),(.13,.55),
         (-.13,.55),(-.21,.49),(-.293,.29),(-.288,-.37)]
p.rings([[(x*k,y,z+.033*max(0,y-.33)) for x,y in outline] for k,z in [(.88,.006),(1,.04),(.92,.068)]],
        lambda row,i:"bodyboard" if row==2 else "navy")
p.annulus((0,.45,.075),.047,.012,("navy",),8,4)

def sphere_prop(slug,title,r,n,sections):
    p=new(slug,title,"Play",pose="Resting")
    south=(0,0,0)
    north=(0,0,2*r)
    rings=[]
    for j in range(1,7):
        phi=pi*j/7
        rings.append([(r*sin(phi)*cos(2*pi*i/n),r*sin(phi)*sin(2*pi*i/n),r*(1-cos(phi)))
                      for i in range(n)])
    p.rings(rings,sections,False,False)
    p.add([south,*rings[0]],[ (0,(i+1)%n+1,i+1) for i in range(n)],
          [sections(0,i) for i in range(n)])
    p.add([north,*rings[-1]],[ (0,i+1,(i+1)%n+1) for i in range(n)],
          [sections(5,i) for i in range(n)])
    return p

sphere_prop("ball-beach","Six-Panel Beach Ball",.255,12,
            lambda row,i:("coral","cream","teal","yellow","cream","navy")[i//2])
sphere_prop("ball-volleyball","Stitched Volleyball",.109,12,
            lambda row,i:"navy" if i%6==1 and row in (1,2) else "yellow" if (i+2*row)%8>=5 else "cream")

p=new("bucket","Sand Pail","Play",pose="Upright")
p.oval([(0,0,.008,.105,.105),(0,0,.223,.147,.147),(0,0,.258,.154,.154),
        (0,0,.264,.129,.129),(0,0,.227,.121,.121),(0,0,.035,.089,.089)],
       lambda row,i:"teal" if row in (0,1,2) else "navy",12)
p.rod((-.146,0,.23),(-.177,0,.289),.014,"yellow")
for i in range(8):
    a=i*pi/8
    b=(i+1)*pi/8
    p.rod((-.177*cos(a),0,.289+.153*sin(a)),(-.177*cos(b),0,.289+.153*sin(b)),.013,"yellow",5)
p.rod((.177,0,.289),(.146,0,.23),.014,"yellow")

p=new("shovel","Toy Sand Shovel","Play",pose="Laid flat")
p.rod((0,-.14,.033),(0,.235,.033),.022,"yellow")
p.annulus((0,.276,.038),.056,.015,("coral",),10,4,elliptical=.65)
blade=[(-.046,-.12),(.046,-.12),(.08,-.213),(.071,-.294),(-.071,-.294),(-.08,-.213)]
p.rings([[(x,y,z) for x,y in reversed(blade)] for z in (.004,.027,.066)],lambda row,i:"coral" if row!=2 else "orange")

p=new("rake","Toy Sand Rake","Play",pose="Laid flat")
p.rod((0,-.08,.029),(0,.164,.029),.016,"teal")
p.box((0,-.099,.034),(.182,.047,.055),"yellow")
for i in range(5):
    p.box(((i-2)*.039,-.143,.031),(.017,.071,.042),"yellow")
p.annulus((0,.205,.028),.034,.012,("teal",),8,4)

p=new("star-mold","Starfish Sand Mold","Play",pose="Open side up")
def star(radius,z):
    return [(radius*(1 if i%2==0 else .56)*cos(i*pi/5),radius*(1 if i%2==0 else .56)*sin(i*pi/5),z)
            for i in range(10)]
p.rings([star(.117,.003),star(.142,.063),star(.129,.065),star(.103,.018)],
        lambda i,j:"coral" if i in (0,1) else "pink")

p=new("sandcastle","Drip Sandcastle","Play",pose="Built on sand")
p.oval([(0,0,.004,.39,.31),(0,0,.053,.33,.269),(0,0,.085,.282,.217)],"sand",10)
for x,y,r,h in [(-.197,-.13,.089,.243),(.196,-.13,.090,.246),(-.197,.13,.089,.26),(.197,.13,.089,.255),
                 (0,.018,.126,.421)]:
    p.oval([(x,y,.068,r+.026,r+.026),(x,y,h-.075,r,r),(x,y,h-.067,r+.018,r+.018),
            (x,y,h-.027,r*.96,r*.96)],"sand",6)
    count=4 if r>.1 else 3
    for d in range(count):
        t=d*2*pi/count
        p.box((x+.73*r*cos(t),y+.73*r*sin(t),h-.014),(.043,.043,.046),"sand")

p=new("flying-disc","Pocket Flying Disc","Play",pose="Laid flat")
p.oval([(0,0,.004,.136,.136),(0,0,.019,.153,.153),(0,0,.032,.137,.137),
        (0,0,.039,.113,.113),(0,0,.043,.102,.102)],
       lambda row,i:"yellow" if row!=2 else "cream",12)

p=new("cooler","Two-Tone Picnic Cooler","Comfort",collision="bounds")
p.box((0,0,.03),(.54,.345,.055),"navy")
p.box((0,0,.177),(.565,.375,.29),"coral")
p.box((0,0,.336),(.586,.39,.055),"cream")
p.box((0,0,.368),(.54,.346,.020),"white")
for x in (-.216,.216):
    p.box((x,-.196,.278),(.047,.019,.072),"cream")
for x in (-.296,.296):
    for y in (-.09,.09):
        p.box((x,y,.225),(.033,.025,.058),"navy")
    p.rod((x,-.104,.251),(x,.104,.251),.021,"cream",6)

p=new("tote","Canvas Beach Tote","Comfort")
p.oval([(0,0,.014,.193,.118),(0,0,.27,.246,.154),(0,0,.291,.249,.155),
        (0,0,.291,.223,.132),(0,0,.052,.169,.098)],
       lambda row,i:"teal" if row==1 else "navy" if row>=2 else "canvas",12)
for y in (-.103,.103):
    for i in range(8):
        a=i*pi/8
        b=(i+1)*pi/8
        p.rod((-.155*cos(a),y,.228+.192*sin(a)),(-.155*cos(b),y,.228+.192*sin(b)),.012,"rope",5)
p.box((0,-.142,.16),(.163,.026,.122),"teal")

p=new("folding-chair","Canvas Folding Chair","Comfort",collision="bounds")
for x in (-.304,.304):
    p.rod((x,-.31,.039),(x,.19,.55),.030,"wood")
    p.rod((x,.34,.047),(x,-.22,.70),.032,"wood")
    p.rod((x,-.19,.42),(x,.02,.93),.027,"wood")
    p.rod((x,-.245,.636),(x,.225,.593),.034,"wood")
    p.rod((x,.225,.593),(x,.23,.454),.023,"wood")
for y,z in ((-.31,.039),(.34,.047),(.19,.535),(.02,.914)):
    p.rod((-.304,y,z),(.304,y,z),.025,"wood")
p.curved_fabric([(-.216,.497),(-.155,.421),(-.025,.431),(.186,.505)],.56,"chair")
# Back fabric rises toward the rear, with both surfaces facing outward.
p.curved_fabric([(-.184,.493),(-.134,.598),(-.073,.739),(.003,.93)],.553,"chair")

p=new("lounger","Striped Sun Lounger","Comfort",collision="bounds")
for x in (-.325,.325):
    p.rod((x,-.99,.33),(x,.41,.31),.028,"wood")
    p.rod((x,.41,.31),(x,.97,.72),.028,"wood")
    for y,z in ((-.72,.322),(.18,.316),(.70,.524)):
        p.rod((x,y-.097,.034),(x,y,z),.024,"wood")
    p.rod((x,.23,.15),(x,.78,.57),.019,"metal")
for y,z in ((-.949,.31),(.27,.296),(.91,.69),(-.61,.059),(.781,.062)):
    p.rod((-.325,y,z),(.325,y,z),.024,"wood")
p.curved_fabric([(-.98,.345),(-.52,.314),(.10,.313),(.41,.326),(.97,.736)],.605,"lounger")

p=new("swim-ring","Coral and Cream Swim Ring","Comfort",pose="Laid flat")
p.annulus((0,0,.101),.356,.10,("coral","coral","cream","cream","coral","coral","cream","cream",
                                           "coral","coral","cream","cream"),steps=12,sides=8)
p.oval([(.333,.111,.177,.017,.017),(.333,.111,.192,.015,.015)],"cream",6)

p=new("air-mattress","Channel Air Mattress","Comfort",pose="Inflated and laid flat")
p.oval([(0,-.008,.007,.277,.848),(0,-.008,.033,.309,.886)],"teal",12)
for i,col in enumerate(("teal","cream","teal","cream","teal")):
    x=(i-2)*.122
    p.oval([(x,-.018,.010,.051,.80),(x,0,.066,.069,.91),(x,0,.107,.058,.83)],col,12)
p.oval([(0,.683,.10,.287,.18),(0,.683,.149,.302,.205),(0,.683,.195,.277,.176)],"teal",12)
p.oval([(.183,-.67,.106,.015,.015),(.183,-.67,.118,.012,.012)],"cream",6)

p=new("flip-flops","Pair of Flip-Flops","Comfort",pose="Laid flat")
for sign in (-1,1):
    x=sign*.083
    y=sign*.029
    ellipse=[(-.04,-.124),(.037,-.124),(.052,-.07),(.051,.046),(.045,.117),(.019,.143),
             (-.037,.132),(-.062,.093),(-.067,.05),(-.047,-.052)]
    p.rings([[(x+px,y+py,z) for px,py in ellipse] for z in (.004,.013,.028,.036)],
            lambda row,i:"navy" if row<=0 else "teal")
    for end in (-1,1):
        p.rod((x-.006,y+.084,.037),(x-.004+end*.027,y+.01,.075),.010,"coral",5)
        p.rod((x-.004+end*.027,y+.01,.075),(x-.005+end*.050,y-.025,.031),.013,"coral",5)

p=new("volleyball-net","Beach Volleyball Net","Play",pose="Strung",collision="two_posts")
for x in (-4.08,4.08):
    p.rod((x,0,.025),(x,0,2.50),.043,"metal",8)
    p.rod((x,0,.016),(x,0,.33),.062,"navy",8)
    p.oval([(x,0,2.49,.048,.048),(x,0,2.53,.041,.041)],"teal",8)
    near=x*.982
    p.rod((x,0,2.39),(near,0,2.315),.009,"rope",4)
    p.rod((x,0,1.28),(near,0,1.33),.009,"rope",4)
def mesh_strip(ax,az,bx,bz):
    # Open cord gaps at a believable gauge. Keep the plane double-sided.
    delta=Vector((bx-ax,bz-az)).normalized()
    wx,wz=delta.y*.010,-delta.x*.010
    p.add([(ax-wx,0,az-wz),(bx-wx,0,bz-wz),(bx+wx,0,bz+wz),(ax+wx,0,az+wz)],
          [(0,1,2,3)],"rope")
for i in range(1,27):
    x=-4.01+i*8.02/27
    mesh_strip(x,1.34,x,2.34)
for i in range(1,4):
    z=1.34+i*.25
    for j in range(4):
        mesh_strip(-4.01+j*2.005,z,-4.01+(j+1)*2.005,z)
for z,r,t in [(2.34,.023,"cream"),(1.34,.017,"cream")]:
    # Long bars use precisely scaled atlas UVs on individual spans.
    for j in range(4):
        x=-4.01+j*2.005
        p.rod((x,0,z),(x+2.005,0,z),r,t,4)
for x in (-4.009,4.009):
    p.rod((x,0,1.34),(x,0,2.34),.018,"cream",4)

def mesh_of(p):
    low = min(v[2] for v in p.verts)
    verts = [Vector((x,y,z-low)) for x,y,z in p.verts]
    faces=[]
    tags=[]
    for face,tag in zip(p.faces,p.swatches):
        points=[verts[i] for i in face]
        normal=(points[1]-points[0]).cross(points[2]-points[0]).normalized()
        if len(face)>3 and any(abs(normal.dot(pt-points[0]))>1e-5 for pt in points[3:]):
            if len(face)==4:
                pieces=[(face[0],face[1],face[2]),(face[0],face[2],face[3])]
            else:
                # Triangulate nonplanar board decks in XY with a constrained polygon fill.
                from mathutils.geometry import tessellate_polygon
                flat=[Vector((verts[i].x,verts[i].y,0)) for i in face]
                pieces=[tuple(face[v] for v in tri) for tri in tessellate_polygon([flat])]
            faces.extend(pieces)
            tags.extend([tag]*len(pieces))
        else:
            faces.append(face)
            tags.append(tag)
    mesh=bpy.data.meshes.new(p.title)
    mesh.from_pydata(verts,[],faces)
    mesh.update()
    obj=bpy.data.objects.new(p.slug,mesh)
    coll=bpy.data.collections.new(p.title)
    bpy.context.scene.collection.children.link(coll)
    coll.objects.link(obj)
    atlas,local_tiles,tex_w,tex_h=prop_atlas(tags)
    mesh.materials.append(atlas)
    layer=mesh.uv_layers.new(name="0.0388 m per texel")
    for polygon,tag in zip(mesh.polygons,tags):
        n=polygon.normal
        points=[verts[mesh.loops[i].vertex_index] for i in polygon.loop_indices]
        tx,ty=local_tiles[tag]
        w=h=128
        center=sum(points,Vector())/len(points)
        if tag in PATTERN_TILES and abs(n.z)>.90:
            # Project world X into the plane, sampling from a common center across the panel.
            u=(Vector((1,0,0))-n.x*n).normalized()
            v=n.cross(u)
            if v.y<0:
                v=-v
            center=Vector()
        else:
            u=(Vector((0,0,1)).cross(n) if abs(n.z)<.90 else Vector((0,1,0)).cross(n)).normalized()
            v=n.cross(u)
        for i,pt in zip(polygon.loop_indices,points):
            delta=pt-center
            px=w/2+delta.dot(u)/TEXEL
            py=h/2-delta.dot(v)/TEXEL
            assert 0<=px<w and 0<=py<h,(p.slug,tag,px,py)
            layer.data[i].uv=((tx+px)/tex_w,1-(ty+py)/tex_h)
    mesh.calc_loop_triangles()
    assert len(mesh.loop_triangles)<500,(p.slug,len(mesh.loop_triangles))
    return obj,verts

asset_entries=[]
for p in catalog:
    obj,points=mesh_of(p)
    low=[min(v[i] for v in points) for i in range(3)]
    high=[max(v[i] for v in points) for i in range(3)]
    dims=[high[i]-low[i] for i in (0,2,1)]
    metadata={"id":p.slug,"name":p.title,"category":p.category,"era":p.era,"pose":p.pose,
              "file":f"props/{p.slug}.glb","size_gltf_m":[round(a,4) for a in dims],
              "triangles":len(obj.data.loop_triangles),"materials":len(obj.data.materials),"collision_boxes":[]}
    if p.collision=="bounds":
        metadata["collision_boxes"].append({"center_m":[round((high[0]+low[0])/2,4),round(high[2]/2,4),round(-(high[1]+low[1])/2,4)],
                                             "size_m":metadata["size_gltf_m"]})
    if p.collision=="pole":
        metadata["collision_boxes"].append({"center_m":[0,.89,0],"size_m":[.13,1.78,.13]})
    if p.collision=="two_posts":
        for x in (-4.08,4.08):
            metadata["collision_boxes"].append({"center_m":[x,1.26,0],"size_m":[.124,2.52,.124]})
    obj["era"]=p.era
    obj["placement_pose"]=p.pose
    asset_entries.append(metadata)
    bpy.ops.object.select_all(action="DESELECT")
    obj.select_set(True)
    bpy.context.view_layer.objects.active=obj
    bpy.ops.export_scene.gltf(filepath=str(ROOT/metadata["file"]),export_format="GLB",
                              use_selection=True,export_extras=True)
for index,p in enumerate(catalog):
    obj=bpy.data.objects[p.slug]
    obj.location=((index%6)*3.45,-(index//6)*3.50,0)
    if p.slug=="volleyball-net":
        obj.location.x+=3.3
for screen in bpy.data.screens:
    for area in screen.areas:
        if area.type=="VIEW_3D":
            from mathutils import Quaternion
            area.spaces.active.region_3d.view_distance=29
            area.spaces.active.region_3d.view_location=(8,-6,0)
            area.spaces.active.region_3d.view_rotation=Quaternion((.89,.43,.06,.10)).normalized()
info={"name":"Beach Day Prop Pack","coordinate_system":"glTF metres with Y up, ground at Y=0, object origin at its footprint",
      "swatches":["plank_horizontal"],"art_texel_m":TEXEL,"props":asset_entries}
(ROOT/"model-info.json").write_text(json.dumps(info,indent=2)+"\n")
bpy.ops.file.pack_all()
bpy.context.preferences.filepaths.save_version=0
bpy.ops.wm.save_as_mainfile(filepath=str(ROOT/"beach-prop-pack.blend"))
print(f"{len(catalog)} finished beach props")
