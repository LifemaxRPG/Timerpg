"""Original four-state corner drugstore, soda fountain and upper office shell."""
import bpy,math,json,sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;U=.0776;T=.0388;F=2;STATE=next((a.split('=',1)[1] for a in sys.argv if a.startswith('--state=')),'family');assert STATE in ['family','retail','closed','cafe'];FOUNTAIN=STATE in ['family','cafe'];DROPPED=STATE in ['retail','closed'];CLOSED=STATE=='closed';REG=json.loads((R/'art-regions.json').read_text());COLL=[];LIGHTS=[];PROPS=[];DOORS=[];STOOLS=[]
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
def empty(n,p=None):
 o=bpy.data.objects.new(n,None);bpy.context.collection.objects.link(o);o.parent=p;return o
ROOT=empty('corner_drugstore');STRUCT=empty('structure',ROOT);LOT=empty('lot',ROOT);STAIR=empty('exterior_stairs',STRUCT);BAR=empty('fountain',STRUCT) if FOUNTAIN else None;parent=STRUCT

def mat(n,f,emit=None,alpha=False):
 m=bpy.data.materials.new(n);m.use_nodes=True;p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Roughness'].default_value=.82;t=m.node_tree.nodes.new('ShaderNodeTexImage');t.image=bpy.data.images.load(str(R/'textures'/f),check_existing=True);t.interpolation='Closest';t.extension='REPEAT';m.node_tree.links.new(t.outputs['Color'],p.inputs['Base Color']);m['texture_file']=f
 if alpha:m.node_tree.links.new(t.outputs['Alpha'],p.inputs['Alpha']);m.surface_render_method='DITHERED'
 if emit:
  e=t
  if emit!='self':e=m.node_tree.nodes.new('ShaderNodeTexImage');e.image=bpy.data.images.load(str(R/'textures'/emit));e.interpolation='Closest';e.extension='REPEAT'
  m.node_tree.links.new(e.outputs['Color'],p.inputs['Emission Color']);p.inputs['Emission Strength'].default_value=0 if CLOSED else .04
 return m
wall=mat('wall_brick','brick.png');oak=mat('wall_interior','interior.png');cream=mat('detail_stone','stone.png');roof=mat('roof_membrane','roof.png');steel=mat('detail_steel','steel.png');floor=mat('ground_floor','floor.png');glass=mat('glass_shop','glass.png','pane-emission.png',True);glow=mat('glow_lamps','glow.png','self');sign=mat('sign_name','signs-'+STATE+'.png','sign-emission-'+STATE+'.png');utility=mat('detail_panels','panels.png');enamel=mat('detail_enamel','enamel.png');paving=mat('ground_paving','paving.png')
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
def prism(n,xy,z0,z1,m,key=None):
 k=len(xy);v=[(x*U,y*U,z*U) for z in [z0,z1] for x,y in xy];return mesh(n,v,[tuple(range(k-1,-1,-1)),tuple(range(k,2*k))]+[(i,(i+1)%k,(i+1)%k+k,i+k) for i in range(k)],m,key)
def slab(n,p,m=roof,depth=2):
 k=len(p);v=[(x*U,y*U,(z-d)*U) for d in [0,depth] for x,y,z in p];mesh(n,v,[tuple(range(k)),tuple(range(2*k-1,k-1,-1))]+[(i,(i+1)%k,(i+1)%k+k,i+k) for i in range(k)],m)
def open_door(n,x,y,w=16,h=32,floor_m=F*U,side='front',external=True):
 dx,dy={'front':(0,-2),'back':(0,2),'left':(-2,0),'right':(2,0)}[side];o=door(n,(x+dx,y+dy,floor_m/U),{'front':0,'back':math.pi,'left':-math.pi/2,'right':math.pi/2}[side]);DOORS.append({'node':n,'position':[o.location.x,o.location.z,-o.location.y],'outward':{'front':[0,0,1],'back':[0,0,-1],'left':[-1,0,0],'right':[1,0,0]}[side],'width_m':w*U,'height_m':h*U,'floor_m':floor_m,'external':external,'open':True})
def solid_box(n,x,y,w,d,bottom,top,attachment=None,group='structure'):
 COLL.append({'source':n,'center':[x*U,y*U,(bottom+top)*U/2],'size':[w*U,d*U,(top-bottom)*U],'support_floor_m':bottom*U,'attachment':attachment or parent.name,'group':group})
def window(n,p,w,h,key,side='front'):
 P(n,p,w,h,key,glass,side);x,y,z=p;B('Window sill',(x,y,z-h/2-1),(w+2,3,2) if side in ['front','back'] else (3,w+2,2),cream)
 if key=='upper':B('Window lintel',(x,y,z+h/2+1),(w+4,3,2) if side in ['front','back'] else (3,w+4,2),cream)
# Chamfer basis: its door really faces diagonally across the corner.
A=Vector((-76,32));D=Vector((-44,0));CENTRE=(A+D)/2;TAN=(D-A).normalized();OUT=Vector((TAN.y,-TAN.x));L=(D-A).length

def segment_wall(n,z0,z1,holes,m=wall):
 cuts=sorted(set([-L/2,L/2]+[v for q in holes for v in q[:2]]))
 for a,b in zip(cuts,cuts[1:]):
  zs=sorted(set([z0,z1]+[v for q in holes if q[0]<(a+b)/2<q[1] for v in q[2:] if z0<v<z1]))
  for lo,hi in zip(zs,zs[1:]):
   if any(q[0]<(a+b)/2<q[1] and q[2]<(lo+hi)/2<q[3] for q in holes):continue
   p=CENTRE+TAN*a;q=CENTRE+TAN*b;beam(n,(*p,(lo+hi)/2),(*q,(lo+hi)/2),2,hi-lo,m)
def diagonal_pane(n,centre_u,z,w,h,key):
 p=CENTRE+TAN*(centre_u-w/2)+OUT*1.15;q=CENTRE+TAN*(centre_u+w/2)+OUT*1.15;v=[(p.x*U,p.y*U,(z-h/2)*U),(q.x*U,q.y*U,(z-h/2)*U),(q.x*U,q.y*U,(z+h/2)*U),(p.x*U,p.y*U,(z+h/2)*U)];o=mesh(n,v,[(0,1,2,3)],glass);r=REG[key]['rect_art'];W,H=bpy.data.images.get(glass['texture_file']).size;assert r[2:]==[w,h]
 uv=[(r[0]*2/W,1-(r[1]+h)*2/H),((r[0]+w)*2/W,1-(r[1]+h)*2/H),((r[0]+w)*2/W,1-r[1]*2/H),(r[0]*2/W,1-r[1]*2/H)]
 for li,u in zip(o.data.polygons[0].loop_indices,uv):o.data.uv_layers.active.data[li].uv=u
# Complete two-storey corner shell, with upper access from the outside stair.
PLAN=[(-44,0),(76,0),(76,256),(-76,256),(-76,32)]
ROOFPLAN=[(-45,-2),(78,-2),(78,258),(-78,258),(-78,31)]
prism('Store floor',PLAN,0,2,floor);prism('Upper floor',PLAN,52,54,floor);prism('Original store ceiling',PLAN,50,52,oak);prism('Upper ceiling',PLAN,100,102,oak)
# Main lower frontage and long street side display windows.
wall_run('Front ground masonry',-44,76,0,54,0,[[-32,20,8,40],[32,68,8,40]])
window('Front display',(-6,-1.2,24),52,32,'front_wide');window('Front display',(50,-1.2,24),36,32,'front_small')
wall_run('West ground masonry',32,256,0,54,-76,[[48,112,8,40],[140,204,8,40]],'y',True)
for y in [80,172]:window('Side display',(-77.2,y,24),64,32,'side_display','left')
wall_run('East ground masonry',0,256,0,54,76,[[20,44,8,36],[200,224,16,36]],'y',False)
window('Side pane',(77.2,32,22),24,28,'side_small','right');window('Rear utility pane',(77.2,212,26),24,20,'service','right')
wall_run('Rear ground masonry',-76,76,0,54,256,[[36,52,2,34],[-58,-34,16,36]],inward=False)
window('Rear service pane',(-46,257.2,26),24,20,'service','back');open_door('door_service',44,257.2,side='back')
segment_wall('Chamfer ground masonry',0,54,[[-10,10,2,36],[-21,-12,6,34],[12,21,6,34]])
for u in [-16.5,16.5]:diagonal_pane('Corner sidelight',u,20,9,28,'corner_sidelight')
p=CENTRE+OUT*3.2;o=door('door_main',(p.x,p.y,F),-math.pi/4);DOORS.append({'node':'door_main','position':[o.location.x,o.location.z,-o.location.y],'outward':[OUT.x,0,-OUT.y],'width_m':20*U,'height_m':34*U,'floor_m':F*U,'external':True,'open':True})
# Upper windows and the rear office door all retain the common shell in every state.
wall_run('Front upper masonry',-44,76,54,104,0,[[x-10,x+10,64,92] for x in [-18,20,58]])
for x in [-18,20,58]:window('Upper sash',(x,-1.2,78),20,28,'upper')
for x,side,inward,ys in [(-76,'left',True,[56,112,168,224]),(76,'right',False,[40,104,168,232])]:
 wall_run('Side upper masonry',32 if x<0 else 0,256,54,104,x,[[y-10,y+10,64,92] for y in ys],'y',inward)
 for y in ys:window('Upper sash',(x+(-1.2 if inward else 1.2),y,78),20,28,'upper',side)
wall_run('Rear upper masonry',-76,76,54,104,256,[[-28,-12,54,86]]+[[x-10,x+10,64,92] for x in [16,54]],inward=False)
for x in [16,54]:window('Rear upper sash',(x,257.2,78),20,28,'upper','back')
open_door('door_upper',-20,257.2,floor_m=54*U,side='back')
segment_wall('Upper chamfer masonry',54,104,[[-10,10,64,92]]);diagonal_pane('Upper corner sash',0,78,20,28,'upper')
# Upper office shells, no loose furniture; a broad internal opening joins the two rooms.
wall_run('Upper office partition',112,256,54,100,0,[[170,190,54,88]],'y',True)
# Street-level plinths stop beside all door openings.
B('Front plinth',(16,0,4),(120,3,4),cream);B('West plinth',(-76,144,4),(3,224,4),cream);B('East plinth',(76,128,4),(3,256,4),cream)
for a,b in [(-76,36),(52,76)]:B('Rear plinth beside door',((a+b)/2,256,4),(b-a,3,4),cream)
for a,b in [(-L/2,-10),(10,L/2)]:
 p=CENTRE+TAN*a;q=CENTRE+TAN*b;beam('Chamfer plinth beside entry',(*p,4),(*q,4),3,4,cream)
# Inter-storey string course and projecting cornice, with the upstairs door kept clear.
for z,h,w in [(54,3,4),(101,4,6)]:
 for a,b in zip(PLAN,PLAN[1:]+PLAN[:1]):
  if a[1]==b[1]==256 and z==54:
   for x0,x1 in [(-76,-28),(-12,76)]:beam('Rear string beside upper door',(x0,256,z),(x1,256,z),w,h,cream)
  else:beam('Masonry string course',(*a,z),(*b,z),w,h,cream)
prism('Membrane roof',ROOFPLAN,102,104,roof)
for a,b in zip(ROOFPLAN,ROOFPLAN[1:]+ROOFPLAN[:1]):beam('Brick parapet',(*a,108),(*b,108),3,8,wall);beam('Stone coping',(*a,113),(*b,113),5,2,cream)
# Restrained name sign plus the specified vertical trade sign.
B('Name fascia case',(12,-2.6,46),(108,3,16),steel);P('sign_name',(12,-4.2,46),100,16,'nameplate',sign)
blade=empty('projecting_trade_sign',STRUCT);parent=blade
B('Trade sign case',(-88,40,68),(24,4,54),steel)
P('Trade sign front',(-88,37.9,68),18,50,'blade',sign);P('Trade sign back',(-88,42.1,68),18,50,'blade',sign,'back')
for z in [44,94]:beam('Trade sign bracket',(-76,40,z),(-99,40,z),1,1,steel)
parent=STRUCT
# Roof and rear-side details, all seated on actual surfaces.
B('Brick chimney',(42,218,119),(10,12,30),wall);B('Chimney cap',(42,218,135),(14,16,2),cream);B('Dark flue',(42,218,136.2),(6,7,.4),steel)
B('Roof hatch curb',(-22,188,105),(22,26,2),steel);B('Roof hatch',(-22,188,106.5),(24,28,1),cream)
for x,y in [(-77.5,250),(77.5,250)]:beam('Rainwater pipe',(x,y,101),(x,y,0),.8,.8,steel);col('Downpipe',x,y,1,1,102)
slab('Rear service canopy',[(22,256,42),(66,256,42),(66,276,38),(22,276,38)])
for x in [25,63]:beam('Awning bracket',(x,257,30),(x,270,39),1,1,steel)
# Twenty-six real metal stair treads in two flights, with supported landings/guards.
parent=STAIR
B('Lower stair landing',(-44,278,1),(24,4,2),paving)
for i in range(13):
 z=4+2*i;B('Lower steel tread',(-44,281.5+3*i,z-.5),(20,3,1),steel)
 z=30+2*i;B('Upper steel tread',(-20,317.5-3*i,z-.5),(20,3,1),steel)
B('Stair half landing',(-32,325,27.5),(48,12,1),steel);B('Upper office landing',(-20,268,53.5),(28,24,1),steel)
for x in [-55,-33]:beam('Lower stringer',(x,280,2),(x,319,28),1.4,2,steel);beam('Lower handrail',(x,280,16),(x,319,42),.7,.7,steel)
for x in [-31,-9]:beam('Upper stringer',(x,319,28),(x,280,54),1.4,2,steel);beam('Upper handrail',(x,319,42),(x,280,68),.7,.7,steel)
for x in [-54,-10]:
 for y in [321,330]:B('Landing support',(x,y,14),(2,2,28),steel);col('Stair landing post',x,y,2,2,28,STAIR.name)
for x in [-32,-8]:B('Upper landing post',(x,278,27),(2,2,54),steel);col('Upper landing post',x,278,2,2,54,STAIR.name)
# Vertical rail uprights track each flight; no box seals a stair route.
for i in [0,4,8,12]:
 y=281.5+3*i;z=4+2*i
 for x in [-55,-33]:beam('Lower rail upright',(x,y,z),(x,y,z+13),.7,.7,steel)
 y=317.5-3*i;z=30+2*i
 for x in [-31,-9]:beam('Upper rail upright',(x,y,z),(x,y,z+13),.7,.7,steel)
for x in [-33,-7]:
 beam('Upper landing guard',(x,257,68),(x,279,68),.7,.7,steel)
 for y in [258,278]:beam('Upper guard post',(x,y,54),(x,y,68),.7,.7,steel)
for x,y in [(-56,331),(-8,331),(-56,320),(-8,320),(-32,331)]:beam('Half landing guard post',(x,y,28),(x,y,42),.7,.7,steel)
for a,b in [((-56,331,42),(-8,331,42)),((-56,320,42),(-56,331,42)),((-8,320,42),(-8,331,42))]:beam('Half landing guard',a,b,.7,.7,steel)
parent=STRUCT
# Fixed prescription counter remains; its generic drawers carry no brands or invented stock.
B('Prescription counter',(-20,225,9),(84,14,14),utility,'cabinet');B('Prescription worktop',(-20,225,16.5),(86,16,1),enamel);solid_box('Prescription counter',-20,225,86,16,2,17)
# The same original fountain geometry is reused only in the family/cafe alternatives.
if FOUNTAIN:
 parent=BAR;xy=[]
 for i in range(9):a=math.pi+i*math.pi/8;xy.append((-48+8*math.cos(a),72+8*math.sin(a)))
 for i in range(9):a=i*math.pi/8;xy.append((-48+8*math.cos(a),184+8*math.sin(a)))
 prism('Rounded fountain cabinet',xy,2,15,utility,'cabinet');prism('Fountain tile worktop',xy,15,16,enamel);solid_box('Fountain counter',-48,128,16,128,2,28,BAR.name)
 B('Fountain preparation cabinet',(-70,128,7.5),(8,104,11),utility,'cabinet');B('Preparation top',(-70,128,13.5),(9,106,1),enamel);solid_box('Prep cabinet',-69.75,128,10.5,106,2,30,BAR.name)
 for y in [104,136,168]:
  cylinder('Soda tap riser',(-70,y,18),1,8,steel,None,8);beam('Tap spout',(-70,y,22),(-65,y,22),.8,.8,steel);beam('Tap nozzle',(-65,y,22),(-65,y,20),.7,.7,steel);B('Tap lever',(-70,y,24),(1,1,4),steel)
 P('Inset sink',(-70,120,14.05),6,8,'basin',utility,'up')
 B('Three-head soda dispenser',(-49,126,22),(4,12,12),enamel)
 for y in [122,126,130]:
  B('Fountain handle',(-46, y,25),(2,2,3),utility,'seat');beam('Dispenser spout',(-47,y,20),(-44,y,20),.7,.7,steel);beam('Downturned nozzle',(-44,y,20),(-44,y,18),.7,.7,steel)
 for y in [92,156]:
  beam('Fixed mixer stand',(-72,y,14),(-72,y,28),1.2,1.2,steel);cylinder('Mixer motor',(-70,y,28),2.2,4,steel,None,8);cylinder('Fixed mixing vessel',(-70,y,21.5),1.6,7,steel,None,8);beam('Mixer cup holder',(-72,y,18),(-70,y,18),.8,.8,steel);beam('Mixer spindle',(-70,y,25),(-70,y,26),.6,.6,steel)

 for i,y in enumerate([80,94,108,122,136,150,164,178]):
  parent=empty('fixed_stool_'+str(i+1),BAR);STOOLS.append(parent)
  cylinder('Stool floor flange',(-32,y,2.4),3.2,.8,steel,None,8);cylinder('Stool pedestal',(-32,y,6.5),.9,8.2,steel,None,8);cylinder('Stool footrest',(-32,y,5),2.5,.5,steel,None,8);cylinder('Upholstered seat',(-32,y,11.3),4,1.4,utility,'seat',12);solid_box('Fixed stool',-32,y,8,8,2,12,parent.name)
 parent=BAR
 for y in [82,126,170]:
  beam('Fountain pendant stem',(-48,y,52),(-48,y,36),.5,.5,steel);cylinder('Pendant shade',(-48,y,35),4,2,enamel,None,8);cylinder('Pendant lens',(-48,y,33.8),3,.4,glow,None,8);light('Fountain pendant light',(-48,y,33),65,10)
 parent=STRUCT
# Retail/closed alternatives have an actual lower ceiling, not simply darker lighting.
if DROPPED:prism('Dropped acoustic ceiling',PLAN,39,41,enamel)
# Closed model boards the display panes while leaving all exterior thresholds open.
if CLOSED:
 for x,w in [(-6,52),(50,36)]:
  for z in [17,29]:B('Closed front window board',(x,-1.6,z),(w,1,3),utility,'board')
 for y in [80,172]:
  for z in [17,29]:B('Closed side window board',(-77.6,y,z),(1,64,3),utility,'board')
# Fixture geometry and staged lights are state-aware; glass/sign masks emit only intended pixels.
for x,y,z in [(20,56,38 if DROPPED else 49),(20,150,38 if DROPPED else 49),(0,224,38 if DROPPED else 49),(-28,80,99),(30,188,99)]:
 ceiling=39 if z==38 else 50 if z==49 else 100;B('Ceiling light mount',(x,y,(z+.5+ceiling)/2),(4,4,ceiling-z-.5),steel);B('Ceiling fixture',(x,y,z),(14,6,1),enamel);B('Fixture lens',(x,y,z-.7),(12,4,.4),glow);light('Ceiling light',(x,y,z-1),100,14)
for x,y,z in [(44,258,38),(-20,258,90)]:
 B('Rear wall lamp',(x,y,z),(5,3,6),steel);B('Rear lamp lens',(x,y+1.6,z),(3,.4,4),glow);light('Rear threshold light',(x,y+4,z-1),50,8)
# Optional pavement and rear service fittings; keep/drop their matching colliders together.
parent=LOT
prism('Front corner pavement',[(-92,-16),(92,-16),(92,0),(-44,0),(-76,32),(-76,272),(-92,272)],0,.5,paving)
B('Rear stair apron',(-22,300,.25),(84,76,.5),paving);B('Rear service apron',(44,270,.25),(48,28,.5),paving)
for x in [88,99]:
 parent=lot_prop('service_bin');B('Bin',(x,246,7),(7,8,14),steel);B('Bin lid',(x,246,14.5),(8,9,1),cream);col('Service bin',x,246,8,9,15,parent.name,group='lot')
# All collider metadata is converted into glTF metres, matching the exported hidden mesh.
parent=ROOT;v=[];faces=[]
for c in COLL:
 x,y,z=c['center'];w,d,h=c['size'];k=len(v);v += [(x+a*w/2,y+b*d/2,z+s*h/2) for a,b,s in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]];faces += [tuple(k+j for j in f) for f in [(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)]]
collision=mesh('collision',v,faces,steel);collision.hide_render=True;collision['collision_only']=True;collision['render']=False
for g in [STRUCT,STAIR,blade,*([BAR] if FOUNTAIN else []),*STOOLS,*PROPS,LOT]:
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
bpy.ops.export_scene.gltf(filepath=str(R/('corner-drugstore-'+STATE+'.glb')),use_selection=True,export_format='GLB',export_yup=True,export_extras=True)
boxes=[]
for b in COLL:
 x,y,z=b['center'];w,d,h=b['size'];q={k:v for k,v in b.items() if k not in ['center','size']};q.update(center=[x,z,-y],size=[w,h,d]);boxes.append(q)
info={'name':'Corner Cafe' if STATE=='cafe' else 'Corner Drugstore','state':STATE,'file':'corner-drugstore-'+STATE+'.glb','years':{'family':[1935,1975],'retail':[1976,2005],'closed':[2006,None],'cafe':[2006,None]}[STATE],'built_style_years':[1920,1935],'units':'metres','axes':'glTF Y up, main street front +Z; corner entry outward diagonally (-X,+Z)','origin':'Grade at centre of original straight front edge. All four states preserve identical origin and door anchors.','footprint_m':[152*U,256*U],'heights_m':{'ground_floor':F*U,'upper_floor':54*U,'store_ceiling':(39 if DROPPED else 50)*U,'upper_ceiling':100*U,'coping':114*U,'chimney':136.4*U},'triangles_visible':tris,'materials':materials,'pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'doors':DOORS,'fountain':{'present':FOUNTAIN,'node':'fountain' if FOUNTAIN else None,'fixed_stools':[o.name for o in STOOLS],'notes':'Fixed counter, taps, prep cabinet and bolted stools only. No loose stock, cups or dispensing behavior.'},'dropped_ceiling':DROPPED,'rooms':[{'id':'shop','floor_m':2*U,'ceiling_m':(39 if DROPPED else 50)*U,'notes':'Open floor for game-provided merchandise/shelving; fixed rear prescription/service counter.'},{'id':'upper_offices','floor_m':54*U,'ceiling_m':100*U,'notes':'Clean upper shell with partition opening; reached by the outside rear staircase.'}],'stairs':{'node':'exterior_stairs','lower_floor_m':2*U,'upper_floor_m':54*U,'half_landing_m':28*U,'risers':26,'rise_m':2*U,'tread_m':3*U,'notes':'Real two-flight metal treads, stringers, posts and rails. Height-aware navigation/collision remains runtime work.'},'sign_name':{'node':'sign_name','material':'sign_name','texture':'textures/signs-'+STATE+'.png','rect_art':[0,0,100,16],'text':'CORNER CAFE' if STATE=='cafe' else 'CORNER DRUGSTORE','binding':'Exactly one town-name region. Separate blade region carries only generic DRUGS/CAFE descriptor; repaint name region only.'},'collision':{'node':'collision','render':False,'coordinate_frame':'glTF metres: Y up, +Z front','boxes_gltf_y_up':boxes,'use_the_walls':True,'notes':'Exterior doorways have no closed panels or gate boxes. Store fixtures are full-height from their supporting floor. Steps, stringers, walls and floors use real architectural contact; runtime needs height-aware traversal.'},'lot_node':'lot','lot_bounds_gltf_m':{'x':[-102*U,108*U],'z':[-338*U,16*U]},'runtime_handoff':['States are alternative simulation-supported outcomes, not an automatic forced timeline.','Cafe requires the old fountain to have been retained, stored or recovered according to actual history; never conjure it after removal.','Closed is a display state, not permission to enter or an invented closure event. Runtime controls business hours, access and state selection.','Open exterior thresholds deliberately carry no panels/gates; all original door positions are identical across states.','Glass/sign emission masks, glTF collision metadata parsing, upper-floor/stair navigation and furnishings need integration.','No residents, brands, prescriptions, inventory, prices, food service or business history are generated.'],'swatches_used':['brick_red','wall_stucco_red','roof_membrane_grey','pavement'],'licence':'LimeZu shared swatches are Timerpg-only; retain original credit. Supplemental art and all geometry original.','renders':'Actual Blender desktop previews, not Filament/S25 captures.'}
(R/('state-'+STATE+'.json')).write_text(json.dumps(info,indent=2)+'\n');print(json.dumps({'state':STATE,'triangles':tris,'materials':len(materials),'doors':len(DOORS),'boxes':len(boxes)}))
# Presentation only, excluded from GLB.
parent=None;gm=bpy.data.materials.new('Presentation terrain');gm.diffuse_color=(.25,.29,.24,1);bpy.ops.mesh.primitive_plane_add(size=200,location=(0,0,-.03));bpy.context.object.data.materials.append(gm)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=20;scene.cycles.use_denoising=False;scene.cycles.max_bounces=4;scene.render.resolution_x=1280;scene.render.resolution_y=960;scene.render.resolution_percentage=100;scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
w=bpy.data.worlds.new('Corner sky');w.use_nodes=True;scene.world=w;bg=w.node_tree.nodes.get('Background');sun=bpy.data.lights.new('Sun','SUN');so=bpy.data.objects.new('Sun',sun);bpy.context.collection.objects.link(so);so.rotation_euler=(.4,-.5,-.6);sun.angle=.08
bpy.ops.object.camera_add();cam=bpy.context.object;scene.camera=cam
ONLY=next((set(a.split('=',1)[1].split(',')) for a in sys.argv if a.startswith('--shots=')),None)
if '--review' in sys.argv:scene.cycles.samples=8;scene.render.resolution_percentage=50
SAVED=False

def shot(n,p,t,scale=35,night=False,perspective=False):
 global SAVED
 cam.data.type='PERSP' if perspective else 'ORTHO';cam.data.lens=22 if perspective else 32;cam.data.ortho_scale=scale;cam.location=p;cam.rotation_euler=(Vector(t)-cam.location).to_track_quat('-Z','Y').to_euler();bg.inputs[0].default_value=(*((.12,.19,.3) if night else (.64,.72,.77)),1);bg.inputs[1].default_value=.5 if night else .65;sun.energy=.25 if night else 2;sun.color=(.5,.67,1) if night else (1,.9,.77)
 for d,power in LIGHTS:d.energy=0 if CLOSED else power if night else .12*power
 for m in [glass,glow,sign]:m.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=0 if CLOSED else .6 if night else .04
 scene.render.filepath=str(R/'renders'/(n+'.png'))
 if not SAVED:bpy.ops.wm.save_as_mainfile(filepath=str(R/('corner-drugstore-'+STATE+'.blend')));SAVED=True
 if ONLY is None or n in ONLY:bpy.ops.render.render(write_still=True)
if STATE=='family':
 shot('01-front-day',(-30,-33,24),(-1,9,4),36)
 shot('02-blue-hour',(-30,-33,23),(-1,9,4),36,True)
 shot('03-rear',(25,40,25),(0,11,4),36)
 shot('04-above',(-18,-18,44),(0,11,2),37)
 shot('05-person-height',(-14,-13,1.7),(-3,4,4),35,False,True)
 shot('06-fountain',(2.4,5.3,1.7),(-4.2,10.8,1.6),35,True,True)
 shot('10-upper-offices',(4,9,5.9),(-2,17,5.7),35,True,True)
elif STATE=='retail':shot('07-retail',(2.4,5.3,1.7),(-4.2,10.8,1.6),35,True,True)
elif STATE=='closed':shot('08-closed',(-30,-33,24),(-1,9,4),36)
else:
 shot('09-cafe',(2.4,5.3,1.7),(-4.2,10.8,1.6),35,True,True)
 shot('11-cafe-exterior',(-30,-33,24),(-1,9,4),36)
