"""Junction Diner: original procedural low-poly architecture and original pixel art.
Run: blender -b --python build_diner.py -- [--draft]
Coordinates: metres; Z up, main door faces -Y. No downloaded/model-pack assets.
"""
import bpy, math, random, json, sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent
random.seed(1958)
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for data in list(bpy.data.materials):bpy.data.materials.remove(data)
COL={}
for name in ['Architecture','Interior','Signs','Roof equipment','Service yard','Street dressing','Presentation']:
 c=bpy.data.collections.new(name);bpy.context.scene.collection.children.link(c);COL[name]=c
group='Architecture'
def assign(o,name):
 o.name=name
 for c in list(o.users_collection):c.objects.unlink(o)
 COL[group].objects.link(o)
 return o
def mat(name,col,rough=.7,metal=0,emit=0,tex=None,alpha=1):
 m=bpy.data.materials.new(name);m.use_nodes=True;m.diffuse_color=(*col,alpha);n=m.node_tree.nodes.get('Principled BSDF');n.inputs['Base Color'].default_value=(*col,alpha);n.inputs['Roughness'].default_value=rough;n.inputs['Metallic'].default_value=metal;n.inputs['Alpha'].default_value=alpha
 n.inputs['Emission Color'].default_value=(*col,1);n.inputs['Emission Strength'].default_value=emit
 if tex:
  t=m.node_tree.nodes.new('ShaderNodeTexImage');t.image=bpy.data.images.load(str(R/'textures'/tex),check_existing=True);t.interpolation='Closest';t.extension='REPEAT';m.node_tree.links.new(t.outputs['Color'],n.inputs['Base Color'])
  if emit:m.node_tree.links.new(t.outputs['Color'],n.inputs['Emission Color'])
  if t.image.channels==4:m.node_tree.links.new(t.outputs['Alpha'],n.inputs['Alpha'])
 if alpha<1 or tex=='open.png':m.surface_render_method='DITHERED';m.use_transparency_overlap=False
 return m
teal=mat('01 • oxidised teal enamel',(.12,.36,.37),tex='teal.png',metal=.15)
cream=mat('02 • warm porcelain enamel',(.85,.8,.65),tex='cream.png',rough=.55)
red=mat('03 • oxblood vinyl',(.55,.12,.1),tex='red.png',rough=.45)
brick=mat('04 • kitchen brick',(.53,.3,.22),tex='brick.png')
roof=mat('05 • rolled roofing',(.23,.28,.3),tex='roof.png')
chrome=mat('06 • brushed aluminium',(.58,.68,.69),rough=.32,metal=.65,tex='metal.png')
dark=mat('07 • deep outline',(.065,.11,.14),rough=.65)
wood=mat('08 • weathered timber',(.35,.31,.23),tex='wood.png')
floor=mat('09 • checker linoleum',(.7,.75,.65),tex='floor.png')
paving=mat('10 • sidewalk concrete',(.55,.54,.49),tex='paving.png')
asphalt=mat('11 • patched road',(.19,.24,.28),tex='asphalt.png')
glass=mat('12 • thin teal glazing',(.24,.52,.56),rough=.18,metal=.16,alpha=.16)
yellow=mat('13 • aged ochre paint',(.7,.51,.19))
rust=mat('14 • copper rust',(.37,.19,.13))
ivory=mat('15 • ceramic',(.93,.86,.68),rough=.28)
coffee=mat('16 • coffee',(.12,.07,.035),rough=.28)
soil=mat('17 • soil',(.16,.15,.09))
green=mat('18 • leaves',(.22,.35,.23))
bulb=mat('19 • warm lamps',(.95,.64,.29),emit=3)
neon=mat('20 • coral neon',(.95,.2,.09),emit=3)
signmat=mat('21 • original JUNCTION pixel lettering',(1,1,1),tex='junction.png',emit=.12)
blademat=mat('22 • original DINER pixel lettering',(1,1,1),tex='blade.png',emit=.35)
strapmat=mat('23 • original menu strap',(1,1,1),tex='strap.png')
menumat=mat('24 • original blackboard',(1,1,1),tex='menu.png')
openmat=mat('25 • original OPEN LATE pixel glass lettering',(1,1,1),tex='open.png',emit=3)
postermat=mat('26 • original coffee poster',(1,1,1),tex='coffee.png')
def mesh(name,verts,faces,material,uv=False):
 me=bpy.data.meshes.new(name);me.from_pydata(verts,[],faces);me.update();o=bpy.data.objects.new(name,me);COL[group].objects.link(o);o.data.materials.append(material)
 lay=me.uv_layers.new(name='Pixel UV')
 for poly in me.polygons:
  axis=max(range(3),key=lambda i:abs(poly.normal[i]));axes=([1,2] if axis==0 else [0,2] if axis==1 else [0,1])
  for j,li in enumerate(poly.loop_indices):
   v=me.vertices[me.loops[li].vertex_index].co
   if uv:co=[(0,0),(1,0),(1,1),(0,1)][j%4]
   else:co=(v[axes[0]]*24/64,v[axes[1]]*24/64)
   lay.data[li].uv=co
 return o
def box(name,p,d,material,rot=0):
 x,y,z=p;w,h,t=d;vs=[]
 for a,b,c in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]:
  u,v=a*w/2,b*h/2;vs.append((x+u*math.cos(rot)-v*math.sin(rot),y+u*math.sin(rot)+v*math.cos(rot),z+c*t/2))
 return mesh(name,vs,[(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)],material)
def cyl(name,p,r,depth,material,verts=12):
 bpy.ops.mesh.primitive_cylinder_add(vertices=verts,radius=r,depth=depth,location=p);o=assign(bpy.context.object,name);o.data.materials.append(material);return o
def beam(name,a,b,r,material,verts=8):
 a,b=Vector(a),Vector(b);o=cyl(name,(a+b)/2,r,(b-a).length,material,verts);o.rotation_euler=(b-a).to_track_quat('Z','Y').to_euler();return o
def strip(name,a,b,z,h,depth,material):
 x=(a[0]+b[0])/2;y=(a[1]+b[1])/2;dx=b[0]-a[0];dy=b[1]-a[1];return box(name,(x,y,z),((dx*dx+dy*dy)**.5,depth,h),material,math.atan2(dy,dx))
def face(name,c,w,h,material,side='front'):
 x,y,z=c
 if side=='front':vs=[(x-w/2,y,z-h/2),(x+w/2,y,z-h/2),(x+w/2,y,z+h/2),(x-w/2,y,z+h/2)]
 elif side=='back':vs=[(x+w/2,y,z-h/2),(x-w/2,y,z-h/2),(x-w/2,y,z+h/2),(x+w/2,y,z+h/2)]
 elif side=='left':vs=[(x,y+w/2,z-h/2),(x,y-w/2,z-h/2),(x,y-w/2,z+h/2),(x,y+w/2,z+h/2)]
 else:vs=[(x,y-w/2,z-h/2),(x,y+w/2,z-h/2),(x,y+w/2,z+h/2),(x,y-w/2,z+h/2)]
 return mesh(name,vs,[(0,1,2,3)],material,True)
def extrude(name,pts,z0,z1,material):
 n=len(pts);vs=[(x,y,z) for z in (z0,z1) for x,y in pts];fs=[tuple(range(n-1,-1,-1)),tuple(range(n,n*2))]+[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)];return mesh(name,vs,fs,material)
def lamp(name,p,power=90,col=(1,.67,.32),size=.4):
 d=bpy.data.lights.new(name,'AREA');d.energy=power;d.color=col;d.shape='DISK';d.size=size;o=bpy.data.objects.new(name,d);COL['Presentation'].objects.link(o);o.location=p;return o
arc=[(-4.5+1.5*math.cos(math.pi+i*math.pi/16),-2.5+1.5*math.sin(math.pi+i*math.pi/16)) for i in range(9)]
outline=arc+[(6,-4),(6,4.4),(-6,4.4)]
extrude('Foundation — whole rounded footprint',outline,0,.22,dark)
extrude('Dining-room checker floor',outline,.22,.255,floor)
extrude('Roof slab — closed, with underside',outline,3.05,3.32,cream)
extrude('Roof weatherproofing — full top',outline,3.32,3.36,roof)
# Glazing modules wrap around the corner rather than a flat front card.
segments=list(zip(arc,arc[1:]))
front=[(-4.5,-4),(-2.6,-4),(-.7,-4),(1.2,-4),(2.3,-4),(3.8,-4),(6,-4)]
segments+=list(zip(front,front[1:]))
left=[(-6,y) for y in [-2.5,-.6,1.3,3.2,4.4]];segments+=list(zip(left,left[1:]))
right=[(6,y) for y in [-4,-1.8,.4,2.6,4.4]];segments+=list(zip(right,right[1:]))
for i,(a,b) in enumerate(segments):
 door=a==(2.3,-4) and b==(3.8,-4)
 if not door:
  strip('Ribbed enamel lower panel %02d'%i,a,b,.65,.79,.13,teal)
  strip('Aluminium kick rail %02d'%i,a,b,.31,.09,.16,chrome)
  strip('Porcelain window sill %02d'%i,a,b,1.12,.13,.19,cream)
  strip('Chrome drip mould %02d'%i,a,b,1.23,.045,.16,chrome)
  strip('Glazed pane %02d'%i,a,b,1.99,1.46,.018,glass)
 else:
  strip('Entrance glass',a,b,1.57,2.46,.018,glass)
  strip('Door lower rail',a,b,.49,.24,.085,chrome)
 strip('Cream upper fascia %02d'%i,a,b,2.91,.3,.15,cream)
 strip('Teal roof edge %02d'%i,a,b,3.2,.12,.2,teal)
 for u in [a]:box('Window mullion %02d'%i,(u[0],u[1],1.97),(.065,.065,1.67),chrome)
# Small geometry, actual door handle, entrance mat and gradual step.
box('Door centre stile',(3.05,-4.025,1.57),(.055,.07,2.45),chrome)
for x in (2.89,3.21):beam('Brass pull handle',(x,-4.11,1.25),(x,-4.11,1.63),.022,yellow)
box('Entrance threshold',(3.05,-4.2,.14),(1.78,.5,.2),chrome)
box('Entrance coir mat',(3.05,-4.75,.042),(1.55,.65,.05),wood)
box('Entrance step',(3.05,-4.6,.065),(2.3,1,.12),paving)
# The canopy is thick, ribbed underneath, and lit with real fixtures.
box('Cantilevered canopy',(0.15,-4.58,2.96),(11.7,1.25,.23),cream)
box('Canopy front teal band',(.15,-5.215,2.97),(11.73,.065,.2),teal)
for x in [-5+i*.62 for i in range(18)]:box('Canopy underside rib',(x,-4.58,2.823),(.035,1.15,.05),chrome)
for x in (-4.8,-2.4,0,2.4,4.8):
 box('Porch light housing',(x,-4.65,2.81),(.3,.25,.065),dark);box('Porch light diffuser',(x,-4.65,2.77),(.22,.17,.025),bulb);lamp('Porch pool',(x,-4.65,2.7),22,size=.35)
for a,b in zip(outline,outline[1:]+outline[:1]):beam('Roofline coral tube',(a[0],a[1],3.39),(b[0],b[1],3.39),.023,neon)
# Back kitchen built from separate walls around its actual door and service hatch.
box('Kitchen floor',(1.75,6.3,.2),(8.5,3.8,.35),paving)
box('Kitchen left wall',(-2.5,6.3,1.68),(.2,3.8,2.98),brick)
box('Kitchen right wall',(6,6.3,1.68),(.2,3.8,2.98),brick)
box('Kitchen rear left',(-.05,8.2,1.67),(4.9,.2,2.96),brick)
box('Kitchen rear right',(4.95,8.2,1.67),(2.1,.2,2.96),brick)
box('Kitchen rear door lintel',(3.15,8.2,2.76),(1.5,.2,.78),brick)
box('Kitchen service door',(3.15,8.25,1.25),(1.25,.11,2.2),teal)
face('Service-door glazed square',(3.15,8.315,1.75),.53,.47,glass,'back')
box('Service-door bar',(3.15,8.36,1.14),(.7,.055,.045),chrome)
box('Service-door lower kick plate',(3.15,8.315,.43),(1.05,.025,.35),chrome)
box('Kitchen rear step',(3.15,8.7,.13),(1.7,.9,.22),paving)
box('Kitchen roof',(1.75,6.3,3.24),(8.8,4.05,.24),cream)
box('Kitchen roof tar',(1.75,6.3,3.375),(8.4,3.65,.035),roof)
for x,w in [(-3.4,5.2),(5.1,1.8)]:box('Kitchen/dining partition',(x,4.4,1.65),(w,.15,2.9),cream)
box('Hatch bottom wall',(1.3,4.4,.65),(4.2,.16,.8),teal)
box('Hatch lintel',(1.3,4.4,2.82),(4.2,.16,.6),cream)
box('Serving hatch sill',(1.3,4.24,1.12),(4.4,.56,.1),chrome)
box('Pass-through heat lamp rail',(1.3,4.2,2.4),(3.8,.12,.1),dark)
for x in (.05,1.3,2.55):cyl('Heat-lamp shade',(x,4.14,2.27),.18,.12,chrome);cyl('Heat-lamp glow',(x,4.14,2.2),.13,.025,bulb)
box('Rear dining wall',(-4.25,4.4,1.65),(3.5,.16,2.9),teal)
# Original signs, backed and edged as three-dimensional objects.
group='Signs'
box('JUNCTION sign metal frame',(-.9,-4.18,3.83),(7.45,.26,1.12),chrome)
box('JUNCTION deep sign case',(-.9,-4.31,3.83),(7.25,.075,.96),dark)
face('JUNCTION original pixel art',(-.9,-4.355,3.83),7.17,.94,signmat)
for x in (-4.25,2.45):box('JUNCTION support',(x,-4.05,3.48),(.09,.1,.55),dark)
box('Menu strap frame',(-.9,-5.25,2.97),(7.9,.08,.39),chrome)
face('COFFEE PIE HOT MEALS front strap',(-.9,-5.3,2.97),7.86,.35,strapmat)
# Two-sided blade, the back and its support are finished too.
box('Vertical blade outline',(5.48,-4.32,4.95),(1.2,.35,3.92),chrome)
box('Vertical blade enamel case',(5.48,-4.33,4.95),(1.07,.34,3.77),red)
face('DINER blade front',(5.48,-4.515,4.95),1.04,3.7,blademat)
face('DINER blade reverse',(5.48,-4.145,4.95),1.04,3.7,blademat,'back')
for z in (3.65,5.75):beam('Blade stand-off bracket',(5.46,-3.6,z),(5.46,-4.1,z),.065,dark)
beam('Blade crown neon',(4.93,-4.55,6.88),(6.03,-4.55,6.88),.026,neon)
face('OPEN LATE window lettering',(-1.65,-4.025,1.79),1.75,.66,openmat)
box('Coffee poster case',(4.95,-4.09,1.97),(.72,.08,1.02),chrome)
face('Original coffee illustration',(4.95,-4.14,1.97),.64,.9,postermat)
# Dining room: real booths, chrome-edged tables, stools and visible service tools.
group='Interior'
for j,x in enumerate((-3.9,-1.55,.8)):
 for k,dx in enumerate((-.78,.78)):
  box('Booth %s base %s'%(j,k),(x+dx,-2.12,.47),(.55,1.55,.44),dark)
  box('Booth %s seat %s'%(j,k),(x+dx,-2.12,.72),(.63,1.64,.16),red)
  box('Booth %s upholstered back %s'%(j,k),(x+dx+(-.22 if dx<0 else .22),-2.12,1.04),(.16,1.66,.77),red)
  box('Booth %s back piping %s'%(j,k),(x+dx+(-.22 if dx<0 else .22),-2.12,1.43),(.18,1.68,.035),cream)
  for y in (-2.68,-2.12,-1.56):box('Booth stitched panel seam',(x+dx+(-.31 if dx<0 else .31),y,1.07),(.012,.025,.56),rust)
 box('Booth table chrome rim',(x,-2.12,.99),(.9,1.52,.1),chrome)
 box('Booth table laminate',(x,-2.12,1.05),(.85,1.47,.035),ivory)
 cyl('Booth table pedestal',(x,-2.12,.65),.06,.65,chrome)
 box('Booth table foot',(x,-2.12,.32),(.6,.75,.06),chrome)
 box('Table napkin dispenser',(x,-1.63,1.19),(.16,.12,.24),chrome)
 box('Paper napkin',(x,-1.7,1.28),(.115,.016,.11),ivory)
 cyl('Ketchup bottle',(x+.2,-1.64,1.18),.038,.22,red,8);cyl('Ketchup cap',(x+.2,-1.64,1.3),.03,.04,ivory,8)
 cyl('White coffee cup',(x+.16,-2.46,1.12),.07,.13,ivory);cyl('Coffee surface',(x+.16,-2.46,1.19),.054,.005,coffee)
beam('Counter footrail',(-4.5,1.15,.5),(4.9,1.15,.5),.035,chrome)
box('Long counter enamel body',(.15,2.05,.85),(9.6,1.3,1.22),teal)
box('Counter chrome nosing',(.15,1.35,1.45),(9.75,.18,.12),chrome)
box('Cream counter top',(.15,2.02,1.5),(9.78,1.5,.09),ivory)
for x in (-4,-2.65,-1.3,.05,1.4,2.75,4.1):
 cyl('Stool foot',(x,.65,.3),.24,.08,chrome);cyl('Stool pedestal',(x,.65,.68),.055,.75,chrome)
 cyl('Stool dark seat rim',(x,.65,1.07),.27,.08,dark);cyl('Stool red upholstered seat',(x,.65,1.14),.25,.1,red)
 box('Stool seat stitch',(x,.65,1.195),(.32,.018,.008),rust)
# Coffee urn, ceramic cups, cake dome and register — no painted fake interior.
cyl('Coffee urn',(3.62,2.12,1.88),.2,.62,chrome,12);cyl('Coffee urn lid',(3.62,2.12,2.23),.22,.08,chrome)
beam('Coffee spigot',(3.62,1.9,1.74),(3.62,1.78,1.74),.03,dark);cyl('Urn lid knob',(3.62,2.12,2.31),.05,.08,dark)
for x in (2.8,3.02,3.24):
 for z in (1.6,1.73):cyl('Stacked clean coffee cups',(x,2.08,z),.075,.11,ivory)
box('Cash register body',(4.4,2.02,1.73),(.45,.38,.34),chrome);box('Register keypad',(4.4,1.8,1.66),(.36,.06,.1),dark)
for x in (4.29,4.4,4.51):
 for z in (1.64,1.7):box('Register cream key',(x,1.76,z),(.05,.025,.035),ivory)
cyl('Pie stand',(-2.9,2,1.61),.31,.06,chrome);cyl('Fresh pie',(-2.9,2,1.72),.24,.15,yellow)
for a in range(0,180,45):beam('Pie lattice',(-3.09,1.88+a/900,1.8),(-2.71,1.88+a/900,1.8),.014,ivory,4)
face('Interior daily menu',(-4.1,4.29,2.1),1.3,.65,menumat)
box('Kitchen prep table',(1.2,6.45,1.1),(4.2,.85,.15),chrome)
for x in (-.5,2.9):box('Prep table leg',(x,6.45,.65),(.07,.65,.9),chrome)
box('Kitchen range',(4.25,6.8,.75),(1.4,1.1,1.05),chrome)
for x in (3.92,4.55):
 for y in (6.55,7.05):cyl('Stove hob',(x,y,1.29),.18,.035,dark)
box('Kitchen hood',(4.25,6.8,2.55),(1.8,1.35,.35),chrome)
for x in (-3.8,-1.3,1.3,3.8):
 box('Dining ceiling lamp',(x,-.1,2.98),(.5,.5,.07),bulb);lamp('Dining warm pool',(x,-.1,2.87),65,size=.8)
lamp('Kitchen working lamp',(1.8,6.2,2.85),80,size=1.2)
# A roof and back worth looking at.
group='Roof equipment'
box('Galvanised roof exhaust plinth',(4.25,6.8,3.55),(1.05,1.05,.32),dark)
box('Galvanised exhaust trunk',(4.25,6.8,4),( .74,.74,.7),chrome)
box('Exhaust weather hood',(4.25,6.8,4.43),(1.25,1.25,.18),chrome)
for z in (3.84,4.02,4.2):box('Exhaust louvre',(4.25,6.4,z),(.65,.06,.055),dark)
box('Rooftop refrigeration unit',(-.7,6.3,3.71),(1.9,1.05,.66),chrome)
for x in [-1.5+i*.13 for i in range(13)]:box('Condenser grille',(x,5.75,3.71),(.035,.025,.44),dark)
for x,y in [(-4.7,3.1),(5.1,2.9)]:
 cyl('Vent pipe',(x,y,3.7),.09,.7,chrome);cyl('Vent mushroom cap',(x,y,4.08),.17,.08,chrome)
for z in [0.5+i*.33 for i in range(11)]:beam('Service ladder rung',(-1.8,8.55,z),(-1.25,8.55,z),.025,chrome)
for x in (-1.8,-1.25):beam('Service ladder rail',(x,8.55,.3),(x,8.55,4.03),.032,chrome)
group='Service yard'
box('Rear door rain hood',(3.15,8.63,2.62),(1.9,.95,.09),teal)
box('Back door lamp case',(4.08,8.34,2.28),(.24,.16,.32),dark);box('Back door lamp diffuser',(4.08,8.44,2.28),(.14,.04,.2),bulb)
rear=lamp('Back door light',(4.08,8.7,2.5),45,size=.3);rear.rotation_euler=(math.radians(35),0,0)
box('Electric meter cabinet',(6.18,6.1,1.55),(.2,.64,.75),dark)
cyl('Water meter face',(6.3,6.1,1.72),.15,.07,chrome).rotation_euler[1]=math.pi/2
beam('Conduit',(6.13,6.1,1.95),(6.13,6.1,3.13),.025,chrome)
for x in (4.8,5.6):
 cyl('Rear bin',(x,9.35,.56),.31,.9,chrome);cyl('Bin lid',(x,9.35,1.05),.35,.08,dark)
 for a in range(0,360,45):
  ra=math.radians(a);beam('Bin raised rib',(x+.315*math.cos(ra),9.35+.315*math.sin(ra),.2),(x+.315*math.cos(ra),9.35+.315*math.sin(ra),.94),.01,dark,4)
for x,y in [(-.5,8.95),(.25,9.1)]:
 box('Milk crate',(x,y,.42),(.6,.5,.48),teal)
 for dx in (-.18,0,.18):
  for dy in (-.12,.12):cyl('Returnable milk bottle',(x+dx,y+dy,.71),.055,.4,ivory,8);cyl('Bottle neck',(x+dx,y+dy,.94),.028,.1,ivory,8)
box('Rear drain grate',(2.5,9.6,.03),(1.1,.45,.03),dark)
for x in [2+i*.12 for i in range(9)]:box('Drain metal bar',(x,9.6,.052),(.035,.42,.015),chrome)
# Original lot dressing, parking stripes, a small terrace, and bicycle.
group='Street dressing'
box('Concrete diner apron',(0,2,-.13),(17,21,.25),paving)
box('Main Street road',(0,-10,-.21),(60,7,.22),asphalt)
box('Raised curb',(0,-6.55,-.04),(60,.18,.25),cream)
for x in range(-28,29,5):box('Road centre dash',(x,-11.2,-.088),(2.3,.12,.012),yellow)
for x in (-7.4,7.4):
 for y in (-5,0,5,10):box('Apron expansion joint',(x,y,.004),(.018,4.85,.012),dark)
for y in (-5.8,9.9):box('Apron cross joint',(0,y,.006),(16.8,.02,.012),dark)
for x in (-5.7,-2.7,.3):box('Parking bay stripe',(x,-5.72,.012),(.07,1.45,.016),ivory)
for x,y in [(-7,-3.7),(7,-3.7)]:
 box('Concrete planter',(x,y,.34),(.8,.8,.6),cream);box('Planter soil',(x,y,.65),(.67,.67,.03),soil)
 for a in range(0,360,60):
  th=math.radians(a);beam('Planter green stem',(x,y,.66),(x+.2*math.cos(th),y+.2*math.sin(th),1.02),.045,green,5)
  box('Small ochre flower',(x+.2*math.cos(th),y+.2*math.sin(th),1.03),(.12,.12,.12),yellow)
for x in (-6.9,7.5):
 beam('Rear yard fence post',(x,10.8,0),(x,10.8,1.25),.055,wood)
for y in [5+i*.55 for i in range(11)]:box('Service fence board',(7.55,y,.66),(.08,.43,1.2),wood)
for z in (.35,1.03):box('Fence backing rail',(7.48,7.75,z),(.08,6,.08),wood)
# Bench in front of the rounded end, formed of individual slats.
for y in (-5.3,-4.85):beam('Bench cast leg',(-6.05,y,.04),(-6.05,y,.49),.04,dark)
for x in (-6.55,-5.55):
 for y in (-5.3,-4.85):beam('Bench foot',(x,y,.05),(x,y,.51),.035,dark)
for y in (-5.3,-5.12,-4.94):box('Bench seat slat',(-6.05,y,.55),(1.45,.13,.055),wood)
for z in (.78,1.02):box('Bench back slat',(-6.05,-4.79,z),(1.45,.07,.14),wood)
# Bicycle: 12-sided real wheels, spokes, frame, handlebar and saddle.
def ring(name,center,r,material):
 x,y,z=center;ps=[(x+r*math.cos(a*math.pi/8),y,z+r*math.sin(a*math.pi/8)) for a in range(16)]
 for a,b in zip(ps,ps[1:]+ps[:1]):beam(name,a,b,.032,material,6)
for x in (4.4,5.75):
 ring('Bicycle tyre',(x,-5.67,.42),.35,dark)
 for a in range(0,360,45):t=math.radians(a);beam('Bicycle spoke',(x,-5.67,.42),(x+.32*math.cos(t),-5.67,.42+.32*math.sin(t)),.006,chrome,4)
for a,b in [((4.4,.42),(4.95,.85)),((4.95,.85),(5.25,.42)),((5.25,.42),(4.4,.42)),((4.95,.85),(5.55,.87)),((5.55,.87),(5.25,.42)),((5.55,.87),(5.75,.42))]:beam('Bicycle teal frame',(a[0],-5.67,a[1]),(b[0],-5.67,b[1]),.025,teal)
box('Bicycle saddle',(4.93,-5.67,.95),(.24,.15,.07),rust);beam('Bicycle handlebars',(5.55,-5.67,.87),(5.51,-5.67,1.1),.02,chrome);beam('Bicycle grip bar',(5.51,-5.86,1.1),(5.51,-5.48,1.1),.025,dark)
# Presentation is deliberately separate from the exportable building.
group='Presentation'
box('Surrounding ground',(0,0,-.35),(200,200,.25),mat('Backdrop • dusty sage',(.25,.31,.3)))
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=48;scene.cycles.use_denoising=False
if '--eevee' in sys.argv:
 scene.render.engine='BLENDER_EEVEE_NEXT'
 scene.eevee.taa_render_samples=96
scene.render.resolution_x=1200;scene.render.resolution_y=900;scene.render.resolution_percentage=100
scene.render.image_settings.file_format='PNG';scene.render.film_transparent=False
scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast';scene.view_settings.exposure=0;scene.view_settings.gamma=1
world=bpy.data.worlds.new('Diner sky');scene.world=world;world.use_nodes=True
bg=world.node_tree.nodes.get('Background')
sun_data=bpy.data.lights.new('Afternoon sun','SUN');sun=bpy.data.objects.new('Afternoon sun',sun_data);COL['Presentation'].objects.link(sun);sun.rotation_euler=(math.radians(28),math.radians(-25),math.radians(-30));sun_data.angle=math.radians(4)
bpy.ops.object.camera_add();cam=assign(bpy.context.object,'Preview camera');scene.camera=cam;cam.data.type='ORTHO';cam.data.lens=42
scene.use_nodes=True;nodes=scene.node_tree.nodes;nodes.clear();rl=nodes.new('CompositorNodeRLayers');glow=nodes.new('CompositorNodeGlare');glow.glare_type='FOG_GLOW';glow.quality='MEDIUM';glow.threshold=1.5;glow.size=7;out=nodes.new('CompositorNodeComposite');scene.node_tree.links.new(rl.outputs['Image'],glow.inputs['Image']);scene.node_tree.links.new(glow.outputs['Image'],out.inputs['Image'])
def shot(name,pos,target,scale,night=False):
 cam.location=pos;cam.rotation_euler=(Vector(target)-cam.location).to_track_quat('-Z','Y').to_euler();cam.data.ortho_scale=scale
 bg.inputs[0].default_value=(*( (.13,.19,.28) if night else (.58,.69,.72)),1);bg.inputs[1].default_value=.28 if night else .65
 sun_data.energy=.35 if night else 2;sun_data.color=(.47,.62,1) if night else (1,.87,.69)
 for l in bpy.data.lights:
  if l.type=='AREA':l.energy=l.get('base_energy',l.energy);l['base_energy']=l.energy;l.energy=l['base_energy']*(1 if night else .35)
 scene.render.filepath=str(R/'renders'/f'{name}.png')
 if name=='01-day-front':bpy.ops.wm.save_as_mainfile(filepath=str(R/'junction-diner.blend'))
 bpy.ops.render.render(write_still=True)
# Store full source and a portable real mesh. The ground backdrop, camera and lights are not exported.
for im in bpy.data.images:
 if im.source=='FILE':im.pack()
bpy.ops.wm.save_as_mainfile(filepath=str(R/'junction-diner.blend'))
stats={'name':'Junction Diner','source':'Original procedural geometry and original pixel textures; no third-party models or sprite art.','renderer':'Blender Cycles desktop prototype, not an in-game/S25 capture.','metres':{'width':12,'main_depth':8.4,'total_depth':12.2,'roof_height':3.4,'sign_height':6.91},'objects':sum(1 for o in bpy.data.objects if o.type=='MESH'),'triangles':sum(sum(len(p.vertices)-2 for p in o.data.polygons) for o in bpy.data.objects if o.type=='MESH'),'materials':len(bpy.data.materials),'texture_pixels':sum(i.size[0]*i.size[1] for i in bpy.data.images if i.source=='FILE'),'nearest_textures':True}
(R/'export'/'model-info.json').write_text(json.dumps(stats,indent=2)+'\n')
sys.path.insert(0,str(R))
from export_model import export_model
export_model(R)
draft='--draft' in sys.argv
if draft:scene.cycles.samples=24;scene.render.resolution_x=1000;scene.render.resolution_y=750
shot('01-day-front',(-15,-20,13),(0,1.2,2),23)
if not draft:
 shot('02-blue-hour',(-15,-20,11),(0,1.2,2),23,True)
 shot('03-rear-service',(18,21,14),(0,3,1.8),23)
 if '--portfolio' not in sys.argv:shot('04-street-eye',(-12,-24,6),(0,.5,2),22,True)
 cam.data.type='PERSP';cam.data.lens=23
 shot('05-counter-detail',(-4.8,-3.38,2.18),(.9,1.75,1.3),12,True)
 cam.data.type='ORTHO'
 if '--portfolio' not in sys.argv:shot('06-overhead',(-12,-17,25),(0,2,1.1),23)
print(json.dumps(stats,indent=2))
