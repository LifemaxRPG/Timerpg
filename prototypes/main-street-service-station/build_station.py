"""Main Street Service: original 1950s–60s full-service station.
Blender 4.3; metres; Z-up source, front -Y. Art pixel 0.0776 m / texel 0.0388 m.
"""
import bpy,math,json,sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent;U=.0776;T=.0388
REG=json.loads((R/'art-regions.json').read_text());COLL=[];LIGHTS=[];PROPS=[]
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
ROOT=bpy.data.objects.new('main_street_service',None);bpy.context.collection.objects.link(ROOT)
STRUCT=bpy.data.objects.new('structure',None);bpy.context.collection.objects.link(STRUCT);STRUCT.parent=ROOT
LOT=bpy.data.objects.new('lot',None);bpy.context.collection.objects.link(LOT);LOT.parent=ROOT
parent=STRUCT
def mat(name,file,alpha=False,emit=False):
    m=bpy.data.materials.new(name);m.use_nodes=True;n=m.node_tree.nodes.get('Principled BSDF');n.inputs['Roughness'].default_value=.8
    tex=m.node_tree.nodes.new('ShaderNodeTexImage');tex.image=bpy.data.images.load(str(R/'textures'/file),check_existing=True);tex.interpolation='Closest';tex.extension='REPEAT';m.node_tree.links.new(tex.outputs['Color'],n.inputs['Base Color']);m['texture_file']=file
    if alpha:m.node_tree.links.new(tex.outputs['Alpha'],n.inputs['Alpha']);m.surface_render_method='DITHERED'
    if emit:m.node_tree.links.new(tex.outputs['Color'],n.inputs['Emission Color']);n.inputs['Emission Strength'].default_value=.12
    return m
wall=mat('wall_porcelain_enamel','porcelain.png');white=wall
brick=mat('wall_brick_base','brick_red_terrace.png')
service_floor=mat('floor_service_concrete','service_concrete.png')
roof=mat('roof_tar_membrane','roofflat_grey_condo.png')
teal=mat('trim_teal_enamel','teal.png');navy=teal
red=mat('trim_red_enamel','red.png')
utility=mat('detail_period_equipment','utility.png')
glass=mat('glass_office','glass.png',alpha=True)
glow=mat('glow_period_signs_and_lamps','signs.png',emit=True)
price=mat('sign_price','prices.png')
asphalt=mat('ground_asphalt','asphalt_plain.png')
paving=mat('ground_paving_and_floor','sidewalk_cream.png')
exec(compile((R/'mesh_helpers.py').read_text(),str(R/'mesh_helpers.py'),'exec'))
def solid(name,points,bottom,top,m):
    count=len(points);v=[(x*U,y*U,z*U) for z in (bottom,top) for x,y in points]
    return mesh(name,v,[tuple(range(count-1,-1,-1)),tuple(range(count,count*2))]+[(i,(i+1)%count,(i+1)%count+count,i+count) for i in range(count)],m)
def wallblock(name,pos,size,m=wall):return box(name,pos,size,m,True)
def sphere(name,pos,r,m,patch=None):
    x,y,z=pos;n=8;rings=4;v=[]
    for j in range(rings+1):
        a=-math.pi/2+math.pi*j/rings
        for i in range(n):v.append(((x+r*math.cos(a)*math.cos(i*math.tau/n))*U,(y+r*math.cos(a)*math.sin(i*math.tau/n))*U,(z+r*math.sin(a))*U))
    faces=[]
    for j in range(rings):
        for i in range(n):
            if j==0:faces.append((i,(j+1)*n+(i+1)%n,(j+1)*n+i))
            elif j==rings-1:faces.append((j*n+i,j*n+(i+1)%n,(j+1)*n+i))
            else:faces.append((j*n+i,j*n+(i+1)%n,(j+1)*n+(i+1)%n,(j+1)*n+i))
    return mesh(name,v,faces,m,patch)
def ring_tire(name,x,y,z):
    # A real open centre, 12-sided silhouette and four-sided cross-section.
    v=[];n=12
    for i in range(n):
        a=i*math.tau/n
        for rr,dy in [(5,-1.5),(5,1.5),(2.5,1.5),(2.5,-1.5)]:v.append(((x+rr*math.cos(a))*U,(y+dy)*U,(z+rr*math.sin(a))*U))
    f=[]
    for i in range(n):
        for j in range(4):f.append((i*4+j,((i+1)%n)*4+j,((i+1)%n)*4+(j+1)%4,i*4+(j+1)%4))
    return mesh(name,v,f,utility,'rubber')

# Low office, higher two-bay service building. A rounded enamel corner, not a front card.
arc=[(-90+10*math.cos(math.pi+i*math.pi/16),10+10*math.sin(math.pi+i*math.pi/16)) for i in range(9)]
office=arc+[(-34,0),(-34,104),(-100,104)]
solid('Office floor',office,-1,0,paving)
box('Service bay floor',(33,52,-.5),(134,104,1),service_floor)
for a,b in zip(arc,arc[1:]):
    solid('Rounded brick corner',[a,b,(b[0]+1.5,b[1]+1.5),(a[0]+1.5,a[1]+1.5)],0,8,brick)
    solid('Rounded enamel corner',[a,b,(b[0]+1.5,b[1]+1.5),(a[0]+1.5,a[1]+1.5)],8,44,wall)
    xs=[a[0],b[0],a[0]+1.5,b[0]+1.5];ys=[a[1],b[1],a[1]+1.5,b[1]+1.5]
    COLL.append({'center':[(min(xs)+max(xs))/2*U,(min(ys)+max(ys))/2*U,22*U],'size':[(max(xs)-min(xs))*U,(max(ys)-min(ys))*U,44*U],'group':'structure','source':'Rounded corner','attachment':'structure'})
    solid('Rounded teal belt',[a,b,(b[0]+1.8,b[1]+1.8),(a[0]+1.8,a[1]+1.8)],40,44,teal)
wallblock('Office left front',(-99,15,22),(2,10,44))
wallblock('Office left rear',(-99,78,22),(2,52,44))
wallblock('Office side sill',(-99,36,6),(2,32,12),brick)
wallblock('Office side lintel',(-99,36,41),(2,32,6))
panel('Office side glazing',(-100.03,36,25),32,26,'office_side',glass,'left')
for x,w in [(-81,14),(-46,16)]:
    wallblock('Office front sill',(x,1,6),(w,2,12),brick)
    panel('Office front window',(x,-.04,25),w,26,'office_left' if w==14 else 'office_right',glass)
for x,w in [(-90,4),(-73,2),(-55,2),(-36,4)]:wallblock('Office jamb',(x,1,22),(w,2,44))
box('Office door frame',(-64,1,38),(16,2,12),teal)
panel('Office entrance',(-64,-.1,16),16,32,'entry',glass)
box('Office front lintel',(-62,1,41),(56,2,6),wall)
door('door_main',(-64,-2,0))
wallblock('Office back',(-67,103,22),(66,2,44))
wallblock('Office/service partition',(-34,52,26),(2,104,52))
wallblock('Bay partition',(32,52,26),(2,104,52))
wallblock('Right side wall',(99,52,26),(2,104,52))
for x,w in [(-31,10),(33,14),(97,6)]:wallblock('Service bay jamb',(x,1,26),(w,2,52))
box('Service bay front header',(33,1,48),(134,2,8),wall)
# Left bay is visibly open; the right slatted door remains closed and separately named.
left_door=box('garage_door_open',(0,2,40),(52,1,8),teal)
right_door=bpy.data.objects.new('garage_door_closed',None);bpy.context.collection.objects.link(right_door);right_door.parent=STRUCT
parent=right_door
box('Closed bay door',(66,.4,22),(52,1,44),wall,True);COLL[-1]['gate_node']='garage_door_closed'
panel('Closed slatted door',(66,-.15,22),52,44,'garage_door',utility)
parent=STRUCT
door('door_bay_1',(0,-2,0));door('door_bay_2',(66,-2,0))
for x,key in [(-31,'bay1'),(33,'bay2')]:panel('Bay number',(x,-1.1,35),8,10,key,glow)
for x,w in [(-2,64),(87,26)]:wallblock('Service rear wall',(x,103,26),(w,2,52))
# Back exit: x58..74, so correct the rear segments to leave that opening only.
# First rear segment ends x30: the middle segment below fills x30..58.
wallblock('Rear middle wall',(44,103,26),(28,2,52))
wallblock('Rear exit lintel',(66,103,42),(16,2,20))
box('Rear exit door',(66,104,16),(16,1,32),teal)
panel('Rear exit art',(66,104.6,16),16,32,'door_back',utility,'back')
door('door_back',(66,107,0),math.pi)
solid('Office closed roof',office,44,46,wall);solid('Office tar roof',office,46,46.2,roof)
box('Garage ceiling',(33,52,52.5),(134,104,1),wall)
box('Garage tar roof',(33,52,53.1),(134,104,.2),roof)
for x in (-100,-34,100):
    h=49 if x==-100 else 56
    height=6 if x==-100 else 8
    cap=52.5 if x==-100 else 60.5
    box('Parapet side',(x,52,h),(2,106,height),wall)
    box('Parapet side cap',(x,52,cap),(4,108,1),teal)
for y in (-1,105):
    box('Office parapet',(-67,y,49),(66,2,6),wall)
    box('Office cap',(-67,y,52.5),(68,4,1),teal)
    box('Garage parapet',(33,y,56),(134,2,8),wall)
    box('Garage cap',(33,y,60.5),(136,4,1),teal)
for x in (-100,100):box('Side red pinstripe',(x,52,40),(2.1,104,2),red)
box('Office awning',(-62,-8,41.5),(58,18,3),wall)
box('Awning red edge',(-62,-17.1,42),(58,.6,2),red)
box('Service sign backing',(-62,-2,48),(58,2,14),teal)
panel('SERVICE sign',(-62,-3.1,48),56,12,'service',glow)
box('Main Street sign backing',(33,-2,56),(98,2,16),teal)
panel('MAIN STREET sign',(33,-3.1,56),96,14,'main',glow)
panel('Full service side sign',(-101.1,64,42),72,10,'full_service',glow,'left')
box('Permanent office counter',(-69,24,8),(30,10,16),teal)
box('Counter top',(-69,24,16.5),(32,12,1),wall)
for x,z in [(-65,43),(0,51),(66,51)]:
    panel('Period ceiling lamp',(x,50,z),16,4,'lamp',glow,'down');light('Warm room light',(x,50,z-1),210,24)
panel('Porch opal diffuser',(-64,-8,39.95),16,4,'lamp',glow,'down')
light('Office porch light',(-64,-8,39),80,12)

# Modest furnace chimney and vents, with a fully finished roof and back.
box('Brick furnace flue',(82,85,61),(10,10,16),brick)
box('Flue cap',(82,85,70),(13,13,2),wall)
for x,y,z in [(-74,86,51),(12,90,58)]:
    cylinder('Roof vent',(x,y,z),1.4,10,utility,n=8)
    cylinder('Roof vent cap',(x,y,z+5.5),2.4,1,utility,n=8)

parent=LOT
box('Compact Main Street forecourt',(0,0,-2),(300,280,2),asphalt)
box('Front shop apron',(0,-9,-.5),(220,18,1),paving)
box('Rear apron',(0,121,-.5),(220,30,1),paving)
box('Raised pump island',(-64,-67,0),(70,18,4),paving)
# Two mechanical pumps with enamel bodies, rounded register heads and plain illuminated globes.
for i,x in enumerate((-83,-45),1):
    node=lot_prop('pump_%02d'%i);parent=node
    box('Pump foot',(x,-67,3),(10,8,2),teal,True)
    box('Red pump cabinet',(x,-67,10),(8,7,12),red)
    pts=[(-4,-5),(4,-5),(6,-3),(6,3),(4,5),(-4,5),(-6,3),(-6,-3)]
    v=[((x+dx)*U,(-67+dy)*U,(21+dz)*U) for dy in (-4.5,4.5) for dx,dz in pts]
    mesh('Rounded mechanical register',v,[tuple(range(7,-1,-1)),tuple(range(8,16))]+[(j,(j+1)%8,(j+1)%8+8,j+8) for j in range(8)],wall)
    for dy,side in [(-4.55,'front'),(4.55,'back')]:panel('Mechanical gallon register',(x,-67+dy,21),10,10,'pump_dial',utility,side)
    cylinder('Globe stem',(x,-67,26.5),1,1,teal,n=8)
    sphere('Plain milk-glass globe',(x,-67,30),3,glow,'globe')
    hose('Hanging fuel hose',[(x+5,-67,24),(x+9,-67,22),(x+10,-67,13),(x+9,-67,5),(x+6,-67,5),(x+6,-67,14)],.55)
    box('Docked metal nozzle',(x+6,-67,15),(2,2,4),utility,patch='metal')
    parent=LOT
for x in (-96,-32):
    cylinder('Island guard post',(x,-67,7),1.3,14,red,n=8)
    COLL.append({'center':[x*U,-67*U,7*U],'size':[3*U,3*U,14*U],'group':'lot','source':'Island post','attachment':'lot'})
# A modest roadside board: fixed labels, two separately replaceable hand-lettered prices.
price_board=lot_prop('roadside_price_board');parent=price_board
box('Price board frame',(-125,-99,28),(34,3,38),teal,True)
panel('Price board fixed labels',(-125,-100.55,28),32,36,'price_labels',utility)
for x in (-137,-113):box('Price board legs',(x,-99,6),(2,4,12),utility,patch='wood')
price_panel=panel('sign_price',(-119,-100.6,22),16,20,'prices',price)
price_panel['dynamic_texture']=True;price_panel['texture_file']='prices.png'
parent=LOT
# Unbranded GAS pole, oil rack and a wall-mounted air/water service point.
fuel_sign=lot_prop('roadside_fuel_sign');parent=fuel_sign
cylinder('Gas sign pole',(-122,-30,36),1.2,72,teal,n=8)
COLL.append({'center':[-122*U,-30*U,36*U],'size':[2.4*U,2.4*U,72*U],'group':'lot','source':'Gas sign pole','attachment':'roadside_fuel_sign'})
box('Gas sign cabinet',(-122,-30,73),(26,4,14),red)
for y,side in [(-32.1,'front'),(-27.9,'back')]:panel('GAS sign',(-122,y,73),24,12,'gas',glow,side)
parent=lot_prop('oil_display')
box('Oil display cabinet',(-42,-7,13),(26,10,26),red,True)
panel('Oil cans display',(-42,-12.05,13),24,24,'oil_cabinet',utility)
parent=lot_prop('air_water_station')
box('Air water stand',(-110,42,12),(12,10,24),red,True)
panel('AIR sign',(-101.15,47,22),36,12,'air',glow,'left')
panel('WATER sign',(-101.15,83,22),36,12,'water',glow,'left')
hose('Air hose',[(-114,42,20),(-119,42,18),(-120,42,3),(-116,42,2),(-114,42,12)],.5)
parent=lot_prop('tire_display')
for x in (108,120,132):ring_tire('Spare tire',x,9,7)
box('Tire display rack',(120,9,2),(38,8,2),teal)
COLL.append({'center':[120*U,9*U,6*U],'size':[38*U,8*U,12*U],'group':'lot','source':'Tire display','attachment':'tire_display'})
parent=LOT
# Exterior back-yard service details.
box('Back utility box',(-70,106,20),(12,4,14),utility,patch='metal')
for x in (-87,-80):cylinder('Back pipe',(x,106,17),.6,34,utility,n=8)
for x in (-28,-12):
    box('Waste bin',(x,120,8),(10,10,16),teal,True)
    box('Waste bin lid',(x,120,16.5),(12,12,1),teal)
for x in (-98,-42):box('Service fence',(x,122,10),(2,32,20),utility,patch='wood')
for x in (-84.5,-55.5):box('Rear service fence',(x,138,10),(29,2,20),utility,patch='wood')
# Geometry is solid on the back; doorway interactions do not invent simulation changes.
parent=ROOT;v=[];f=[]
for c in COLL:
    x,y,z=c['center'];w,d,h=c['size'];off=len(v)
    v.extend([(x+a*w/2,y+b*d/2,z+s*h/2) for a,b,s in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]])
    f.extend([tuple(off+j for j in face) for face in [(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)]])
collision=mesh('collision',v,f,teal);collision.hide_render=True;collision['collision_only']=True;collision['render']=False
for container in [STRUCT,LOT,right_door]+PROPS:
    groups={}
    for o in list(container.children):
        if o.type=='MESH' and o not in (price_panel,left_door,right_door):groups.setdefault(o.data.materials[0].name,[]).append(o)
    for name,obs in groups.items():
        bpy.ops.object.select_all(action='DESELECT')
        for o in obs:o.select_set(True)
        bpy.context.view_layer.objects.active=obs[0]
        if len(obs)>1:bpy.ops.object.join()
        obs[0].name=container.name+'__'+name
model=[ROOT]+list(ROOT.children_recursive)
triangles=sum(sum(len(p.vertices)-2 for p in o.data.polygons) for o in model if o.type=='MESH' and o!=collision)
materials=sorted({m.name for o in model if o.type=='MESH' for m in o.data.materials});assert len(materials)<=12 and triangles<=5000
for im in bpy.data.images:
    if im.source=='FILE':im.pack()
bpy.ops.object.select_all(action='DESELECT')
for o in model:o.select_set(True)
bpy.ops.export_scene.gltf(filepath=str(R/'main-street-service-station.glb'),use_selection=True,export_format='GLB',export_yup=True,export_extras=True)
doors=[{'node':n,'position':[x*U,0,-y*U],'outward':[0,0,z]} for n,x,y,z in [('door_main',-64,-2,1),('door_back',66,107,-1),('door_bay_1',0,-2,1),('door_bay_2',66,-2,1)]]
info={'name':'Main Street Service','era':{'suggested_built':1953,'design_years':[1950,1969],'survival_after_1969':'Allowed; age and reuse follow simulation facts, not an automatic expiry.'},'units':'metres','axes':'glTF Y up; front +Z','origin':'Ground level at middle of building front edge','footprint_m':{'building_width':200*U,'building_depth':104*U,'lot_width':300*U,'lot_depth':280*U},'heights_m':{'office_roof':46.2*U,'service_roof':53.2*U,'highest_structure':71*U,'roadside_sign':80*U},'triangles_visible':triangles,'triangles_collision':len(COLL)*12,'materials':materials,'doors':doors,'collision':{'node':'collision','render':False,'boxes_blender_z_up':COLL,'note':'Suppress collision rendering. Skip lot boxes when dropping lot. Remove garage_door_closed collision only if the simulation/interaction opens bay 2.'},'lot_node':'lot','interior':'Clean office and two service-bay shells with fixed counter; game supplies furniture and vehicles.','garage_states':{'garage_door_open':'Bay 1 raised, with vehicle-height access','garage_door_closed':'Bay 2 closed; change its panel and matching collision together'},'swatches_used':['brick_red_terrace','asphalt_plain','sidewalk_cream','roofflat_grey_condo'],'swatch_license':'LimeZu: Timerpg only; credit required','pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'dynamic_prices':{'node':'sign_price','material':'sign_price','texture':'textures/prices.png','texels':[32,40],'grades':['regular','premium'],'year':1959,'values':'Illustrative artwork only; simulation supplies actual per-year prices.'},'history_handoff':{'site_role':'Main Street, compact parcel','later_station_asset':'prototypes/modern-gas-station/modern-gas-station.glb','later_station_min_design_year':2005,'relocation_requires':['Simulation says the business moved or a new station opened','An eligible highway parcel exists'],'old_parcel':'Keep this building and its identity unless the simulation records demolition or reuse. Both stations may coexist.','optional_repair_only_hide':['pump_01','pump_02','roadside_price_board','roadside_fuel_sign'],'repair_only_requires':'A recorded reuse/closure change; do not apply merely because the year advanced.'},'renders':'Blender desktop previews, not Filament or S25 captures'}
info['history_handoff']['collision_reuse_rule']='When hiding a fuel node for repair-only reuse, also drop metadata collision boxes whose attachment matches that node.'
(R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n')
if '--export-only' in sys.argv:print(triangles,len(materials));sys.exit(0)
parent=None
bgmat=bpy.data.materials.new('presentation_ground');bgmat.diffuse_color=(.24,.29,.29,1)
bpy.ops.mesh.primitive_plane_add(size=250,location=(0,0,-.38));bpy.context.object.data.materials.append(bgmat)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=32;scene.cycles.use_denoising=False;scene.cycles.max_bounces=6;scene.cycles.transparent_max_bounces=6
scene.render.resolution_x=1280;scene.render.resolution_y=960;scene.render.resolution_percentage=100;scene.render.image_settings.file_format='PNG'
scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
world=bpy.data.worlds.new('Main Street sky');world.use_nodes=True;scene.world=world;bg=world.node_tree.nodes.get('Background')
sd=bpy.data.lights.new('Sun','SUN');so=bpy.data.objects.new('Sun',sd);bpy.context.collection.objects.link(so);so.rotation_euler=(.42,-.5,-.5);sd.angle=.08
bpy.ops.object.camera_add();cam=bpy.context.object;scene.camera=cam
scene.use_nodes=True;ns=scene.node_tree.nodes;ns.clear();rl=ns.new('CompositorNodeRLayers');gl=ns.new('CompositorNodeGlare');gl.glare_type='FOG_GLOW';gl.threshold=2;gl.size=6;out=ns.new('CompositorNodeComposite');scene.node_tree.links.new(rl.outputs['Image'],gl.inputs['Image']);scene.node_tree.links.new(gl.outputs['Image'],out.inputs['Image'])
def shot(name,pos,target,scale=33,night=False,perspective=False):
    cam.data.type='PERSP' if perspective else 'ORTHO';cam.data.lens=32;cam.data.ortho_scale=scale;cam.location=pos;cam.rotation_euler=(Vector(target)-cam.location).to_track_quat('-Z','Y').to_euler()
    bg.inputs[0].default_value=(*((.12,.18,.28) if night else (.64,.71,.75)),1);bg.inputs[1].default_value=.35 if night else .65
    sd.energy=.35 if night else 2;sd.color=(.5,.65,1) if night else (1,.88,.72)
    for d,p in LIGHTS:d.energy=p*(1 if night else .12)
    glow.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=1 if night else .12
    scene.render.filepath=str(R/'renders'/(name+'.png'))
    if name=='01-day-front':bpy.ops.wm.save_as_mainfile(filepath=str(R/'main-street-service-station.blend'))
    bpy.ops.render.render(write_still=True)
if '--draft' in sys.argv:scene.cycles.samples=16;scene.render.resolution_x=1000;scene.render.resolution_y=750
shot('01-day-front',(-24,-29,20),(0,0,1.2))
if '--draft' not in sys.argv:
    shot('02-blue-hour',(-24,-29,17),(0,0,1.3),33,True)
    shot('03-back',(25,29,19),(0,2,1.3),34)
    shot('04-above',(-13,-20,42),(0,0,0),33)
    shot('05-person-height',(18,-26,1.7),(0,1,2.2),33,True,True)
    shot('06-mechanical-pumps',(-1,-11.5,2.1),(-5,-5,1.65),20,False,True)
print(json.dumps({'visible_triangles':triangles,'materials':len(materials),'collision_boxes':len(COLL)}))
