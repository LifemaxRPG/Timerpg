"""Original Gothic brick church and compact white clapboard farm-town alternative."""
import bpy,math,json,sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;sys.path.insert(0,str(R));U=.0776;T=.0388
from arch_profile import arch
VAR=next((a.split('=',1)[1] for a in sys.argv if a.startswith('--variant=')),'brick');assert VAR in ['brick','country'];BRICK=VAR=='brick';F=8 if BRICK else 6;DEPTH=6 if BRICK else 4
REG=json.loads((R/'art-regions.json').read_text());COLL=[];LIGHTS=[];PROPS=[];DOORS=[];PATHS=[];WINDOWS=[];GLOW_AREA=0
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
def empty(n,p=None):
 o=bpy.data.objects.new(n,None);bpy.context.collection.objects.link(o);o.parent=p;return o
ROOT=empty('church_'+VAR);LOT=empty('lot',ROOT);NAVE=empty('nave',ROOT);HALL=empty('parish_hall' if BRICK else 'vestry',ROOT);TOWER=empty('corner_steeple' if BRICK else 'front_belfry',ROOT);GROUPS=[NAVE,HALL,TOWER,LOT];parent=NAVE

def mat(n,f,alpha=False,emit=False,tinted=False):
 m=bpy.data.materials.new(n);m.use_nodes=True;m['texture_file']=f;p=m.node_tree.nodes.get('Principled BSDF');p.inputs['Roughness'].default_value=.83;t=m.node_tree.nodes.new('ShaderNodeTexImage');t.image=bpy.data.images.load(str(R/'textures'/f),check_existing=True);t.interpolation='Closest';t.extension='REPEAT'
 if tinted:
  c=m.node_tree.nodes.new('ShaderNodeVertexColor');c.layer_name='Tint';mix=m.node_tree.nodes.new('ShaderNodeMixRGB');mix.blend_type='MULTIPLY';mix.inputs[0].default_value=1;m.node_tree.links.new(t.outputs['Color'],mix.inputs[1]);m.node_tree.links.new(c.outputs['Color'],mix.inputs[2]);m.node_tree.links.new(mix.outputs[0],p.inputs['Base Color'])
 else:m.node_tree.links.new(t.outputs['Color'],p.inputs['Base Color'])
 if alpha:m.node_tree.links.new(t.outputs['Alpha'],p.inputs['Alpha']);m.surface_render_method='DITHERED'
 if emit:
  e=t
  if alpha:e=m.node_tree.nodes.new('ShaderNodeTexImage');e.image=bpy.data.images.load(str(R/'textures/glass-emission.png'),check_existing=True);e.interpolation='Closest';e.extension='REPEAT'
  m.node_tree.links.new(e.outputs['Color'],p.inputs['Emission Color']);p.inputs['Emission Strength'].default_value=.025
 return m
wall=mat('wall_brick' if BRICK else 'wall_siding','brick.png' if BRICK else 'siding.png');stone=mat('wall_stone','stone.png');plaster=mat('wall_interior','interior.png');roof=mat('roof_slate','roof.png');metal=mat('detail_metal','metal.png');bronze=mat('detail_bronze','bronze.png');wood=mat('detail_wood','wood.png');grass=mat('ground_grass','grass.png');paving=mat('ground_paving','paving.png',tinted=True);glass=mat('glass_windows','glass.png',True,True);glow=mat('glow_lamps','glow.png',emit=True);sign=mat('sign_name','signs.png');utility=metal
exec(compile((R/'mesh_helpers.py').read_text(),str(R/'mesh_helpers.py'),'exec'))
trim=stone if BRICK else wall

def B(n,p,s,m=wall):return box(n,p,s,m)
def P(n,p,w,h,key,m=glass,side='front'):return panel(n,p,w,h,key,m,side)
def tint(o,c):
 colors={'path':(.94,.92,.82,1),'asphalt':(.34,.38,.37,1),'soil':(.48,.36,.25,1),'stripe':(.99,.96,.84,1)};a=o.data.color_attributes.new(name='Tint',type='FLOAT_COLOR',domain='CORNER')
 for v in a.data:v.color=colors[c]
 return o
def plane(n,xy,z,m=paving,color=None):
 o=mesh(n,[(x*U,y*U,z*U) for x,y in xy],[tuple(range(len(xy)))],m)
 if color:tint(o,color)
 return o
def slab(n,x,y,w,d,z,c='path'):return tint(B(n,(x,y,z/2),(w,d,z),paving),c)
def beam(n,a,b,w=1,d=None,m=wood):
 a=Vector(a)*U;b=Vector(b)*U;t=(b-a).normalized();ref=Vector((0,0,1)) if abs(t.z)<.9 else Vector((1,0,0));e=t.cross(ref).normalized()*w*U/2;v=t.cross(e).normalized()*(d or w)*U/2
 return mesh(n,[tuple(p+x*e+y*v) for p in [a,b] for x,y in [(-1,-1),(1,-1),(1,1),(-1,1)]],[(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)],m)
def block(n,x,y,w,d,z0,z1,attachment=None,group=None):COLL.append({'source':n,'center':[x*U,(z0+z1)*U/2,-y*U],'size':[w*U,(z1-z0)*U,d*U],'support_floor_m':z0*U,'attachment':attachment or parent.name,'group':group or ('lot' if parent in PROPS else 'structure')})
def prop(n):
 global parent
 parent=lot_prop(n);GROUPS.append(parent);return parent

def local_point(u,f,z,axis='x'):return (u*U,f*U,z*U) if axis=='x' else (f*U,u*U,z*U)
def prism_face(n,poly,f,m=wall,axis='x',depth=DEPTH):
 # Remove zero-length edges at arch crown before making a closed wall prism.
 poly=[p for i,p in enumerate(poly) if i==0 or Vector(p)!=Vector(poly[i-1])]
 if len(poly)>2 and Vector(poly[0])==Vector(poly[-1]):poly.pop()
 if len(poly)<3:return
 vs=[local_point(u,ff,z,axis) for ff in [f-depth/2,f+depth/2] for u,z in poly];k=len(poly)
 return mesh(n,vs,[tuple(range(k-1,-1,-1)),tuple(range(k,2*k))]+[(i,(i+1)%k,(i+1)%k+k,i+k) for i in range(k)],m)
def wallrun(n,a,b,z0,z1,f,holes=[],axis='x',m=None,inside=1,depth=DEPTH):
 m=m or wall;coords=sorted(set([a,b]+[v for h in holes for v in h[:2] if a<v<b]))
 for aa,bb in zip(coords,coords[1:]):
  cuts=sorted(set([z0,z1]+[v for h in holes if h[0]<(aa+bb)/2<h[1] for v in h[2:] if z0<v<z1]))
  for lo,hi in zip(cuts,cuts[1:]):
   if any(h[0]<(aa+bb)/2<h[1] and h[2]<(lo+hi)/2<h[3] for h in holes):continue
   o=B(n,((aa+bb)/2,f,(lo+hi)/2) if axis=='x' else (f,(aa+bb)/2,(lo+hi)/2),(bb-aa,depth,hi-lo) if axis=='x' else (depth,bb-aa,hi-lo),m)
   if inside:
    o.data.materials.append(plaster);face=o.data.polygons[(3 if inside>0 else 1) if axis=='x' else (2 if inside>0 else 4)];face.material_index=1;old=bpy.data.images.get(m['texture_file']).size;new=bpy.data.images.get(plaster['texture_file']).size
    for li in face.loop_indices:o.data.uv_layers.active.data[li].uv*=Vector((old[0]/new[0],old[1]/new[1]))
def aperture(n,u,f,b,w,h,axis='x',normal=-1,pointed=True,key=None,fill=None,frame=True):
 poly,curve,spring=arch(w,h,b,pointed);outside=f+normal*(DEPTH/2+.2);pane=f+normal*(DEPTH/2-4 if BRICK else DEPTH/2-2)
 # Full-depth masonry above the curved head closes the rectangular rough opening.
 for (a,za),(c,zc) in zip(curve,curve[1:]):prism_face('Arch spandrel',[(u+a,za),(u+c,zc),(u+c,b+h),(u+a,b+h)],f,wall,axis)
 if fill or key:
  m=fill or glass;o=mesh(n,[local_point(u+x,pane,z,axis) for x,z in poly],[tuple(range(len(poly)))],m)
  if key:
   rr=REG[key]['rect_art'];W,H=bpy.data.images.get(m['texture_file']).size
   for li,(x,z) in zip(o.data.polygons[0].loop_indices,poly):o.data.uv_layers.active.data[li].uv=((rr[0]+x+w/2)*2/W,1-(rr[1]+h-(z-b))*2/H)
   area=abs(sum(poly[i][0]*poly[(i+1)%len(poly)][1]-poly[(i+1)%len(poly)][0]*poly[i][1] for i in range(len(poly))))/2*U*U
   WINDOWS.append({'source_label':o.name,'part_node':parent.name+'__'+glass.name,'key':key,'width_art':w,'height_art':h,'bottom_art':b,'profile':'pointed' if pointed else 'round','area_m2':area})
 if frame:
  fm=trim;thick=3 if BRICK else 2
  outer=[(x*(w+2*thick)/w,z+thick) for x,z in curve]
  for (a,za),(c,zc),(oa,oza),(oc,ozc) in zip(curve,curve[1:],outer,outer[1:]):prism_face('Arch ring',[(u+a,za),(u+c,zc),(u+oc,ozc),(u+oa,oza)],outside,fm,axis,2)
  for side in [-1,1]:
   xy=(u+side*(w/2+thick/2),f,(b+spring)/2) if axis=='x' else (f,u+side*(w/2+thick/2),(b+spring)/2)
   B('Arch jamb',xy,(thick,DEPTH+2,spring-b) if axis=='x' else (DEPTH+2,thick,spring-b),fm)
  if fill or key:B('Window sill',(u,f,b-1) if axis=='x' else (f,u,b-1),(w+2*thick+2,DEPTH+3,2) if axis=='x' else (DEPTH+3,w+2*thick+2,2),fm)
def rect_window(n,u,f,b,key,axis='x',normal=-1):
 w,h=REG[key]['rect_art'][2:];o=P(n,(u,f+normal*(DEPTH/2+.2),b+h/2) if axis=='x' else (f+normal*(DEPTH/2+.2),u,b+h/2),w,h,key,glass,('front' if normal<0 else 'back') if axis=='x' else ('left' if normal<0 else 'right'));WINDOWS.append({'source_label':o.name,'part_node':parent.name+'__'+glass.name,'key':key,'width_art':w,'height_art':h,'profile':'rect','area_m2':w*h*U*U})
 B('Rectangular sill',(u,f,b-1) if axis=='x' else (f,u,b-1),(w+4,DEPTH+3,2) if axis=='x' else (DEPTH+3,w+4,2),trim)
def entry(n,x,y,w,h,side='front',floor=F):
 yaw={'front':0,'back':math.pi,'left':-math.pi/2,'right':math.pi/2}[side];o=door(n,(x,y,floor),yaw);o['exterior']=True;DOORS.append({'node':n,'position':[x*U,floor*U,-y*U],'outward':{'front':[0,0,1],'back':[0,0,-1],'left':[-1,0,0],'right':[1,0,0]}[side],'width_m':w*U,'height_m':h*U,'floor_m':floor*U,'external':True,'open':True})
def roof_slab(n,x0,x1,y0,y1,z0,z1,m=roof):
 # Thin closed roof prism; real slope, not a flat image of a roof.
 vs=[(x*U,y*U,(z+t)*U) for t in [0,2] for x,y,z in [(x0,y0,z0),(x1,y0,z1),(x1,y1,z1),(x0,y1,z0)]]
 return mesh(n,vs,[(0,3,2,1),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)],m)
def gable_roof(n,half,y0,y1,eave,ridge,over=3,cut=False):
 z=lambda x:ridge-(ridge-eave)*abs(x)/half
 spans=[(y0-over,y1+over,-half-over,0),(y0-over,y1+over,0,half+over)]
 if cut:spans=[(y0-over,24,-half-over,0),(y0-over,24,0,half+over),(24,64,-half-over,-20),(24,64,20,half+over),(64,y1+over,-half-over,0),(64,y1+over,0,half+over)]
 for a,b,x0,x1 in spans:
  if b>a:roof_slab(n,x0,x1,a,b,z(x0),z(x1))
 for y in [y0-over,y1+over]:
  beam('Gable rake',(-half-over,y,z(-half-over)+1),(0,y,ridge+1),2.5,2,m=trim);beam('Gable rake',(0,y,ridge+1),(half+over,y,z(half+over)+1),2.5,2,m=trim)
 beam('Roof ridge',(0,y0-over,ridge+2),(0,y1+over,ridge+2),2,2,m=metal)
def ceiling(half,y0,y1,eave,ridge,cut=False):
 spans=[(y0,y1,-half,0),(y0,y1,0,half)]
 if cut:spans=[(y0,64,-half,-20),(y0,64,20,half),(64,y1,-half,0),(64,y1,0,half)]
 for a,b,x0,x1 in spans:
  z=lambda x:ridge-(ridge-eave)*abs(x)/half
  mesh('Vaulted plaster ceiling',[(x0*U,a*U,z(x0)*U),(x1*U,a*U,z(x1)*U),(x1*U,b*U,z(x1)*U),(x0*U,b*U,z(x0)*U)],[(3,2,1,0)],plaster)
def walk(x0,y0,x1,y1,w=18):
 assert x0==x1 or y0==y1
 PATHS.append([x0-w/2,min(y0,y1),x0+w/2,max(y0,y1)] if x0==x1 else [min(x0,x1),y0-w/2,max(x0,x1),y0+w/2])
def lens(n,x,y,z,w,d,power):
 global GLOW_AREA
 B(n+' case',(x,y,z+.7),(w+2,d+2,1.4),bronze);mesh(n+' lens',[(xx*U,yy*U,z*U) for xx,yy in [(x-w/2,y+d/2),(x+w/2,y+d/2),(x+w/2,y-d/2),(x-w/2,y-d/2)]],[(0,1,2,3)],glow);GLOW_AREA+=w*d*U*U;light(n,(x,y,z-1),power,max(w,d))
def octa(n,x,y,z,r,m):
 return mesh(n,[((x+a*r)*U,(y+b*r)*U,(z+c*r)*U) for a,b,c in [(1,0,0),(-1,0,0),(0,1,0),(0,-1,0),(0,0,1),(0,0,-1)]],[(0,2,4),(2,1,4),(1,3,4),(3,0,4),(2,0,5),(1,2,5),(3,1,5),(0,3,5)],m)

half,y0,y1,eave,ridge=(90,32,418,114,176) if BRICK else (58,24,256,76,112)
parent=NAVE;B('Nave floor',(0,(y0+y1)/2,F/2),(half*2,y1-y0,F),wood)
# Side walls, actual arch openings, and clear links to the rear hall/vestry.
for x,normal in [(-half,-1),(half,1)]:
 ys=([140,200,260,320,380] if x<0 else [80,140,200,260,320,380]) if BRICK else [60,116,172,228]
 w,h,b=(32,68,30) if BRICK else (22,42,18);holes=[[y-w/2,y+w/2,b,b+h] for y in ys]
 if BRICK and x<0:holes.append([52,76,F,44])
 wallrun('Nave side',y0,y1,0,eave,x,holes,'y',inside=1 if x<0 else -1)
 for y in ys:aperture('Pointed nave glass' if BRICK else 'Country arched glass',y,x,b,w,h,'y',normal,BRICK,'lancet' if BRICK else 'country')
 # Solid wall plates and full-depth corner trim seat the interior rafters.
 beam('Eave wall plate',(x-normal*4,y0+3,eave-2),(x-normal*4,y1-3,eave-2),2,2,m=wood)
front_windows=[(-28,16,28,84),(0,16,28,84),(28,16,28,84)] if BRICK else [(-42,20,40,18),(42,20,40,18)]
front_holes=[[-16,16,F,56] if BRICK else [-12,12,F,42]]+[[x-w/2,x+w/2,b,b+h] for x,w,h,b in front_windows]
wallrun('Nave front',-half,half,0,eave,y0,front_holes)
for x,w,h,b in front_windows:aperture('Front arched glass',x,y0,b,w,h,'x',-1,BRICK,'front_lancet' if BRICK else 'country_front')
linkx,linkw=(68,18) if BRICK else (42,14)
wallrun('Nave rear',-half,half,0,eave,y1,[[linkx-linkw/2,linkx+linkw/2,F,F+36]],inside=-1)
if BRICK:prism_face('Front brick gable',[(-half,eave),(half,eave),(0,ridge)],y0)
else:
 # The front belfry has a real roof/gable cutout, not overlapping coplanar faces.
 zcut=ridge-(ridge-eave)*20/half
 prism_face('Front gable left',[(-half,eave),(-20,eave),(-20,zcut)],y0)
 prism_face('Front gable middle',[(-20,eave),(20,eave),(20,96),(-20,96)],y0)
 prism_face('Front gable right',[(20,eave),(half,eave),(20,zcut)],y0)
prism_face('Rear gable',[(-half,eave),(half,eave),(0,ridge)],y1)
gable_roof('Nave slate roof',half,y0,y1,eave,ridge,4,not BRICK)
ceiling(half-3,y0+3,y1-3,eave-2,ridge-6 if BRICK else ridge-4,not BRICK)
for y in ([90,190,290,390] if BRICK else [70,140,210]):
 hh=half-3;ee=eave-2;rr=ridge-6 if BRICK else ridge-4
 beam('Nave timber rib',(-hh,y,ee),(0,y,rr),2,2);beam('Nave timber rib',(0,y,rr),(hh,y,ee),2,2)
beam('Interior ridge purlin',(0,y0+3,ridge-6 if BRICK else ridge-4),(0,y1-3,ridge-6 if BRICK else ridge-4),2,2)
# Fixed raised chancel; side aisles remain clear to the rear connection.
plat_half,plat_start,raise_by=(56,330,6) if BRICK else (32,206,4)
B('Chancel platform',(0,(plat_start+y1-6)/2,F+raise_by/2),(plat_half*2,y1-6-plat_start,raise_by),wood)
for i in range(int(raise_by/2)):B('Chancel step',(0,plat_start-2-4*(int(raise_by/2)-1-i),F+i+1),(plat_half*2,4,2*(i+1)),wood)
block('Raised chancel',0,(plat_start+y1-6)/2,plat_half*2,y1-6-plat_start,F,F+raise_by)
plane('Nave centre aisle',[(-12,y0+4),(12,y0+4),(12,plat_start-raise_by*2),(-12,plat_start-raise_by*2)],F+.04,paving,'path')
# Original open entry porch and supported gable.
B('Porch floor',(0,y0/2,F/2),(72 if BRICK else 52,y0,F),stone)
if BRICK:
 wallrun('Porch front',-32,32,0,56,0,[[-16,16,F,F+46]])
 aperture('Open pointed entry',0,0,F,32,46,'x',-1,True,None,None)
 for x in [-32,32]:wallrun('Porch side',0,y0,0,56,x,[],'y',inside=1 if x<0 else -1)
 prism_face('Porch gable',[(-32,56),(32,56),(0,80)],0);gable_roof('Porch slate roof',32,0,y0,56,80,3)
 entry('door_main',0,-3.4,24,32)
else:
 for x in [-20,20]:B('Porch painted post',(x,0,23),(6,6,46),wall)
 B('Porch header',(0,0,44),(46,6,4),wall);prism_face('Porch gable',[(-26,44),(26,44),(0,64)],0,wall,depth=4);gable_roof('Porch slate roof',26,0,y0,44,64,3)
 entry('door_main',0,-2.4,24,34)
for i in range(F//2):B('Front approach step',(0,-2-4*(F//2-1-i),i+1),(60 if BRICK else 44,4,2*(i+1)),stone)
# Stepped brick buttresses, with sloped weathering caps, are structural shapes rather than individual bricks.
if BRICK:
 for x,normal in [(-half,-1),(half,1)]:
  for y in ([110,170,230,290,350,408] if x<0 else [48,110,170,230,290,350,408]):
   B('Buttress stone footing',(x+normal*7,y,4),(16,14,8),stone);B('Buttress lower',(x+normal*7,y,38),(10,8,60),wall);B('Buttress upper',(x+normal*4,y,83),(6,8,30),wall)
   pts=[(x+normal,y-5,104),(x+normal*8,y-5,98),(x+normal*8,y+5,98),(x+normal,y+5,104)]
   mesh('Buttress weathering',[tuple(v*U for v in p) for p in pts],[(0,1,2,3)],stone);block('Buttress',x+normal*7,y,16,14,0,104)
else:
 for x in [-half,half]:
  for y in [y0,y1]:B('Painted corner board',(x,y,eave/2),(3,5,eave),wall)
# Rear parish hall / vestry: complete low shell, own open exterior entrance and finished roof.
parent=HALL;hh,hy,he,hr=(84,570,62,88) if BRICK else (52,312,48,62)
B('Hall floor',(0,(y1+hy)/2,F/2),(hh*2,hy-y1,F),wood);B('Hall ceiling',(0,(y1+hy)/2,he-1),(hh*2-DEPTH,hy-y1-DEPTH,2),plaster)
for x,normal in [(-hh,-1),(hh,1)]:
 ys=([466,532] if x<0 else [532]) if BRICK else [284];key='hall' if BRICK else 'vestry';ww,wh=REG[key]['rect_art'][2:];wb=22 if BRICK else 14
 holes=[[y-ww/2,y+ww/2,wb,wb+wh] for y in ys]
 if BRICK and x>0:holes.append([450,470,F,F+34])
 wallrun('Rear hall side',y1,hy,0,he,x,holes,'y',inside=1 if x<0 else -1)
 for y in ys:rect_window('Hall side window',y,x,wb,key,'y',normal)
doorx,doorw=(44,20) if BRICK else (0,18);key='hall' if BRICK else 'vestry';ww,wh=REG[key]['rect_art'][2:];wb=22 if BRICK else 14
backwin=[-32] if BRICK else [-30,30]
wallrun('Hall back',-hh,hh,0,he,hy,[[doorx-doorw/2,doorx+doorw/2,F,F+34]]+[[x-ww/2,x+ww/2,wb,wb+wh] for x in backwin],inside=-1)
for x in backwin:rect_window('Hall rear window',x,hy,wb,key,normal=1)
prism_face('Hall rear gable',[(-hh,he),(hh,he),(0,hr)],hy);gable_roof('Hall slate roof',hh,y1,hy,he,hr,3)
entry('door_back',doorx,hy+DEPTH/2+1.4,doorw,34,'back')
B('Back entry landing',(doorx,hy+7,F/2),(30,8,F),stone)
for i in range(F//2):B('Back entry step',(doorx,hy+13+4*(F//2-1-i),i+1),(30,4,2*(i+1)),stone)
if BRICK:
 entry('door_hall',hh+DEPTH/2+1.4,460,20,34,'right');B('Parish side landing',(92,460,F/2),(10,30,F),stone)
 for i in range(4):B('Parish side step',(111-4*i,460,i+1),(4,30,2*(i+1)),stone)
 B('Serving counter base',(-73,534,F+5),(16,60,10),wood);B('Serving counter top',(-73,534,F+10.5),(18,62,1),stone);block('Fixed serving counter',-73,534,18,62,F,F+11)
# A closed, complete corner bell tower for town, a small open belfry for farm country.
parent=TOWER
if BRICK:
 tx,ty=-122,64;B('Tower floor',(tx,ty,F/2),(64,64,F),stone);B('Tower lower ceiling',(tx,ty,103),(58,58,2),plaster);B('Belfry support floor',(tx,ty,193),(58,58,2),wood)
 for y,normal in [(32,-1),(96,1)]:
  wallrun('Tower lower wall',-154,-90,0,194,y,[[tx-16,tx+16,b,b+68] for b in [30,112]],inside=1 if normal<0 else -1)
  for b in [30,112]:aperture('Tower lancet',tx,y,b,32,68,'x',normal,True,'lancet')
 wallrun('Tower west wall',32,96,0,194,-154,[[ty-16,ty+16,b,b+68] for b in [30,112]],'y',inside=1)
 for b in [30,112]:aperture('Tower lancet',ty,-154,b,32,68,'y',-1,True,'lancet')
 wallrun('Tower upper east',32,96,114,194,-90,[],'y',inside=-1)
 for z in [10,106,190]:
  for x in [-154,-90]:B('Tower stone belt',(x,ty,z),(8,70,4),stone)
  for y in [32,96]:B('Tower stone belt',(tx,y,z),(70,8,4),stone)
 for axis,fixed,u,normal in [('x',32,tx,-1),('x',96,tx,1),('y',-154,ty,-1),('y',-90,ty,1)]:
  a,b=(-154,-90) if axis=='x' else (32,96);wallrun('Belfry wall',a,b,194,254,fixed,[[u-14,u+14,198,246]],axis,inside=1 if normal<0 else -1);aperture('Dark belfry louvers',u,fixed,198,28,48,axis,normal,True,None,wood)
 B('Belfry cornice',(tx,ty,257),(74,74,6),stone);B('Spire base plate',(tx,ty,262),(72,72,4),metal)
 vs=[((tx+34*math.cos(i*math.tau/8))*U,(ty+34*math.sin(i*math.tau/8))*U,264*U) for i in range(8)]+[(tx*U,ty*U,384*U)];mesh('Octagonal spire',vs,[(i,(i+1)%8,8) for i in range(8)],metal)
 for x in [tx-30,tx+30]:
  for y in [ty-30,ty+30]:mesh('Corner pinnacle',[(xx*U,yy*U,z*U) for xx,yy,z in [(x-3,y-3,264),(x+3,y-3,264),(x+3,y+3,264),(x-3,y+3,264),(x,y,278)]],[(0,1,4),(1,2,4),(2,3,4),(3,0,4)],stone)
 octa('Spire finial',tx,ty,386,2,bronze)
else:
 B('Belfry seated base',(0,44,111),(40,40,30),wall)
 for y in [24,64]:wallrun('Open bell stage',-20,20,126,158,y,[[-10,10,130,154]],inside=0,depth=4)
 for x in [-20,20]:wallrun('Open bell stage',24,64,126,158,x,[[34,54,130,154]],'y',inside=0,depth=4)
 B('Belfry cap',(0,44,159),(48,48,2),wall)
 mesh('Country steeple cap',[(-24*U,20*U,160*U),(24*U,20*U,160*U),(24*U,68*U,160*U),(-24*U,68*U,160*U),(0,44*U,184*U)],[(0,1,4),(1,2,4),(2,3,4),(3,0,4)],metal);octa('Country finial',0,44,186,2,bronze)
 # Static, hollow eight-sided bronze bell, visibly hung from a crossbeam.
 B('Bell hanger beam',(0,44,156),(36,4,4),wood);beam('Bell suspension',(0,44,156),(0,44,153),1.5,m=bronze)
 profile=[(2,154),(4,152),(5.5,145),(7,142),(7,141),(5.8,141),(4.5,144),(2.5,150)];vs=[(r*math.cos(i*math.tau/8)*U,(44+r*math.sin(i*math.tau/8))*U,z*U) for r,z in profile for i in range(8)]
 mesh('Fixed bronze bell',vs,[(j*8+i,j*8+(i+1)%8,(j+1)*8+(i+1)%8,(j+1)*8+i) for j in range(len(profile)-1) for i in range(8)],bronze);beam('Bell clapper',(0,44,151),(0,44,140),.7,m=bronze);octa('Bell clapper end',0,44,140,1.3,bronze)
# Mounted lighting: emission stays on the small faces, never whole walls or roofs.
parent=NAVE
for y in ([130,250,370] if BRICK else [100,200]):
 top=ridge-6 if BRICK else ridge-4;z=104 if BRICK else 68;beam('Pendant stem',(0,y,top),(0,y,z+2),.8,m=bronze);lens('Nave pendant',0,y,z,12 if BRICK else 10,10 if BRICK else 8,260 if BRICK else 120)
for x in [-25,25] if BRICK else [-20,20]:
 B('Porch lantern mount',(x,-4,31 if BRICK else 28),(5,5,10),bronze);B('Porch lantern lens',(x,-6.6,31 if BRICK else 28),(3,.4,6),glow);GLOW_AREA+=2*(3*.4+3*6+.4*6)*U*U;light('Porch pool',(x,-9,29 if BRICK else 26),45,8)
parent=HALL
for y in ([466,532] if BRICK else [284]):lens('Hall ceiling light',0,y,he-3,10,6,90)
# Finished drainage and service detail on the rear side.
for x in [-hh-DEPTH/2-.4,hh+DEPTH/2+.4]:beam('Hall downpipe',(x,hy-2,he),(x,hy-2,0),.8,m=metal);block('Downpipe',x,hy-2,1,1,0,he)
parent=NAVE
for x in [-half-DEPTH/2-.4,half+DEPTH/2+.4]:beam('Nave downpipe',(x,y1-2,eave),(x,y1-2,0),.8,m=metal);block('Downpipe',x,y1-2,1,1,0,eave)
# Ground, parking, paths and quiet planted side garden. No invented graves or residents.
parent=LOT;bounds=(-215,390,-90,615) if BRICK else (-140,260,-70,350);xa,xb,ya,yb=bounds;B('Church lawn',((xa+xb)/2,(ya+yb)/2,.2),(xb-xa,yb-ya,.4),grass)
if BRICK:
 slab('Parking apron',242,226,224,160,1,'asphalt');slab('Parking drive',242,28,76,236,1,'asphalt')
 for y in range(162,291,32):
  for a,b in [(132,196),(288,352)]:plane('Parking stripe',[(a,y),(b,y),(b,y+1),(a,y+1)],1.15,paving,'stripe')
 walk(0,-90,0,-16,30);walk(116,-90,116,600,18);walk(44,600,116,600,18);walk(113,460,116,460,24);walk(116,200,130,200,18)
 walk(0,-40,-180,-40,18);walk(-180,-40,-180,120,18);walk(-180,120,-132,120,18);walk(-132,120,-132,600,18);walk(-132,600,116,600,18)
else:
 slab('Country parking',168,170,152,144,1,'asphalt');slab('Country drive',200,14,64,168,1,'asphalt')
 for y in range(108,237,32):plane('Country parking stripe',[(100,y),(164,y),(164,y+1),(100,y+1)],1.15,paving,'stripe')
 walk(0,-70,0,-12,26);walk(80,-70,80,338,16);walk(0,338,80,338,16);walk(80,180,92,180,16);walk(0,-32,-82,-32,16);walk(-82,-32,-82,338,16);walk(-82,338,80,338,16)
# Each path junction is meshed once, avoiding coplanar overlays.
prop('paths');edges=sorted(set(v for r in PATHS for v in [r[0],r[2]]))
for a,b in zip(edges,edges[1:]):
 intervals=sorted((r[1],r[3]) for r in PATHS if r[0]<(a+b)/2<r[2]);merged=[]
 for c,d in intervals:
  if merged and c<=merged[-1][1]:merged[-1][1]=max(merged[-1][1],d)
  else:merged.append([c,d])
 for c,d in merged:plane('Joined stone walk',[(a,c),(b,c),(b,d),(a,d)],.72,paving,'path')
# One modest roadside board, repainted by the game, not a real congregation name.
prop('church_sign');sx,sy=(164,-35) if BRICK else (126,-28)
for x in [sx-12,sx+12]:B('Sign post',(x,sy,19),(3,3,38),wood)
B('Sign board',(sx,sy,28),(36,3,24),wood);B('Sign cap',(sx,sy,41),(38,5,2),metal);P('sign_name',(sx,sy-1.7,28),32,20,'name',sign);block('Sign board',sx,sy,38,6,0,42)
# Two low stone-edged planting beds and a simple separate garden bench.
for i,y in enumerate([250,360] if BRICK else [150,200]):
 prop('garden_bed_'+str(i));x=-182 if BRICK else -116;w,d=(40,28) if BRICK else (24,20)
 for yy in [y-d/2,y+d/2]:B('Bed stone edge',(x,yy,2),(w,2,4),stone)
 for xx in [x-w/2,x+w/2]:B('Bed stone edge',(xx,y,2),(2,d,4),stone)
 plane('Bed soil',[(x-w/2+1,y-d/2+1),(x+w/2-1,y-d/2+1),(x+w/2-1,y+d/2-1),(x-w/2+1,y+d/2-1)],3.5,paving,'soil')
 octa('Garden shrub',x,y,10,9 if BRICK else 7,grass);block('Garden bed',x,y,w+2,d+2,0,20)
prop('garden_bench');x,y=(-158,300) if BRICK else (-107,230);B('Bench seat',(x,y,5),(20,6,2),wood);B('Bench back',(x,y+2.7,10),(20,1,8),wood)
for xx in [x-7,x+7]:B('Bench legs',(xx,y,2),(2,5,4),metal)
block('Bench',x,y,22,8,0,14)
# Two original low-poly trees, each beneath the small-prop budget.
bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=1,radius=1);temp=bpy.context.object;tv=[v.co.copy() for v in temp.data.vertices];tf=[tuple(p.vertices) for p in temp.data.polygons];bpy.data.objects.remove(temp,do_unlink=True)
for i,(x,y,s) in enumerate([(-166,-15,.75),(300,520,.9)] if BRICK else [(-100,30,.6),(200,304,.55)]):
 prop('church_tree_'+str(i));cylinder('Tree trunk',(x,y,35*s),2.5*s,70*s,wood,None,8)
 for dx,z,rx,ry,rz in [(0,100,38,32,40),(-22,86,30,26,30),(22,87,30,26,30)]:mesh('Leaf crown',[((x+(dx+v.x*rx)*s)*U,(y+v.y*ry*s)*U,(z+v.z*rz)*s*U) for v in tv],tf,grass)
 block('Tree trunk column',x,y,7*s,7*s,0,140*s)
for i,x in enumerate([-54,-40] if BRICK else [-30]):
 prop('service_bin_'+str(i));yy=hy+12;B('Service bin',(x,yy,6),(8,8,12),metal);B('Bin lid',(x,yy,12.5),(9,9,1),metal);block('Service bin',x,yy,9,9,0,13)
# Hidden box companion and merged material-role meshes.
parent=ROOT;vs=[];fs=[]
for b in COLL:
 x,z,ny=b['center'];w,h,d=b['size'];y=-ny;k=len(vs);vs.extend([(x+a*w/2,y+c*d/2,z+s*h/2) for a,c,s in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]]);fs.extend([tuple(k+j for j in f) for f in [(0,3,2,1),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)]])
collision=mesh('collision',vs,fs,metal);collision.hide_render=True;collision['collision_only']=True;collision['render']=False
for g in GROUPS:
 mats={}
 for o in list(g.children):
  if o.type=='MESH' and o.name!='sign_name':mats.setdefault(o.data.materials[0].name,[]).append(o)
 for mn,obs in mats.items():
  bpy.ops.object.select_all(action='DESELECT')
  for o in obs:o.select_set(True)
  bpy.context.view_layer.objects.active=obs[0]
  if len(obs)>1:bpy.ops.object.join()
  obs[0].name=g.name+'__'+mn
model=[ROOT]+list(ROOT.children_recursive)
def count(obs):return sum(sum(len(p.vertices)-2 for p in o.data.polygons) for o in obs if o.type=='MESH' and o!=collision)
tris=count(model);mats=sorted({m.name for o in model if o.type=='MESH' for m in o.data.materials});assert tris<=12000 and len(mats)<=12,(tris,len(mats));assert GLOW_AREA<15
for im in bpy.data.images:
 if im.source=='FILE':im.pack()
bpy.ops.object.select_all(action='DESELECT')
for o in model:o.select_set(True)
bpy.ops.export_scene.gltf(filepath=str(R/('brick-church.glb' if BRICK else 'country-church.glb')),use_selection=True,export_format='GLB',export_yup=True,export_extras=True,export_vertex_color='ACTIVE')
info={'name':'Town Church','variant':VAR,'file':'brick-church.glb' if BRICK else 'country-church.glb','design_years':[1880,1925] if BRICK else [1870,1930],'typical_use_years':[1950,2030],'units':'metres','axes':'glTF Y up, +Z entrance/front','origin':'Grade at the middle of the projecting front porch edge. Two alternative designs, not a historical replacement chain.','nave_footprint_m':[half*2*U,(y1-y0)*U],'total_building_depth_m':hy*U,'spire_height_m':(388 if BRICK else 188)*U,'floor_m':F*U,'triangles_visible':tris,'triangles_per_part':{g.name:count(g.children_recursive) for g in [NAVE,HALL,TOWER,LOT]},'prop_triangles':{g.name:count(g.children_recursive) for g in PROPS},'materials':mats,'doors':DOORS,'windows':WINDOWS,'rooms':[{'id':'nave','floor_m':F*U,'notes':'Unfurnished nave, centre aisle finish, raised chancel and open rear link. Game supplies pews and loose furnishings.'},{'id':'parish_hall' if BRICK else 'vestry','floor_m':F*U,'notes':'Rear room shell, its own open outside entry and a fixed serving counter in the brick variant.'},{'id':'corner_tower' if BRICK else 'front_belfry','notes':'Upper belfry is architectural detail only; no stair access or ringing behavior is implemented.'}],'chancel':{'rise_m':raise_by*U,'width_m':plat_half*2*U,'steps':int(raise_by/2),'clear_side_link_x_m':linkx*U},'collision':{'node':'collision','render':False,'coordinate_frame':'glTF metres, Y up/+Z front','boxes_gltf_y_up':COLL,'use_the_walls':True,'notes':'Walls, floors, real steps and architectural shells supply collision. Supplementary boxes cover buttresses, raised chancel/counter, sign, planters, bench, bins, downpipes and full-height tree-trunk columns. Do not apply the legacy Blender rotation.'},'sign_name':{'node':'sign_name','material':'sign_name','texture':'textures/signs.png','rect_art':[0,0,32,20],'text':'TOWN / CHURCH','notes':'One modest, non-emissive roadside name region. Repaint per town using the 3x5 pixel alphabet; no real congregation or denomination.'},'lot_node':'lot','lot_bounds_gltf_m':{'x':[xa*U,xb*U],'z':[-yb*U,-ya*U]},'parking_bays':8 if BRICK else 4,'non_window_emissive_area_m2':GLOW_AREA,'pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'swatches_used':['brick_red'] if BRICK else [],'runtime_handoff':['Alternative visual designs selected by real town facts; no residents, services, named graves, congregation history or scheduled construction is created.','All exterior entrances stay open. The game supplies pews, loose furnishings, occupants and interactions.','Read boxes_gltf_y_up directly; the legacy collision parser needs an adapter without rotating from Blender a second time.','Ground navigation, collisions, analytic lights, pane-only emission masking, lot selection and per-town sign repainting remain game integration work.','The country bell is fixed geometry with no ringing or animation; upper tower/belfry access is not modeled.'],'licence':'Shared LimeZu swatches are Timerpg-only; retain original credit. Supplemental art and all geometry original.','renders':'Actual Blender desktop renders, not Filament/S25 captures.'}
info['swatches_used']+=['roof_slate','wall_stucco_red','asphalt_worn'];(R/('model-info-'+VAR+'.json')).write_text(json.dumps(info,indent=2)+'\n');print('CHURCH_EXPORT',VAR,tris,len(mats),GLOW_AREA,flush=True)
# Presentation-only scene, excluded from the exported model.
parent=None;gm=bpy.data.materials.new('Presentation ground');gm.diffuse_color=(.25,.29,.24,1);bpy.ops.mesh.primitive_plane_add(size=600,location=(0,0,-.04));bpy.context.object.data.materials.append(gm)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=16;scene.cycles.use_denoising=False;scene.cycles.max_bounces=4;scene.render.resolution_x=1024;scene.render.resolution_y=768;scene.render.resolution_percentage=100;scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
if '--review' in sys.argv:scene.cycles.samples=4;scene.render.resolution_percentage=50
world=bpy.data.worlds.new('Church sky');world.use_nodes=True;scene.world=world;bg=world.node_tree.nodes.get('Background');sun=bpy.data.lights.new('Sun','SUN');so=bpy.data.objects.new('Sun',sun);bpy.context.collection.objects.link(so);so.rotation_euler=(.45,-.45,-.65);sun.angle=.09
bpy.ops.object.camera_add();cam=bpy.context.object;scene.camera=cam;ONLY=next((set(a.split('=')[1].split(',')) for a in sys.argv if a.startswith('--shots=')),None);saved=False;raw=Path('/tmp/church-raw');raw.mkdir(exist_ok=True)
def shot(n,p,target,scale,night=False,persp=False):
 global saved
 cam.data.type='PERSP' if persp else 'ORTHO';cam.data.lens=22 if persp else 35;cam.data.ortho_scale=scale;cam.location=p;cam.rotation_euler=(Vector(target)-cam.location).to_track_quat('-Z','Y').to_euler();bg.inputs[0].default_value=(*((.13,.19,.27) if night else (.62,.70,.75)),1);bg.inputs[1].default_value=.6 if night else .7;sun.energy=.22 if night else 2;sun.color=(.5,.65,1) if night else (1,.91,.77)
 for l,power in LIGHTS:l.energy=power if night else power*.1
 for m in [glass,glow]:m.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=.55 if night else .025
 scene.render.filepath=str(raw/(VAR+'-'+n+'.png'))
 if not saved:bpy.ops.wm.save_as_mainfile(filepath=str(R/('brick-church.blend' if BRICK else 'country-church.blend')));saved=True
 if ONLY is None or n in ONLY:bpy.ops.render.render(write_still=True)
if BRICK:
 shot('day',(39,-47,32),(-1,18,10),64)
 shot('night',(39,-47,28),(-1,18,10),64,True)
 shot('rear',(-37,66,33),(0,20,8),64)
 shot('above',(37,-18,90),(0,19,2),78)
 shot('person',(-20,-27,1.7),(-4,8,13),64,False,True)
 shot('nave',(-1.5,5,2.3),(0,24,3),64,True,True)
 shot('hall',(3.8,35,2.3),(-3.5,41,1.3),64,True,True)
else:
 shot('day',(24,-30,21),(0,11,5),43)
 shot('night',(24,-30,19),(0,11,5),43,True)
 shot('rear',(-25,39,20),(0,12,4),43)
 shot('above',(23,-16,60),(2,12,1),48)
 shot('person',(-12,-16,1.7),(0,7,5),43,False,True)
 shot('nave',(-.8,3.8,2.1),(0,16,2),43,True,True)
 shot('belfry',(1.5,-10,12.8),(0,3.414,11.1),11)
