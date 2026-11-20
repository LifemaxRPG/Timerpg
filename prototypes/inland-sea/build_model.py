"""Build The Inland Sea in metres. Run with Blender's Python interpreter."""
import bpy, json, sys, struct
from mathutils import Vector
from pathlib import Path
from math import pi, sin, cos, radians
R = Path(__file__).resolve().parent
bpy.ops.wm.read_factory_settings(use_empty=True)
V = Vector
TEXEL = .0388
CENTRE_Y = 50
materials = {}
def material(role, texture, metal, rough, emission=0):
    m=bpy.data.materials.new(role)
    m.use_nodes=True
    nodes=m.node_tree.nodes
    p=nodes.get("Principled BSDF")
    p.inputs["Metallic"].default_value=metal
    p.inputs["Roughness"].default_value=rough
    im=bpy.data.images.load(str(R/"textures"/(texture+".png")),check_existing=True)
    im.pack()
    tx=nodes.new("ShaderNodeTexImage")
    tx.image=im
    tx.interpolation="Closest"
    tx.extension="REPEAT"
    m.node_tree.links.new(tx.outputs["Color"],p.inputs["Base Color"])
    if emission:
        m.node_tree.links.new(tx.outputs["Color"],p.inputs["Emission Color"])
        p.inputs["Emission Strength"].default_value=emission
    materials[role]=m
    return m
pat=material("metal_verdigris","verdigris",.52,.38)
deep=material("metal_blue_bronze","deep",.46,.36)
gold=material("metal_chased_brass","brass",.64,.29)
pale=material("metal_champagne","ivory",.52,.37)
stone=material("wall_travertine","ivory",0,.85)
rock=material("wall_blue_basalt","basalt",.08,.76)
pave=material("ground_promenade","paving",0,.89)
water=material("water_reflecting_basin","water",.44,.12)
lamp=material("glow_lantern","lens",.05,.3,1.8)
sign=material("sign_name","name",.22,.56)

def empty(name,parent=None):
    ob=bpy.data.objects.new(name,None)
    bpy.context.collection.objects.link(ob)
    ob.parent=parent
    return ob
root=empty("inland_sea")
whale=empty("leviathan",root)
waves=empty("tidal_buttresses",root)
lot=empty("lot",root)
plaza=empty("foundry_court",root)
batches={}
def add(name,parent,vs,faces,m,smooth=False):
    # Keep connected pieces editable while batching repeated pieces by component.
    key=(name,parent.name)
    verts,polys,slots,flags=batches.setdefault(key,([],[],[],[]))
    base=len(verts)
    verts.extend(V(v) for v in vs)
    for i,f in enumerate(faces):
        polys.append([base+j for j in f])
        slots.append(m[i] if isinstance(m,list) else m)
        flags.append(smooth)
def loft(name,parent,rings,mat,caps=True,smooth=False):
    n=len(rings[0])
    vs=sum(rings,[])
    fs=[];ms=[]
    for i in range(len(rings)-1):
        for j in range(n):
            fs.append([i*n+j,i*n+(j+1)%n,(i+1)*n+(j+1)%n,(i+1)*n+j])
            ms.append(mat(i,j) if callable(mat) else mat)
    if caps:
        fs.extend([list(reversed(range(n))),list(range(len(vs)-n,len(vs)))])
        ms.extend([ms[0],ms[-1]])
    add(name,parent,vs,fs,ms,smooth)
def block(name,parent,x,y,z,w,d,h,m):
    rings=[[(x-w/2,y-d/2,k),(x+w/2,y-d/2,k),(x+w/2,y+d/2,k),(x-w/2,y+d/2,k)] for k in (z,z+h)]
    loft(name,parent,rings,m)
def drum(name,parent,x,y,levels,m,n=48):
    rings=[[(x+rx*cos(i*2*pi/n),y+ry*sin(i*2*pi/n),z) for i in range(n)] for z,rx,ry in levels]
    loft(name,parent,rings,m)
def tube(name,parent,points,radii,m,sides=6,closed=False):
    pts=list(map(V,points))
    rings=[]
    for i,p in enumerate(pts):
        prev=pts[(i-1)%len(pts)] if closed or i else pts[0]
        nex=pts[(i+1)%len(pts)] if closed or i<len(pts)-1 else pts[-1]
        axis=(nex-prev).normalized()
        ref=V((0,1,0)) if abs(axis.y)<.92 else V((0,0,1))
        u=ref.cross(axis).normalized()
        v=axis.cross(u)
        r=radii[i] if isinstance(radii,list) else radii
        rings.append([p+r*(cos(j*2*pi/sides)*u+sin(j*2*pi/sides)*v) for j in range(sides)])
    if closed:rings.append(rings[0])
    loft(name,parent,rings,m,not closed)

tilt=radians(40)
def animal(s,y,z):
    roll=radians(19+24*min(1,max(0,(-s-16)/27)))
    lateral=y*cos(roll)-z*sin(roll)
    vertical=y*sin(roll)+z*cos(roll)
    tail_lift=14*(max(0,-s-16)/32)**2
    return V((s*cos(tilt)-vertical*sin(tilt),lateral,41+s*sin(tilt)+vertical*cos(tilt)+tail_lift))
# A narrow peduncle opens into a deep barrel, then a broad, flattened rostrum.
stations=[(-46,1.2,.9,2),(-43,2.15,1.65,1.5),(-38,2.6,2.5,.3),(-33,3.3,3.6,-.5),(-28,4.6,5.1,-.8),(-22,5.8,6.7,-.6),(-16,7.2,8.1,0),(-10,8.5,9.6,.55),(-4,9.65,10.8,.85),(2,10.65,11.6,.9),(8,11.35,12.25,.85),(14,11.5,12.25,.7),(20,10.9,11.65,.55),(25,10.2,10.65,.6),(30,9.2,9,.85),(35,7.5,7.2,1.15),(39,5.5,5.2,1.55),(42,3.5,3.2,1.85),(43.5,1.85,1.7,2),(44.2,.45,.48,2.1)]
def section(s):
    for a,b in zip(stations,stations[1:]):
        if s<=b[0]+1e-6:
            t=(s-a[0])/(b[0]-a[0])
            return [a[i]*(1-t)+b[i]*t for i in (1,2,3)]
    return stations[-1][1:]
def skin(s,a,offset=0):
    w,h,k=section(s)
    return animal(s,(w+offset)*cos(a),k+(h+offset)*sin(a))
N=24
def shell(s0,s1,indices,name,mat):
    ss=sorted({s0,s1,*[p[0] for p in stations if s0<p[0]<s1]})
    vs=[skin(s,j*2*pi/N) for s in ss for j in range(N)]
    fs=[];ms=[]
    for i in range(len(ss)-1):
        for j in indices:
            fs.append([i*N+j,i*N+(j+1)%N,(i+1)*N+(j+1)%N,(i+1)*N+j])
            ms.append(mat((ss[i]+ss[i+1])/2, (j+.5)*2*pi/N))
    if s0==stations[0][0]:fs.append(list(reversed(range(N))));ms.append(deep)
    if s1==stations[-1][0]:fs.append(list(range(len(vs)-N,len(vs))));ms.append(pat)
    add(name,whale,vs,fs,ms,True)
def hide(s,a):
    if sin(a)<-.34 and s>1:return pale
    if sin(a)<-.06:return deep
    return pat
shell(-46,-33,range(N),"Tail stock",hide)
shell(-33,2,list(range(3,9))+list(range(16,20)),"Dorsal plate and keel",hide)
shell(2,44.2,range(N),"Cast head and barrel",hide)
# Deep rectangular ship frames make the negative spaces visible from both shores.
for s in [-33,-29,-25,-21,-17,-13,-9,-5,-1,2]:
    vs=[];fs=[];ms=[]
    for t,r in [(s-.5,.05),(s+.5,.05),(s+.5,-.55),(s-.5,-.55)]:
        vs.extend(skin(t,i*2*pi/N,r) for i in range(N))
    for side in range(4):
        for i in range(N):
            fs.append([side*N+i,side*N+(i+1)%N,((side+1)%4)*N+(i+1)%N,((side+1)%4)*N+i])
            ms.append(gold if side==0 else deep)
    add("Bronze hull frames",whale,vs,fs,ms)
for a in [0,pi]:
    tube("Sheer stringers",whale,[skin(s,a,-.3) for s in [-33,-28,-22,-16,-10,-4,2]],.24,deep,5)
tube("Inner backbone",whale,[animal(s,0,section(s)[2]) for s in range(-34,5,3)],.85,gold,8)
for angle in (0,pi):
    tube("Amber seam in keel",whale,[animal(s,.84*cos(angle),section(s)[2]) for s in [-32,-24,-16,-8,1]],.11,lamp,4)
# Flippers and flukes have lens sections, a curved leading edge and tapered tips.
def foil(name,sections,side,upper,lower):
    rings=[]
    for span,along,chord,height,thick in sections:
        if name=="Long pectorals" and side<0:
            reach=max(0,(span-9)/25)
            height-=13*reach**1.25
            along+=9*reach
        rings.append([animal(along+chord*u,side*span,height+thick*v) for u,v in
                      [(1,0),(.68,.72),(.08,1),(-.72,.52),(-1,0),(-.56,-.58),(.14,-.7),(.75,-.44)]])
    if side>0:rings=[list(reversed(r)) for r in rings]
    loft(name,whale,rings,lambda i,j: (upper if (j<4)==(side<0) else lower),True,True)
for side in (-1,1):
    foil("Swept flukes",[(.1,-44,2.2,1.8,1.1),(3,-43.5,4.4,1.9,1.4),(7,-44,5.2,2.1,1.25),(12,-45,4.7,2.8,.92),(17,-46.8,3.5,3.9,.74),(21,-49.1,2.2,5.2,.44),(24,-52,.32,6.7,.19)],side,pat,gold)
    foil("Long pectorals",[(8.8,12.5,3.2,-3.4,1.6),(11,12.2,4.5,-3.9,1.9),(14.8,10,4.7,-4.15,1.65),(19,6.9,4.15,-4.5,1.3),(23.5,2.6,3.45,-5.3,1.12),(27,-1.6,2.9,-6.25,.83),(30,-5.3,2,-7,.64),(33,-9.8,.82,-6.8,.35),(34,-12,.18,-6.25,.13)],side,pat,pale)
# Recurved dorsal fin rises from the posterior hump.
rings=[]
for z,s,half,wide in [(5,-22,5.8,1.65),(7,-21,5,1.35),(9,-21.4,3.1,.94),(11,-22.8,1.8,.57),(12.1,-24.3,.28,.25)]:
    rings.append([animal(s+half*cos(i*pi/4),wide*sin(i*pi/4),z) for i in range(8)])
loft("Dorsal fin",whale,rings,pat,True,True)
# Throat pleats converge on the chin. Each is a real raised chased-metal bead.
for j in range(13):
    a=pi+radians(18+j*144/12)
    pp=[skin(s,a,.055) for s in [2.2,8,14,20,25,30,35,39,42,43]]
    tube("Chased ventral pleats",whale,pp,[.055]+[.13]*7+[.09,.04],gold,4)
for side in (-1,1):
    def flank(angle):return radians(angle) if side==1 else pi-radians(angle)
    points=[skin(s,flank(deg),.12) for s,deg in [(17,-9),(20,-11),(24,-10),(28,-7),(32,-3),(36,1),(39,5),(42,10),(43.6,15)]]
    tube("Mouth commissures",whale,points,[.12,.28,.35,.34,.31,.27,.22,.17,.07],deep,7)
    eye=skin(16.2,flank(1),.08)
    def jewel(name,center,radii,m):
        rings=[]
        for angle in [-75,-22,31,78]:
            a=radians(angle)
            rings.append([center+V((radii[0]*cos(a)*cos(i*pi/4),radii[1]*cos(a)*sin(i*pi/4),radii[2]*sin(a))) for i in range(8)])
        loft(name,whale,rings,m,True,True)
    jewel("Cast orbital ridges",eye,(1.48,.7,1.16),gold)
    jewel("Obsidian eyes",eye+V((.08,side*.46,.02)),(.97,.47,.85),deep)
    jewel("Eye catchlights",eye+V((.35,side*.84,.24)),(.19,.12,.17),pale)
    for s,deg,size in [(25,48,.65),(29.8,42,.74),(34.4,37,.81),(38,37,.72),(40.9,36,.56),(42.5,46,.39)]:
        jewel("Rostral knuckles",skin(s,flank(deg),-.16),(size*1.25,size,size*.85),pat)
# Fine chased scrolls follow the cheeks like a shipwright's sheer lines.
for flank_sign in (-1,1):
    for k in range(3):
        ss=[2.4,5,8,11,14,17,20,23,26,29,32]
        strip=[]
        for i,s in enumerate(ss):
            t=i/(len(ss)-1)
            lat=radians(17+5*k+17*sin(t*pi)-11*t)
            half=radians(.24+.73*(sin(pi*t)**.6))
            if flank_sign<0:lat=pi-lat
            strip.extend([skin(s,lat-half,.10),skin(s,lat+half,.10)])
        add("Engraved cheek scrolls",whale,strip,[[i,i+1,i+3,i+2] for i in range(0,len(strip)-2,2)],gold)
    for score_s in (6.5,13.2):
        strip=[]
        for i in range(11):
            lat=radians(36+i*5.3)
            if flank_sign<0:lat=pi-lat
            strip.extend([skin(score_s+d,lat,.09) for d in (-.055,.055)])
        add("Casting joints",whale,strip,[[i,i+1,i+3,i+2] for i in range(0,20,2)],deep)
# Tapering wave-shaped masonry supports key directly into the belly.
def wave(index,s,angle,start,base_width):
    tip=skin(s,radians(angle),-.9)
    foot=V(start); path=[]
    c1=foot+V((0,0,11));c2=tip+V((-13,0,-4))
    for i in range(17):
        t=i/16
        p=(1-t)**3*foot+3*t*(1-t)**2*c1+3*t*t*(1-t)*c2+t**3*tip
        path.append(p)
    rings=[];trim=[]
    for i,p in enumerate(path):
        t=i/16
        tangent=(path[min(16,i+1)]-path[max(0,i-1)]).normalized()
        side=V((0,1,0))
        normal=side.cross(tangent).normalized()
        side=tangent.cross(normal).normalized()
        width=base_width*(1-.82*t)
        thick=2.7*(1-.57*t)
        rings.append([V((q.x,q.y,2.54 if i==0 else q.z)) for x,y in [(1,0),(.86,.82),(.1,1),(-.9,.78),(-1,0),(-.85,-.82),(.05,-1),(.88,-.76)] for q in [p+normal*width*x+side*thick*y]])
        trim.append(rings[-1][6])
    loft("Breaker %d"%index,waves,rings,lambda i,j:stone if j in (0,1,5,6) else rock,True,True)
    tube("Bronze cutwaters",waves,trim[1:],.21,gold,4)
    return list(tip)
bearings=[wave(1,-36,270,(-43,-2,2.55),8),wave(2,-17,225,(-38,-8,2.55),8),wave(3,5,290,(-28,9,2.55),8.8)]
# Plaza: axial promenade, an oval pool, a dry central causeway and an island.
drum("Oval promenade",plaza,0,0,[(0,65,50),(.16,65,50)],pave,64)
drum("Basalt pool curb",plaza,0,0,[(.16,54,34),(.68,54,34),(.83,53.7,33.7)],rock,64)
add("Quiet water",plaza,[(53.15*cos(i*pi/32),33.15*sin(i*pi/32),.86) for i in range(64)],[list(range(64))],water)
drum("Foundry island",plaza,-24,0,[(.83,30.5,16.4),(1.4,30.5,16.4),(1.55,30.2,16.1)],rock,56)
drum("Travertine coping",plaza,-24,0,[(1.55,29.6,15.6),(2.55,29.6,15.6)],stone,56)
# Two paths at a constant gentle grade meet the island from front and rear.
for side in (-1,1):
    ys=[side*k for k in (45,37,17,14.5)]
    zs=[.161,.80,2.50,2.58]
    vs=[]
    for y,z in zip(ys,zs):
        vs.extend([(-26.7,y,.15),(-21.3,y,.15),(-21.3,y,z),(-26.7,y,z)])
    loft("Tidal causeways",plaza,[vs[i:i+4] for i in range(0,len(vs),4)],stone)
    for x in (-26.35,-21.65):
        tube("Causeway inlay",plaza,[(x,y,z+.015) for y,z in zip(ys,zs)],.07,gold,5)
# Low, concentric water ripples are bronze survey lines let into the basin.
for rx,ry in [(46,26),(41,22),(36,18)]:
    for start,end in [(-75,73),(120,164),(195,233)]:
        angles=[radians(a) for a in range(start,end+1,4)]
        verts=[((rx+dr)*cos(a),(ry+dr)*sin(a),.88) for a in angles for dr in (-.10,.10)]
        add("Basin contour inlay",plaza,verts,[[i,i+1,i+3,i+2] for i in range(0,len(verts)-2,2)],gold)
for i in range(12):
    a=2*pi*(i+.5)/12
    x=59.5*cos(a);y=41.3*sin(a)
    group=empty("tide_light_%02d"%i,lot)
    levels=[(.16,.67),(.42,.67),(.54,.53),(.54,.31),(3.4,.19),(3.58,.49),(3.58,.39),(4.29,.49),(4.29,.61),(4.44,.57),(4.68,.12)]
    rings=[[(x+r*cos(j*pi/3),y+r*sin(j*pi/3),z) for j in range(6)] for z,r in levels]
    loft("Tide lantern %02d"%i,group,rings,lambda k,j: rock if k<3 else (deep if k<6 else (lamp if k==6 else gold)))
for i in range(6):
    a=radians([18,53,128,163,210,326][i]);x=57*cos(a);y=39*sin(a)
    group=empty("settle_%02d"%i,lot)
    for dx in (-1.3,1.3):block("Feet %d"%i,group,x+dx,y,.16,.42,.77,.37,rock)
    block("Seat %d"%i,group,x,y,.53,3.9,.93,.20,stone)
# Narrow clipped cypresses give the square a human-scale skyline at the back.
leaves=material("ground_cypress","cypress",0,.84)
for i,deg in enumerate([14,34,54,125,145,165,196,343]):
    a=radians(deg);x=60*cos(a);y=44*sin(a)
    group=empty("cypress_%02d"%i,lot)
    drum("Tree grate %d"%i,group,x,y,[(.16,1.38,1.38),(.26,1.38,1.38)],deep,8)
    drum("Tree stem %d"%i,group,x,y,[(.26,.24,.24),(2.0,.16,.16)],rock,6)
    crown_height=6.5+.4*(i%3)
    drum("Cypress foliage %d"%i,group,x,y,[(1.6,.48,.54),(2.2,1.18,1.22),(4.3,1.13,1.09),(crown_height-.8,.62,.55),(crown_height,.12,.12)],leaves,7)
# Cast dedication tablet sized to the original pixel lettering.
block("Inscription plinth",plaza,3,-40,.16,10.65,1.12,2.92,rock)
w=128*2*TEXEL;h=32*2*TEXEL
add("sign_name",plaza,[(3-w/2,-40.569,1.6-h/2),(3+w/2,-40.569,1.6-h/2),(3+w/2,-40.569,1.6+h/2),(3-w/2,-40.569,1.6+h/2)],[[0,1,2,3]],sign)
def bake_component(name,parent_name,data):
    verts,faces,mats,flags=data
    # Triangulate warped strips before assigning metric UVs. Flat caps may be ngons.
    flat=[];flat_m=[];flat_s=[]
    for f,m,s in zip(faces,mats,flags):
        triads=[f]
        if name=="Dorsal fin" and len(f)>4:
            triads=[[f[0],f[j],f[j+1]] for j in range(1,len(f)-1)]
        elif len(f)==4:
            triads=[[f[0],f[1],f[2]],[f[0],f[2],f[3]]]
        for t in triads:
            flat.append(t);flat_m.append(m);flat_s.append(s)
    me=bpy.data.meshes.new(name)
    me.from_pydata([v+V((0,CENTRE_Y,0)) for v in verts],[],flat)
    me.update()
    ob=bpy.data.objects.new(name,me)
    bpy.context.collection.objects.link(ob)
    ob.parent=bpy.data.objects[parent_name]
    slots=list(dict.fromkeys(flat_m))
    for m in slots:me.materials.append(m)
    uv=me.uv_layers.new(name="Physical pixel grid")
    for poly,m,smooth in zip(me.polygons,flat_m,flat_s):
        poly.material_index=slots.index(m)
        poly.use_smooth=smooth
        n=poly.normal
        ref=V((0,0,1)) if abs(n.z)<.94 else V((0,1,0))
        u=ref.cross(n).normalized()
        v=n.cross(u)
        image=next(node.image for node in m.node_tree.nodes if node.type=="TEX_IMAGE")
        sx,sy=image.size
        for li in poly.loop_indices:
            co=me.vertices[me.loops[li].vertex_index].co
            if m==sign:
                uv.data[li].uv=((co.x-3)/w+.5,(co.z-1.6)/h+.5)
            else:uv.data[li].uv=(u.dot(co)/(TEXEL*sx),v.dot(co)/(TEXEL*sy))
    return ob
obs=[bake_component(k[0],k[1],d) for k,d in batches.items()]
for ob in obs:
    ob.data.calc_loop_triangles()
tris=sum(len(ob.data.loop_triangles) for ob in obs)
min_z=min(v.co.z for ob in obs for v in ob.data.vertices)
height=max(v.co.z for ob in obs for v in ob.data.vertices)
assert min_z>=-.001,min_z
assert tris<=12000,tris
scene=bpy.context.scene
bpy.ops.object.select_all(action="SELECT")
glb=R/"inland-sea.glb"
bpy.ops.export_scene.gltf(filepath=str(glb),export_format="GLB",use_selection=True,export_yup=True,export_extras=True)
# Request nearest sampling at both scales, including on readers with glTF defaults.
raw=glb.read_bytes()
size=struct.unpack_from("<I",raw,12)[0]
g=json.loads(raw[20:20+size])
for s in g["samplers"]:s.update(magFilter=9728,minFilter=9728,wrapS=10497,wrapT=10497)
j=json.dumps(g,separators=(",",":")).encode()
j+=b" "*(-len(j)%4)
tail=raw[20+size:]
glb.write_bytes(struct.pack("<4sIIII",b"glTF",2,20+len(j)+len(tail),len(j),0x4e4f534a)+j+tail)
info={"name":"The Inland Sea","era":"Original civic commission suitable from the 1930s onward","units":"metres","axes":"glTF Y up, +Z front, origin at the front tangent of the promenade","footprint_m":[130,100],"height_m":round(height,3),"triangles_visible":tris,"materials":list(materials),"swatches_used":["pavement"],"texture_scale_m_per_texel":TEXEL,"texels_per_art_pixel":2,"bearing_tips_gltf_m":[[x,z,-y-CENTRE_Y] for x,y,z in bearings],"doors":[],"collision":{"use_the_walls":True,"notes":"Outdoor sculpture. Use mesh surfaces for curved piers, slopes, basin, seats and tablet. The approach across each causeway stays open. Open water needs the engine's water boundary."},"lot_node":"lot","sign_name":"Front tablet carries the dedication; replace its 256 x 64 texture to relabel.","runtime":"Static sculpture and water surface. Desktop lighting is presentation only. No in-game installation, moving water or engineering certification."}
(R/"model-info.json").write_text(json.dumps(info,indent=2)+"\n")
print("MODEL",tris,height,flush=True)
exec(compile((R/"render_views.py").read_text(),str(R/"render_views.py"),"exec"))
