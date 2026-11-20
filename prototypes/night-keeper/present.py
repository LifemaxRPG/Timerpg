"""Export the model before adding the desktop-only context and cameras."""
scene=bpy.context.scene
def at(p):return V(p)+V((0,Y0,0))
if not early:
    # Join by material inside each structural group; keep identifying panels and removable props.
    for g in [figure,lantern,court]+list(lot.children):
        bins={}
        for ob in list(g.children):
            if ob.type=='MESH' and ob.name!='sign_name':bins.setdefault(tuple(m.name for m in ob.data.materials),[]).append(ob)
        for key,obs in bins.items():
            if len(obs)<2:continue
            bpy.ops.object.select_all(action='DESELECT')
            for ob in obs:ob.select_set(True)
            bpy.context.view_layer.objects.active=obs[0];bpy.ops.object.join();obs[0].name=g.name+'__'+key[0]
    objs=[ob for ob in body.children_recursive if ob.type=='MESH']
    tri=sum(len(p.vertices)-2 for ob in objs for p in ob.data.polygons)
    mats={m.name for ob in objs for m in ob.data.materials}
    height=max(v.co.z for ob in objs for v in ob.data.vertices)
    assert tri<=12000 and len(mats)<=12
    info={'name':'The Night Keeper','era':'Original interwar civic commission, suitable from the 1930s onward','footprint_m':[62,50],'height_m':round(height,4),'triangles_visible':tri,'materials':sorted(mats),'units':'metres, glTF Y up, +Z south/front; origin at south sidewalk','doors':[{'node':o.name,'position_gltf_m':[o.location.x,o.location.z,-o.location.y]} for o in [door,back]],'collision':{'use_the_walls':True,'notes':'Use pier, figure, square, pavilion post and pane geometry. Both lantern portals stay open.'},'swatches_used':['brick_red','pavement','plank_horizontal'],'texel_size_m':.0388,'texels_per_art_pixel':2,'runtime':'Static civic sculpture. Light and navigation integration require engine testing.'}
    (R/'model-info.json').write_text(json.dumps(info,indent=2)+'\n')
    bpy.ops.object.select_all(action='SELECT')
    bpy.ops.export_scene.gltf(filepath=str(R/'night-keeper.glb'),export_format='GLB',use_selection=True,export_yup=True,export_extras=True)
stage=group('Desktop review')
bpy.ops.mesh.primitive_plane_add(size=2000,location=(0,Y0,-.03))
base=bpy.context.object;base.parent=stage
m=bpy.data.materials.new('Context ground');m.diffuse_color=(.21,.24,.22,1);base.data.materials.append(m)
world=bpy.data.worlds.new('Review sky');world.use_nodes=True;scene.world=world;bg=world.node_tree.nodes.get('Background')
exec(compile((R/'studio.py').read_text(),str(R/'studio.py'),'exec'))
sun=bpy.data.lights.new('Late sun','SUN');so=bpy.data.objects.new('Late sun',sun);bpy.context.collection.objects.link(so);so.parent=stage
so.rotation_euler=V((.6,.65,-.93)).to_track_quat('-Z','Y').to_euler();sun.angle=.12
def area(name,pos,target,pow,color,size):
    d=bpy.data.lights.new(name,'AREA');d.energy=pow;d.size=size;d.color=color
    ob=bpy.data.objects.new(name,d);bpy.context.collection.objects.link(ob);ob.location=at(pos);ob.parent=stage
    ob.rotation_euler=(at(target)-ob.location).to_track_quat('-Z','Y').to_euler()
    return d,pow
lights=[area('Warm lantern bounce',(11,-10,5),(0,-.5,28),7200,(1,.69,.40),6),area('Plaza fill',(-12,-18,2),(-4,0,22),8700,(.64,.76,1),8),area('Back rim',(10,12,19),(-2,3,20),6300,(1,.71,.47),9)]
scene.render.engine='CYCLES';scene.cycles.samples=18;scene.cycles.max_bounces=2
scene.cycles.use_denoising=False
scene.render.resolution_x=1000;scene.render.resolution_y=800;scene.render.resolution_percentage=100
scene.view_settings.view_transform='Standard';scene.view_settings.look='Medium High Contrast'
bpy.ops.object.camera_add();cam=bpy.context.object;cam.parent=stage;scene.camera=cam;cam.data.clip_end=1500
def shot(name,pos,target,lens,night=False):
    cam.location=at(pos);cam.rotation_euler=(at(target)-cam.location).to_track_quat('-Z','Y').to_euler();cam.data.lens=lens
    bg.inputs[0].default_value=(.16,.22,.34,1) if night else (.67,.76,.82,1)
    bg.inputs[1].default_value=.48 if night else .82
    sun.energy=.26 if night else 2.2;sun.color=(.65,.72,1) if night else (1,.91,.77)
    sky_colors(night)
    for d,w in lights:d.energy=w if night else 0
    glass.node_tree.nodes.get('Principled BSDF').inputs['Emission Strength'].default_value=1.65 if night else .13
    if name=='day' and not early:
        bpy.context.preferences.filepaths.save_version=0
        bpy.ops.wm.save_as_mainfile(filepath=str(R/'night-keeper.blend'))
    scene.render.filepath=str((Path('/tmp/keeper-study') if early else R/'renders')/(name+'.png'))
    bpy.ops.render.render(write_still=True)
if early:
    # Place the original Gazette shop at a known offset, unchanged metres and scale.
    path=Path(os.environ['KEEPER_REFERENCE_GLB'])
    before=set(bpy.data.objects)
    bpy.ops.import_scene.gltf(filepath=str(path))
    for ob in set(bpy.data.objects)-before:
        if ob.name.startswith('collision') or ob.get('collision_only'):ob.hide_render=True
        if not ob.parent:ob.location+=at((-28,-9,0))
    scene.render.resolution_percentage=65;scene.cycles.samples=4
    shot('comparison',(24,-81,1.7),(-8,0,14),35)
else:
    shot('day',(9,-84,18),(0,0,14.4),43)
    shot('night',(6,-76,14),(1,0,15),41,True)
    shot('walking',(13,-42,1.7),(2,-2,14),30)
    shot('rear',(-43,57,32),(-1,2,15),43)
    shot('above',(35,-30,82),(0,0,4),44)
    shot('lantern',(16,-21,2),(10,-8,4),34,True)

    if os.environ.get('KEEPER_REFERENCE_GLB'):exec(compile((R/'street_study.py').read_text(),str(R/'street_study.py'),'exec'))
