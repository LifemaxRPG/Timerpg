"""Fitted sculptural features, workwear seams, lantern interior and public square."""
from mathutils.bvhtree import BVHTree
# Project the inlaid shapes onto the actual faceted stone; raised centres catch side light.
def fit_surface(ob,transform=None):
    verts=[v.co-V((0,Y0,0)) for v in ob.data.vertices]
    tree=BVHTree.FromPolygons(verts,[list(p.vertices) for p in ob.data.polygons])
    def point(x,z,relief=0):
        start=transform(x,-30,z) if transform else V((x,-30,z))
        along=(transform(x,-29,z)-start).normalized() if transform else V((0,1,0))
        hit=tree.ray_cast(start,along,80)[0]
        if hit is None:raise ValueError((ob.name,x,z))
        return hit-along*relief
    return point
face=fit_surface(bpy.data.objects['Cut limestone face'],head)
coat=fit_surface(bpy.data.objects['Work coat'])
def inlay(name,g,points,fit,m,relief=.06,peak=.07):
    rim=[fit(x,z,relief) for x,z in points]
    cx=sum(x for x,z in points)/len(points);cz=sum(z for x,z in points)/len(points)
    vs=rim+[fit(cx,cz,relief+peak)]
    return mesh(name,g,vs,[(len(rim),i,(i+1)%len(rim)) for i in range(len(rim))],m)
def face_line(name,path,amt,m):
    tube(name,figure,[face(x,z,d) for x,z,d in path],amt,m,5)
# Features are cut with long planes and a few high-contrast inlays for a clear downcast gaze.
for side in [-1,1]:
    def sym(coords):return [(side*x,z) for x,z in coords]
    inlay('Planed cheek',figure,sym([(.36,4.50),(1.48,4.79),(2.08,4.38),(1.72,3.76),(.7,3.82)]),face,skin,.04,.30)
    inlay('Inset eyelid',figure,sym([(.42,5.09),(.82,5.29),(1.42,5.32),(1.80,5.15),(1.42,4.95),(.79,4.93)]),face,iron,.16,.01)
    inlay('Ivory eye',figure,sym([(.53,5.10),(.91,5.19),(1.37,5.21),(1.59,5.14),(1.3,5.03),(.78,5.01)]),face,skin,.23,.015)
    inlay('Downcast pupil',figure,sym([(.84,5.17),(1.09,5.17),(1.08,5.00),(.86,5.00)]),face,iron,.27,.02)
    inlay('Carved brow',figure,sym([(.36,5.52),(.79,5.71),(1.50,5.70),(1.91,5.49),(1.73,5.38),(1.15,5.45),(.42,5.34)]),face,iron,.15,.20)
    hoval('Carved ear',[(side*2.43,.06,3.94,.33,.45),(side*2.58,.02,4.38,.46,.69),(side*2.58,.05,5.26,.49,.65),(side*2.46,.05,5.68,.38,.49)],skin,8)
    ear=[head(side*2.92,-.14,4.39),head(side*3,-.24,4.82),head(side*2.96,.03,5.17),head(side*2.86,.27,5.08)]
    tube('Ear channel',figure,ear,.08,gold,5)
    inlay('Sideburn',figure,sym([(2.00,6.23),(2.39,6.18),(2.42,5.3),(2.19,4.88),(2.04,5.1)]),face,iron,.10)
    inlay('Whisker wing',figure,sym([(.04,3.96),(.48,4.0),(.93,3.84),(1.45,3.56),(1.64,3.6),(1.42,3.27),(.96,3.3),(.54,3.48),(.04,3.52)]),face,iron,.26,.32)
# A wedge-shaped nose with a broad base and separate shadow below.
nose=[face(-.35,5.54,.06),face(.35,5.54,.06),face(.64,4.06,.08),face(-.63,4.06,.08),head(0,-3.16,4.4),head(0,-2.84,3.99)]
mesh('Cutwater nose',figure,nose,[(0,1,4),(1,2,4),(2,5,4),(2,3,5),(3,4,5),(0,4,3)],skin)
face_line('Lower lip',[(-.79,3.13,.09),(0,3.08,.20),(.79,3.13,.09)],.095,clay)
inlay('Chin plane',figure,[(-.96,2.80),(.96,2.80),(.72,2.14),(0,1.93),(-.71,2.15)],face,skin,.045,.18)
# Hair wraps around the back of the head, from behind one ear to the other.
rings=[]
for z,rx,ry in [(4.65,2.65,2.48),(5.12,2.73,2.54),(6.55,2.72,2.53)]:
    rings.append([head(rx*cos(radians(-10+j*200/12)),.03+ry*sin(radians(-10+j*200/12)),z+.12*sin(j*.8)) for j in range(13)])
mesh('Carved nape',figure,sum(rings,[]),[(k*13+j,k*13+j+1,(k+1)*13+j+1,(k+1)*13+j) for k in (0,1) for j in range(12)],iron)
# Cap piping and eight broad wedge seams meet at a covered button.
hoval('Cap piping',[(0,.07,6.89,2.85,2.61),(0,.07,7.01,2.85,2.61)],gold)
for i in range(8):
    a=(i+.5)*pi/4
    tube('Cap panel seam',figure,[head(-.12+3.20*cos(a),.18+2.92*sin(a),7.27),head(-.25+2.84*cos(a),.38+2.69*sin(a),7.95),head(-.14+1.94*cos(a),.43+1.84*sin(a),8.41),head(.47*cos(a),.40+.55*sin(a),8.58)],.06,iron,4)
hoval('Top button',[(0,.4,8.53,.52,.52),(0,.4,8.73,.41,.41)],blue,8)
# Large lapels and pocket flaps bring the same dark edging as the Gazette fascia up to skyline scale.
inlay('Shirt front',figure,[(-3.57,25.64),(1.95,25.72),(-.1,20.23),(-1.30,19.46)],coat,blue,.12,.17)
for poly in [[(-5.16,25.45),(-3.35,26.18),(-.98,21.08),(-3.55,22.44),(-3.00,23.20),(-5.16,24.29)],[(1.32,26.15),(3.71,25.43),(2.99,24.60),(2.11,24.38),(2.57,23.75),(-1.02,20.54)]]:
    inlay('Lapelled clay collar',figure,poly,coat,clay,.28,.35)
    tube('Collar welt',figure,[coat(x,z,.30) for x,z in poly[:3]],.13,gold,5)
inlay('Ochre scarf',figure,[(-2.82,26.25),(1.37,26.20),(.66,25.17),(-.09,24.62),(-1.20,25.15),(-2.30,25.04)],coat,gold,.61,.30)
inlay('Folded scarf end',figure,[(-.2,25.0),(.87,25.15),(.61,23.70),(-.35,22.10),(-1.05,22.57)],coat,gold,.49,.25)
inlay('Tabbed breast pocket',figure,[(-5.48,23.32),(-3.52,23.07),(-3.68,21.65),(-4.60,21.23),(-5.56,21.75)],coat,clay,.16,.13)
tube('Pocket top binding',figure,[coat(-5.5,23.3,.24),coat(-4.5,23.05,.28),coat(-3.52,23.07,.24)],.11,gold,5)
for z in (20.5,19.05,16.65,14.95):
    p=coat(-1.40,z,.29)
    oval('Horn coat button',figure,[(p.x,p.y,p.z-.25,.27,.14),(p.x,p.y-.06,p.z,.31,.17),(p.x,p.y,p.z+.25,.25,.13)],iron,8)
oval('Leather utility belt',figure,[(-2.23,3.47,17.53,5.37,4.14),(-2.1,3.29,18.37,5.47,4.19)],iron)
box('Buckle',figure,(-1.9,-.78,18.02),(1.41,.27,.95),gold)
box('Buckle inset',figure,(-1.9,-.94,18.02),(.94,.14,.53),iron)
# Cuffs follow the slope of each forearm. Fingers curl independently around the bail and knee.
for j in range(4):
    x=9.91+j*.64
    limb('Fingers on bail',[(x,-8.97,14.85),(x+.09,-9.60,14.33),(x+.14,-9.53,13.66),(x+.10,-9.0,13.46)],[(.36,.39),(.38,.41),(.32,.36),(.23,.29)],skin,7)
    x=-8.94+j*.69
    limb('Fingers on knee',[(x,-8.80,18.2),(x-.05,-9.44,17.63),(x+.03,-9.42,16.88+.13*j)],[(.39,.43),(.38,.40),(.26,.32)],skin,7)
limb('Carrying thumb',[(12.42,-8.35,14.93),(12.69,-8.86,14.25),(12.27,-9.26,13.94)],[(.53,.55),(.52,.52),(.36,.36)],skin,8)
limb('Resting thumb',[(-6.40,-8.12,18.30),(-6.18,-8.77,18.15),(-6.51,-9.18,17.66)],[(.47,.53),(.48,.5),(.35,.36)],skin,8)
# Stitching and patches are silhouettes of applied metal on the monumental workwear.
for ob,pts in [('Raised trouser leg',[(-8.4,16.0),(-6.55,16.6),(-5.82,15.44),(-6.43,14.15),(-8.02,13.96),(-8.79,14.82)])]:
    f=fit_surface(bpy.data.objects[ob]);inlay('Hammered knee patch',figure,pts,f,blue,.13,.17)
    tube('Patch edge',figure,[f(x,z,.15) for x,z in pts+[pts[0]]],.075,iron,5)
boot=fit_surface(bpy.data.objects['Forward boot'])
for z in (3.45,3.93,4.39,4.83):
    tube('Boot laces',figure,[boot(-8.1,z,.06),boot(-7,z+.13,.12),boot(-5.9,z,.06)],.095,gold,5)
# Architectural scale trim. The light at the ceiling is visible from within both portals.
for k in range(6):
    a=k*pi/3
    x,y=lx+radius*cos(a),ly+radius*sin(a)
    oval('Capital',lantern,[(x,y,5.99,.33,.33),(x,y,6.34,.48,.48),(x,y,6.59,.47,.47)],gold,8)
    b=(k+1)*pi/3
    for z,rr in [(7.21,radius+.60),(8.74,1.99)]:
        beam('Eave rim',lantern,(lx+rr*cos(a),ly+rr*sin(a),z),(lx+rr*cos(b),ly+rr*sin(b),z),(.11,.11),(.11,.11),gold,6)
    beam('Roof hip',lantern,(lx+(radius+.22)*cos(a),ly+(radius+.22)*sin(a),7.40),(lx+2.08*cos(a),ly+2.08*sin(a),8.65),(.09,.09),(.08,.08),gold,6)
for side in [-1,1]:
    beam('Bail lug',lantern,(lx+side*4.4,ly,7.45),(lx+side*4.4,ly,8.08),(.47,.5),(.43,.5),gold)
    # Upper roof breaks provide the mounting for the bail at each side.
    beam('Bail seat',lantern,(lx+side*3.75,ly,7.43),(lx+side*4.4,ly,7.67),(.34,.36),(.39,.4),iron)
lhex('Pendant mount',[(6.40,.52),(6.90,.72)],iron)
lhex('Interior lantern diffuser',[(5.62,.42),(5.95,.81),(6.44,.71)],gold)
for i in range(5):
    box('North pavilion stair',lantern,(lx,ly+5.99-i*.42,.12+(i+1)*.077),(3.95,.42,(i+1)*.154),skin)
# The paving motif repeats the six pointed lantern cap. The visitor walks along its central ray.
for x in (lx-2.34,lx+2.34):
    box('Path border',court,(x,-20.22,.128),(.22,9.53,.016),gold)
for j in range(6):
    y=-23.9+j*1.45
    for s in (-1,1):
        p=[(lx,y+.53,.143),(lx+s*1.65,y-.23,.143),(lx+s*1.65,y-.03,.143),(lx,y+.74,.143)]
        mesh('Chevron inlay',court,p,[(0,1,2,3)],skin)
box('Dedication tablet bed',court,(-4.4,-12.56,1.16),(11.2,1.01,2.08),brick)
box('Tablet coping',court,(-4.4,-12.56,2.26),(11.55,1.30,.18),skin)
w,h=128*.0776,16*.0776
plaque('sign_name',court,[(-4.4-w/2,-13.072,1.16-h/2),(-4.4+w/2,-13.072,1.16-h/2),(-4.4+w/2,-13.072,1.16+h/2),(-4.4-w/2,-13.072,1.16+h/2)],letter,(0,0,128,16),(128,16))
for x,y in [(-24,-15),(-24,3),(-23,16),(20,15),(24,3),(24,-16)]:
    g=group('square_tree',lot)
    terrace('Tree box',g,x,y,.12,3.3,3.3,.51,.42,brick)
    terrace('Tree box rim',g,x,y,.63,3.48,3.48,.19,.42,skin)
    oval('Trunk',g,[(x,y,.82,.32,.37),(x+.15,y,3.7,.23,.24)],wood,7)
    for dx,dy,dz,s in [(0,0,0,1),(-.83,.04,-.14,.72),(.73,.24,.23,.76)]:
        oval('Pollarded foliage',g,[(x+dx,y+dy,2.87+dz,.62*s,.73*s),(x+dx,y+dy,3.72+dz,1.60*s,1.56*s),(x+dx,y+dy,5.18+dz,1.41*s,1.47*s),(x+dx,y+dy,6.09+dz,.47*s,.51*s)],leaf,7)
for x,y in [(-14,-15),(1,-16),(-18,13),(13,15),(22,-5)]:
    g=group('public_settle',lot)
    for dx in [-1.2,1.2]:
        box('Bench standard',g,(x+dx,y,.42),(.32,.74,.60),iron)
        beam('Bench back standard',g,(x+dx,y+.2,.54),(x+dx,y+.4,1.49),(.105,.11),(.105,.11),iron)
    for dy in [-.28,0,.28]:box('Timber slat',g,(x,y+dy,.76),(3.32,.24,.16),wood)
    for z in [1.11,1.40]:box('Back slat',g,(x,y+.39,z),(3.32,.16,.23),wood)
# A divided back yoke and kick pleat complete the greatcoat on its rear approach.
reverse=lambda x,y,z:V((-x,12-y,z))
backfit=fit_surface(bpy.data.objects['Work coat'],reverse)
for side in [-1,1]:
    points=[(1+side*3.65,25.35),(1+side*3.8,24.32),(1+side*2.45,23.9),(1,23.63)]
    tube('Back yoke piping',figure,[backfit(x,z,.10) for x,z in points],.13,iron,5)
for sign_dir in [-1,1]:
    points=[(2.6+sign_dir*.86,17.42),(2.65+sign_dir*1.26,15.35),(2.7+sign_dir*1.94,13.47),(2.8+sign_dir*2.32,12.25)]
    tube('Kick pleat',figure,[backfit(x,z,.07) for x,z in points],.11,clay,5)
# The lamplighter's narrow service pouch hangs from the belt.
oval('Leather wick pouch',figure,[(2.3,3.0,14.3,1.10,.66),(2.62,3.0,14.8,1.38,.87),(2.77,3.0,17.41,1.25,.8),(2.67,3.0,17.68,1.11,.7)],iron,8)
beam('Wick pouch clasp',figure,(3.82,2.76,16.07),(3.94,2.76,16.83),(.21,.20),(.20,.18),gold,6)
# Dropped fanlights make a second, human-scale lintel. Their centre remains above head clearance.
for orientation in (-1,1):
    py=ly+orientation*radius*sin(pi/3)
    for sx in [-1,1]:
        a=(lx+sx*2.13,py,6.57);b=(lx+sx*2.13,py,4.30);c=(lx+sx*.63,py,6.52)
        mesh('Portal spandrel',lantern,[a,b,c],[(0,1,2)],gold)
        beam('Bracket shadow',lantern,b,c,(.14,.14),(.14,.14),iron,6)
    # A fixed enamel star is set on the exterior of each portal head.
    z=6.68;front=py+orientation*.43;vs=[(lx,front+orientation*.10,z)]
    for j in range(8):
        a=j*pi/4;r=.60 if j%2==0 else .23
        vs.append((lx+r*cos(a),front,z+r*sin(a)))
    mesh('Compass star',lantern,vs,[(0,1+j,1+(j+1)%8) for j in range(8)],gold)
