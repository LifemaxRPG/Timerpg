"""Original 1920s stone-front bank and separate later two-lane drive-through state."""
import bpy,math,json,sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;U=.0776;T=.0388;F=4;LATER='--later' in sys.argv;STATE='drive-through' if LATER else '1920s';REG=json.loads((R/'art-regions.json').read_text());COLL=[];LIGHTS=[];PROPS=[];DOORS=[];TERMINALS=[]
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
def empty(n,p=None):
 o=bpy.data.objects.new(n,None);bpy.context.collection.objects.link(o);o.parent=p;return o
ROOT=empty('small_town_bank');STRUCT=empty('structure',ROOT);LOT=empty('lot',ROOT);VAULT=empty('vault_door',STRUCT);DRIVE=empty('drive_through',STRUCT) if LATER else None;parent=STRUCT

def mat(n,f,emit=False,alpha=False):
 m=bpy.data.materials.new(n);m.use_nodes=True;p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Roughness'].default_value=.8;t=m.node_tree.nodes.new('ShaderNodeTexImage');t.image=bpy.data.images.load(str(R/'textures'/f),check_existing=True);t.interpolation='Closest';t.extension='REPEAT';m.node_tree.links.new(t.outputs['Color'],p.inputs['Base Color']);m['texture_file']=f
 if alpha:m.node_tree.links.new(t.outputs['Alpha'],p.inputs['Alpha']);m.surface_render_method='DITHERED'
 if emit:
  e=t
  if n=='glass_bank':e=m.node_tree.nodes.new('ShaderNodeTexImage');e.image=bpy.data.images.load(str(R/'textures/pane-emission.png'));e.interpolation='Closest';e.extension='REPEAT'
  m.node_tree.links.new(e.outputs['Color'],p.inputs['Emission Color']);p.inputs['Emission Strength'].default_value=.04
 return m
wall=mat('wall_brick','brick.png');cream=mat('wall_stone','stone.png');oak=mat('wall_interior','interior.png');roof=mat('roof_membrane','roof.png');bronze=mat('detail_bronze','bronze.png');steel=mat('detail_steel','steel.png');floor=mat('ground_floor','floor.png');glass=mat('glass_bank','glass.png',True,True);glow=mat('glow_lamps','glow.png',True);sign=mat('sign_name','signs.png');utility=mat('detail_panels','panels.png');paving=mat('ground_paving','paving.png')
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
def door_open(n,x,y,w=24,h=38,side='front'):
 dx,dy={'front':(0,-2),'back':(0,2),'right':(2,0),'left':(-2,0)}[side];o=door(n,(x+dx,y+dy,F),{'front':0,'back':math.pi,'right':math.pi/2,'left':-math.pi/2}[side]);DOORS.append({'node':n,'position':[o.location.x,o.location.z,-o.location.y],'outward':{'front':[0,0,1],'back':[0,0,-1],'right':[1,0,0],'left':[-1,0,0]}[side],'width_m':w*U,'height_m':h*U,'floor_m':F*U,'external':True,'open':True})
def block(n,x,y,w,d,bottom,top,attachment=None,group='structure'):
 COLL.append({'source':n,'center':[x*U,y*U,(bottom+top)*U/2],'size':[w*U,d*U,(top-bottom)*U],'support_floor_m':bottom*U,'attachment':attachment or parent.name,'group':group})
def face_prism(n,pts,y,m,depth=2):
 pts=[p for i,p in enumerate(pts) if i==0 or Vector(p)!=Vector(pts[i-1])]
 if len(pts)>2 and Vector(pts[0])==Vector(pts[-1]):pts.pop()
 if len(pts)<3:return
 k=len(pts);v=[(x*U,yy*U,z*U) for yy in [y,y+depth] for x,z in pts];mesh(n,v,[tuple(range(k)),tuple(range(2*k-1,k-1,-1))]+[(i,i+k,(i+1)%k+k,(i+1)%k) for i in range(k)],m)
def arched_transom():
 curve=[(22*math.cos(i*math.pi/8),58+22*math.sin(i*math.pi/8)) for i in range(9)];poly=[(-22,44),(22,44)]+curve;v=[(x*U,-1.2*U,z*U) for x,z in poly];o=mesh('Large round arched transom',v,[tuple(range(len(v)))],glass);W,H=bpy.data.images.get(glass['texture_file']).size
 for li,(x,z) in zip(o.data.polygons[0].loop_indices,poly):o.data.uv_layers.active.data[li].uv=((x+22)*2/W,1-(80-z)*2/H)
 for (a,za),(b,zb) in zip(curve,curve[1:]):face_prism('Stone arch spandrel',[(b,zb),(a,za),(a,80),(b,80)],-1,cream)
 for i in range(8):
  a=i*math.pi/8;b=(i+1)*math.pi/8;face_prism('Stone arch surround',[(22*math.cos(a),58+22*math.sin(a)),(25*math.cos(a),58+25*math.sin(a)),(25*math.cos(b),58+25*math.sin(b)),(22*math.cos(b),58+22*math.sin(b))],-1.6,cream)
 for x in [-24,24]:B('Arch jamb',(x,-1.5,31),(6,4,54),cream)
def window(n,p,w,h,key,side):
 P(n,p,w,h,key,glass,side);x,y,z=p
 B('Stone sill',(x,y,z-h/2-1),(w+4,4,2) if side in ['front','back'] else (4,w+4,2),cream)
 B('Stone lintel',(x,y,z+h/2+1),(w+4,3,2) if side in ['front','back'] else (3,w+4,2),cream)
def disc(n,p,r,depth,m,nside=16):
 x,y,z=p;v=[((x+r*math.cos(i*math.tau/nside))*U,(y+s*depth/2)*U,(z+r*math.sin(i*math.tau/nside))*U) for s in [-1,1] for i in range(nside)];return mesh(n,v,[tuple(range(nside)),tuple(range(2*nside-1,nside-1,-1))]+[(i,i+nside,(i+1)%nside+nside,(i+1)%nside) for i in range(nside)],m)
def ring(n,p,ri,ro,depth,m,nside=16):
 x,y,z=p;v=[((x+r*math.cos(i*math.tau/nside))*U,(y+d)*U,(z+r*math.sin(i*math.tau/nside))*U) for d in [0,depth] for r in [ri,ro] for i in range(nside)];f=[]
 for i in range(nside):
  j=(i+1)%nside;f.extend([(i,i+nside,j+nside,j),(i+2*nside,j+2*nside,j+3*nside,i+3*nside),(i,j,j+2*nside,i+2*nside),(i+nside,i+3*nside,j+3*nside,j+nside)])
 return mesh(n,v,f,m)
# Original 14 x 18m hall. Matching ashlar stone on all exterior walls.
B('Bank floor',(0,116,2),(180,232,4),floor);B('Hall ceiling',(0,116,87),(180,232,2),oak)
old=wall;wall=cream
wall_run('Stone front',-90,90,0,112,0,[[-12,12,4,42],[-22,22,44,80],[-22,-14,10,42],[14,22,10,42],[-74,-54,20,72],[54,74,20,72]])
wall=old;arched_transom();door_open('door_main',0,-1.2)
for x in [-18,18]:P('Bronze-framed sidelight',(x,-1.2,26),8,32,'sidelight',glass)
for x in [-13,13]:B('Bronze entry jamb',(x,-1.6,23),(2,3,38),bronze)
B('Bronze entry transom',(0,-1.6,43),(28,3,2),bronze)
for x in [-64,64]:window('Tall front window',(x,-1.2,46),20,52,'tall','front')
for x in [-84,84]:B('Front pilaster',(x,-1.5,56),(10,3,112),cream)
wall=cream
for x,side,inward in [(-90,'left',True),(90,'right',False)]:
 wall_run('Stone side',0,232,0,92,x,[[y-12,y+12,22,66] for y in [44,100,156,210]],'y',inward)
 for y in [44,100,156,210]:window('Side banking window',(x+(-1.2 if inward else 1.2),y,44),24,44,'side',side)
wall_run('Stone rear',-90,90,0,92,232,[[-76,-60,4,38],[58,78,26,62]],inward=False)
door_open('door_staff',-68,233.2,16,34,'back');window('Rear office window',(68,233.2,44),20,36,'rear','back')
# Plinths/cornices never cross an exterior aperture.
for x in [-90,90]:B('Side stone base',(x,116,4),(3,232,8),cream);B('Side cornice',(x,116,91),(5,236,4),cream)
for a,b in [(-90,-76),(-60,90)]:B('Rear base beside door',((a+b)/2,232,4),(b-a,3,8),cream)
B('Rear cornice',(0,232,91),(184,5,4),cream)
B('Membrane roof',(0,116,91),(184,236,2),roof)
for x,y,w,d in [(-91,116,3,232),(91,116,3,232),(0,233,184,3)]:B('Stone parapet',(x,y,97),(w,d,10),wall);B('Parapet coping',(x,y,103),(w+2 if d==3 else 5,5 if d==3 else d,2),cream)
B('Front coping',(0,0,113),(186,6,2),cream)
P('sign_name',(0,-1.2,102),128,18,'nameplate',sign)
# Two tall low-sided stone columns, bearing a real portico entablature and roof.
B('Front terrace',(0,-10,2),(200,20,4),paving)
B('Lower approach step',(0,-26,1),(80,4,2),paving);B('Upper approach step',(0,-22,2),(80,4,4),paving)
for x in [-34,34]:
 B('Column plinth',(x,-12,7),(14,14,6),cream);cylinder('Column torus',(x,-12,11),5.5,2,cream,None,12);cylinder('Column shaft',(x,-12,41),4,58,cream,None,12);cylinder('Column neck',(x,-12,71),4.8,2,cream,None,12);B('Column capital',(x,-12,75),(14,14,6),cream);block('Portico column',x,-12,14,14,0,78)
 B('Portico side beam',(x,-6,83),(8,16,10),cream)
B('Portico entablature',(0,-12,83),(104,14,10),cream);B('Portico cornice',(0,-12,89),(110,18,2),cream);B('Portico roof',(0,-9,91),(112,24,2),roof)
# Side offices and a closed fixed vault enclosure behind the teller line.
wall=old
for a,b,c in [(-90,-48,-68),(48,90,68)]:wall_run('Office front',a,b,4,86,170,[[c-8,c+8,4,38]])
for x in [-48,48]:wall_run('Office inner wall',170,232,4,86,x,[],'y',x<0)
wall_run('Vault front wall',-48,48,4,70,194,[[-24,24,4,54]])
B('Vault chamber ceiling',(0,213,69),(96,38,2),cream)
# The vault door is intentionally fixed/closed interior geometry, not an exterior gate.
parent=VAULT
B('Vault backing plate',(0,193,29),(44,4,50),steel)
for x in [-24,24]:B('Vault jamb',(x,192,30),(6,8,52),cream)
B('Vault header',(0,192,57),(54,8,6),cream);B('Vault threshold',(0,192,6),(48,8,4),steel)
disc('Circular vault leaf',(0,190,28),20,6,steel);ring('Vault rim',(0,185.8,28),18.5,22,1.2,bronze)
for x in [-8,8]:B('Vertical locking bar',(x,186.3,28),(1.3,1.4,28),bronze)
for z in [18,38]:B('Locking crossbar',(0,186.3,z),(28,1.4,1.2),bronze)
disc('Wheel hub',(0,185,28),1.5,4,bronze,12);ring('Vault handwheel',(0,182.5,28),4.7,5.5,1,bronze,12)
for a in [math.pi/2,7*math.pi/6,11*math.pi/6]:beam('Wheel spoke',(0,183,28),(5.1*math.cos(a),183,28+5.1*math.sin(a)),.7,.7,bronze)
for z in [14,42]:cylinder('Vault hinge',(22,190,z),2.5,8,steel,None,8)
block('Fixed vault front',0,189.25,54,14.5,4,60,VAULT.name)
parent=STRUCT
# Four teller wickets and a fixed timber/stone counter, with clear staff routes at both ends.
B('Teller counter',(0,144,11),(128,16,14),utility,'wood');B('Teller counter cap',(0,144,18.5),(132,18,1),cream)
for x in [-64,-32,0,32,64]:B('Teller bronze post',(x,144,31),(2,2,26),bronze)
B('Teller top rail',(0,144,43),(130,2,2),bronze)
for x in [-48,-16,16,48]:P('Clear teller wicket',(x,142.8,31),30,22,'teller',glass);P('Transaction dish',(x,138,19.05),8,6,'tray',utility,'up')
block('Teller line',0,144,132,18,4,44)
# Properly mounted exterior and interior fixtures.
for x in [-84,84]:
 B('Front lamp bracket',(x,-3,56),(5,4,12),bronze);B('Front lantern lens',(x,-5.5,56),(4,2,8),glow);B('Lantern hood',(x,-5.5,61),(7,5,2),bronze);light('Portico wall light',(x,-8,53),90,12)
B('Portico ceiling fixture',(0,-10,89.5),(14,10,1),bronze);B('Portico light lens',(0,-10,88.8),(12,8,.4),glow);light('Front entry pool',(0,-10,88),140,16)
for x,y,z,power in [(0,64,85,180),(0,126,85,180),(-68,198,84,100),(68,198,84,100),(0,182,66,100)]:
 ceiling=86
 if z==66:beam('Vault pendant stem',(x,y,z+.5),(x,y,ceiling),.6,.6,bronze)
 else:B('Fixture mount',(x,y,(z+.5+ceiling)/2),(4,4,ceiling-z-.5),bronze)
 B('Ceiling fixture',(x,y,z),(16,12,1),bronze);B('Ceiling lens',(x,y,z-.7),(14,10,.4),glow);light('Bank hall light',(x,y,z-1),power,16)
B('Rear staff landing',(-68,240,2),(28,16,4),paving);B('Rear staff step',(-68,250,1),(28,4,2),paving)
B('Rear lamp',(-56,234,42),(5,3,8),bronze);B('Rear lamp lens',(-56,235.6,42),(3,.4,5),glow);light('Staff entry light',(-56,238,39),65,10)
# Complete roof/service side with seated equipment, access hatch and downpipes.
B('Roof ventilator curb',(-50,178,93),(26,28,2),steel);B('Roof ventilator',(-50,178,103),(24,26,18),cream);P('Ventilator louver',(-50,164.8,103),24,16,'louver',utility)
B('Ventilator lid',(-50,178,112.5),(28,30,1),steel);B('Roof hatch curb',(40,126,93),(22,26,2),steel);B('Roof hatch',(40,126,94.5),(24,28,1),bronze)
for x,y in [(-92.5,230),(92.5,230)]:beam('Downpipe',(x,y,101),(x,y,0),.8,.8,bronze);col('Downpipe',x,y,1,1,102)
# Later state: two concrete drive-through lanes, overhead canopy and remote service kiosks.
if LATER:
 parent=DRIVE
 B('Canopy membrane',(161,117,59),(134,110,2),roof)
 for x,y,w,d in [(161,62,138,4),(161,172,138,4),(94,117,4,106),(228,117,4,106)]:B('Canopy fascia',(x,y,60),(w,d,6),cream)
 for x in [100,222]:
  B('Long canopy beam',(x,117,55),(4,106,6),steel)
  for y in [68,166]:
   B('Canopy column base',(x,y,3),(8,8,6),cream);B('Canopy steel column',(x,y,31.5),(3,3,51),steel);B('Canopy capital',(x,y,58),(7,7,2),steel);block('Canopy post',x,y,8,8,0,59,DRIVE.name)
 for y in [68,166]:B('Canopy cross beam',(161,y,55),(122,4,6),steel)
 for x,key in [(132,'lane_1'),(192,'lane_2')]:P('Lane number',(x,59.9,60),4,6,key,utility)
 for x in [132,192]:
  for y in [92,142]:B('Canopy light casing',(x,y,57.5),(14,8,1),steel);B('Canopy light lens',(x,y,56.8),(12,6,.4),glow);light('Drive canopy pool',(x,y,56),90,14)
 for name,xc,w in [('near',106,12),('far',164,16)]:
  parent=empty('terminal_'+name,DRIVE);TERMINALS.append(parent)
  xy=[(xc-w/2+3,90),(xc+w/2-3,90),(xc+w/2,93),(xc+w/2,143),(xc+w/2-3,146),(xc-w/2+3,146),(xc-w/2,143),(xc-w/2,93)];prism('Concrete island',xy,0,2,cream)
  ux=xc+w/2-4;B('Drive terminal pedestal',(ux,114,12.5),(8,14,21),steel);B('Terminal cap',(ux,114,23.5),(10,16,1),cream);P('Document terminal',(ux+4.1,114,15),10,18,'terminal',utility,'right')
  B('Extended document tray',(ux+6,114,11),(4,8,1),bronze);cylinder('Pneumatic riser',(ux-2,114,39),1,34,steel,None,8)
  block('Drive terminal and island',(xc-w/2+ux+8)/2,118,ux+8-(xc-w/2),56,0,56,parent.name)
  ref=empty('anchor_'+name,ROOT);ref.location=((ux+8)*U,114*U,11*U);ref['reference_only']=True
 parent=DRIVE
 beam('Overhead service conduit',(90,114,56),(166,114,56),2,2,steel)
 # Night-deposit fixture is mounted into solid side masonry, clear of the front window.
 deposit=empty('night_deposit',DRIVE);parent=deposit
 B('Night deposit case',(92,16,20),(4,12,18),bronze);P('Night deposit envelope',(94.1,16,20),10,16,'night_drop',utility,'right');B('Deposit handle',(95,16,21),(2,8,1),steel);block('Night deposit',93,16,6,12,0,29,deposit.name)
 ref=empty('anchor_night_deposit',ROOT);ref.location=(98*U,16*U,20*U);ref['reference_only']=True
 parent=STRUCT
# Optional lot grounds. Adjacent slabs butt instead of producing coplanar overlaps.
parent=LOT
B('Front public walk',(0,-34,.25),(216,12,.5),paving);B('West walk',(-98,116,.25),(12,232,.5),paving)
if not LATER:B('East walk',(98,116,.25),(12,232,.5),paving)
B('Rear apron',(-4,248,.25),(200,32,.5),paving)
if LATER:
 B('Drive-through pavement',(162,118,.25),(132,292,.5),paving)
 for x in [112,152,172,212]:B('Lane edge stripe',(x,100,.6),(1,240,.2),cream)
 for x in [132,192]:
  for y in [28,198]:
   mesh('Lane direction arrow',[(xx*U,yy*U,.75*U) for xx,yy in [(x-3,y),(x+3,y),(x,y+7)]],[(0,1,2)],cream)
# Quiet small-town service dressing, kept optional as requested.
for x in [-82,-72]:
 parent=lot_prop('rear_bin');B('Bin',(x,260,7),(7,8,14),steel);B('Bin lid',(x,260,14.5),(8,9,1),bronze);col('Rear bin',x,260,8,9,15,parent.name,group='lot')
# Hidden box-only companion; metadata converts coordinates to glTF Y-up metres.
parent=ROOT;v=[];faces=[]
for c in COLL:
 x,y,z=c['center'];w,d,h=c['size'];k=len(v);v += [(x+a*w/2,y+b*d/2,z+s*h/2) for a,b,s in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]];faces += [tuple(k+j for j in f) for f in [(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)]]
collision=mesh('collision',v,faces,steel);collision.hide_render=True;collision['collision_only']=True;collision['render']=False
GROUPS=[STRUCT,VAULT]+([DRIVE,deposit,*TERMINALS] if LATER else [])+PROPS+[LOT]
for g in GROUPS:
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
bpy.ops.export_scene.gltf(filepath=str(R/('small-town-bank-'+STATE+'.glb')),use_selection=True,export_format='GLB',export_yup=True,export_extras=True)
boxes=[]
for b in COLL:
 x,y,z=b['center'];w,d,h=b['size'];q={k:v for k,v in b.items() if k not in ['center','size']};q.update(center=[x,z,-y],size=[w,h,d]);boxes.append(q)
info={'name':'Town State Bank','state':STATE,'file':'small-town-bank-'+STATE+'.glb','design_years':[1900,1930],'drive_through_from':1965 if LATER else None,'units':'metres','axes':'glTF Y up; public entrance +Z','origin':'Grade at centre of original front stone wall. Both states retain the same origin and original door anchors.','exterior_walls':'Matching ashlar stone on front, sides, rear and parapets','main_footprint_m':[180*U,232*U],'heights_m':{'hall_floor':F*U,'hall_ceiling':86*U,'front_coping':114*U,'canopy_nominal_clearance':51.5*U if LATER else None},'triangles_visible':tris,'materials':materials,'pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'doors':DOORS,'rooms':[{'id':'banking_hall','floor_m':F*U,'notes':'Fixed four-wicket teller counter; open staff routes at both ends. No loose furnishings or generated financial contents.'},{'id':'left_office','floor_m':F*U,'notes':'Unfurnished side-office shell; rear staff exit.'},{'id':'right_office','floor_m':F*U,'notes':'Unfurnished side-office shell.'},{'id':'vault_chamber','floor_m':F*U,'notes':'Closed fixed circular vault-door geometry and enclosure; no opening, security, loot or account behavior.'}],'vault':{'node':'vault_door','fixed_closed':True,'notes':'Circular leaf, metal rim, hinges, locking bars and three-spoke handwheel; not an exterior entry anchor.'},'sign_name':{'node':'sign_name','material':'sign_name','texture':'textures/signs.png','rect_art':[0,0,128,18],'text':'TOWN STATE BANK','binding':'Exactly one carved frieze name region. Repaint per town; preview name creates no real business or bank identity.'},'collision':{'node':'collision','render':False,'coordinate_frame':'glTF metres: Y up, +Z front','boxes_gltf_y_up':boxes,'use_the_walls':True,'notes':'Exterior entries remain open without door panels/gates. Teller/vault blockers begin at the hall floor; exterior columns/fixtures are full-height from grade. Actual architectural surfaces provide walls/floors.'},'lot_node':'lot','lot_bounds_gltf_m':{'x':[-110*U,(236 if LATER else 110)*U],'z':[-265*U,40*U]},'drive_through':{'present':LATER,'node':'drive_through' if LATER else None,'lane_direction_gltf':[0,0,-1],'lane_reference_only':True,'lanes':[{'bounds_x_m':[a*U,b*U],'entry':[c*U,.5*U,28*U],'service':[c*U,.5*U,-114*U],'exit':[c*U,.5*U,-264*U]} for a,b,c in [(112,152,132),(172,212,192)]] if LATER else [],'terminals':[o.name for o in TERMINALS],'night_deposit_node':'night_deposit' if LATER else None,'notes':'Static canopy, islands, document terminals, pneumatic conduit and deposit fixture. Anchors/lanes are references only; no banking transactions, tube transport, vehicle spawning/routing or deposit handling is implemented.'},'runtime_handoff':['No financial balances, money, accounts, transactions, residents, ownership or town history are generated.','State construction/drive-through timing must follow simulation facts; 1965 is eligibility, not a forced event.','Exterior thresholds are open. The interior vault is intentionally fixed/closed and not a working lock.','Importer must consume glTF collision coordinates without applying the older Blender-frame rotation.','Glass emission mask, lights, navigation, state selection and vehicle interactions need game integration.','Both complete states preserve the original doors and carved-name region; pick one GLB at a time.'],'swatches_used':['brick_red','wall_stucco_red','roof_membrane_grey','pavement'],'licence':'LimeZu shared swatches are Timerpg-only; retain original credit. Supplemental pixel art and all geometry original.','renders':'Actual Blender desktop previews, not Filament/S25 captures.'}
(R/('state-'+STATE+'.json')).write_text(json.dumps(info,indent=2)+'\n');print(json.dumps({'state':STATE,'triangles':tris,'materials':len(materials),'doors':len(DOORS),'boxes':len(boxes)}))
# Presentation only: cameras, terrain and lights are excluded from GLB.
parent=None;gm=bpy.data.materials.new('Presentation terrain');gm.diffuse_color=(.25,.29,.24,1);bpy.ops.mesh.primitive_plane_add(size=200,location=(0,0,-.03));bpy.context.object.data.materials.append(gm)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=20;scene.cycles.use_denoising=False;scene.cycles.max_bounces=4;scene.render.resolution_x=1280;scene.render.resolution_y=960;scene.render.resolution_percentage=100;scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
w=bpy.data.worlds.new('Town bank sky');w.use_nodes=True;scene.world=w;bg=w.node_tree.nodes.get('Background');sun=bpy.data.lights.new('Sun','SUN');so=bpy.data.objects.new('Sun',sun);bpy.context.collection.objects.link(so);so.rotation_euler=(.4,-.5,-.6);sun.angle=.08
bpy.ops.object.camera_add();cam=bpy.context.object;scene.camera=cam
ONLY=next((set(a.split('=',1)[1].split(',')) for a in sys.argv if a.startswith('--shots=')),None)
if '--review' in sys.argv:scene.cycles.samples=8;scene.render.resolution_percentage=50
SAVED=False

def shot(n,p,t,scale=34,night=False,perspective=False):
 global SAVED
 cam.data.type='PERSP' if perspective else 'ORTHO';cam.data.lens=(18 if n=='07-vault' else 24) if perspective else 32;cam.data.ortho_scale=scale;cam.location=p;cam.rotation_euler=(Vector(t)-cam.location).to_track_quat('-Z','Y').to_euler();bg.inputs[0].default_value=(*((.12,.19,.3) if night else (.64,.72,.77)),1);bg.inputs[1].default_value=.5 if night else .65;sun.energy=.25 if night else 2;sun.color=(.5,.67,1) if night else (1,.9,.77)
 for d,power in LIGHTS:d.energy=power if night else .12*power
 for m in [glass,glow]:m.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=.6 if night else .04
 scene.render.filepath=str(R/'renders'/(n+'.png'))
 if not SAVED:bpy.ops.wm.save_as_mainfile(filepath=str(R/('small-town-bank-'+STATE+'.blend')));SAVED=True
 if ONLY is None or n in ONLY:bpy.ops.render.render(write_still=True)
if LATER:
 shot('08-drive-through',(32,-25,20),(3,9,3),38)
 shot('09-drive-lanes',(16.6,5.8,1.3),(9.6,8.85,1.3),34,True,True)
else:
 shot('01-front-day',(28,-31,24),(0,8,4),34)
 shot('02-blue-hour',(28,-31,22),(0,8,4),34,True)
 shot('03-rear',(-27,36,23),(0,9,4),34)
 shot('04-above',(18,-16,43),(0,9,2),35)
 shot('05-person-height',(-11,-14,1.7),(0,2,4.7),34,False,True)
 shot('06-banking-hall',(1.8,7.2,2.01),(-.3,16.0,2.17),34,True,True)
 shot('07-vault',(1.4,12.2,2.01),(0,14.9,2.5),34,True,True)
