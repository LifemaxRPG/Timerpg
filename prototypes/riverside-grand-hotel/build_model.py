"""The Riverside Grand: a city-scale original turn-of-century hotel landmark."""
import bpy,math,json,sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;sys.path.insert(0,str(R));from arch_profile import arch
U=.0776;T=.0388;F=6;BRICK=True;DEPTH=4
REG=json.loads((R/'art-regions.json').read_text());COLL=[];LIGHTS=[];PROPS=[];DOORS=[];PATHS=[];WINDOWS=[];GLOW_AREA=0;STAIRS=[]
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
def empty(n,p=None):
 o=bpy.data.objects.new(n,None);bpy.context.collection.objects.link(o);o.parent=p;return o
ROOT=empty('riverside_grand');HOTEL=empty('hotel',ROOT);VERANDA=empty('veranda',ROOT);TOWER=empty('clock_tower',ROOT);GARDEN=empty('winter_garden',ROOT);LOT=empty('lot',ROOT);GROUPS=[HOTEL,VERANDA,TOWER,GARDEN,LOT];parent=HOTEL

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
wall=mat('wall_brick','brick.png');stone=mat('wall_limestone','stone.png');plaster=mat('wall_interior','plaster.png');roof=mat('roof_slate','slate.png');copper=mat('roof_copper','copper.png');metal=mat('detail_iron','metal.png');wood=mat('detail_wood','wood.png');foliage=mat('ground_foliage','foliage.png');paving=mat('ground_paving','paving.png',tinted=True);glass=mat('glass_windows','glass.png',True,True);glow=mat('glow_lamps','glow.png',emit=True);sign=mat('sign_name','signs.png');utility=metal;trim=stone;bronze=metal
exec(compile((R/'mesh_helpers.py').read_text(),str(R/'mesh_helpers.py'),'exec'))
B=box;P=panel

def tint(o,c):
 cols={'path':(.93,.90,.77,1),'asphalt':(.39,.46,.43,1),'soil':(.42,.34,.24,1),'stripe':(.99,.94,.79,1),'tile':(.75,.80,.69,1)};a=o.data.color_attributes.new(name='Tint',type='FLOAT_COLOR',domain='CORNER')
 for v in a.data:v.color=cols[c]
 return o
def plane(n,xy,z,m=paving,color=None):
 o=mesh(n,[(x*U,y*U,z*U) for x,y in xy],[tuple(range(len(xy)))],m)
 if color:tint(o,color)
 return o
def slab(n,x,y,w,d,z,c='path'):return tint(B(n,(x,y,z/2),(w,d,z),paving),c)
exec(compile((R/'architecture_helpers.py').read_text(),str(R/'architecture_helpers.py'),'exec'))

def stepflight(n,x,y,z,width=26,reverse=False):
 # A closed sawtooth prism: 13 real 0.1552m risers and 0.3104m treads.
 profile=[(0,0)]
 for i in range(13):profile.extend([(i*4,(i+1)*2),((i+1)*4,(i+1)*2)])
 profile.append((52,0));vs=[((x+side*width/2)*U,(y+(-yy if reverse else yy))*U,(z+zz)*U) for side in [-1,1] for yy,zz in profile];k=len(profile)
 mesh(n,vs,[tuple(range(k-1,-1,-1)),tuple(range(k,2*k))]+[(i,(i+1)%k,(i+1)%k+k,i+k) for i in range(k)],stone)
 for xx in [x-width/2,x+width/2]:
  beam('Stair handrail',(xx,y,z+13),(xx,y+(-52 if reverse else 52),z+39),1.2,1.2,wood)
  for yy,zz in [(0,0),(52,26)]:B('Stair rail support',(xx,y+(-yy if reverse else yy),z+zz+6.5),(1.2,1.2,13),metal)
 STAIRS.append({'id':n,'start_floor_m':z*U,'end_floor_m':(z+26)*U,'risers':13,'rise_m':2*U,'run_m':4*U,'width_m':width*U,'start_gltf_m':[x*U,z*U,-y*U],'direction_z':1 if reverse else -1})
def floorplate(z):
 # Upper plates keep a genuine open rear stairwell, with an adjoining landing.
 if z==6:B('Ground floor',(0,205,z/2),(540,310,z),stone);return
 for x0,x1,y0,y1 in [(-270,-266,50,360),(-266,-210,50,270),(-266,-210,356,360),(-210,270,50,360)]:
  # First upper level has a real two-storey lobby void; subtract it from this tile.
  cut=[(-62,62,80,208)] if z==58 else []
  xs=sorted(set([x0,x1]+[v for r in cut for v in r[:2] if x0<v<x1]));ys=sorted(set([y0,y1]+[v for r in cut for v in r[2:] if y0<v<y1]))
  for a,b in zip(xs,xs[1:]):
   for c,d in zip(ys,ys[1:]):
    if any(a>r[0]-.01 and b<r[1]+.01 and c>r[2]-.01 and d<r[3]+.01 for r in cut):continue
    B('Upper timber floor',((a+b)/2,(c+d)/2,z-1),(b-a,d-c,2),wood)
 B('Rear stair floor landing',(-238,275,z-1),(56,10,2),wood)

# Four-storey primary block: 41.9 x 24.1m, plus separate tower and winter garden.
parent=HOTEL
for z in [6,58,110,162]:floorplate(z)
B('Top-floor ceiling',(0,205,216),(536,306,2),plaster)
wingxs=[-244,-198,-152,-106,106,152,198,244];upperxs=[-244,-198,-152,-106,-58,0,58,106,152,198,244]
front_holes=[[-24,24,6,76],[-78,-38,18,98],[38,78,18,98]]
for z in [6,58]:front_holes += [[x-12,x+12,z+12,z+40] for x in wingxs]
for z in [110,162]:front_holes += [[x-12,x+12,z+12,z+40] for x in upperxs]
wallrun('Front brick facade',-270,270,0,218,50,front_holes)
for x in [-58,58]:aperture('Tall lobby arch',x,50,18,40,80,'x',-1,False,'lobby')
aperture('Open grand entrance',0,50,6,48,70,'x',-1,False)
for z in [6,58,110,162]:
 for x in (wingxs if z<110 else upperxs):rect_window('Tall paired guest window',x,50,z+12,'guest')
# Complete side/rear facades, with actual openings and stone sills.
for x,normal in [(-270,-1),(270,1)]:
 holes=[]
 for z in [6,58,110,162]:
  ys=[132,182,232,282,332] if x<0 else [82,132,182,232,282,332]
  if x>0 and z==6:ys=[82,132,182,282,332]
  for y in ys:holes.append([y-12,y+12,z+12,z+40])
 if x<0:holes.append([60,88,6,42])
 else:holes.append([196,224,6,48])
 wallrun('Side brick facade',50,360,0,218,x,holes,'y',inside=1 if normal<0 else -1)
 for z in [6,58,110,162]:
  ys=[132,182,232,282,332] if x<0 else [82,132,182,232,282,332]
  if x>0 and z==6:ys=[82,132,182,282,332]
  for y in ys:rect_window('Side guest window',y,x,z+12,'guest','y',normal)
rear_holes=[[-16,16,6,44]]
for z in [6,58,110,162]:
 for x in upperxs:
  if x==0 and z==6:continue
  rear_holes.append([x-12,x+12,z+12,z+40])
wallrun('Rear brick facade',-270,270,0,218,360,rear_holes,inside=-1)
for z in [6,58,110,162]:
 for x in upperxs:
  if x or z!=6:rect_window('Rear guest window',x,360,z+12,'guest','x',1)
# Horizontal stone belts and articulated corner quoins establish real scale.
for z,h in [(8,4),(17,3),(69,3),(121,3),(173,3),(213,8),(221,4)]:
 for y in [47,363]:B('Stone belt',(0,y,z),(548,3,h),stone)
 for x in [-273,273]:B('Side stone belt',(x,205,z),(3,316,h),stone)
for x in [-269,269]:
 for y in [51,359]:
  B('Corner stone pilaster',(x,y,110),(8,8,208),stone)
  for z in [10,210]:B('Pilaster foot and capital',(x,y,z),(11,11,6),stone)
# A closed steep mansard with a smaller flat cap. No wall picture posing as a roof.
outer=[(-280,40),(280,40),(280,370),(-280,370)];inner=[(-246,74),(246,74),(246,336),(-246,336)]
for i in range(4):
 j=(i+1)%4;mesh('Mansard slate pitch',[(outer[i][0]*U,outer[i][1]*U,222*U),(outer[j][0]*U,outer[j][1]*U,222*U),(inner[j][0]*U,inner[j][1]*U,270*U),(inner[i][0]*U,inner[i][1]*U,270*U)],[(0,1,2,3)],roof)
B('Mansard top',(0,205,271),(492,262,2),roof)
for i in range(4):
 j=(i+1)%4;beam('Copper mansard ridge',(inner[i][0],inner[i][1],272),(inner[j][0],inner[j][1],272),2,2,copper)
for x in [-190,190]:
 B('Brick chimney',(x,265,285),(15,17,30),wall);B('Chimney stone cap',(x,265,302),(20,22,4),stone)
# Upper guest floorplates are unfurnished; corridor walls with open room portals define wings.
for level,z in enumerate([6,58,110,162]):
 room_h=53 if z==162 else 50
 for side in [-1,1]:
  x=side*152;holes=[[84,102,z,z+29],[144,162,z,z+29],[204,222,z,z+29],[264,282,z,z+29],[324,342,z,z+29]]
  # Left rear stair occupies the last bay; keep its access open.
  if side<0:holes=holes[:-1]+[[280,356,z,z+38]]
  wallrun('Guest corridor wall',62,354,z,z+room_h,x,holes,'y',m=plaster,inside=0,depth=2)
  for yy in [120,180,240,300]:
   if side<0 and yy==300:continue
   B('Guest room dividing wall',(side*210,yy,z+room_h/2),(116,2,room_h),plaster)
 # Transverse upper hall remains open through the middle; no invented occupants or room numbers.
# Real service stair from the ground to all three upper floors.
for z in [6,58,110]:
 stepflight('Rear stair first flight',-253,280,z,20);B('Rear half landing',(-238,343,z+25),(56,22,2),stone)
 stepflight('Rear stair return flight',-223,332,z+26,20,True)
for z in [58,110,162]:
 B('Rear stairwell guard',(-209,318,z+6),(2,76,12),wood);B('Rear stairwell handrail',(-209,318,z+13),(3,76,2),metal)
# Double-height lobby, grand split stair and continuous gallery.
stepflight('Lobby stair outward',-40,132,6,28);B('Lobby half landing',(0,193,31),(124,18,2),stone);stepflight('Lobby stair return',40,184,32,28,True)
B('Lobby gallery stair arrival',(44,124,57),(36,16,2),wood)
for x in [-63,63]:
 for a,b in ([(80,208)] if x<0 else [(80,116),(132,208)]):
  B('Gallery timber guard',(x,(a+b)/2,64),(2,b-a,12),wood)
  B('Gallery handrail',(x,(a+b)/2,71),(3,b-a,2),metal)
for y in [80,208]:
 B('Gallery end guard',(0,y,64),(124,2,12),wood);B('Gallery end handrail',(0,y,71),(124,3,2),metal)
# Avoid railing across the stair's upper arrival at x26..62/y116..132.
for x in [-82,82]:
 for y in [84,176]:
  B('Lobby square column',(x,y,56),(5,5,100),stone);B('Lobby column capital',(x,y,106),(9,9,4),stone);block('Lobby column',x,y,9,9,6,108)
B('Concierge counter',(0,212,12),(70,12,12),wood);B('Concierge counter stone top',(0,212,19),(74,15,2),stone);block('Fixed concierge desk',0,212,74,15,6,20)
plane('Lobby floor mosaic',[(-84,54),(84,54),(84,128),(-84,128)],6.05,paving,'tile')
for x in [-76,76]:plane('Lobby border inlay',[(x-1,54),(x+1,54),(x+1,128),(x-1,128)],6.08,paving,'soil')
# Four-tier chandelier is fixed architectural light, not a loose furnishing.
for z,r in [(94,14),(88,20)]:
 for i in range(8):
  a=i*math.tau/8;b=(i+1)*math.tau/8;beam('Chandelier iron ring',(r*math.cos(a),110+r*math.sin(a),z),(r*math.cos(b),110+r*math.sin(b),z),1.2,1.2,metal)
  lens('Chandelier lamp',r*math.cos(a),110+r*math.sin(a),z-2,2,2,48)
beam('Chandelier chain',(0,110,90),(0,110,108),1,1,metal)
for x in [-14,14]:beam('Chandelier stay',(x,110,94),(0,110,104),1,1,metal)
for a in [0,math.pi/2,math.pi,math.pi*1.5]:beam('Chandelier ring strut',(14*math.cos(a),110+14*math.sin(a),94),(20*math.cos(a),110+20*math.sin(a),88),1,1,metal)
for x in [-82,82]:lens('Lobby wall lantern',x,76,40,3,5,75)
# Wraparound raised veranda and monumental arrival porch.
parent=VERANDA;B('Front veranda floor',(0,30,3),(540,40,6),stone);B('Entry porch floor',(0,5,3),(160,10,6),stone)
B('West return veranda floor',(-290,220,3),(36,220,6),stone)
for x in [-270,-210,-150,-90,90,150,210,270]:
 B('Veranda column',(x,14,28),(4,4,44),stone);B('Column stone foot',(x,14,8),(8,8,4),stone);B('Veranda capital',(x,14,49),(8,8,4),stone);block('Veranda column',x,14,8,8,6,51)
B('Veranda copper roof',(0,30,54),(550,46,3),copper);B('Veranda stone fascia',(0,7,50),(550,3,7),stone)
for y in [126,192,258,324]:
 B('West veranda column',(-305,y,28),(4,4,44),stone);block('West veranda column',-305,y,6,6,6,52)
B('West return roof',(-291,220,54),(42,224,3),copper)
# Central porch lifts above the wing veranda; one repaintable sign on its front fascia.
for x in [-78,78]:
 B('Entry pier',(x,4,38),(8,8,64),stone);B('Entry pier capital',(x,4,71),(12,12,4),stone);block('Entry pier',x,4,12,12,6,73)
B('Entrance canopy',(0,24,75),(174,56,4),copper);B('Entrance fascia',(0,-4,63),(172,3,24),stone);P('sign_name',(0,-5.6,63),120,20,'name',sign)
for x in [-185,0,185]:
 front=0 if x==0 else 10;w=90 if x==0 else 30
 for i in range(3):B('Front stone stair',(x,front-10+4*i,i+1),(w,4,2*(i+1)),stone)
# Front rails leave three wide paths to the real openings in the porch.
for a,b in [(-270,-202),(-168,-48),(48,168),(202,270)]:
 B('Veranda low rail',((a+b)/2,9,13),(b-a,2,14),metal);B('Veranda rail cap',((a+b)/2,9,21),(b-a,3,2),stone)
entry('door_main',0,0,48,44,'front',6)
# Main facade side doors use true open apertures too; no opaque door pictures.
# Window-free wing entrance bays at -185/+185 are reached via the main porch for now.
for x in [-120,120]:lens('Veranda downlight',x,30,51,4,7,24)
# Corner clock tower, fully closed floors and real arched belfry openings.
parent=TOWER;tx=-302;left,right=-334,-270;front,back=30,110
B('Tower stone plinth',(tx,70,3),(64,80,6),stone)
for ff,normal in [(front,-1),(back,1)]:
 holes=[[-314,-290,6,48]] if normal<0 else []
 holes += [[tx-10,tx+10,285,323]]
 wallrun('Tower front brick',left,right,0,346,ff,holes,inside=1 if normal<0 else -1)
 if normal<0:aperture('Open tower arch',tx,ff,6,24,42,'x',normal,False)
 aperture('Tower bell arch',tx,ff,285,20,38,'x',normal,False,None,None)
for xx,normal in [(left,-1),(right,1)]:
 holes=[[60,88,6,42]] if normal>0 else []
 holes += [[60,80,285,323]]
 wallrun('Tower side brick',front,back,0,346,xx,holes,'y',inside=1 if normal<0 else -1)
 aperture('Tower side bell arch',70,xx,285,20,38,'y',normal,False,None,None)
for z in [58,218,278,336]:
 B('Tower interior floor',(tx,70,z-1),(60,76,2),stone)
for z in [12,222,280,330,346]:
 for ff in [27,113]:
  spans=[(-338,-314),(-290,-266)] if (z,ff)==(12,27) else [(-338,-266)]
  for lo,hi in spans:B('Tower stone belt',((lo+hi)/2,ff,z),(hi-lo,4,5),stone)
 for xx in [-337,-267]:
  spans=[(26,60),(88,114)] if (z,xx)==(12,-267) else [(26,114)]
  for lo,hi in spans:B('Tower side belt',(xx,(lo+hi)/2,z),(4,hi-lo,5),stone)
# Clocks are drawn pixel art at a fixed sample time, never an implied working game clock.
for side,p in [('front',(tx,27,250)),('back',(tx,113,250)),('left',(-337,70,250)),('right',(-267,70,250))]:P('Clock face',p,48,48,'clock',sign,side)
lens('Tower belfry lantern',tx,70,332,4,6,60)
# Four-facet shoulder and eight-sided copper lantern/spire, not a flat roof silhouette.
base=[(left-8,front-8),(right+8,front-8),(right+8,back+8),(left-8,back+8)];top=[(tx-20,50),(tx+20,50),(tx+20,90),(tx-20,90)]
for i in range(4):
 j=(i+1)%4;mesh('Tower copper shoulder',[(base[i][0]*U,base[i][1]*U,348*U),(base[j][0]*U,base[j][1]*U,348*U),(top[j][0]*U,top[j][1]*U,388*U),(top[i][0]*U,top[i][1]*U,388*U)],[(0,1,2,3)],copper)
B('Lantern sealed deck',(tx,70,388),(40,40,1),copper);cylinder('Lantern drum',(tx,70,401),20,26,copper,None,8)
vs=[((tx+24*math.cos(i*math.tau/8))*U,(70+24*math.sin(i*math.tau/8))*U,414*U) for i in range(8)]+[(tx*U,70*U,474*U)]
mesh('Copper needle spire',vs,[tuple(range(7,-1,-1))]+[(i,(i+1)%8,8) for i in range(8)],copper);cylinder('Spire finial',(tx,70,477),1.4,6,metal,None,8)
entry('door_tower',tx,27,24,42,'front',6)
# Exterior tower door has its own true landing and steps.
B('Tower front landing',(tx,22,3),(38,16,6),stone)
for i in range(3):B('Tower entry step',(tx,4+4*i,i+1),(38,4,2*(i+1)),stone)
# Iron-and-glass winter garden: complete glazed gable, transparent pitched roof, clean shell.
parent=GARDEN;gx0,gx1,gy0,gy1=272,440,80,316;gm=(gx0+gx1)/2
B('Winter garden floor',(gm,198,3),(168,236,6),stone)
for xx in [gx0,gx1]:
 if xx==gx0:
  for a,b in [(80,196),(224,316)]:B('Garden stone base',(xx,(a+b)/2,12),(4,b-a,12),stone)
 else:B('Garden stone base',(xx,198,12),(4,236,12),stone)
 if xx==gx1:
  for y in range(96,293,28):P('Garden wall glazing',(xx,y,42),24,48,'garden',glass,'right')
  for y in range(110,279,28):B('Garden iron mullion',(xx,y,42),(4,4,52),metal)
  B('Garden front end pier',(xx,82,37),(4,4,62),stone);B('Garden rear end pier',(xx,310,37),(4,12,62),stone)
 B('Garden eave beam',(xx,198,68),(4,240,4),metal)
for yy,side in [(gy0,'front'),(gy1,'back')]:
 wallrun('Garden lower base',gx0,gx1,0,18,yy,[[gm-24,gm+24,6,46]] if yy==gy0 else [],m=stone,inside=0)
 for x in [gm-70,gm-42,gm+42,gm+70]:P('Garden end glass',(x,yy,42),24,48,'garden',glass,side)
 for x in [gm-56,gm+56]:B('Garden end mullion',(x,yy,42),(4,4,52),metal)
 B('Garden end beam',(gm,yy,68),(172,4,4),metal)
 for x in [gx0,gx1]:B('Garden corner pier',(x,yy,37),(5,5,62),stone);block('Garden pier',x,yy,5,5,6,68)
 if yy==gy0:
  for x in [gm-27,gm+27]:B('Garden door jamb',(x,yy,34),(6,4,68),metal)
  B('Garden door lintel',(gm,yy,48),(48,4,4),metal);P('Garden entrance fanlight',(gm,yy,59),48,18,'garden_fan',glass,side)
 else:
  P('Garden rear centre glass',(gm,yy,42),48,48,'garden_centre',glass,side)
  for x in [gm-27,gm+27]:B('Garden centre mullion',(x,yy,42),(6,4,52),metal)
 # Triangular glazed gable, fully sealed and mapped at physical texel density.
 mesh('Garden triangular glazing',[(gx0*U,yy*U,68*U),(gx1*U,yy*U,68*U),(gm*U,yy*U,110*U)],[(0,1,2)],glass,'roof_glass')
for a,b in [(gx0,gm),(gm,gx1)]:
 za=110-abs(a-gm)*.5;zb=110-abs(b-gm)*.5
 mesh('Garden roof glazing',[(a*U,gy0*U,za*U),(b*U,gy0*U,zb*U),(b*U,gy1*U,zb*U),(a*U,gy1*U,za*U)],[(0,1,2,3)],glass,'roof_glass')
for y in range(80,317,28):
 beam('Garden roof rib',(gx0,y,68),(gm,y,110),2,2,metal);beam('Garden roof rib',(gm,y,110),(gx1,y,68),2,2,metal)
beam('Garden ridge',(gm,gy0,110),(gm,gy1,110),3,3,metal)
# A closed common-wall connection aligns with the hotel's actual side opening.
# The actual hotel side wall supplies this common wall and its open connection.
B('Garden connection threshold',(271,210,3),(2,28,6),stone)
# Open main hotel doorway must continue through the garden connection wall; the rest is intentional common wall.
entry('door_garden',gm,gy0-2.5,48,40,'front',6)
B('Garden entrance landing',(gm,68,3),(58,24,6),stone)
for i in range(3):B('Garden entry steps',(gm,46+4*i,i+1),(58,4,2*(i+1)),stone)
for y in [114,198,282]:lens('Winter garden pendant',gm,y,94,4,6,75)
# Complete rear services and a supported back exit.
parent=HOTEL;B('Rear landing',(0,374,3),(54,28,6),stone);entry('door_back',0,386,32,38,'back',6)
for i in range(3):B('Rear access step',(0,398-4*i,i+1),(54,4,2*(i+1)),stone)
for x in [-260,260]:
 B('Rear downpipe',(x,364,109),(2,2,218),metal);B('Downpipe shoe',(x,367,2),(3,7,4),metal);block('Rear downpipe',x,365,4,8,0,218)
for x in [-140,140]:
 B('Roof exhaust curb',(x,250,276),(16,18,10),stone);B('Roof exhaust cap',(x,250,284),(20,22,6),metal)
for y in [92,200,300]:
 for x in [-200,200]:lens('Hotel interior light',x,y,208,3,8,20)
# Lot: arrival court, raised walks, garden terraces, trees, rear service and quiet promenade.
parent=LOT;slab('City parcel',45,155,930,630,.4,'asphalt')
# One connected forecourt plane, avoiding overlapping path triangles.
prop('arrival_court');plane('Arrival paving',[(-376,-120),(470,-120),(470,32),(260,32),(260,8),(-270,8),(-270,30),(-376,30)],.7,paving,'path')
prop('garden_terrace');slab('Garden forecourt',354,26,160,40,.7,'path');slab('Winter garden east promenade',467,198,34,300,.7,'path')
prop('rear_service_walk');slab('Rear service paving',0,401,600,62,.7,'path')
# Concrete curb beds and true 3D trees, each under 500 triangles.
bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=1,radius=1);temp=bpy.context.object;tv=[v.co.copy() for v in temp.data.vertices];tf=[tuple(p.vertices) for p in temp.data.polygons];bpy.data.objects.remove(temp,do_unlink=True)
for i,(x,y,s) in enumerate([(-360,-62,1.1),(300,-72,.85),(458,-67,.85),(-360,265,1.0),(470,378,.9),(-350,412,.8)]):
 prop('tree_'+str(i));cylinder('Tree trunk',(x,y,28*s),3*s,56*s,wood,None,8)
 for dx,z,rr in [(0,82,30),(-18,70,23),(18,72,23)]:mesh('Tree crown',[((x+(dx+v.x*rr)*s)*U,(y+v.y*rr*.85*s)*U,(z+v.z*rr)*s*U) for v in tv],tf,foliage)
 block('Tree trunk',x,y,8*s,8*s,0,112*s)
for i,(x,y,w,d) in enumerate([(-165,-66,90,28),(50,-66,90,28),(170,-66,58,28),(468,274,26,76)]):
 prop('planter_'+str(i))
 for yy in [y-d/2,y+d/2]:B('Planter rim',(x,yy,3),(w,2,6),stone)
 for xx in [x-w/2,x+w/2]:B('Planter return',(xx,y,3),(2,d,6),stone)
 plane('Planter soil',[(x-w/2+1,y-d/2+1),(x+w/2-1,y-d/2+1),(x+w/2-1,y+d/2-1),(x-w/2+1,y+d/2-1)],5,paving,'soil')
 for xx in range(int(x-w/2+8),int(x+w/2-5),18):octa('Clipped shrub',xx,y,12,8,foliage)
 block('Planter',x,y,w+2,d+2,0,20)
for i,(x,y) in enumerate([(-220,-32),(100,-32),(465,142),(465,240)]):
 prop('bench_'+str(i));B('Bench seat',(x,y,6),(24,7,2),wood);B('Bench back',(x,y+3,11),(24,1,8),wood)
 for xx in [x-8,x+8]:B('Bench leg',(xx,y,2.5),(2,6,5),metal)
 block('Bench',x,y,25,9,0,15)
for i,(x,y) in enumerate([(-90,390),(-108,390)]):
 prop('service_bin_'+str(i));B('Service bin',(x,y,6),(12,10,12),metal);B('Bin lid',(x,y,12.5),(13,11,1),metal);block('Service bin',x,y,13,11,0,13)
# A small paved service parking strip; no car models or invented driveway behavior.
prop('parking_marks')
for x in range(-235,246,40):plane('Rear parking stripe',[(x,436),(x+1,436),(x+1,462),(x,462)],.45,paving,'stripe')
# Architecture-derived collision companion, already glTF frame.
parent=ROOT;vs=[];fs=[]
for c in COLL:
 x,z,ny=c['center'];w,h,d=c['size'];y=-ny;k=len(vs);vs.extend([(x+a*w/2,y+b*d/2,z+s*h/2) for a,b,s in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]]);fs.extend([tuple(k+j for j in f) for f in [(0,3,2,1),(4,5,6,7),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7)]])
collision=mesh('collision',vs,fs,metal);collision.hide_render=True;collision['render']=False;collision['collision_only']=True
if '--inspect' in sys.argv:
 hist={}
 for o in HOTEL.children:
  if o.type=='MESH':
   key=o.name.split('.')[0];hist[key]=hist.get(key,0)+sum(len(p.vertices)-2 for p in o.data.polygons)
 print('HOTEL_PROFILE',sorted(hist.items(),key=lambda v:-v[1]),flush=True)
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
tris=count(model);mats=sorted({m.name for o in model if o.type=='MESH' for m in o.data.materials});print('HOTEL_COUNTS',tris,len(mats),{g.name:count(g.children_recursive) for g in [HOTEL,VERANDA,TOWER,GARDEN,LOT]},flush=True);assert tris<=12000 and len(mats)<=12
for im in bpy.data.images:
 if im.source=='FILE':im.pack()
bpy.ops.object.select_all(action='DESELECT')
for o in model:o.select_set(True)
bpy.ops.export_scene.gltf(filepath=str(R/'riverside-grand-hotel.glb'),use_selection=True,export_format='GLB',export_yup=True,export_extras=True,export_vertex_color='ACTIVE')
info={'name':'Riverside Grand Hotel','file':'riverside-grand-hotel.glb','design_year':1908,'typical_years':[1908,2030],'units':'metres','axes':'glTF Y up/+Z front','origin':'Grade at the midpoint of the projecting entrance loggia front edge.','main_block_footprint_m':[540*U,310*U],'overall_width_m':max(v.co.x for g in [HOTEL,VERANDA,TOWER,GARDEN] for o in g.children_recursive if o.type=='MESH' for v in o.data.vertices)-min(v.co.x for g in [HOTEL,VERANDA,TOWER,GARDEN] for o in g.children_recursive if o.type=='MESH' for v in o.data.vertices),'lot_bounds_gltf_m':{'x':[-420*U,510*U],'z':[-470*U,160*U]},'main_roof_height_m':272*U,'tower_height_m':480*U,'floor_levels_m':[z*U for z in [6,58,110,162]],'triangles_visible':tris,'materials':mats,'triangles_per_part':{g.name:count(g.children_recursive) for g in [HOTEL,VERANDA,TOWER,GARDEN,LOT]},'prop_triangles':{g.name:count(g.children_recursive) for g in PROPS},'doors':DOORS,'stairs':STAIRS,'windows':WINDOWS,'rooms':[{'id':'lobby','notes':'Actual double-height volume, grand return stair, first-floor gallery, architectural chandelier and fixed concierge counter.'},{'id':'guest_wings','notes':'Four actual floorplates with corridor walls and unfurnished room bays. Rear return stair connects all floors. Furniture, room assignments, occupants and operation are not implemented.'},{'id':'winter_garden','notes':'Double-height iron-and-glass shell and pitched glazed roof; game supplies planters, dining furnishings and occupants.'},{'id':'clock_tower','notes':'Clock faces show a fixed sample time; upper lantern/belfry is exterior architecture without access or clock animation.'}],'collision':{'node':'collision','render':False,'coordinate_frame':'glTF metres/Y up/+Z front','boxes_gltf_y_up':COLL,'use_the_walls':True,'notes':'Use the architectural surfaces for walls/floors/stairs and the full-height listed boxes for obstructions. Do not apply the legacy Blender rotation.'},'sign_name':{'node':'sign_name','material':'sign_name','texture':'textures/signs.png','rect_art':[0,0,120,20],'text':'RIVERSIDE / GRAND HOTEL','clock_region_art':[0,32,48,48],'notes':'Repaint only the name region per town. Clock is a separate static sample-time region, not additional branding.'},'lot_node':'lot','non_window_emissive_area_m2':GLOW_AREA,'pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'swatches_used':['brick_red','roof_slate','plank_horizontal','pavement'],'runtime_handoff':['Original grand-hotel asset, not an automatic generated business or landmark spawn.','A city parcel is required. Riverside compatibility is a placement suggestion; no river is generated or assumed.','Clock faces and chandelier are static geometry. Hotel operation, room allocation, guests, furniture, doors, navigation, collisions, analytic lighting and pane masks need engine integration.','The model is not measured in Filament or on the S25. No frame rate or AAA runtime performance claim.'],'renders':'Actual Blender desktop model previews, not Filament/S25 captures.'}
(R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n');print('HOTEL_EXPORT_OK',tris,flush=True)
# Presentation scene excluded from exported model.
parent=None;gm=bpy.data.materials.new('Presentation ground');gm.diffuse_color=(.25,.29,.24,1);bpy.ops.mesh.primitive_plane_add(size=800,location=(0,0,-.04));bpy.context.object.data.materials.append(gm)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=16;scene.cycles.use_denoising=False;scene.cycles.max_bounces=4;scene.render.resolution_x=1200;scene.render.resolution_y=800;scene.render.resolution_percentage=100;scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
if '--review' in sys.argv:scene.cycles.samples=4;scene.render.resolution_percentage=50
world=bpy.data.worlds.new('Hotel sky');world.use_nodes=True;scene.world=world;bg=world.node_tree.nodes.get('Background');sun=bpy.data.lights.new('Sun','SUN');so=bpy.data.objects.new('Sun',sun);bpy.context.collection.objects.link(so);so.rotation_euler=(.45,-.45,-.65);sun.angle=.09
bpy.ops.object.camera_add();cam=bpy.context.object;scene.camera=cam;ONLY=next((set(a.split('=')[1].split(',')) for a in sys.argv if a.startswith('--shots=')),None);raw=Path('/tmp/hotel-raw');raw.mkdir(exist_ok=True);saved=False

def shot(n,p,target,scale,night=False,persp=False):
 global saved
 scene.cycles.samples=32 if n in ['lobby','garden','gallery'] else 16
 cam.data.type='PERSP' if persp else 'ORTHO';cam.data.lens=21 if persp else 35;cam.data.ortho_scale=scale;cam.location=p;cam.rotation_euler=(Vector(target)-cam.location).to_track_quat('-Z','Y').to_euler();bg.inputs[0].default_value=(*((.13,.19,.27) if night else (.62,.70,.75)),1);bg.inputs[1].default_value=.65 if night else .9;sun.energy=.22 if night else 2;sun.color=(.5,.65,1) if night else (1,.91,.77)
 for l,power in LIGHTS:l.energy=power if night else power*.1
 for m in [glass,glow]:m.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=.5 if night else .025
 scene.render.filepath=str(raw/('hotel-'+n+'.png'))
 if not saved:bpy.ops.wm.save_as_mainfile(filepath=str(R/'riverside-grand-hotel.blend'));saved=True
 if ONLY is None or n in ONLY:bpy.ops.render.render(write_still=True)
shot('day',(69,-77,49),(0,13,11),96)
shot('night',(69,-77,40),(0,13,11),96,True)
shot('rear',(-70,76,42),(0,15,11),94)
shot('above',(55,-38,118),(0,14,2),105)
shot('person',(15,-31,1.7),(-8,6,13),96,False,True)
shot('lobby',(4.6,5.2,2.1),(-.4,15,4.2),96,True,True)
shot('garden',(28,7.5,2),(28,22,5),96,True,True)
shot('gallery',(-5.3,8.4,5.7),(3,14,4),96,True,True)
