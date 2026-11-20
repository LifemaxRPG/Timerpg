"""1912 public library and separately exported 1970s rear addition state."""
import bpy,math,json,sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;U=.0776;T=.0388;F=24;LATER='--later' in sys.argv;STATE='1970s' if LATER else '1912';REG=json.loads((R/'art-regions.json').read_text());COLL=[];LIGHTS=[];PROPS=[];DOORS=[]
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
def empty(n,p=None):
 o=bpy.data.objects.new(n,None);bpy.context.collection.objects.link(o);o.parent=p;return o
ROOT=empty('public_library');STRUCT=empty('structure',ROOT);LOT=empty('lot',ROOT);STAIR=empty('basement_stairs',STRUCT);ADD=empty('addition_1970s',STRUCT) if LATER else None;parent=STRUCT

def mat(n,f,emit=False,alpha=False):
 m=bpy.data.materials.new(n);m.use_nodes=True;p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Roughness'].default_value=.82;t=m.node_tree.nodes.new('ShaderNodeTexImage');t.image=bpy.data.images.load(str(R/'textures'/f),check_existing=True);t.interpolation='Closest';t.extension='REPEAT';m.node_tree.links.new(t.outputs['Color'],p.inputs['Base Color']);m['texture_file']=f
 if alpha:m.node_tree.links.new(t.outputs['Alpha'],p.inputs['Alpha']);m.surface_render_method='DITHERED'
 if emit:
  e=t
  if n=='glass_reading':e=m.node_tree.nodes.new('ShaderNodeTexImage');e.image=bpy.data.images.load(str(R/'textures/glass-emission.png'));e.interpolation='Closest';e.extension='REPEAT'
  m.node_tree.links.new(e.outputs['Color'],p.inputs['Emission Color']);p.inputs['Emission Strength'].default_value=.04
 return m
wall=mat('wall_brick','brick.png');cream=mat('detail_stone','stone.png');oak=mat('wall_interior','interior.png');roof=mat('roof_slate','slate.png');membrane=mat('roof_membrane','membrane.png');wood=mat('detail_wood','wood.png');steel=mat('detail_steel','steel.png');glass=mat('glass_reading','glass.png',True,True);glow=mat('glow_lamps','glow.png',True);sign=mat('sign_name','signs.png');paving=mat('ground_paving','paving.png');utility=mat('detail_panels','panels.png')
exec(compile((R/'mesh_helpers.py').read_text(),str(R/'mesh_helpers.py'),'exec'))
def B(n,p,s,m=wall,key=None):return box(n,p,s,m,patch=key)
def P(n,p,w,h,key,m=glass,side='front'):return panel(n,p,w,h,key,m,side)
def beam(n,a,b,w=1,d=None,m=steel):
 a=Vector(a)*U;b=Vector(b)*U;t=(b-a).normalized();ref=Vector((0,0,1)) if abs(t.z)<.9 else Vector((1,0,0));e=t.cross(ref).normalized()*w*U/2;v=t.cross(e).normalized()*(d if d else w)*U/2
 return mesh(n,[tuple(p+x*e+y*v) for p in (a,b) for x,y in [(-1,-1),(1,-1),(1,1),(-1,1)]],[(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)],m)
def col(n,x,y,w,d,top,attachment=None,gate=None,group='structure'):
 c={'source':n,'center':[x*U,y*U,top*U/2],'size':[w*U,d*U,top*U],'group':group,'attachment':attachment or parent.name}
 if gate:c['gate_node']=gate
 COLL.append(c)
def wall_run(n,a,b,z0,z1,fixed,openings=[],axis='x',inward=True):
 coords=sorted(set([a,b]+[v for q in openings for v in q[:2] if a<v<b]))
 for l,r in zip(coords,coords[1:]):
  cuts=sorted(set([z0,z1]+[v for q in openings if q[0]<(l+r)/2<q[1] for v in q[2:] if z0<v<z1]))
  for lo,hi in zip(cuts,cuts[1:]):
   if any(q[0]<(l+r)/2<q[1] and q[2]<(lo+hi)/2<q[3] for q in openings):continue
   o=B(n,((l+r)/2,fixed,(lo+hi)/2) if axis=='x' else (fixed,(l+r)/2,(lo+hi)/2),(r-l,2,hi-lo) if axis=='x' else (2,r-l,hi-lo),m=wall)
   o.data.materials.append(oak);f=o.data.polygons[(3 if inward else 1) if axis=='x' else (2 if inward else 4)];f.material_index=1;old=bpy.data.images.get(wall['texture_file']).size;new=bpy.data.images.get(oak['texture_file']).size
   for li in f.loop_indices:o.data.uv_layers.active.data[li].uv*=Vector((old[0]/new[0],old[1]/new[1]))
def slab(n,pts,m=roof,depth=2):
 k=len(pts);v=[(x*U,y*U,(z-d)*U) for d in [0,depth] for x,y,z in pts];mesh(n,v,[tuple(range(k))]+[(i,(i+1)%k,(i+1)%k+k,i+k) for i in range(k)],m);mesh(n+' soffit',v,[tuple(range(2*k-1,k-1,-1))],oak)
def door_open(n,x,y,w=24,h=38,side='front',external=True):
 dx,dy={'front':(0,-2),'back':(0,2),'left':(-2,0),'right':(2,0)}[side];o=door(n,(x+dx,y+dy,F),{'front':0,'back':math.pi,'left':-math.pi/2,'right':math.pi/2}[side]);DOORS.append({'node':n,'position':[o.location.x,o.location.z,-o.location.y],'outward':{'front':[0,0,1],'back':[0,0,-1],'left':[-1,0,0],'right':[1,0,0]}[side],'floor_m':F*U,'width_m':w*U,'height_m':h*U,'external':external,'open':True})
def transform(x,y,u,z,side):return {'front':(x+u,y,z),'back':(x-u,y,z),'left':(x,y-u,z),'right':(x,y+u,z)}[side]
def prism_face(n,pts,x,y,side,m,depth=2):
 pts=[p for i,p in enumerate(pts) if i==0 or Vector(p)!=Vector(pts[i-1])]
 if len(pts)>2 and Vector(pts[0])==Vector(pts[-1]):pts.pop()
 if len(pts)<3:return
 normal={'front':Vector((0,-1,0)),'back':Vector((0,1,0)),'left':Vector((-1,0,0)),'right':Vector((1,0,0))}[side];front=[Vector(transform(x,y,u,z,side))*U for u,z in pts];v=[tuple(p) for p in front]+[tuple(p-normal*depth*U) for p in front];k=len(front);mesh(n,v,[tuple(range(k)),tuple(range(2*k-1,k-1,-1))]+[(i,i+k,(i+1)%k+k,(i+1)%k) for i in range(k)],m)
def arched(x,y,side):
 r=14;bottom=36;spring=62;top=76;curve=[(r*math.cos(i*math.pi/8),spring+r*math.sin(i*math.pi/8)) for i in range(9)];poly=[(-r,bottom),(r,bottom)]+curve
 # The real pane has an arched perimeter; UVs cover precisely its art-pixel rectangle.
 v=[tuple(Vector(transform(x,y,u,z,side))*U) for u,z in poly];o=mesh('Round arched pane',v,[tuple(range(len(v)))],glass);W,H=bpy.data.images.get(glass['texture_file']).size
 for li,(u,z) in zip(o.data.polygons[0].loop_indices,poly):o.data.uv_layers.active.data[li].uv=((u+r)*2/W,1-(40-(z-bottom))*2/H)
 # Fill the two spandrels left by the rectangular wall cut, using the same faceted arc.
 for (a,za),(b,zb) in zip(curve,curve[1:]):
  if a<b:a,b,za,zb=b,a,zb,za
  # Source arc runs right to left; reverse to make the front-facing polygon CCW.
  prism_face('Brick arch spandrel',[(b,zb),(a,za),(a,top),(b,top)],x,y,side,wall)
 for i in range(8):
  a=i*math.pi/8;b=(i+1)*math.pi/8;prism_face('Stone archivolt',[(14*math.cos(a),spring+14*math.sin(a)),(17*math.cos(a),spring+17*math.sin(a)),(17*math.cos(b),spring+17*math.sin(b)),(14*math.cos(b),spring+14*math.sin(b))],x,y,side,cream)
 normal={'front':(0,-.3),'back':(0,.3),'left':(-.3,0),'right':(.3,0)}[side]
 for u in [-15.5,15.5]:
  p=transform(x,y,u,(bottom+spring)/2,side);B('Stone window jamb',p,(3,3,spring-bottom),cream)
 p=transform(x,y,0,bottom-1,side);B('Window sill',p,(34,5,2) if side in ['front','back'] else (5,34,2),cream)
def flat_window(x,y,w=28,h=24,key='modern',side='front',z=48):
 P('Rectangular pane',(x,y,z),w,h,key,glass,side);B('Rectangular sill',(x,y,z-h/2-1),(w+4,4,2) if side in ['front','back'] else (4,w+4,2),cream)
def foundation(axis,fixed,a,b,windows,inward=True):
 global wall
 old=wall;wall=cream;wall_run('Stone basement',a,b,-18,24,fixed,windows,axis,inward);wall=old
 for u in [(q[0]+q[1])/2 for q in windows]:
  x,y=(u,fixed-1.2 if inward else fixed+1.2) if axis=='x' else (fixed-1.2 if inward else fixed+1.2,u);side=('front' if inward else 'back') if axis=='x' else ('left' if inward else 'right');P('Basement light',(x,y,8),20,12,'basement',glass,side)
# Raised main storey over a real basement. The floor has a genuine stairwell aperture.
B('Basement floor',(0,90,-17),(232,180,2),wood)
for x0,x1,y0,y1 in [(-116,116,0,138),(-116,-32,138,180),(32,116,138,180)]:B('Main floor',((x0+x1)/2,(y0+y1)/2,23),(x1-x0,y1-y0,2),wood)
B('Main ceiling',(0,90,85),(232,180,2),oak)
foundation('x',0,-116,116,[[x-10,x+10,2,14] for x in [-76,76]])
foundation('x',180,-116,116,[[x-10,x+10,2,14] for x in [-76,0,76]],False)
for x,inward in [(-116,True),(116,False)]:foundation('y',x,0,180,[[y-10,y+10,2,14] for y in [32,90,148]],inward)
wall_run('Front brick',-116,116,24,88,0,[[-12,12,24,62]]+[[x-14,x+14,36,76] for x in [-76,76]])
for x in [-76,76]:arched(x,-1.25,'front')
door_open('door_main',0,-1.2)
wall_run('Rear brick',-116,116,24,88,180,[[66,86,24,60]]+[[x-14,x+14,36,76] for x in [-76,0]],inward=False)
for x in [-76,0]:arched(x,181.25,'back')
door_open('door_back',76,181.2,20,36,'back',not LATER)
for x,side,inward in [(-116,'left',True),(116,'right',False)]:
 wall_run('Reading room side',0,180,24,88,x,[[y-14,y+14,36,76] for y in [32,90,148]],'y',inward)
 for y in [32,90,148]:arched(x+(-1.25 if inward else 1.25),y,side)
# Two reading rooms flank the central desk hall, with broad open passages.
for x in [-36,36]:wall_run('Reading room partition',0,128,24,84,x,[[28,60,24,66],[98,126,24,66]],'y',x<0)
B('Fixed circulation desk',(0,58,32),(40,14,16),wood);B('Desk cap',(0,58,40.5),(44,18,1),cream)
# Supporting-floor collision: this upstairs counter must not block the basement room below.
COLL.append({'source':'Circulation desk','center':[0,58*U,32.5*U],'size':[44*U,18*U,17*U],'group':'structure','attachment':'structure','support_floor_m':F*U})
# Twenty real 0.1552m risers, two flights, down to the children's room.
parent=STAIR
for i in range(10):
 top=-14+2*i;B('Lower basement stair',(-20,139.5+3*i,(top-16)/2),(20,3,top+16),wood)
 top=6+2*i;B('Upper basement stair',(20,166.5-3*i,(top-16)/2),(20,3,top+16),wood)
B('Half landing',(0,173,-6),(64,10,20),wood)
for a,b in [((-31,138,-2),(-31,168,18)),((31,168,18),(31,138,38))]:beam('Stair handrail',a,b,.7,.7,steel)
for x in [-32,32]:
 beam('Stairwell guard',(x,138,38),(x,178,38),.7,.7,steel)
 for y in [138,158,178]:beam('Guard upright',(x,y,24),(x,y,38),.7,.7,steel)
# Open top landing at Y138 joins the central hall; no flat floor seals the stair opening.
parent=STRUCT
# Hipped slate roof: four actual pitches, complete soffits, ridge and gutters.
for n,p in [('Front hip',[(-120,-4,88),(120,-4,88),(0,50,124)]),('East pitch',[(120,-4,88),(120,184,88),(0,130,124),(0,50,124)]),('Rear hip',[(120,184,88),(-120,184,88),(0,130,124)]),('West pitch',[(-120,184,88),(-120,-4,88),(0,50,124),(0,130,124)])]:slab(n,p)
beam('Roof ridge',(0,50,124),(0,130,124),1,1,steel)
for x,y,w,d in [(0,0,236,4),(0,180,236,4),(-116,90,4,180),(116,90,4,180)]:B('Stone cornice',(x,y,87.5),(w,d,5),cream)
for x in [-118,118]:
 beam('Gutter',(x,-3,87),(x,183,87),1.5,1.5,steel)
 for y in [2,178]:beam('Downpipe',(x,y,87),(x,y,0),.8,.8,steel);col('Downpipe',x,y,1,1,88)
# Columned portico: two round columns, real terrace and twelve approach risers.
B('Portico terrace',(0,-18,12),(104,36,24),paving)
for i in range(12):
 top=2*(i+1);B('Entrance stair',(0,-82+4*i,top/2),(72,4,top),paving)
# Last tread ends at -36, exactly touching the terrace.
# Stone stair cheeks and supported handrails follow the full raised approach.
for x in [-38,38]:
 v=[(xx*U,y*U,z*U) for xx in [x-2,x+2] for y,z in [(-84,0),(-36,0),(-36,28),(-84,4)]]
 mesh('Stone stair cheek',v,[(3,2,1,0),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)],cream)
 beam('Approach handrail',(x,-82,15),(x,-36,38),.8,.8,steel)
 for y in [-82,-66,-50,-36]:
  base=4+(y+84)*24/48;beam('Handrail upright',(x,y,base),(x,y,base+10),.8,.8,steel)
for x in [-50,50]:
 beam('Terrace rail',(x,-34,38),(x,-2,38),.8,.8,steel)
 for y in [-34,-18,-2]:beam('Terrace guard post',(x,y,24),(x,y,38),.8,.8,steel)
 col('Terrace guard',x,-18,2,34,39)
for x in [-34,34]:
 B('Column plinth',(x,-27,26),(12,12,4),cream);cylinder('Column base',(x,-27,29),5,2,cream,None,12);cylinder('Doric shaft',(x,-27,45),3.5,30,cream,None,12);cylinder('Doric neck',(x,-27,61),4,2,cream,None,12);B('Column capital',(x,-27,64),(12,12,4),cream);col('Portico column',x,-27,12,12,66)
 B('Back pilaster',(x,-2,45),(7,4,42),cream);B('Portico side entablature',(x,-14.5,73),(8,29,15),cream)
B('Entablature',(0,-27,69),(92,12,6),cream);P('sign_name',(0,-33.15,69),100,16,'nameplate',sign)
# Stone name case matches its panel width and remains above the open entrance.
B('Carved name frieze',(0,-28,69),(104,10,18),cream)
slab('Pediment west',[(-54,-37,78),(0,-37,90),(0,2,90),(-54,2,78)])
slab('Pediment east',[(0,-37,90),(54,-37,78),(54,2,78),(0,2,90)])
v=[(x*U,y*U,z*U) for y in [-35,-33] for x,z in [(-54,78),(54,78),(0,90)]];mesh('Stone pediment',v,[(0,1,2),(5,4,3),(0,3,4,1),(1,4,5,2),(2,5,3,0)],cream)
# Portico roof closes against the front masonry and main roof weather junction.
for x in [-19,19]:
 B('Entrance lantern bracket',(x,-2,55),(3,4,12),steel);B('Entrance lantern glass',(x,-5,55),(4,4,8),glow);B('Lantern cap',(x,-5,60),(6,6,2),steel);light('Entrance light',(x,-8,52),75,10)
# Rear terrace leads to the original raised service door (or the later short link).
if not LATER:
 B('Rear terrace',(76,190,12),(36,20,24),paving)
 for i in range(12):
  top=24-2*i;B('Rear stair',(76,202+4*i,top/2),(28,4,top),paving)
else:
 parent=ADD
 B('Link floor',(76,194,23),(48,28,2),wood);B('Link ceiling',(76,194,61),(48,28,2),oak);B('Link flat roof',(76,194,63),(52,32,2),membrane)
 for x,inward,side in [(52,True,'left'),(100,False,'right')]:
  wall_run('Glazed link',180,208,24,62,x,[[186,202,30,54]],'y',inward);flat_window(x+(-1.2 if inward else 1.2),194,16,24,'link',side,42)
 B('Addition floor',(0,254,23),(232,92,2),wood);B('Addition ceiling',(0,254,67),(232,92,2),oak)
 wall_run('Addition front',-116,116,24,68,208,[[66,86,24,60],[-100,-52,36,60],[-48,0,36,60]])
 flat_window(-24,206.8,48,24,'ribbon','front');flat_window(-76,206.8,48,24,'ribbon','front')
 wall_run('Addition rear',-116,116,24,68,300,[[-10,10,24,60],[-94,-46,36,60],[46,94,36,60]],inward=False)
 door_open('door_addition_back',0,301.2,20,36,'back')
 for x in [-70,70]:flat_window(x,301.2,48,24,'ribbon','back')
 for x,side,inward in [(-116,'left',True),(116,'right',False)]:
  ys=[228,280] if inward else [230,274]
  wall_run('Addition side',208,300,24,68,x,[[y-14,y+14,36,60] for y in ys]+([[244,264,24,60]] if inward else []),'y',inward)
  for y in ys:flat_window(x+(-1.2 if inward else 1.2),y,28,24,'modern',side)
 B('Addition membrane',(0,254,69),(238,98,2),membrane)
 for x,y,w,d in [(0,207,238,4),(0,301,238,4),(-117,254,4,90),(117,254,4,90)]:B('Addition concrete fascia',(x,y,69),(w,d,6),cream)
 for z in [28,64]:
  if z==28:
   for a,b in [(-116,-10),(10,116)]:B('Rear band beside door',((a+b)/2,300,z),(b-a,3,3),cream)
   for a,b in [(208,244),(264,300)]:B('West band beside door',(-116,(a+b)/2,z),(3,b-a,3),cream)
  else:B('Addition horizontal band',(0,300,z),(232,3,3),cream);B('Addition horizontal band',(-116,254,z),(3,92,3),cream)
  B('Addition horizontal band',(116,254,z),(3,92,3),cream)
 door_open('door_addition',-117.2,254,20,36,'left')
 # Original door coordinates are unchanged. New addition thresholds have actual terraces/steps.
 B('Addition side landing',(-124,254,12),(16,32,24),paving)
 for i in range(12):
  top=24-2*i;B('Addition west stair',(-134-4*i,254,top/2),(4,28,top),paving)
 B('Addition rear landing',(0,310,12),(32,20,24),paving)
 for i in range(12):
  top=24-2*i;B('Addition rear stair',(0,322+4*i,top/2),(28,4,top),paving)
 for x,y in [(0,246),(72,270)]:B('Addition ceiling fixture',(x,y,65),(18,8,1),glow);light('Addition room light',(x,y,64),120,16)
 parent=STRUCT
# Ceiling lanterns and basement ceiling lamps, with light emission only on glass.
for x,y,z in [(-76,85,82),(76,85,82),(0,74,82),(0,110,19),(-76,112,19),(76,112,19)]:
 B('Ceiling fixture',(x,y,z),(12,12,1),steel);B('Fixture lens',(x,y,z-.7),(10,10,.4),glow);light('Room illumination',(x,y,z-1),135,16)
# Optional grounds and lamps; no fabricated books, memorial names or resident history.
parent=LOT
B('Front walk',(0,-94,.3),(80,20,.6),paving)
for x in [-46,46]:
 parent=lot_prop('stair_lamp');B('Lamp pedestal',(x,-74,8),(8,8,16),cream);B('Lamp lens',(x,-74,20),(5,5,8),glow);B('Lamp hood',(x,-74,25),(8,8,2),steel);col('Stair lamp',x,-74,8,8,26,parent.name,group='lot');light('Step light',(x,-74,23),55,10)
for x in [-88,88]:
 parent=lot_prop('stone_bench');B('Bench seat',(x,-14,9),(34,8,2),cream)
 for xx in [x-11,x+11]:B('Bench support',(xx,-14,4),(4,8,8),cream)
 col('Bench',x,-14,34,8,10,parent.name,group='lot')
parent=lot_prop('bicycle_racks')
for x in [-102,-90,-78]:
 for xx in [x-3,x+3]:B('Bike hoop leg',(xx,-38,7),(1,1,14),steel)
 B('Bike hoop top',(x,-38,14),(7,1,1),steel)
col('Bicycle racks',-90,-38,32,2,15,parent.name,group='lot')
# Hidden boxes. Metadata below converts every centre/size into glTF Y-up metres.
parent=ROOT;v=[];faces=[]
for c in COLL:
 x,y,z=c['center'];w,d,h=c['size'];k=len(v);v += [(x+a*w/2,y+b*d/2,z+s*h/2) for a,b,s in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]];faces += [tuple(k+j for j in f) for f in [(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)]]
collision=mesh('collision',v,faces,steel);collision.hide_render=True;collision['collision_only']=True;collision['render']=False
for g in [STRUCT,STAIR,*([ADD] if LATER else []),*PROPS,LOT]:
 groups={}
 for o in list(g.children):
  if o.type=='MESH' and o.name!='sign_name':groups.setdefault(o.data.materials[0].name,[]).append(o)
 for n,obs in groups.items():
  bpy.ops.object.select_all(action='DESELECT')
  for o in obs:o.select_set(True)
  bpy.context.view_layer.objects.active=obs[0]
  if len(obs)>1:bpy.ops.object.join()
  obs[0].name=g.name+'__'+n
model=[ROOT]+list(ROOT.children_recursive);tris=sum(sum(len(p.vertices)-2 for p in o.data.polygons) for o in model if o.type=='MESH' and o!=collision);materials=sorted({m.name for o in model if o.type=='MESH' for m in o.data.materials});assert tris<=12000 and len(materials)<=12,(tris,len(materials))
for im in bpy.data.images:
 if im.source=='FILE':im.pack()
bpy.ops.object.select_all(action='DESELECT')
for o in model:o.select_set(True)
bpy.ops.export_scene.gltf(filepath=str(R/('public-library-'+STATE+'.glb')),use_selection=True,export_format='GLB',export_yup=True,export_extras=True)
boxes=[]
for b in COLL:
 x,y,z=b['center'];w,d,h=b['size'];q={k:v for k,v in b.items() if k not in ['center','size']};q.update(center=[x,z,-y],size=[w,h,d]);boxes.append(q)
info={'name':'Public Library','state':STATE,'file':'public-library-'+STATE+'.glb','design_year':1912,'activation_years':[1965,1985] if LATER else [1905,1920],'units':'metres','axes':'glTF Y up; front +Z','origin':'Grade at middle of original brick frontage. Both states retain identical origin and original door coordinates.','main_footprint_m':[232*U,180*U],'heights_m':{'hip_ridge':124*U,'main_floor':24*U,'basement_floor':-16*U,'main_ceiling':84*U},'triangles_visible':tris,'materials':materials,'pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'doors':DOORS,'rooms':[{'id':'central_desk_hall','floor_m':24*U,'notes':'Fixed circulation desk; open passages into both reading rooms.'},{'id':'west_reading_room','floor_m':24*U,'notes':'Unfurnished room with three actual round-arched side windows.'},{'id':'east_reading_room','floor_m':24*U,'notes':'Unfurnished room with three actual round-arched side windows.'},{'id':'basement_children_room','floor_m':-16*U,'notes':'Unfurnished basement shell, reached by actual two-flight stairs. Runtime supplies books, shelves and furniture.'}]+([{'id':'later_reading_addition','floor_m':24*U,'notes':'Low brick/glass 1970s shell, joined to the unchanged rear door by a short glazed link.'}] if LATER else []),'stairs':{'node':'basement_stairs','upper_floor_m':24*U,'lower_floor_m':-16*U,'half_landing_m':4*U,'risers':20,'rise_m':2*U,'tread_m':3*U,'notes':'Real floor aperture, two flights and handrails; height-aware traversal and basement furnishing remain runtime work.'},'sign_name':{'node':'sign_name','material':'sign_name','texture':'textures/signs.png','rect_art':[0,0,100,16],'text':'PUBLIC LIBRARY','binding':'Exactly one carved name region, repainted per town.'},'collision':{'node':'collision','render':False,'coordinate_frame':'glTF metres: Y up, +Z front','boxes_gltf_y_up':boxes,'use_the_walls':True,'notes':'Exterior doorways are open and have no closed panels or gate boxes. Architectural walls, floors and stairs supply contact. The upstairs desk blocker starts at its supporting floor, preserving basement space below; runtime must use vertical collision/navigation.'},'lot_node':'lot','lot_bounds_gltf_m':{'x':[-184*U,184*U],'z':[-(368 if LATER else 248)*U,104*U]},'basement':'Intentional below-grade architecture, not a sunken lot. All exterior lot paving is at/above grade. Cut terrain under original footprint and supply basement navigation.','swatches_used':['brick_red','wall_stucco_red','roof_slate','roof_membrane_grey','pavement'],'licence':'Shared LimeZu textures: Timerpg-only; retain original credit. All geometry and supplemental art original.','runtime_handoff':['No residents, books, collection contents, ownership or history are created.','Both states preserve original door nodes/coordinates. In the later state door_back opens into the link; hide/rebind its exterior interaction as appropriate.','Open exterior doorways intentionally have no panels/gates. Runtime provides entry behavior.','Basement, stairs, glass emission mask, terrain cutout and state selection need game integration.','State years are eligibility hints; simulation decides construction and addition timing.'],'renders':'Actual Blender desktop previews, not Filament/S25 captures.'}
(R/('state-'+STATE+'.json')).write_text(json.dumps(info,indent=2)+'\n');print(json.dumps({'state':STATE,'triangles':tris,'materials':len(materials),'doors':len(DOORS),'boxes':len(boxes)}))
# Staged presentation, excluded from GLB. Terrain is hidden for interior basement views.
parent=None;gm=bpy.data.materials.new('Presentation terrain');gm.diffuse_color=(.26,.3,.24,1);bpy.ops.mesh.primitive_plane_add(size=200,location=(0,0,-.03));terrain=bpy.context.object;terrain.data.materials.append(gm)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=20;scene.cycles.use_denoising=False;scene.cycles.max_bounces=4;scene.render.resolution_x=1280;scene.render.resolution_y=960;scene.render.resolution_percentage=100;scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
w=bpy.data.worlds.new('Library sky');w.use_nodes=True;scene.world=w;bg=w.node_tree.nodes.get('Background');sun=bpy.data.lights.new('Sun','SUN');so=bpy.data.objects.new('Sun',sun);bpy.context.collection.objects.link(so);so.rotation_euler=(.4,-.5,-.6);sun.angle=.08
bpy.ops.object.camera_add();cam=bpy.context.object;scene.camera=cam
ONLY=next((set(a.split('=',1)[1].split(',')) for a in sys.argv if a.startswith('--shots=')),None)
if '--review' in sys.argv:scene.cycles.samples=8;scene.render.resolution_percentage=50

def shot(n,p,t,scale=35,night=False,perspective=False):
 cam.data.type='PERSP' if perspective else 'ORTHO';cam.data.lens=22 if perspective else 32;cam.data.ortho_scale=scale;cam.location=p;cam.rotation_euler=(Vector(t)-cam.location).to_track_quat('-Z','Y').to_euler();terrain.hide_render=n in ['06-reading-hall','08-basement'];bg.inputs[0].default_value=(*((.12,.19,.3) if night else (.64,.72,.77)),1);bg.inputs[1].default_value=.5 if night else .65;sun.energy=.25 if night else 2;sun.color=(.5,.67,1) if night else (1,.9,.77)
 for d,power in LIGHTS:d.energy=power if night else .12*power
 for m in [glass,glow]:m.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=.6 if night else .04
 scene.render.filepath=str(R/'renders'/(n+'.png'))
 if n in ['01-front-day','07-addition']:bpy.ops.wm.save_as_mainfile(filepath=str(R/('public-library-'+STATE+'.blend')))
 if ONLY is None or n in ONLY:bpy.ops.render.render(write_still=True)
if LATER:shot('07-addition',(34,42,30),(0,10,4),43)
else:
 shot('01-front-day',(30,-34,25),(0,5,4),35)
 shot('02-blue-hour',(30,-34,24),(0,5,4),35,True)
 shot('03-rear',(-29,34,24),(0,6,4),35)
 shot('04-above',(20,-15,42),(0,6,2),36)
 shot('05-person-height',(-11,-15,1.7),(0,2,5),35,False,True)
 shot('06-reading-hall',(2.328,2.0176,3.57),(-.5,9.3,3.0),35,True,True)
 shot('08-basement',(-5,10,.35),(0,12.8,1.0),35,True,True)
