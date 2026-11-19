"""Original Willow House: converted residence, chapel wing and covered arrival lane."""
import bpy,math,json,sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;U=.0776;T=.0388;F=6;REG=json.loads((R/'art-regions.json').read_text());COLL=[];LIGHTS=[];PROPS=[];DOORS=[];MOVING=[]
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
def empty(n,p=None):
 o=bpy.data.objects.new(n,None);bpy.context.collection.objects.link(o);o.parent=p;return o
ROOT=empty('willow_house');STRUCT=empty('structure',ROOT);LOT=empty('lot',ROOT);parent=STRUCT

def mat(n,f,emit=False):
 m=bpy.data.materials.new(n);m.use_nodes=True;p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Roughness'].default_value=.82;t=m.node_tree.nodes.new('ShaderNodeTexImage');t.image=bpy.data.images.load(str(R/'textures'/f),check_existing=True);t.interpolation='Closest';t.extension='REPEAT';m.node_tree.links.new(t.outputs['Color'],p.inputs['Base Color']);m['texture_file']=f
 if emit:
  e=t
  if n=='glass_home':e=m.node_tree.nodes.new('ShaderNodeTexImage');e.image=bpy.data.images.load(str(R/'textures/pane-emission.png'));e.interpolation='Closest';e.extension='REPEAT'
  m.node_tree.links.new(e.outputs['Color'],p.inputs['Emission Color']);p.inputs['Emission Strength'].default_value=.04
 return m
wall=mat('wall_siding','siding.png');oak=mat('wall_interior','interior.png');brick=mat('wall_brick_base','brick.png');roof=mat('roof_slate','slate.png');cream=mat('detail_trim','trim.png');wood=mat('ground_oak','wood.png');steel=mat('detail_steel','steel.png');utility=steel;glass=mat('glass_home','glass-panels.png',True);glow=mat('glow_lamps','glow.png',True);sign=mat('sign_name','signs.png');paving=mat('ground_paving','paving.png');lawn=mat('ground_lawn','lawn.png')
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
   o=B(n,((l+r)/2,fixed,(lo+hi)/2) if axis=='x' else (fixed,(l+r)/2,(lo+hi)/2),(r-l,2,hi-lo) if axis=='x' else (2,r-l,hi-lo))
   o.data.materials.append(oak);f=o.data.polygons[(3 if inward else 1) if axis=='x' else (2 if inward else 4)];f.material_index=1;old=bpy.data.images.get(wall['texture_file']).size;new=bpy.data.images.get(oak['texture_file']).size
   for li in f.loop_indices:o.data.uv_layers.active.data[li].uv*=Vector((old[0]/new[0],old[1]/new[1]))
def enter(n,x,y,w,h,key,side='front',floor=F,leaves=1,external=True):
 global parent
 parent=empty('panel_'+n,STRUCT);MOVING.append(parent)
 for i in range(leaves):
  offset=(i+.5)*w/leaves-w/2;p=(x+offset,y,floor+h/2) if side in ['front','back'] else (x,y+offset,floor+h/2)
  P(n+' leaf',p,w/leaves,h,key,glass,side)
 parent=STRUCT;dx,dy={'front':(0,-2),'back':(0,2),'right':(2,0),'left':(-2,0)}[side];o=door(n,(x+dx,y+dy,floor),{'front':0,'back':math.pi,'right':math.pi/2,'left':-math.pi/2}[side]);DOORS.append({'node':n,'position':[o.location.x,o.location.z,-o.location.y],'outward':{'front':[0,0,1],'back':[0,0,-1],'right':[1,0,0],'left':[-1,0,0]}[side],'floor_m':floor*U,'panel':'panel_'+n,'external':external})
 col('Closed '+n,x,y,w if side in ['front','back'] else 2,2 if side in ['front','back'] else w,h+floor,'panel_'+n,'panel_'+n)
def slab(n,pts,m=roof,depth=2):
 # Input perimeter viewed from above is counter-clockwise.
 k=len(pts);v=[(x*U,y*U,(z-d)*U) for d in [0,depth] for x,y,z in pts];mesh(n,v,[tuple(range(k))]+[(i,(i+1)%k,(i+1)%k+k,i+k) for i in range(k)],m);mesh(n+' soffit',v,[tuple(range(2*k-1,k-1,-1))],oak)
def gable(n,x0,x1,y,z,eave,ridge,depth=2,m=wall):
 # Closed triangular prism, ridge at midpoint; no zero-height duplicate vertices.
 pts=[(x0,eave),(x1,eave),((x0+x1)/2,ridge)];v=[(x*U,yy*U,h*U) for yy in [y-depth/2,y+depth/2] for x,h in pts];return mesh(n,v,[(0,1,2),(5,4,3),(0,3,4,1),(1,4,5,2),(2,5,3,0)],m)
def roof_gable(n,x0,x1,y0,y1,eave,ridge):
 mid=(x0+x1)/2
 slab(n+' west',[(x0,y0,eave),(mid,y0,ridge),(mid,y1,ridge),(x0,y1,eave)])
 slab(n+' east',[(mid,y0,ridge),(x1,y0,eave),(x1,y1,eave),(mid,y1,ridge)])
 for y in [y0,y1]:
  beam('Cream rake',(x0,y,eave),(mid,y,ridge),1.4,1.4,cream);beam('Cream rake',(mid,y,ridge),(x1,y,eave),1.4,1.4,cream)
 for x in [x0,x1]:beam('Eave gutter',(x,y0,eave-1),(x,y1,eave-1),1.2,1.2,steel)
def win(n,p,w,h,key,side):
 P(n,p,w,h,key,glass,side);x,y,z=p
 if side in ['front','back']:
  B('Window sill',(x,y,z-h/2-1),(w+4,4,2),cream)
  for xx in [x-w/2-1,x+w/2+1]:B('Window casing',(xx,y,z),(2,3,h+4),cream)
  B('Window lintel',(x,y,z+h/2+1),(w+4,3,2),cream)
 else:
  B('Window sill',(x,y,z-h/2-1),(4,w+4,2),cream)
  for yy in [y-w/2-1,y+w/2+1]:B('Window casing',(x,yy,z),(3,2,h+4),cream)
  B('Window lintel',(x,y,z+h/2+1),(3,w+4,2),cream)
def lamp(x,y,z,side='front'):
 dx,dy={'front':(0,-1),'back':(0,1),'left':(-1,0),'right':(1,0)}[side]
 B('Lantern bracket',(x,y,z),(3,3,9),steel);B('Lantern glow',(x+dx*2,y+dy*2,z),(3,3,6),glow);B('Lantern cap',(x+dx*2,y+dy*2,z+4),(5,5,2),steel);light('Warm lantern',(x+dx*4,y+dy*4,z),45,6)
# Main converted-house shell. Architectural contact uses real surfaces.
B('Brick foundation',(0,52,2),(128,104,4),brick);B('Main oak floor',(0,52,5),(128,104,2),wood);B('Main ceiling',(0,52,65),(128,104,2),oak)
wall_run('Front clapboard',-64,64,0,68,0,[[-12,12,6,40],[-54,-30,14,44],[30,54,14,44]])
enter('door_main',0,-1.15,24,34,'front_leaf',leaves=2)
for x in [-42,42]:win('Front sash',(x,-1.2,29),24,30,'home_window','front')
wall_run('West clapboard',0,104,0,68,-64,[[43,61,6,38],[2,22,16,44],[82,102,16,44]],'y',True)
enter('door_arrival',-65.15,52,18,32,'side_door','left')
for y in [12,92]:win('West sash',(-65.2,y,30),20,28,'side_window','left')
wall_run('East clapboard',0,104,0,68,64,[[40,64,6,38],[2,14,16,44],[76,96,54,66]],'y',False)
win('Front side sash',(65.2,8,30),12,28,'small_window','right');win('High side sash',(65.2,86,60),20,12,'high_window','right');enter('door_chapel',65.15,52,24,32,'chapel_leaf','right',leaves=2,external=False)
wall_run('Rear clapboard',-64,64,0,68,104,[[-40,-24,6,38],[24,40,6,38],[-10,10,16,44]],inward=False)
win('Rear sash',(0,105.2,30),20,28,'side_window','back');enter('door_staff',32,105.15,16,32,'staff_door','back');enter('door_service_inner',-32,105.15,16,32,'staff_door','back',external=False)
# Attic gable front has a real sash opening. Split the profile around it.
def attic_front(y,cut=True):
 xs=[-64,-12,0,12,64] if cut else [-64,0,64]
 for l,r in zip(xs,xs[1:]):
  h=lambda x:106-abs(x)*40/68
  spans=[(68,78),(94,None)] if cut and -12<=(l+r)/2<=12 else [(68,None)]
  for lo,hi in spans:
   a=hi or h(l);b=hi or h(r);pts=[(l,lo),(r,lo),(r,b),(l,a)];v=[(x*U,yy*U,z*U) for yy in [y-1,y+1] for x,z in pts];mesh('Attic gable',v,[(0,1,2,3),(7,6,5,4),(0,4,5,1),(1,5,6,2),(2,6,7,3),(3,7,4,0)],wall)
attic_front(0);attic_front(104,False);win('Attic sash',(0,-1.2,86),24,16,'attic','front')
roof_gable('House slate roof',-68,68,-4,108,66,106)
# Corner boards, friezes and foundation bands split at every doorway.
for x in [-64,64]:
 for y in [0,104]:B('Corner board',(x,y,36),(3,3,64),cream)
 B('Side frieze',(x,52,67.5),(3,104,3),cream)
for y in [0,104]:B('Gable frieze',(0,y,68),(128,3,3),cream)
B('Brick chimney',(-34,82,101),(10,10,34),brick);B('Chimney cap',(-34,82,119),(14,14,2),cream);B('Flue',(-34,82,120.2),(5,5,.4),steel)
# Public portico: stepped approach and a restrained pediment.
B('Front porch',(0,-14,3),(60,28,6),paving)
for y,top in [(-35.5,2),(-32.5,4),(-29.5,6)]:B('Porch step',(0,y,top/2),(40,3,top),paving)
roof_gable('Portico slate',-30,30,-30,2,48,64);gable('Portico pediment',-28,28,-28,0,48,63,m=cream)
for x in [-24,24]:
 for y in [-24,-4]:
  B('Column plinth',(x,y,7),(7,7,2),cream);B('Column shaft',(x,y,27),(4,4,38),cream);B('Column capital',(x,y,47),(7,7,2),cream);col('Portico column',x,y,7,7,48)
 B('Portico header',(x,-14,48),(5,30,3),cream)
B('Portico front beam',(0,-24,48),(52,5,3),cream)
# Covered side arrival lane, with physical roof underside and credible columns.
slab('Arrival roof south',[(-148,24,46),(-65,24,46),(-65,52,62),(-148,52,62)])
slab('Arrival roof north',[(-148,52,62),(-65,52,62),(-65,80,46),(-148,80,46)])
# End triangle oriented across Y, extruded along X.
v=[(x*U,y*U,z*U) for x in [-149,-147] for y,z in [(24,46),(80,46),(52,62)]];mesh('Arrival end pediment',v,[(2,1,0),(3,4,5),(0,1,4,3),(1,2,5,4),(2,0,3,5)],cream)
for x in [-148,-65]:
 beam('Arrival rake',(x,24,46),(x,52,62),1.5,1.5,cream);beam('Arrival rake',(x,52,62),(x,80,46),1.5,1.5,cream)
for x in [-140,-70]:
 for y in [28,76]:
  B('Arrival pier',(x,y,2),(8,8,4),brick);B('Arrival column',(x,y,24),(4,4,40),cream);B('Arrival capital',(x,y,45),(7,7,2),cream);col('Arrival column',x,y,8,8,47)
 B('Arrival header',(x,52,46),(5,52,2.5),cream)
B('Arrival landing',(-71,52,3),(14,24,6),paving)
for x,top in [(-85.5,2),(-82.5,4),(-79.5,6)]:B('Arrival step',(x,52,top/2),(3,24,top),paving)
# Vaulted chapel shell, directly connected to main house, not a furnished scene.
B('Chapel foundation',(96,70,2),(64,100,4),brick);B('Chapel floor',(96,70,5),(64,100,2),wood)
wall_run('Chapel front',64,128,0,50,20,[[82,110,14,42]]);win('Chapel front privacy',(96,18.8,28),28,28,'chapel_front','front')
wall_run('Chapel east',20,120,0,50,128,[[y-10,y+10,12,44] for y in [38,70,102]],'y',False)
for y in [38,70,102]:win('Chapel leaded privacy',(129.2,y,28),20,32,'chapel_window','right')
wall_run('Chapel west return',104,120,0,50,64,[],'y',True);wall_run('Chapel rear',64,128,0,50,120,[[88,104,6,38]],inward=False);enter('door_chapel_exit',96,121.15,16,32,'staff_door','back')
gable('Chapel front gable',64,128,20,0,50,70);gable('Chapel rear gable',64,128,120,0,50,70);roof_gable('Chapel slate',62,130,16,124,48.75,70)
for x in [64,128]:
 for y in [20,120]:B('Chapel corner',(x,y,26),(3,3,48),cream)
# Rear service bay: shed roof and actual step down from the house.
B('Service floor',(-32,144,1),(64,80,2),paving)
for x,inside in [(-64,True),(0,False)]:
 wall_run('Service side',104,184,0,46,x,[[132,152,19,37]] if x==0 else [],'y',inside)
 v=[(xx*U,y*U,z*U) for xx in [x-1,x+1] for y,z in [(104,46),(184,46),(104,52)]];mesh('Service side wedge',v,[(2,1,0),(3,4,5),(0,1,4,3),(1,2,5,4),(2,0,3,5)],wall)
wall_run('Service rear',-64,0,0,46,184,[[-56,-8,2,40]],inward=False);enter('door_service_bay',-32,185.15,48,38,'garage_leaf','back',floor=2,leaves=2);win('Service privacy',(1.2,142,28),20,18,'garage_window','right')
slab('Service shed roof',[(-66,103,52.1),(2,103,52.1),(2,188,45.7),(-66,188,45.7)])
for x in [-66,2]:beam('Service roof edge',(x,103,52.1),(x,188,45.7),1.5,1.5,cream)
B('Inner service landing',(-32,105,4),(16,2,4),paving);B('Inner service step',(-32,107.5,3),(16,3,2),paving)
# Patio/door landings butt walls; no coplanar overlapping paving.
for n,x,y,w,d in [('Staff',32,112,24,16),('Chapel',96,128,28,16)]:
 B(n+' landing',(x,y,3),(w,d,6),paving)
 for off,top in [(1.5,4),(4.5,2)]:B(n+' step',(x,y+d/2+off,top/2),(w,3,top),paving)
# Fixed reception counter only, with a clear circulation route to all doors.
B('Reception counter',(30,28,14),(30,12,16),wood);B('Reception counter cap',(30,28,22.5),(32,14,1),cream);col('Reception counter',30,28,32,14,23)
# Garden monument: quiet, double-sided editable name panel, no neon or real brand.
B('Monument footing',(96,-16,2),(64,12,4),brick);B('Sign case',(96,-16,17),(52,8,26),steel)
P('sign_name',(96,-20.1,17),48,24,'nameplate',sign);P('sign_name_back',(96,-11.9,17),48,24,'nameplate',sign,'back')
for x in [68,124]:
 B('Monument pier',(x,-16,16),(6,10,28),brick);B('Pier cap',(x,-16,31),(9,13,2),cream);B('Pier lantern',(x,-16,35),(4,4,6),glow);B('Lantern hat',(x,-16,39),(6,6,2),steel);light('Garden lantern',(x,-16,37),25,6)
col('Garden sign',96,-16,66,14,40)
for x in [-18,18]:lamp(x,-2,36)
lamp(-66,38,34,'left');lamp(44,106,34,'back');lamp(-3,186,38,'back');lamp(110,122,35,'back')
for x,y,z in [(0,-16,44),(-110,52,57),(0,50,62),(96,65,64)]:
 beam('Pendant stem',(x,y,z),(x,y,z-7),.6,.6,steel);B('Pendant cage',(x,y,z-10),(6,6,8),steel);B('Pendant glass',(x,y-3.1,z-10),(4,.4,6),glow);B('Pendant glass',(x+3.1,y,z-10),(.4,4,6),glow);B('Pendant glass',(x,y+3.1,z-10),(4,.4,6),glow);B('Pendant glass',(x-3.1,y,z-10),(.4,4,6),glow);B('Pendant glass',(x,y,z-14.1),(4,4,.4),glow);light('Interior warm pool',(x,y,z-14),110,14)
for y in [54,86]:lamp(126,y,38,'left')
for x,y,z in [(-66,102,65),(66,102,65),(130,118,48)]:
 beam('Rainwater downpipe',(x,y,z),(x,y,1),.8,.8,steel);col('Downpipe',x,y,1,1,z)
# Optional grounds. Ground planes tile at the same physical pixel density.
parent=LOT
# Lawn is below paved surfaces, not coplanar; never below grade.
B('Lawn',(0,78,.1),(340,292,.2),lawn)
B('Front footpath',(0,-52,.4),(40,32,.4),paving)
B('Arrival drive',(-118,64,.4),(62,220,.4),paving)
B('Rear service apron',(-32,201,.4),(96,34,.4),paving)
for x,y in [(-56,-20),(48,-22),(143,40),(143,102),(42,130)]:
 parent=lot_prop('garden_shrub');cylinder('Plant bed',(x,y,1),7,2,brick,None,8);cylinder('Low shrub',(x,y,6),6,10,lawn,None,8);col('Shrub',x,y,14,14,11,parent.name,group='lot')
parent=lot_prop('garden_bench')
for y in [142,146]:B('Bench slat',(96,y,7),(28,2,2),wood)
for x in [85,107]:B('Bench foot',(x,144,3),(3,8,6),steel)
B('Bench back',(96,148,12),(28,2,8),wood);col('Bench',96,144,28,10,16,parent.name,group='lot')
parent=lot_prop('service_bins')
for x in [-74,-84]:B('Service bin',(x,174,7),(7,8,14),steel);B('Bin lid',(x,174,14.5),(8,9,1),cream)
col('Service bins',-79,174,18,9,15,parent.name,group='lot')
# Explicit box companion; walls/floors/steps use their own architectural surfaces.
parent=ROOT;v=[];faces=[]
for c in COLL:
 x,y,z=c['center'];w,d,h=c['size'];k=len(v);v += [(x+a*w/2,y+b*d/2,z+s*h/2) for a,b,s in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]];faces += [tuple(k+j for j in f) for f in [(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)]]
collision=mesh('collision',v,faces,steel);collision.hide_render=True;collision['collision_only']=True;collision['render']=False
for g in [STRUCT,*MOVING,*PROPS,LOT]:
 groups={}
 for o in list(g.children):
  if o.type=='MESH' and o.name not in ['sign_name','sign_name_back']:groups.setdefault(o.data.materials[0].name,[]).append(o)
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
bpy.ops.export_scene.gltf(filepath=str(R/'funeral-home.glb'),use_selection=True,export_format='GLB',export_yup=True,export_extras=True)
built=[v.co for o in [STRUCT]+list(STRUCT.children_recursive) if o.type=='MESH' for v in o.data.vertices];bmin=[min(v[i] for v in built) for i in range(3)];bmax=[max(v[i] for v in built) for i in range(3)]
info={'name':'Willow House Funeral Home','era':{'design_years':[1910,1935],'fits':[1950,2030],'notes':'Converted family house with a later chapel, covered arrival lane and discreet service bay. Fictional business; no operations or resident history is created.'},'units':'metres','axes':'glTF Y up, front entrance +Z','origin':'Ground at middle of main house front edge','building_m':[bmax[0]-bmin[0],bmax[1]-bmin[1]],'main_house_m':[128*U,104*U],'built_bounds_blender_m':{'min':bmin,'max':bmax},'lot_bounds_blender_art':[-170,-68,170,224],'heights_m':{'ridge':106*U,'chimney':120.4*U},'triangles_visible':tris,'materials':materials,'pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'doors':DOORS,'door_panels':[g.name for g in MOVING],'rooms':[{'id':'reception','floor_m':F*U,'ceiling_m':64*U,'bounds_blender_art':[-64,0,64,104],'notes':'Open converted-house shell; fixed reception counter only. Attic is a closed non-occupiable roof void.'},{'id':'chapel','floor_m':F*U,'bounds_blender_art':[64,20,128,120],'notes':'Unfurnished vaulted room with privacy glazing; no religious fittings or staged ceremony.'},{'id':'service_bay','floor_m':2*U,'bounds_blender_art':[-64,104,0,184],'notes':'Empty architectural bay. Two 0.1552m risers from main floor to lower bay; no vehicle or operational behavior.'}],'sign_name':{'node':'sign_name','back_node':'sign_name_back','material':'sign_name','texture':'textures/signs.png','rect_art':[0,0,48,24],'text':'WILLOW HOUSE / FUNERAL HOME','binding':'Static fictional preview label. Runtime must replace both front and back panel regions together.'},'collision':{'node':'collision','render':False,'use_the_walls':True,'boxes_blender_z_up':COLL,'strategy':'Architectural surfaces provide walls, floors, steps and closed panels. Full-height boxes cover posts, counter, low sign and optional props.','runtime':'Hide collision mesh; gate_node boxes follow each closed door panel. Height-aware steps, interior navigation and vehicle routing remain integration work.'},'lot_node':'lot','runtime_handoff':['No residents, death events, business history, services or simulation facts are created.','No bodies, coffins, loose furnishings, vehicles or religious ceremony are modeled.','Arrival lane is geometry only; no functioning traffic or accessibility certification is claimed.','Opaque privacy panes use separate emission mask. Importer must retain nearest/repeat sampling and emissive textures.','Blender lights and terrain/cameras are presentation only; staged lighting is not exported as working runtime lights.'],'swatches_used':['wall_stucco_red','brick_red','roof_slate','pavement'],'licence':'Shared LimeZu swatches: Timerpg-only use, retain original credit. Other textures and all geometry original.','renders':'Blender desktop previews, not Filament/S25 captures.'}
(R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n');print(json.dumps({'triangles':tris,'materials':len(materials),'doors':len(DOORS),'boxes':len(COLL)}))
# Presentation only, excluded from export.
parent=None;gm=bpy.data.materials.new('Presentation terrain');gm.diffuse_color=(.25,.29,.24,1);bpy.ops.mesh.primitive_plane_add(size=200,location=(0,0,-.03));bpy.context.object.data.materials.append(gm)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=20;scene.cycles.use_denoising=False;scene.cycles.max_bounces=4;scene.render.resolution_x=1280;scene.render.resolution_y=960;scene.render.resolution_percentage=100;scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
w=bpy.data.worlds.new('Willow House sky');w.use_nodes=True;scene.world=w;bg=w.node_tree.nodes.get('Background');sun=bpy.data.lights.new('Sun','SUN');so=bpy.data.objects.new('Sun',sun);bpy.context.collection.objects.link(so);so.rotation_euler=(.4,-.5,-.6);sun.angle=.08
bpy.ops.object.camera_add();cam=bpy.context.object;scene.camera=cam
ONLY=next((set(a.split('=',1)[1].split(',')) for a in sys.argv if a.startswith('--shots=')),None)
if '--review' in sys.argv:scene.cycles.samples=8;scene.render.resolution_percentage=50

def shot(n,p,t,scale=31,night=False,perspective=False):
 cam.data.type='PERSP' if perspective else 'ORTHO';cam.data.lens=24 if n=='06-chapel' else 30;cam.data.ortho_scale=scale;cam.location=p;cam.rotation_euler=(Vector(t)-cam.location).to_track_quat('-Z','Y').to_euler();bg.inputs[0].default_value=(*((.12,.19,.3) if night else (.64,.72,.77)),1);bg.inputs[1].default_value=.5 if night else .65;sun.energy=.25 if night else 2;sun.color=(.5,.67,1) if night else (1,.9,.77)
 for d,power in LIGHTS:d.energy=power if night else .12*power
 for m in [glass,glow]:m.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=1 if night else .04
 scene.render.filepath=str(R/'renders'/(n+'.png'))
 if n=='01-front-day':bpy.ops.wm.save_as_mainfile(filepath=str(R/'funeral-home.blend'))
 if ONLY is None or n in ONLY:bpy.ops.render.render(write_still=True)
shot('01-front-day',(-29,-32,23),(-.5,6,3),32)
shot('02-blue-hour',(-29,-32,21),(-.5,6,3),32,True)
shot('03-rear',(27,37,22),(-1,7,3),32)
shot('04-above',(-18,-13,40),(-1,7,1),33)
shot('05-person-height',(-8,-18,1.7),(0,3,3),31,False,True)
shot('06-chapel',(8.7,8.1,1.9),(7.2,2.8,3.0),31,True,True)
