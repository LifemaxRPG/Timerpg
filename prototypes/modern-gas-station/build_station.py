"""Crossfield Fuel & Market. Original landmark; follows docs/CHATGPT_MODEL_GUIDE.md.
Blender 4.3: blender -b --python build_station.py -- [--draft] [--no-render]
Source axes Z up, front -Y. Metres; one art pixel is exactly 0.0776m.
"""
import bpy, math, json, sys
from pathlib import Path
from mathutils import Vector
R=Path(__file__).resolve().parent
U=.0776; T=.0388
REG=json.loads((R/'art-regions.json').read_text())
bpy.ops.object.select_all(action='SELECT');bpy.ops.object.delete(use_global=False)
for m in list(bpy.data.materials):bpy.data.materials.remove(m)
ROOT=bpy.data.objects.new('crossfield_station',None);bpy.context.collection.objects.link(ROOT)
LOT=bpy.data.objects.new('lot',None);bpy.context.collection.objects.link(LOT);LOT.parent=ROOT
STRUCT=bpy.data.objects.new('structure',None);bpy.context.collection.objects.link(STRUCT);STRUCT.parent=ROOT
COLL=[];LIGHTS=[]; parent=STRUCT

def mat(name,file,alpha=False,emission=False):
    m=bpy.data.materials.new(name);m.use_nodes=True
    n=m.node_tree.nodes.get('Principled BSDF');n.inputs['Roughness'].default_value=.8
    n.inputs['Metallic'].default_value=.08 if 'enamel' in name else 0
    tex=m.node_tree.nodes.new('ShaderNodeTexImage');tex.image=bpy.data.images.load(str(R/'textures'/file),check_existing=True);tex.interpolation='Closest';tex.extension='REPEAT'
    m.node_tree.links.new(tex.outputs['Color'],n.inputs['Base Color'])
    if alpha:
        m.node_tree.links.new(tex.outputs['Alpha'],n.inputs['Alpha']);m.surface_render_method='DITHERED';n.inputs['Roughness'].default_value=.32
    if emission:
        m.node_tree.links.new(tex.outputs['Color'],n.inputs['Emission Color']);n.inputs['Emission Strength'].default_value=.2
    m['texture_file']=file
    return m

wall=mat('wall_concrete','concrete_office_light.png')
clad=mat('wall_corrugated','metal_corrugated_hotel.png')
roof=mat('roof_membrane','roofflat_grey_condo.png')
asphalt=mat('ground_asphalt','asphalt_plain.png')
paving=mat('ground_paving','sidewalk_cream.png')
floor=mat('interior_floor','fl_0_00.png')
white=mat('trim_enamel_cream','enamel_white.png')
navy=mat('trim_enamel_charcoal','enamel_navy.png')
orange=mat('trim_enamel_terracotta','enamel_orange.png')
utility=mat('detail_utility','utility.png')
glass=mat('glass_storefront','glass.png',alpha=True)
glow=mat('glow_station_signs_and_lamps','signs.png',emission=True)

def mesh(name,verts,faces,material,patch=None):
    flat_faces=[]
    for face in faces:
        co=[Vector(verts[i]) for i in face]
        normal=(co[1]-co[0]).cross(co[2]-co[0]).normalized()
        if len(face)>3 and any(abs((p-co[0]).dot(normal))>1e-7 for p in co[3:]):
            flat_faces.extend([(face[0],face[j],face[j+1]) for j in range(1,len(face)-1)])
        else:flat_faces.append(face)
    me=bpy.data.meshes.new(name);me.from_pydata(verts,[],flat_faces);me.update()
    ob=bpy.data.objects.new(name,me);bpy.context.collection.objects.link(ob);ob.parent=parent;me.materials.append(material)
    lay=me.uv_layers.new(name='Physical pixel grid')
    tex=bpy.data.images.get(Path(material['texture_file']).name)
    W,H=tex.size
    for p in me.polygons:
        co=[me.vertices[me.loops[i].vertex_index].co for i in p.loop_indices]
        origin=co[0]
        a=Vector((-p.normal.y,p.normal.x,0)).normalized() if abs(p.normal.z)<.95 else Vector((1,0,0))
        a=(a-p.normal*a.dot(p.normal)).normalized()
        b=p.normal.cross(a).normalized()
        local=[((v-origin).dot(a),(v-origin).dot(b)) for v in co]
        lo=(min(v[0] for v in local),min(v[1] for v in local))
        # Coordinate basis is orthonormal on each face: UV scale cannot squeeze pixels.
        px,py=0,0
        if patch:
            r=REG[patch]['rect_art'];px=r[0]*2;py=H-(r[1]+r[3])*2
        for li,xy in zip(p.loop_indices,local):
            lay.data[li].uv=((px+(xy[0]-lo[0])/T)/W,(py+(xy[1]-lo[1])/T)/H)
    return ob

def box(name,pos,size,m=wall,collision=False,patch=None):
    x,y,z=[v*U for v in pos];w,d,h=[v*U for v in size]
    vs=[(x+a*w/2,y+b*d/2,z+c*h/2) for a,b,c in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]]
    ob=mesh(name,vs,[(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)],m,patch)
    if collision:COLL.append({'center':[x,y,z],'size':[w,d,h],'group':'lot' if parent==LOT else 'structure','source':name})
    return ob

def panel(name,p,w,h,key,m,side='front'):
    x,y,z=[v*U for v in p];w*=U;h*=U
    if side=='front':v=[(x-w/2,y,z-h/2),(x+w/2,y,z-h/2),(x+w/2,y,z+h/2),(x-w/2,y,z+h/2)]
    elif side=='back':v=[(x+w/2,y,z-h/2),(x-w/2,y,z-h/2),(x-w/2,y,z+h/2),(x+w/2,y,z+h/2)]
    elif side=='left':v=[(x,y+w/2,z-h/2),(x,y-w/2,z-h/2),(x,y-w/2,z+h/2),(x,y+w/2,z+h/2)]
    elif side=='up':v=[(x-w/2,y-h/2,z),(x+w/2,y-h/2,z),(x+w/2,y+h/2,z),(x-w/2,y+h/2,z)]
    elif side=='down':v=[(x-w/2,y+h/2,z),(x+w/2,y+h/2,z),(x+w/2,y-h/2,z),(x-w/2,y-h/2,z)]
    else:v=[(x,y-w/2,z-h/2),(x,y+w/2,z-h/2),(x,y+w/2,z+h/2),(x,y-w/2,z+h/2)]
    ob=mesh(name,v,[(0,1,2,3)],m)
    r=REG[key]['rect_art'];W,H=bpy.data.images.get(Path(m['texture_file']).name).size
    assert abs(w/U-r[2])<1e-5 and abs(h/U-r[3])<1e-5,(name,w/U,h/U,r)
    uv=[(r[0]*2/W,1-(r[1]+r[3])*2/H),((r[0]+r[2])*2/W,1-(r[1]+r[3])*2/H),((r[0]+r[2])*2/W,1-r[1]*2/H),(r[0]*2/W,1-r[1]*2/H)]
    for li,v in zip(ob.data.polygons[0].loop_indices,uv):ob.data.uv_layers.active.data[li].uv=v
    return ob

def cylinder(name,pos,r,h,m=utility,patch='metal',n=8):
    x,y,z=[v*U for v in pos];r*=U;h*=U
    v=[(x+r*math.cos(i*2*math.pi/n),y+r*math.sin(i*2*math.pi/n),z+s*h/2) for s in (-1,1) for i in range(n)]
    return mesh(name,v,[tuple(range(n-1,-1,-1)),tuple(range(n,n*2))]+[(i,(i+1)%n,(i+1)%n+n,i+n) for i in range(n)],m,patch)

def hose(name,pts,r=0.6):
    pts=[Vector(p)*U for p in pts];v=[];n=6
    for i,p in enumerate(pts):
        t=(pts[min(i+1,len(pts)-1)]-pts[max(0,i-1)]).normalized();a=Vector((0,1,0));b=t.cross(a).normalized()
        for j in range(n):v.append(tuple(p+U*r*(a*math.cos(j*math.tau/n)+b*math.sin(j*math.tau/n))))
    f=[tuple(range(n-1,-1,-1)),tuple(range((len(pts)-1)*n,len(pts)*n))]
    for i in range(len(pts)-1):
        for j in range(n):f.append((i*n+j,i*n+(j+1)%n,(i+1)*n+(j+1)%n,(i+1)*n+j))
    return mesh(name,v,f,utility,'rubber')

def light(name,p,power,size,color=(1,.87,.68)):
    d=bpy.data.lights.new(name,'AREA');d.energy=power;d.shape='DISK';d.size=size*U;d.color=color
    ob=bpy.data.objects.new(name,d);bpy.context.collection.objects.link(ob);ob.location=[a*U for a in p];LIGHTS.append((d,power));return ob

def door(name,p,yaw=0):
    o=bpy.data.objects.new(name,None);bpy.context.collection.objects.link(o);o.parent=ROOT;o.location=[a*U for a in p];o.rotation_euler.z=yaw;o['interaction']='Go inside';return o

# Main shop: 17.38 x 8.69m. Floor at entrance level; shell and fixed counter only.
box('Shop floor',(0,56,-.5),(224,112,1),floor)
box('Left exterior wall',(-111,56,24),(2,112,48),wall,True)
box('Right wall rear',(111,80,24),(2,64,48),wall,True)
box('Right wall front',(111,8,24),(2,16,48),wall,True)
box('Right window sill',(111,32,5),(2,32,10),wall,True)
box('Right window lintel',(111,32,43),(2,32,10),wall,True)
panel('Right side window',(112.02,32,24),32,28,'side_glass',glass,'right')
for x,w in [(-28,168),(92,40)]:box('Rear wall',(x,111,24),(w,2,48),wall,True)
box('Service exit lintel',(64,111,40),(16,2,16),wall,True)
box('Service exit door',(64,112,16),(16,1,32),navy)
panel('Service exit face',(64,112.6,16),16,32,'service_door',utility,'back')
door('door_back',(64,115,0),math.pi)
# Painted window frames reside in the glass art. The door remains a separate component.
for x in (-63,63):
    box('Front knee wall',(x,1,4),(90,2,8),wall,True)
    panel('Shop glazing',(x,-.05,26),90,36,'front_glass',glass)
for x in (-110,110,-17,17):box('Front structural jamb',(x,1,24),(4,2,48),navy,True)
box('Front glazing header',(0,1,46),(224,2,4),clad)
box('Entry transom support',(0,1,40),(32,2,16),navy)
panel('Double entry doors',(0,-.1,16),32,32,'entry_glass',glass)
door('door_main',(0,-2,0))
box('Ceiling and closed roof',(0,56,48.5),(224,112,1),white)
box('Roof membrane',(0,56,49.1),(224,112,.2),roof)
for x in (-113,113):box('Parapet side',(x,56,53),(2,116,8),clad)
for y in (-1,113):box('Parapet front or back',(0,y,53),(224,2,8),clad)
for x in (-113,113):box('Parapet coping',(x,56,57.5),(4,118,1),white)
for y in (-1,113):box('Parapet coping',(0,y,57.5),(224,4,1),white)
box('Orange storefront trim',(0,-2,47),(224,2,2),orange)
box('Brand sign backing',(0,-3,53),(82,2,14),navy)
panel('Shop brand sign',(0,-4.1,53),80,12,'brand',glow)
box('Permanent checkout counter',(-65,30,8),(48,12,16),clad)
box('Counter worktop',(-65,30,16.5),(50,14,1),white)
box('Entry welcome mat',(0,8,.1),(30,16,.2),navy)
for x in (-64,64):
    panel('Interior ceiling diffuser',(x,56,47.7),16,4,'lamp',glow,'down');light('Store light',(x,56,46),320,32)

# Roof equipment, with painted louvres and modest low-poly pipes.
for x,y in [(-60,76),(60,72)]:
    box('HVAC curb',(x,y,50.5),(34,24,3),navy)
    box('Roof HVAC',(x,y,60),(32,22,16),utility,patch='metal')
    panel('HVAC louvres',(x,y-11.1,60),24,16,'louvre',utility)
    # Louvred face is taller than the curb but rests on it; all details are artwork.
    cylinder('Condenser fan cap',(x,y,68.5),10,1,navy,patch=None,n=12)
for x,y in [(-94,92),(90,94)]:
    cylinder('Roof vent stem',(x,y,55),1.5,12,utility,n=8);cylinder('Roof vent cap',(x,y,61.5),3,1,utility,n=8)

# Canopy: four pumps, two columns and a solid lit underside, physically separate from store.
box('Canopy underside',(0,-168,66),(248,152,2),white)
box('Canopy weatherproof roof',(0,-168,69),(248,152,4),roof)
for x in (-123,123):
    box('Canopy navy side fascia',(x,-168,69),(2,154,6),orange)
    box('Canopy orange side stripe',(x,-168,71),(3,154,2),orange)
    box('Canopy white cap',(x,-168,73),(4,156,2),white)
for y in (-245,-91):
    box('Canopy navy front fascia',(0,y,69),(248,2,6),orange)
    box('Canopy orange front stripe',(0,y,71),(248,3,2),orange)
    box('Canopy white cap',(0,y,73),(252,4,2),white)
panel('Canopy road-facing brand',(0,-246.6,68),96,8,'canopy_brand',glow)
panel('Canopy store-facing brand',(0,-89.4,68),96,8,'canopy_brand',glow,'back')
for x in (-64,64):
    box('Canopy column',(x,-168,32.5),(6,6,65),white,True)
    box('Column base guard',(x,-168,5),(8,8,10),navy)
    for y in (-213,-123):
        panel('Canopy ceiling light',(x,y,64.8),16,4,'lamp',glow,'down');light('Forecourt pool',(x,y,63),420,40,(.9,.94,1))

# Pump props are compact solid shapes; controls are pixel art, hoses use six-sided tubes.
for i,(x,y) in enumerate([(-64,-202),(-64,-134),(64,-202),(64,-134)],1):
    node=bpy.data.objects.new('pump_%02d'%i,None);bpy.context.collection.objects.link(node);node.parent=STRUCT
    old=parent;parent=node
    box('Pump plinth',(x,y,2),(20,16,4),navy,True)
    box('Pump lower cabinet',(x,y,11),(16,12,18),orange)
    box('Pump display head',(x,y,27),(18,14,16),white)
    # The 16x20 art includes display, grade buttons and card slot at the fixed grid.
    for dy,side in [(-7.1,'front'),(7.1,'back')]:panel('Pump controls',(x,y+dy,25),16,20,'pump',utility,side)
    for dx in (-12,12):
        hose('Pump hose',[(x+dx*.75,y,32),(x+dx,y,28),(x+dx*1.2,y,16),(x+dx*1.1,y,6),(x+dx*.8,y,7),(x+dx*.7,y,18)])
        box('Docked nozzle',(x+dx*.7,y,19),(2,3,5),navy)
    panel('Pump number',(x,y-7.2,40),8,10,'number'+str(i),glow)
    box('Pump number backing',(x,y-6,40),(10,2,12),navy)
    parent=old

# All removable surroundings are children of lot. No long presentation road in the asset.
parent=LOT
box('Forecourt asphalt',(0,-90,-2),(416,416,2),asphalt)
box('Store footpath',(0,-12,-.5),(248,24,1),paving)
box('Front kerb',(0,-24,0),(248,2,2),paving)
for x in (-64,64):
    box('Pump island',(x,-168,0),(26,114,4),paving)
    for y in (-223,-113):
        cylinder('Safety bollard',(x,y,7),1.5,14,utility,'yellow')
for x in (-95,-58,58,95):
    for y in (-34,-50,-66,-82):panel('Parking paint',(x,y,-.85),2,16,'paint_strip',utility,'up')
for x in (-76,76):box('Parking wheel stop',(x,-29,0),(24,3,2),paving)
for x in (-166,166):
    box('Side planted bed border',(x,-16,-.2),(24,104,2),paving)
    box('Gravel bed',(x,-16,1),(21,101,1),roof)
    for y in (-48,-16,16):box('Low landscape block',(x,y,5),(14,16,8),utility,patch='leaf')
# Price pylon, fully backed and readable on both sides.
box('Pylon base',(-170,-248,2),(28,16,4),paving)
box('Price pylon mast',(-170,-248,46),(8,8,88),navy,True)
box('Price cabinet',(-170,-248,67),(42,8,66),white)
for y,side in [(-252.1,'front'),(-243.9,'back')]:panel('Fuel price sign',(-170,y,67),40,64,'price',glow,side)
# Rear yard: service access, utility pipework, bins, short textured enclosure.
box('Back service apron',(0,133,-.5),(248,42,1),paving)
for x in (30,46):
    box('Waste bin',(x,133,8),(12,12,16),navy,True)
    panel('Bin front',(x,126.9,8),12,16,'bin',utility)
    box('Bin lid',(x,133,17),(14,14,2),navy)
for x in (-72,-54):
    cylinder('Tank-fill access',(x,-276,-.7),3,.4,utility,'metal',n=8)
for x in (-95,-80):
    cylinder('Rear pipe',(x,114,16),.75,32,utility,n=8)
    cylinder('Pipe cap',(x,114,32),1.5,1,utility,n=8)
box('Rear meter box',(-60,115,20),(12,4,16),utility,patch='metal')
for x in (-103,-36):box('Enclosure side',(x,135,12),(2,36,24),clad,True)
box('Enclosure rear',(-69.5,153,12),(69,2,24),clad,True)
panel('Storm drain',(80,145,.1),24,8,'drain',utility,'up')
# Air/water station along the right-hand drive lane.
box('Air station plinth',(155,72,1),(24,20,2),paving)
box('Air station body',(155,72,14),(20,12,26),navy,True)
box('Air label backing',(155,66.8,22),(26,2,14),navy)
panel('Air station label',(155,65.7,22),24,12,'air',glow)
hose('Air station hose',[(164,72,24),(170,72,22),(171,72,4),(166,72,3),(164,72,13)],.5)

# Collision is explicitly separate and is never included in presentation renders.
parent=ROOT;vs=[];fs=[]
for c in COLL:
    x,y,z=c['center'];w,d,h=c['size'];off=len(vs)
    vs.extend([(x+a*w/2,y+b*d/2,z+s*h/2) for a,b,s in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]])
    fs.extend([tuple(off+i for i in f) for f in [(0,3,2,1),(0,1,5,4),(1,2,6,5),(2,3,7,6),(3,0,4,7),(4,5,6,7)]])
collision=mesh('collision',vs,fs,navy);collision.hide_render=True;collision['collision_only']=True;collision['render']=False

# Merge by material within structure, pumps and lot, preserving optional lot and entrance nodes.
for container in [STRUCT,LOT]+[o for o in bpy.data.objects if o.name.startswith('pump_') and o.type=='EMPTY']:
    groups={}
    for o in list(container.children):
        if o.type=='MESH':groups.setdefault(o.data.materials[0].name,[]).append(o)
    for name,obs in groups.items():
        bpy.ops.object.select_all(action='DESELECT')
        for o in obs:o.select_set(True)
        bpy.context.view_layer.objects.active=obs[0]
        if len(obs)>1:bpy.ops.object.join()
        obs[0].name=container.name+'__'+name

# Export before adding presentation-only scenery and lights/camera.
model_objects=[ROOT]+list(ROOT.children_recursive)
triangles=sum(sum(len(p.vertices)-2 for p in o.data.polygons) for o in model_objects if o.type=='MESH' and o!=collision)
materials=sorted({m.name for o in model_objects if o.type=='MESH' for m in o.data.materials})
assert len(materials)<=12 and triangles<=12000,(len(materials),triangles)
for im in bpy.data.images:
    if im.source=='FILE':im.pack()
bpy.ops.object.select_all(action='DESELECT')
for o in model_objects:o.select_set(True)
bpy.ops.export_scene.gltf(filepath=str(R/'modern-gas-station.glb'),use_selection=True,export_format='GLB',export_yup=True,export_extras=True)
info={'name':'Crossfield Fuel & Market','era':{'built':2005,'typical_years':[2005,2029]},'units':'metres','axes':'glTF Y up, front +Z','origin':'Ground level at the middle of the store front edge','footprint_m':{'store_width':224*U,'store_depth':112*U,'lot_width':416*U,'lot_depth':452*U,'asphalt_depth':416*U,'store_roof_width':230*U,'store_roof_depth':118*U},'heights_m':{'store_roof':58*U,'store_sign_top':60*U,'roof_equipment_top':69*U,'canopy':74*U,'canopy_clearance':64*U,'canopy_ceiling':65*U,'price_pylon':100*U},'triangles_visible':triangles,'triangles_collision':len(COLL)*12,'materials':materials,'doors':[{'node':'door_main','position':[0,0,2*U],'outward':[0,0,1]},{'node':'door_back','position':[64*U,0,-115*U],'outward':[0,0,-1]}],'pixel_grid':{'metres_per_texel':T,'texels_per_art_pixel':2},'lot_node':'lot','interior':'Shell plus fixed checkout counter; no loose furniture','collision':{'node':'collision','render':False,'boxes_blender_z_up':COLL,'note':'Importer must suppress collision mesh rendering and use these boxes for blocking; door openings remain clear. Skip boxes tagged lot when dropping the optional lot group.'},'swatches_used':['concrete_office_light','metal_corrugated_hotel','roofflat_grey_condo','asphalt_plain','sidewalk_cream','fl_0_00'],'swatch_license':'LimeZu: Timerpg game use only, credit required','renders':'Blender desktop previews, not Filament or S25 captures'}
(R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n')

if '--export-only' in sys.argv:
    print(json.dumps({'visible_triangles':triangles,'materials':len(materials),'collision_boxes':len(COLL)}));sys.exit(0)
# A detached ground plane is presentation-only; there is no baked lighting in textures.
parent=None
bgmat=bpy.data.materials.new('presentation_background');bgmat.diffuse_color=(.22,.25,.29,1)
bpy.ops.mesh.primitive_plane_add(size=300,location=(0,0,-.4));bpy.context.object.name='Presentation only ground';bpy.context.object.data.materials.append(bgmat)
scene=bpy.context.scene;scene.render.engine='CYCLES';scene.cycles.samples=32;scene.cycles.use_denoising=False
scene.cycles.max_bounces=6;scene.cycles.transparent_max_bounces=6
scene.render.resolution_x=1280;scene.render.resolution_y=960;scene.render.resolution_percentage=100;scene.render.image_settings.file_format='PNG'
scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
world=bpy.data.worlds.new('Crossfield sky');world.use_nodes=True;scene.world=world;bg=world.node_tree.nodes.get('Background')
sun_data=bpy.data.lights.new('Sun','SUN');sun_obj=bpy.data.objects.new('Sun',sun_data);bpy.context.collection.objects.link(sun_obj);sun_obj.rotation_euler=(.4,-.5,-.5);sun_data.angle=.08
bpy.ops.object.camera_add();camera=bpy.context.object;camera.name='Preview camera';scene.camera=camera;camera.data.type='ORTHO'
scene.use_nodes=True;nd=scene.node_tree.nodes;nd.clear();rl=nd.new('CompositorNodeRLayers');gl=nd.new('CompositorNodeGlare');gl.glare_type='FOG_GLOW';gl.quality='MEDIUM';gl.threshold=2;gl.size=6;out=nd.new('CompositorNodeComposite');scene.node_tree.links.new(rl.outputs['Image'],gl.inputs['Image']);scene.node_tree.links.new(gl.outputs['Image'],out.inputs['Image'])
def shot(name,pos,target,scale=49,night=False,perspective=False):
    camera.data.type='PERSP' if perspective else 'ORTHO';camera.data.lens=30 if perspective else 34;camera.data.ortho_scale=scale;camera.location=pos;camera.rotation_euler=(Vector(target)-camera.location).to_track_quat('-Z','Y').to_euler()
    bg.inputs[0].default_value=(*((.12,.18,.29) if night else (.62,.69,.78)),1);bg.inputs[1].default_value=.35 if night else .7
    sun_data.energy=.38 if night else 2;sun_data.color=(.48,.62,1) if night else (1,.88,.72)
    for d,p in LIGHTS:d.energy=p*(1 if night else .12)
    glow.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=1.0 if night else .12
    scene.render.filepath=str(R/'renders'/(name+'.png'))
    if name=='01-day-front':bpy.ops.wm.save_as_mainfile(filepath=str(R/'modern-gas-station.blend'))
    bpy.ops.render.render(write_still=True)

if '--no-render' not in sys.argv:
    if '--draft' in sys.argv:scene.cycles.samples=16;scene.render.resolution_x=1000;scene.render.resolution_y=750
    shot('01-day-front',(-34,-44,29),(0,-7,1.2),49)
    if '--draft' not in sys.argv:
        shot('02-blue-hour',(-34,-44,22),(0,-7,1.5),49,True)
        shot('03-back',(30,28,22),(0,-3,1.5),48)
        shot('04-above',(-18,-28,57),(0,-5,0),49)
        shot('05-person-height',(-18,-38,1.7),(0,-5,2.5),49,True,True)
else:
    bpy.ops.wm.save_as_mainfile(filepath=str(R/'modern-gas-station.blend'))
print(json.dumps({'visible_triangles':triangles,'materials':len(materials),'collision_boxes':len(COLL)},indent=2))
