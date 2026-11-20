"""Original four-state Lincoln campus. Dimensions below are whole 0.0776m art units."""
import bpy,math,json,sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;U=.0776;T=.0388;F=8
STATE=next((a.split('=')[1] for a in sys.argv if a.startswith('--state=')),'1925');assert STATE in ['1925','1955','1975','2000'];ERA=int(STATE);POST=ERA>=1955;CONS=ERA>=1975;NEW=ERA>=2000
REG=json.loads((R/'art-regions.json').read_text());COLL=[];LIGHTS=[];PROPS=[];DOORS=[];ROOMS=[];INST={};GLOW_AREA=0
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
def empty(n,p=None):
 o=bpy.data.objects.new(n,None);bpy.context.collection.objects.link(o);o.parent=p;return o
ROOT=empty('lincoln_high_school');LOT=empty('lot',ROOT);CORE=empty('school_1925',ROOT);WING=empty('modern_wing',ROOT) if POST else None;GYM=empty('gymnasium',ROOT) if POST else None;LINK=empty('cafeteria_link',ROOT) if POST else None;SEC=empty('security_entry',ROOT) if NEW else None;GROUPS=[ROOT,LOT,CORE]+([WING,GYM,LINK] if POST else [])+([SEC] if NEW else []);parent=CORE

def mat(n,f,emission=False,alpha=False,tint=False):
 m=bpy.data.materials.new(n);m.use_nodes=True;p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Roughness'].default_value=.83;t=m.node_tree.nodes.new('ShaderNodeTexImage');t.image=bpy.data.images.load(str(R/'textures'/f),check_existing=True);t.interpolation='Closest';t.extension='REPEAT';m['texture_file']=f
 if tint:
  c=m.node_tree.nodes.new('ShaderNodeVertexColor');c.layer_name='Tint';mix=m.node_tree.nodes.new('ShaderNodeMixRGB');mix.blend_type='MULTIPLY';mix.inputs[0].default_value=1;m.node_tree.links.new(t.outputs['Color'],mix.inputs[1]);m.node_tree.links.new(c.outputs['Color'],mix.inputs[2]);m.node_tree.links.new(mix.outputs[0],p.inputs['Base Color'])
 else:m.node_tree.links.new(t.outputs['Color'],p.inputs['Base Color'])
 if alpha:m.node_tree.links.new(t.outputs['Alpha'],p.inputs['Alpha']);m.surface_render_method='DITHERED'
 if emission:
  e=t
  if alpha:e=m.node_tree.nodes.new('ShaderNodeTexImage');e.image=bpy.data.images.load(str(R/'textures/glass-emission.png'),check_existing=True);e.interpolation='Closest';e.extension='REPEAT'
  m.node_tree.links.new(e.outputs['Color'],p.inputs['Emission Color']);p.inputs['Emission Strength'].default_value=.02
 return m
wall=mat('wall_brick','brick.png');stone=mat('wall_stone','stone.png');plaster=mat('wall_interior','plaster.png');roof=mat('roof_membrane','roof.png');metal=mat('detail_metal','metal.png');wood=mat('detail_wood','wood.png');grass=mat('ground_grass','grass.png');sport=mat('ground_sport','sport.png',tint=True);glass=mat('glass_windows','glass.png',True,True);glow=mat('glow_lamps','glow.png',True);sign=mat('sign_name','signs.png');utility=mat('detail_panels','panels.png')
exec(compile((R/'mesh_helpers.py').read_text(),str(R/'mesh_helpers.py'),'exec'))
def B(n,p,s,m=wall):return box(n,p,s,m)
def P(n,p,w,h,key,m=glass,side='front'):return panel(n,p,w,h,key,m,side)
def IP(n,p,w,h,key,m=glass,side='front'):
 k=(key,m.name,side)
 if k not in INST:
  o=P(n,(0,0,0),w,h,key,m,side);INST[k]=o
 else:o=bpy.data.objects.new(n,INST[k].data);bpy.context.collection.objects.link(o);o.parent=parent
 o.location=Vector(p)*U;o['linked_panel']=True;return o
COLORS={'soil':(.49,.35,.23,1),'asphalt':(.30,.36,.36,1),'chalk':(.94,.92,.79,1),'turf':(.35,.49,.31,1),'turf_light':(.43,.55,.36,1),'track':(.52,.31,.24,1),'court':(.76,.59,.34,1),'blue':(.24,.38,.42,1)}
def tint(o,color):
 a=o.data.color_attributes.new(name='Tint',type='FLOAT_COLOR',domain='CORNER');c=COLORS.get(color,color)
 for v in a.data:v.color=c
 return o
def slab(n,x,y,w,d,z=1,color='asphalt'):
 return tint(B(n,(x,y,z/2),(w,d,z),sport),color)
def plane(n,xy,z,m=grass,color=None):
 o=mesh(n,[(x*U,y*U,z*U) for x,y in xy],[tuple(range(len(xy)))],m)
 if color:tint(o,color)
 return o
def beam(n,a,b,w=1,d=None,m=metal):
 a=Vector(a)*U;b=Vector(b)*U;t=(b-a).normalized();ref=Vector((0,0,1)) if abs(t.z)<.9 else Vector((1,0,0));e=t.cross(ref).normalized()*w*U/2;v=t.cross(e).normalized()*(d or w)*U/2
 return mesh(n,[tuple(p+x*e+y*v) for p in [a,b] for x,y in [(-1,-1),(1,-1),(1,1),(-1,1)]],[(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)],m)
def block(n,x,y,w,d,top,bottom=0,attachment=None,group=None):COLL.append({'source':n,'center':[x*U,(top+bottom)*U/2,-y*U],'size':[w*U,(top-bottom)*U,d*U],'support_floor_m':bottom*U,'attachment':attachment or parent.name,'group':group or ('lot' if parent in PROPS else 'structure')})
def doorway(n,x,y,w=24,h=40,f=F,side='front',external=True):
 yaw={'front':0,'back':math.pi,'left':-math.pi/2,'right':math.pi/2}[side];o=door(n,(x,y,f),yaw);o['exterior']=external
 DOORS.append({'node':n,'position':[x*U,f*U,-y*U],'outward':{'front':[0,0,1],'back':[0,0,-1],'left':[-1,0,0],'right':[1,0,0]}[side],'width_m':w*U,'height_m':h*U,'floor_m':f*U,'external':external,'open':True})
def wallrun(n,a,b,z0,z1,fixed,holes=[],axis='x',m=wall,inside=True):
 coords=sorted(set([a,b]+[v for h in holes for v in h[:2] if a<v<b]))
 for l,r in zip(coords,coords[1:]):
  cut=sorted(set([z0,z1]+[v for h in holes if h[0]<(l+r)/2<h[1] for v in h[2:] if z0<v<z1]))
  for lo,hi in zip(cut,cut[1:]):
   if any(h[0]<(l+r)/2<h[1] and h[2]<(lo+hi)/2<h[3] for h in holes):continue
   o=B(n,((l+r)/2,fixed,(lo+hi)/2) if axis=='x' else (fixed,(l+r)/2,(lo+hi)/2),(r-l,2,hi-lo) if axis=='x' else (2,r-l,hi-lo),m)
   if inside:
    o.data.materials.append(plaster);f=o.data.polygons[(3 if inside is True else 1) if axis=='x' else (2 if inside is True else 4)];f.material_index=1
    old=bpy.data.images.get(m['texture_file']).size;new=bpy.data.images.get(plaster['texture_file']).size
    for li in f.loop_indices:o.data.uv_layers.active.data[li].uv*=Vector((old[0]/new[0],old[1]/new[1]))
def win(n,p,key='class',side='front',shrink=False):
 x,y,z=p;w,h=REG[key]['rect_art'][2:]
 if shrink:
  # Reduced 1970s pane inside the unchanged 1925 masonry aperture.
  sw,sh=32,22
  for dx in [-18,18]:B('Energy infill side',(x+dx,y,z),(4,1,38),wood)
  for dz in [-15,15]:B('Energy infill band',(x,y,z+dz),(32,1,8),wood)
  IP(n,(x,y-0.2 if side=='front' else y+0.2,z),sw,sh,'small',glass,side)
 else:IP(n,p,w,h,key,glass,side)
 B('Window stone sill',(x,y,z-h/2-1),(w+4,4,2) if side in ['front','back'] else (4,w+4,2),stone)
 B('Window lintel',(x,y,z+h/2+1),(w+2,3,2) if side in ['front','back'] else (3,w+2,2),stone)
def room(n,box,f=F,notes='Unfurnished shell'):
 ROOMS.append({'id':n,'bounds_source_art':box,'floor_m':f*U,'notes':notes})
def prop(n):
 global parent
 parent=lot_prop(n);GROUPS.append(parent);return parent

def fence(n,x0,x1,y,gates=[],height=28):
 # Mesh alpha panels plus full-height metadata segments; gate gaps stay open.
 spans=[];cursor=x0
 for a,b in sorted(gates):
  if cursor<a:spans.append((cursor,a))
  cursor=b
 if cursor<x1:spans.append((cursor,x1))
 for a,b in spans:
  steps=max(1,math.ceil((b-a)/120));w=(b-a)/steps
  for i in range(steps):
   x=a+(i+.5)*w;o=P(n,(x,y,height/2),128,80,'net',glass)
   # Crop the texture UV to the actual physical panel, never stretch texels.
   for v in o.data.vertices:v.co.x=x*U+(v.co.x-x*U)*w/128;v.co.z=height*U/2+(v.co.z-height*U/2)*height/80
   W,H=bpy.data.images.get('glass.png').size
   rr=REG['net']['rect_art']
   for li,uv in zip(o.data.polygons[0].loop_indices,[(rr[0]*2/W,1-(rr[1]+height)*2/H),((rr[0]+w)*2/W,1-(rr[1]+height)*2/H),((rr[0]+w)*2/W,1-rr[1]*2/H),(rr[0]*2/W,1-rr[1]*2/H)]):o.data.uv_layers.active.data[li].uv=uv
   B('Fence upright',(a+i*w,y,height/2),(1.5,1.5,height),metal)
  B('Fence end post',(b,y,height/2),(1.5,1.5,height),metal);beam('Fence top rail',(a,y,height),(b,y,height),1,m=metal);block(n,(a+b)/2,y,b-a,2,height)
def ramp(n,x0,x1,y0,y1,high,along='y'):
 if along=='y':vs=[(x0,y0,0),(x1,y0,0),(x1,y1,high),(x0,y1,high),(x0,y1,0),(x1,y1,0)]
 else:vs=[(x0,y0,0),(x1,y0,high),(x1,y1,high),(x0,y1,0),(x1,y0,0),(x1,y1,0)]
 faces=[(0,1,2,3),(0,4,5,1),(3,2,5,4),(1,5,2),(0,3,4)] if along=='y' else [(0,1,2,3),(0,3,5,4),(4,5,2,1),(0,4,1),(3,2,5)]
 return mesh(n,[tuple(c*U for c in p) for p in vs],faces,stone)
def lamp(n,x,y,z,w=10,d=6,power=60):
 global GLOW_AREA
 B(n+' mount',(x,y,z+.6),(w+2,d+2,1),metal);mesh(n+' lens',[(xx*U,yy*U,z*U) for xx,yy in [(x-w/2,y+d/2),(x+w/2,y+d/2),(x+w/2,y-d/2),(x-w/2,y-d/2)]],[(0,1,2,3)],glow);GLOW_AREA+=w*d*U*U;light(n,(x,y,z-1),power,max(w,d))

# Campus ground uses a single consistent lot through all four states.
parent=LOT;B('Campus lawn',(0,1030,.2),(3000,2060,.4),grass)
# Original school mass, full three-storey shell.
parent=CORE;B('Ground floor',(0,224,4),(520,208,8),stone)
for z in [68,128]:
 # First upper slab leaves a real stairwell, upper access is gated at the landing.
 if z==68:
  B('Upper slab west',(-144,224,z+1),(232,208,2),plaster);B('Upper slab east',(144,224,z+1),(232,208,2),plaster);B('Upper slab front',(0,183,z+1),(56,126,2),plaster)
 else:B('Third-storey slab',(0,224,z+1),(520,208,2),plaster)
B('School roof',(0,224,189),(524,212,2),roof)
xs=[-224,-160,-96,96,160,224];zs=[37,97,157]
front_holes=[[x-20,x+20,z-19,z+19] for x in xs for z in zs]+[[-18,18,8,52]]
rear_holes=[[x-20,x+20,z-19,z+19] for x in xs for z in zs]+[[-18,18,8,48]]
wallrun('Original front',-260,260,0,188,120,front_holes)
wallrun('Original rear',-260,260,0,188,328,rear_holes,inside='back')
for x in xs:
 for z in zs:
  win('Classroom front',(x,118.8,z),shrink=CONS and not NEW and x in [-160,160]);win('Classroom rear',(x,329.2,z),'class','back')
for x,side,inner in [(-260,'left',True),(260,'right','back')]:
 holes=[[y-20,y+20,z-19,z+19] for y in [160,288] for z in zs]
 if POST:holes.append([208,248,8,48])
 wallrun('Original side',120,328,0,188,x,holes,'y',inside=inner)
 for y in [160,288]:
  for z in zs:win('Classroom side',(x+(-1.2 if x<0 else 1.2),y,z),'class',side)
for z in [10,66,126,184]:
 B('Stone horizontal belt front',(0,118,z),(524,4,3),stone);B('Stone horizontal belt rear',(0,330,z),(524,4,3),stone)
 for x in [-261,261]:
  if z==10 and POST:
   for a,b in [(118,208),(248,330)]:B('Stone side belt',(x,(a+b)/2,z),(4,b-a,3),stone)
  else:B('Stone side belt',(x,224,z),(4,212,3),stone)
# Door apertures are protected from the lower stone belt.
# Cut centre belt pieces instead of spanning the entrance at foot level.
for o in list(CORE.children):
 if o.name.startswith('Stone horizontal belt') and abs(o.data.vertices[0].co.z/U-8.5)<.01:
  bpy.data.objects.remove(o,do_unlink=True)
for y in [118,330]:
 for a,b in [(-262,-18),(18,262)]:B('Plinth beside entry',((a+b)/2,y,10),(b-a,4,3),stone)
for x,y,w,d in [(-261,224,3,212),(261,224,3,212),(0,329,524,3),(0,119,524,3)]:B('Brick parapet',(x,y,197),(w,d,14),wall);B('Coping',(x,y,205),(w+2 if d==3 else 5,5 if d==3 else d,2),stone)
# Projecting entrance tower, open through to the original ground floor.
B('Tower floor',(0,110,4),(144,24,8),stone)
wallrun('Tower front',-72,72,0,222,100,[[-18,18,8,52],[-16,16,76,126],[-16,16,148,198]])
for x in [-72,72]:B('Tower return',(x,110,111),(2,20,222),wall);B('Tower stone pier',(x,98,111),(6,5,222),stone)
for z in [101,173]:win('Tower window',(0,98.8,z),'tower')
for x in [-24,24]:B('Entrance stone jamb',(x,97,31),(8,6,46),stone)
B('Entrance lintel',(0,97,56),(56,6,8),stone);B('Tower cornice',(0,110,223),(152,28,4),stone);B('Tower copper cap',(0,110,227),(156,32,4),metal)
B('Tower stepped crest',(0,110,232),(82,24,6),wall);B('Tower crest coping',(0,110,236),(88,28,2),stone)
P('Datestone',(0,96.8,66),28,10,'date',utility)
if not CONS:P('sign_name',(0,98.6,141),128,12,'historic_name',sign)
else:B('Cleaned historic frieze',(0,98.6,141),(128,.4,12),stone)
doorway('door_main',0,98,36,44,external=not NEW);doorway('door_back',0,330,36,40,side='back')
for i in range(4):B('Original entry steps',(0,84+i*4,(i+1)),(64,4,2*(i+1)),stone)
# Clean ground-floor rooms, open corridor and built-in lockers.
room('corridor',[-258,198,258,242],notes='Long east-west circulation; wall lockers and built-in empty trophy case only.')
for y in [196,244]:
 holes=[]
 for x in [-208,-132,132,208]:holes.append([x-7,x+7,8,40])
 holes.append([-28,28,8,64]);wallrun('Corridor partition',-258,258,8,66,y,holes,m=plaster,inside=False)
 for x in [-208,-132,132,208]:doorway('door_room_'+('front' if y==196 else 'rear')+'_'+str(x),x,y+(-2 if y==244 else 2),14,32,side='back' if y==196 else 'front',external=False)
 for x in [-248,-236,-188,-176,-164,-116,-104,-92,92,104,116,164,176,188,236,248]:
  B('Locker backing',(x,y+(1.2 if y==196 else -1.2),23),(12,3,30),metal);IP('Built-in lockers',(x,y+(2.8 if y==196 else -2.8),23),12,30,'lockers',utility,'back' if y==196 else 'front')
for x in [-170,170]:
 for y,d in [(158,72),(286,80)]:wallrun('Classroom divider',y-d/2,y+d/2,8,66,x,[],'y',m=plaster,inside=False)
for y0,y1,side in [(122,194,'front'),(246,326,'rear')]:
 for a,b in [(-258,-170),(-170,-32),(32,170),(170,258)]:
  name='office' if side=='front' and a==-170 else side+'_classroom_'+str(a)
  room(name,[a,y0,b,y1],notes='Unfurnished office shell.' if name=='office' else 'Unfurnished classroom shell; game supplies loose furniture and occupants.')
for x in [-32,32]:wallrun('Entry hall side',120,196,8,66,x,[],'y',m=plaster,inside=False)
# Empty fixed trophy case recessed along the entrance hall.
B('Trophy case back',(-30,164,31),(3,50,26),wood);B('Trophy case plinth',(-24,164,18),(12,54,4),wood);B('Trophy case head',(-24,164,44),(12,54,4),wood);IP('Trophy case glass',(-18,164,31),48,24,'case',glass,'right')
# A dog-leg stair: 30 risers at .1552 m, each tread .3104 m.
for i in range(15):B('First stair flight',(-14,250+i*4,8+(i+1)),(24,4,2*(i+1)),stone)
B('Stair landing',(0,316,23),(56,12,30),stone)
for i in range(15):B('Return stair flight',(14,308-i*4,38+(i+1)),(24,4,2*(i+1)),stone)
for x in [-27,27]:beam('Stair rail',(x,248,20),(x,310,50),1,m=metal)
B('Upper access rail',(14,247,77),(26,2,18),metal);block('Upper floor closed access',14,247,28,2,86,68)
room('stairwell',[-28,246,28,326],notes='Real dog-leg stair to first landing; upper floors closed pending navigation/furnishing integration.')
B('Corridor ceiling west',(-144,224,66),(232,208,2),plaster);B('Corridor ceiling east',(144,224,66),(232,208,2),plaster);B('Entry ceiling',(0,173,66),(64,146,2),plaster)
for x in [-210,-126,-42,42,126,210]:lamp('Corridor fixture',x,220,64,10,6,90)
for x in [-36,36]:B('Entrance lantern bracket',(x,96,41),(6,4,10),metal);B('Entrance lantern',(x,93,41),(4,2,8),glow);GLOW_AREA+=2*(4*8+4*2+2*8)*U*U;light('Entry lantern',(x,89,40),45,8)
# Roof/service details are seated on their curbs; no floating rooftop pieces.
for x,y in [(-180,284),(182,280)]:
 B('Vent curb',(x,y,191),(26,28,2),stone);B('Roof ventilator',(x,y,201),(24,26,18),metal);IP('Vent louvers',(x,y-13.2,201),24,16,'vent',utility);B('Vent lid',(x,y,211),(28,30,2),metal)
B('Service chimney',(-220,316,212),(20,18,44),wall);B('Chimney cap',(-220,316,235),(24,22,2),stone)
for x in [-263,263]:beam('Downpipe',(x,326,202),(x,326,0),1,m=metal)
# Later additions have their own coherent shells and open entrances.
if POST:
 parent=LINK;B('Cafeteria link floor',(-290,230,4),(60,60,8),stone);B('Link roof',(-290,230,63),(64,64,2),roof)
 for y in [200,260]:wallrun('Link wall',-320,-260,8,62,y,[[-310,-270,24,48]],inside=y==200);IP('Cafeteria window',(-290,y+(-1.2 if y==200 else 1.2),36),40,38,'class',glass,'front' if y==200 else 'back')
 room('cafeteria_link',[-320,200,-260,260],notes='Unfurnished connecting shell, open between core and gym.')
 parent=WING;B('Wing floor',(480,250,4),(440,200,8),stone);B('Wing ceiling',(480,250,61),(440,200,2),plaster);B('Wing flat roof',(480,250,65),(444,204,6),roof)
 for y in [150,350]:
  holes=[[x-48,x+48,24,48] for x in [326,436,546,656]]
  if y==150:holes=[h for h in holes if h[0]>370]+[[298,334,8,48]]
  wallrun('Modern wing wall',260,700,8,60,y,holes,inside=y==150)
  for x in ([436,546,656] if y==150 else [326,436,546,656]):IP('Ribbon classroom window',(x,y+(-1.2 if y==150 else 1.2),36),96,24,'ribbon',glass,'front' if y==150 else 'back')
 wallrun('Wing east',150,350,8,60,700,[],'y',inside='back');wallrun('Wing west',150,350,8,60,260,[[208,248,8,48]],'y')
 doorway('door_wing',316,148,36,40);room('modern_classrooms',[262,152,698,348],notes='Unfurnished wing shell with open connection to original school.')
 for x in [390,500,610]:wallrun('Wing room divider',230,348,8,60,x,[],'y',m=plaster,inside=False)
 wallrun('Wing corridor partition',262,698,8,60,230,[[x-8,x+8,8,40] for x in [350,445,555,660]],m=plaster,inside=False)
 parent=GYM;B('Gym floor',(-500,305,4),(360,310,8),wood)
 wallrun('Gym front',-680,-320,8,96,150,[[-518,-482,8,48],[-624,-576,36,86],[-424,-376,36,86]])
 for x in [-600,-400]:win('Gym tall window',(x,148.8,61),'gym')
 wallrun('Gym back',-680,-320,8,96,460,[[-518,-482,8,48]],inside='back')
 wallrun('Gym west',150,460,8,96,-680,[],'y');wallrun('Gym east',150,460,8,96,-320,[[208,248,8,48]],'y',inside='back')
 # Twelve real bowstring roof facets, with closed end gables.
 arc=[(-500+180*math.cos(i*math.pi/12),96+34*math.sin(i*math.pi/12)) for i in range(13)]
 for (a,za),(b,zb) in zip(arc,arc[1:]):mesh('Bowstring roof',[(a*U,149*U,za*U),(a*U,461*U,za*U),(b*U,461*U,zb*U),(b*U,149*U,zb*U)],[(0,1,2,3)],roof)
 for y in [150,460]:mesh('Gym end gable',[(x*U,y*U,z*U) for x,z in arc],[tuple(range(len(arc)))],wall)
 doorway('door_gym',-500,148,36,40);doorway('door_gym_back',-500,462,36,40,side='back');room('gym',[-678,152,-322,458],notes='Court lines, fixed folding bleachers and stage; no loose sports equipment.')
 # Textured court surface and proper geometric line markings.
 plane('Court',[(-650,220),(-350,220),(-350,400),(-650,400)],8.15,sport,'court')
 for y in [220,400]:plane('Court sideline',[(-650,y-.5),(-350,y-.5),(-350,y+.5),(-650,y+.5)],8.3,sport,'blue')
 for x in [-650,-350,-500]:plane('Court end/centre line',[(x-.5,220),(x+.5,220),(x+.5,400),(x-.5,400)],8.3,sport,'blue')
 for cx in [-650,-350]:
  for yy in [270,350]:plane('Key line',[(cx,yy),(cx+(60 if cx< -500 else -60),yy),(cx+(60 if cx< -500 else -60),yy+1),(cx,yy+1)],8.35,sport,'blue')
 for cx in [-590,-410]:plane('Free throw line',[(cx-.5,270),(cx+.5,270),(cx+.5,351),(cx-.5,351)],8.35,sport,'blue')
 for i in range(12):
  a=i*math.tau/12;b=(i+1)*math.tau/12;plane('Centre circle',[(-500+30*math.cos(a),310+30*math.sin(a)),(-500+31*math.cos(a),310+31*math.sin(a)),(-500+31*math.cos(b),310+31*math.sin(b)),(-500+30*math.cos(b),310+30*math.sin(b))],8.35,sport,'blue')
 # Fixed wall-mounted backboards and eight-sided rims, without loose sports props.
 for x,toward in [(-662,1),(-338,-1)]:
  beam('Basketball wall bracket',(x-18*toward,310,55),(x,310,55),2,m=metal)
  IP('Basketball backboard',(x,310,52),24,14,'backboard',utility,'right' if toward>0 else 'left')
  cx=x+4*toward;verts=[((cx+r*math.cos(i*math.tau/8))*U,(310+r*math.sin(i*math.tau/8))*U,48*U) for r in [2.6,3.2] for i in range(8)]
  mesh('Basketball rim',verts,[(i,(i+1)%8,(i+1)%8+8,i+8) for i in range(8)],wood)
  beam('Rim mount',(x,310,48),(cx,310,48),.8,m=metal)
  for i in range(8):
   a=i*math.tau/8;beam('Hoop net wire',(cx+3*math.cos(a),310+3*math.sin(a),48),(cx+2*math.cos(a),310+2*math.sin(a),43),.25,m=plaster)
 B('Gym stage',(-500,444,11),(220,28,6),wood)
 for i in range(5):B('Fixed folded bleacher row',(-500,180+i*5,10+i*2),(240,4,2+i*2),wood)
 block('Fixed gym bleachers',-500,190,242,28,22,8);block('Stage',-500,444,220,28,14,8)
 for x in [-590,-410]:
  for y in [240,365]:beam('Gym light suspension',(x,y,96+34*math.sqrt(.75)),(x,y,94),.6,m=metal);lamp('Gym light',x,y,94,14,8,900)
# Exterior landings and four modest risers match each raised floor.
parent=CORE;B('Rear entry landing',(0,337,4),(48,14,8),stone)
for i in range(4):B('Rear entry step',(0,358-i*4,i+1),(48,4,2*(i+1)),stone)
if POST:
 for g,x in [(WING,316),(GYM,-500)]:
  parent=g;B('Entry landing',(x,145,4),(48,10,8),stone)
  for i in range(4):B('Entry approach step',(x,126+i*4,i+1),(48,4,2*(i+1)),stone)

if POST:
 parent=GYM;B('Gym rear landing',(-500,467,4),(48,10,8),stone)
 for i in range(4):B('Gym rear step',(-500,492-i*4,i+1),(48,4,2*(i+1)),stone)
# Front campus walks, pole and later bus circuit.
parent=LOT
if POST:
 slab('Bus loop',0,72,1460,84,1,'asphalt');B('Bus loop lawn',(0,72,1.05),(1200,38,.1),grass)
 for x in [-680,680]:plane('Bus direction arrow',[(x-8,60),(x+8,60),(x,78)],1.15,sport,'chalk')
# Long walks over the lawn are only slightly proud, with crossings above loop paving.
slab('Main walk',0,43,48,86,1.5,'chalk');slab('Front terrace',0,105,156,10,2,'chalk')
prop('flagpole');cylinder('Flagpole',(-310,80,48),1,96,metal,None,8);B('Flagpole base',(-310,80,3),(12,12,6),stone);block('Flagpole',-310,80,12,12,96)
# Plain fictional school pennant, no national symbols or real logos.
mesh('School pennant',[(-310*U,80*U,90*U),(-270*U,80*U,88*U),(-310*U,80*U,72*U)],[(0,1,2)],wood)
# Regulation 90ft base paths; compact town outfield is not claimed regulation.
prop('baseball_diamond');hx,hy=-1150,650;diamond=[(hx,hy),(hx+250,hy+250),(hx,hy+500),(hx-250,hy+250)]
plane('Dirt infield',[(hx,hy-40),(hx+290,hy+250),(hx,hy+540),(hx-290,hy+250)],.65,sport,'soil')
for (a,b),(c,d) in zip(diamond,diamond[1:]+diamond[:1]):
 dx,dy=c-a,d-b;le=math.hypot(dx,dy);nx,ny=-dy/le,dx/le;plane('Base path chalk',[(a+nx,b+ny),(c+nx,d+ny),(c-nx,d-ny),(a-nx,b-ny)],.8,sport,'chalk')
for x,y in diamond:B('Base bag',(x,y,1),(5,5,.6),stone)
cylinder('Pitch mound',(hx,hy+238,1),22,1.2,sport,None,12);tint(bpy.context.collection.objects.get('Pitch mound'),'soil');B('Pitch rubber',(hx,hy+238,1.8),(8,2,.4),stone)
# Backstop uses wood framing and a non-emissive mesh cut from the glass atlas.
prop('wooden_backstop');fence('Backstop',hx-120,hx+120,hy-45,[],64)
for x in [hx-120,hx,hx+120]:B('Backstop timber post',(x,hy-45,32),(4,4,64),wood)
for z in [24,62]:beam('Backstop timber rail',(hx-120,hy-45,z),(hx+120,hy-45,z),3,m=wood)
if POST:
 prop('football_field');x0,x1,y0,y1=-320,1094,800,1430;cy=(y0+y1)/2
 plane('Football grass',[(x0,y0),(x1,y0),(x1,y1),(x0,y1)],.7,grass if not NEW else sport,'turf' if NEW else None)
 if NEW:
  # Five lanes around an approximately 400m inner oval, in actual geometry.
  for lane in range(5):
   ri=475+lane*15;ro=ri+15
   for x in [-157,931]:
    start=math.pi/2 if x<0 else -math.pi/2
    for i in range(12):
     a=start+i*math.pi/12;b=start+(i+1)*math.pi/12
     plane('Running track curve',[(x+ri*math.cos(a),cy+ri*math.sin(a)),(x+ro*math.cos(a),cy+ro*math.sin(a)),(x+ro*math.cos(b),cy+ro*math.sin(b)),(x+ri*math.cos(b),cy+ri*math.sin(b))],.75,sport,'track')
     plane('Track lane boundary',[(x+(ro-1)*math.cos(a),cy+(ro-1)*math.sin(a)),(x+ro*math.cos(a),cy+ro*math.sin(a)),(x+ro*math.cos(b),cy+ro*math.sin(b)),(x+(ro-1)*math.cos(b),cy+(ro-1)*math.sin(b))],.85,sport,'chalk')
   for sy in [-1,1]:
    plane('Running track straight',[(-157,cy+sy*ri),(931,cy+sy*ri),(931,cy+sy*ro),(-157,cy+sy*ro)],.75,sport,'track');plane('Track straight boundary',[(-157,cy+sy*(ro-1)),(931,cy+sy*(ro-1)),(931,cy+sy*ro),(-157,cy+sy*ro)],.85,sport,'chalk')
  for i in range(12):plane('Turf mowing band',[(x0+i*(x1-x0)/12,y0),(x0+(i+1)*(x1-x0)/12,y0),(x0+(i+1)*(x1-x0)/12,y1),(x0+i*(x1-x0)/12,y1)],.8,sport,'turf_light' if i%2 else 'turf')
 for i in range(13):
  x=x0+i*(x1-x0)/12;plane('Ten yard stripe',[(x-.65,y0),(x+.65,y0),(x+.65,y1),(x-.65,y1)],.95,sport,'chalk')
 for y in [y0,y1]:plane('Football touchline',[(x0,y-.65),(x1,y-.65),(x1,y+.65),(x0,y+.65)],.95,sport,'chalk')
 for i in range(1,100):
  x=x0+(i+10)*(x1-x0)/120
  for y in [cy-70,cy+70]:plane('Yard hash',[(x-.5,y-3),(x+.5,y-3),(x+.5,y+3),(x-.5,y+3)],.95,sport,'chalk')
 # Period goalposts are outside the playing endlines.
 for x in [x0-6,x1+6]:
  prop('goalpost');beam('Goal mast',(x,cy,0),(x,cy,40),2,m=metal);beam('Goal crossbar',(x,cy-36,40),(x,cy+36,40),2,m=metal)
  for y in [cy-36,cy+36]:beam('Goal upright',(x,y,40),(x,y,90),1.5,m=metal)
  block('Goalpost',x,cy,4,4,90)
 # One coherent eight-row grandstand, outside the future track footprint.
 prop('steel_bleachers' if CONS else 'wood_bleachers');bm=metal if CONS else wood
 for i in range(8):
  y=1720+i*10;z=5+i*4;B('Bleacher tread',(360,y,z),(760,9,2),bm);B('Bench plank',(360,y+2,z+5),(752,3,2),bm)
  for x in [10,240,480,710]:B('Grandstand support',(x,y,z/2),(3,3,z),bm)
 for x in [-20,740]:beam('Grandstand raker',(x,1715,2),(x,1795,35),3,m=bm)
 for y in [1715,1798]:
  beam('Grandstand guardrail',(-20,y,48),(740,y,48),1.5,m=metal)
  for x in [-20,240,480,740]:B('Grandstand rail post',(x,y,24),(1.5,1.5,48),metal)
 block('Grandstand',360,1757,768,90,50)
 if CONS:
  B('Press platform',(360,1780,55),(128,52,2),metal)
  for xx in [302,418]:
   for yy in [1758,1802]:B('Press box leg',(xx,yy,27.5),(3,3,55),metal)
  B('Press box',(360,1780,70),(128,52,30),plaster);B('Press box roof',(360,1780,86),(136,60,2),roof);IP('Press window',(360,1753.8,72),80,20,'press',glass);block('Press box',360,1780,136,60,87)
 for x in [-100,850]:
  for y in [540,1695]:
   prop('field_light');cylinder('Light pole',(x,y,100),1.2,200,metal,None,8);beam('Light crossarm',(x-18,y,194),(x+18,y,194),2,m=metal)
   for xx in [-15,-5,5,15]:
    B('Floodlight case',(x+xx,y,195),(8,6,6),metal)
    mesh('Floodlight face',[((x+xx+a)*U,(y+(-3.1 if y>1000 else 3.1))*U,(195+b)*U) for a,b in [(-3.5,-2.5),(3.5,-2.5),(3.5,2.5),(-3.5,2.5)]],[(0,1,2,3)],glow);GLOW_AREA+=7*5*U*U
   block('Field light pole',x,y,40,8,200)
   # Staged light pools, not exported gameplay illumination.
   lo=light('Field pool',(x,y,190),1100,70);lo.rotation_euler=(.3 if y>1000 else -.3,0,0)
 prop('field_fence');fence('Field fence',-750,1490,530,[(-20,30),(720,780)],30);fence('Field fence',-750,1490,1820,[(300,350)],30)
 # End fences use the same gate-capable pattern rotated as a group.
 for x in [-750,1490]:
  for y in [600,900,1200,1500,1800]:
   B('Field end fence upright',(x,y,15),(1.5,1.5,30),metal)
  for a,b in [(530,1050),(1100,1820)]:
   beam('End fence top rail',(x,a,30),(x,b,30),1,m=metal);block('Field end fence',x,(a+b)/2,2,b-a,30)
   segments=math.ceil((b-a)/120);width=(b-a)/segments
   for i in range(segments):
    y=a+(i+.5)*width;o=P('Field end net',(x,y,15),128,80,'net',glass,'right')
    for v in o.data.vertices:v.co.y=y*U+(v.co.y-y*U)*width/128;v.co.z=15*U+(v.co.z-15*U)*30/80
    W,H=bpy.data.images.get('glass.png').size;rr=REG['net']['rect_art']
    for li,uv in zip(o.data.polygons[0].loop_indices,[(rr[0]*2/W,1-(rr[1]+30)*2/H),((rr[0]+width)*2/W,1-(rr[1]+30)*2/H),((rr[0]+width)*2/W,1-rr[1]*2/H),(rr[0]*2/W,1-rr[1]*2/H)]):o.data.uv_layers.active.data[li].uv=uv
 # Car park is geometry and stripes, not spawned vehicles.
 if CONS:
  prop('student_parking');slab('Student parking',1080,270,680,240,1,'asphalt')
  for x in range(760,1401,32):
   for y in [184,356]:plane('Parking stripe',[(x,y-32),(x+1,y-32),(x+1,y+32),(x,y+32)],1.15,sport,'chalk')
  for y in [148,392]:B('Parking kerb',(1080,y,2),(686,4,4),stone)
# Three portable classrooms appear only in the consolidation state.
if CONS and not NEW:
 for i,cx in enumerate([-170,30,230],1):
  prop('portable_'+str(i));B('Portable foundation',(cx,450,2.5),(140,90,5),stone);B('Portable floor',(cx,450,5.5),(140,90,1),wood);B('Portable ceiling',(cx,450,42),(140,90,2),plaster);B('Portable roof',(cx,450,44),(146,96,2),roof)
  wallrun('Portable front',cx-70,cx+70,6,41,405,[[cx-10,cx+10,6,38],[cx-57,cx-29,17,35],[cx+29,cx+57,17,35]],m=plaster)
  wallrun('Portable rear',cx-70,cx+70,6,41,495,[],m=plaster,inside='back')
  for x in [cx-70,cx+70]:wallrun('Portable end',405,495,6,41,x,[],'y',m=plaster,inside=False)
  for x in [cx-43,cx+43]:IP('Portable window',(x,403.8,26),28,18,'portable',glass)
  doorway('door_portable_'+str(i),cx,403,20,32,f=6);ramp('Portable ramp',cx-10,cx+10,333,405,6)
  for x in [cx-11,cx+11]:beam('Portable handrail',(x,333,12),(x,405,18),.8,m=metal)
  room('portable_'+str(i),[cx-69,406,cx+69,494],f=6,notes='Unfurnished raised shell; 1:12 geometric ramp, no runtime accessibility certification.')
# New name is one active repaintable region, moved to a road monument in 1975.
if CONS:
 prop('monument_sign');B('Brick monument',(230,25,16),(156,12,32),wall);B('Monument cap',(230,25,33),(164,18,2),stone);P('sign_name',(230,18.8,18),144,18,'modern_name',sign);block('Monument sign',230,25,164,18,34)
# Glass-and-brick secure vestibule. Original door_main is retained inside it.
if NEW:
 parent=SEC;B('Vestibule floor',(0,84,4),(100,32,8),stone);B('Vestibule roof',(0,85,65),(104,38,4),roof)
 for x in [-47,47]:
  if x<0:
   for y in [72,98]:B('Vestibule brick pier',(x,y,35),(4,4,54),wall)
  else:B('Vestibule brick pier',(x,85,35),(4,30,54),wall)
 for x in [-32,32]:IP('Security front glass',(x,68.8,29),28,42,'security_front',glass)
 IP('Security transom',(0,68.8,57),96,14,'security_transom',glass)
 for x in [-48,48]:
  # Side entry on left is an open 20-art-pixel doorway at ramp landing.
  if x<0:
   B('Accessible side jamb',(x,72,35),(2,4,54),metal);B('Accessible side header',(x,85,57),(2,30,14),metal)
  else:IP('Vestibule side glass',(x+1.2,85,36),30,56,'security_side',glass,'right')
 for x in [-18,18]:B('Security entry jamb',(x,68.5,30),(2,3,44),metal)
 doorway('door_security',0,68,34,42);doorway('door_access',-50,85,20,40,side='left');ramp('Accessible ramp',-146,-50,75,95,8,'x')
 for y in [74,96]:
  beam('Accessible ramp handrail',(-146,y,12),(-50,y,20),1,m=metal)
  for x in [-146,-98,-50]:B('Ramp handrail post',(x,y,6+(x+146)/12/2),(1,1,12+(x+146)/12),metal)
 # Remove old forward steps now enclosed by the new vestibule; add proper new approach.
 for o in list(CORE.children):
  if o.name.startswith('Original entry steps'):bpy.data.objects.remove(o,do_unlink=True)
 for i in range(4):B('Renovated front steps',(0,54+i*4,i+1),(48,4,2*(i+1)),stone)
 room('secure_vestibule',[-46,70,46,100],notes='Open glass entrance shell and accessible side ramp. No security behavior implemented.')
 prop('plain_scoreboard');B('Scoreboard face backing',(1090,1750,79),(104,6,40),metal);P('Plain scoreboard',(1090,1746.8,79),96,32,'scoreboard',utility)
 for x in [1054,1126]:B('Scoreboard support',(x,1750,37),(4,4,74),metal)
 block('Scoreboard',1090,1750,104,8,100)
# Discreet rear service bins, separate drop-able lot nodes.
for i,x in enumerate([-235,-221]):
 prop('service_bin_'+str(i));B('Service bin',(x,346,7),(10,10,14),metal);B('Bin lid',(x,346,14.5),(12,12,1),metal);block('Service bin',x,346,12,12,15)

# Collision companion: exact separate boxes, already in the glTF frame in metadata.
parent=ROOT;vs=[];fs=[]
for b in COLL:
 x,z,ny=b['center'];w,h,d=b['size'];y=-ny;k=len(vs);vs.extend([(x+a*w/2,y+c*d/2,z+s*h/2) for a,c,s in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]]);fs.extend([tuple(k+j for j in f) for f in [(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)]])
collision=mesh('collision',vs,fs,metal);collision.hide_render=True;collision['collision_only']=True;collision['render']=False
for g in GROUPS:
 mats={}
 for o in list(g.children):
  if o.type=='MESH' and o.name not in ['collision','sign_name'] and not o.get('linked_panel'):mats.setdefault(o.data.materials[0].name,[]).append(o)
 for mn,obs in mats.items():
  bpy.ops.object.select_all(action='DESELECT')
  for o in obs:o.select_set(True)
  bpy.context.view_layer.objects.active=obs[0]
  if len(obs)>1:bpy.ops.object.join()
  obs[0].name=g.name+'__'+mn
model=[ROOT]+list(ROOT.children_recursive)
def count(objects):return sum(sum(len(p.vertices)-2 for p in o.data.polygons) for o in objects if o.type=='MESH' and o!=collision)
tris=count(model);mats=sorted({m.name for o in model if o.type=='MESH' for m in o.data.materials});assert tris<=15000 and len(mats)<=12,(tris,len(mats));assert GLOW_AREA<15,GLOW_AREA
for im in bpy.data.images:
 if im.source=='FILE':im.pack()
def export(objects,path):
 bpy.ops.object.select_all(action='DESELECT')
 for o in objects:o.select_set(True)
 bpy.ops.export_scene.gltf(filepath=str(path),use_selection=True,export_format='GLB',export_yup=True,export_extras=True,export_vertex_color='ACTIVE')
export(model,R/('lincoln-high-school-'+STATE+'.glb'))
part_tris={g.name:count(g.children_recursive) for g in GROUPS if g not in [ROOT,LOT]};part_tris['lot_total']=count(LOT.children_recursive)
info={'name':'Lincoln Consolidated' if CONS else 'Lincoln High School','state':STATE,'file':'lincoln-high-school-'+STATE+'.glb','years':[ERA,{'1925':1954,'1955':1974,'1975':1999,'2000':2030}[STATE]],'units':'metres','axes':'glTF Y up, +Z street front','origin':'Grade at middle of the campus front street edge; all four states use identical lot/origin.','campus_m':[3000*U,2060*U],'original_school_m':[520*U,208*U],'hall_floor_m':F*U,'original_roof_m':190*U,'tower_m':237*U,'triangles_visible':tris,'triangles_per_part':part_tris,'materials':mats,'doors':DOORS,'rooms':ROOMS,'sign_name':{'node':'sign_name','texture':'textures/signs.png','rect_art':REG['modern_name' if CONS else 'historic_name']['rect_art'],'text':'LINCOLN CONSOLIDATED' if CONS else 'LINCOLN HIGH SCHOOL','notes':'Exactly one active school-name panel. Road monument replaces the blanked historic frieze from 1975. Repaint per town; Lincoln is the requested fictional preview name.'},'datestone':{'texture':'textures/panels.png','rect_art':REG['date']['rect_art'],'text':'1925'},'collision':{'node':'collision','render':False,'coordinate_frame':'glTF metres, Y up/+Z front','boxes_gltf_y_up':COLL,'use_the_walls':True,'notes':'Architectural meshes supply complete wall/floor/stair collision, including all open exterior portals. Listed boxes supplement props, backstop, fences, field poles, grandstand and closed upper landing. Keep fence gate gaps. Do not apply the old Blender rotation.'},'features':{'modern_wing':POST,'gym':POST,'bus_loop':POST,'baseball_diamond':True,'football_field':POST,'wood_bleachers':POST and not CONS,'steel_bleachers':CONS,'press_box':CONS,'student_parking':CONS,'portables':3 if CONS and not NEW else 0,'security_entry':NEW,'turf':NEW,'running_track':NEW,'scoreboard':NEW},'sports_dimensions':{'base_path_m':250*U*math.sqrt(2),'football_including_endzones_m':[1414*U,630*U],'track_inner_circumference_m':(2*1088+math.tau*475)*U if NEW else None,'track_lanes':5 if NEW else 0},'non_window_emissive_area_m2_upper_bound':GLOW_AREA,'pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'lot_node':'lot','lot_bounds_gltf_m':{'x':[-1500*U,1500*U],'z':[-2060*U,0]},'swatches_used':['brick_red','wall_stucco_red','roof_membrane_grey'],'licence':'Retain original LimeZu credits; shared swatches Timerpg-only. Supplemental art and geometry original.','runtime_handoff':['No pupils, staff, vehicles, admissions, security, school renaming or sports simulation is implemented. States are visual alternatives selected by actual town history.','Main entrance node retains the same position in all states; in 2000 it is the original interior portal and door_security is the new exterior entrance.','Upper floors have exterior shells and floor slabs but are closed at the stair landing; no upper classroom navigation is claimed.','Importer must read boxes_gltf_y_up without the legacy Blender-axis rotation. Wall/floor collision, lights, pane mask, navigation and state selection require game integration.','The requested sports grounds require a wider campus than the compact repository suggestion: this lot is 232.8 by 159.856 metres.','Base paths and football envelope are near regulation scale; compact baseball outfield and five-lane approximate 400m track are not certified facilities.'],'renders':'Actual Blender desktop renders, not Filament/S25 captures.'}
(R/('state-'+STATE+'.json')).write_text(json.dumps(info,indent=2)+'\n');print('SCHOOL_EXPORT',STATE,tris,len(mats),'glow',GLOW_AREA)
# Very cheap 1955 silhouette fallback. Only appropriate to the post-war state.
if STATE=='1955':
 far=empty('school_far');parent=far
 B('Far main',(0,224,94),(520,208,188),wall);B('Far tower',(0,110,118),(144,24,236),wall);B('Far wing',(480,250,32),(440,200,64),wall);B('Far link',(-290,230,32),(60,60,64),wall);B('Far gym',(-500,305,48),(360,310,96),wall)
 curve=[(-500+180*math.cos(i*math.pi/4),96+34*math.sin(i*math.pi/4)) for i in range(5)]
 for (a,za),(b,zb) in zip(curve,curve[1:]):mesh('Far gym bow',[(a*U,150*U,za*U),(a*U,460*U,za*U),(b*U,460*U,zb*U),(b*U,150*U,zb*U)],[(0,1,2,3)],roof)
 for y in [150,460]:mesh('Far gym gable',[(x*U,y*U,z*U) for x,z in curve],[tuple(range(5))],wall)
 B('Far grounds',(0,1030,.2),(3000,2060,.4),grass);B('Far field',(387,1115,.6),(1414,630,.4),grass);B('Far bleachers',(360,1757,22),(768,90,44),wood)
 farobjects=[far]+list(far.children_recursive);ft=count(farobjects);assert ft<400;export(farobjects,R/'school-far.glb');(R/'far-info.json').write_text(json.dumps({'file':'school-far.glb','state':'1955','triangles':ft,'notes':'Static post-war silhouette only. Same campus origin/metres; no doors, collision, interiors or automatic LOD selection.'},indent=2)+'\n')
 for o in farobjects:o.hide_render=True;o.hide_set(True)
# Actual model previews; staging terrain/lights/camera are excluded from GLB.
parent=None;gm=bpy.data.materials.new('Presentation terrain');gm.diffuse_color=(.25,.29,.24,1);bpy.ops.mesh.primitive_plane_add(size=1500,location=(0,0,-.04));bpy.context.object.data.materials.append(gm)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=12;scene.cycles.max_bounces=3;scene.cycles.use_denoising=False;scene.render.resolution_x=1024;scene.render.resolution_y=768;scene.render.resolution_percentage=100;scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
if '--review' in sys.argv:scene.cycles.samples=4;scene.render.resolution_percentage=50
world=bpy.data.worlds.new('Campus sky');world.use_nodes=True;scene.world=world;bg=world.node_tree.nodes.get('Background');sun=bpy.data.lights.new('Sun','SUN');so=bpy.data.objects.new('Sun',sun);bpy.context.collection.objects.link(so);so.rotation_euler=(.45,-.45,-.65);sun.angle=.09
bpy.ops.object.camera_add();cam=bpy.context.object;scene.camera=cam;ONLY=next((set(a.split('=')[1].split(',')) for a in sys.argv if a.startswith('--shots=')),None);saved=False
rawdir=Path('/tmp/lincoln-school-raw');rawdir.mkdir(exist_ok=True)
def shot(n,p,target,scale=145,night=False,perspective=False):
 global saved
 cam.data.type='PERSP' if perspective else 'ORTHO';cam.data.lens=22 if perspective else 35;cam.data.ortho_scale=scale;cam.location=p;cam.rotation_euler=(Vector(target)-cam.location).to_track_quat('-Z','Y').to_euler();bg.inputs[0].default_value=(*((.13,.19,.27) if night else (.62,.70,.75)),1);bg.inputs[1].default_value=.5 if night else .7;sun.energy=.22 if night else 2;sun.color=(.5,.65,1) if night else (1,.91,.77)
 for light,power in LIGHTS:light.energy=(power*.4 if n=='gym' and power==900 else power) if night else .1*power
 scene.view_settings.exposure=-.8 if n=='gym' else 0
 for m in [glass,glow]:m.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=.65 if night else .02
 scene.render.filepath=str(rawdir/(STATE+'-'+n+'.png'))
 if not saved:bpy.ops.wm.save_as_mainfile(filepath=str(R/('lincoln-high-school-'+STATE+'.blend')));saved=True
 if ONLY is None or n in ONLY:bpy.ops.render.render(write_still=True)
shot('day',(92,-94,76),(0,23,5) if POST else (0,18,6),135 if POST else 72)
shot('night',(92,-94,72),(0,23,5) if POST else (0,18,6),135 if POST else 72,True)
shot('rear',(-138,188,115),(0,74,3),220)
shot('above',(100,-80,280),(0,80,1),280)
if STATE=='1925':
 shot('entrance',(-12,-4,1.7),(0,10,8),100,False,True)
 shot('corridor',(-16,17.1,1.8),(15,17.1,1.9),100,True,True)
if STATE=='1955':shot('gym',(-46,14.5,4),(-38,25.5,1),100,True,True)
if STATE=='1975':
 shot('bleachers',(12,139,4.3),(25,85,1.4),100,False,True)
 shot('portables',(33,52,22),(2,33,1),66)
if STATE=='2000':shot('security',(-13,1,1.7),(0,6.7,3.8),100,False,True)
