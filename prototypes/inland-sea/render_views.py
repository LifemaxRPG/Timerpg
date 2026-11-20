"""Desktop camera rig. Executed after the selected-model GLB export."""
from mathutils import Vector as V
def at(xyz):return V(xyz)+V((0,CENTRE_Y,0))
def aim(ob,target):ob.rotation_euler=(at(target)-ob.location).to_track_quat("-Z","Y").to_euler()
stage=empty("Desktop presentation")
bpy.ops.mesh.primitive_plane_add(size=6000,location=(0,0,-.04))
ground=bpy.context.object
ground.parent=stage
gm=bpy.data.materials.new("Distant neutral review ground")
gm.diffuse_color=(.19,.225,.218,1)
ground.data.materials.append(gm)
exec(compile((R/'review_context.py').read_text(),str(R/'review_context.py'),'exec'))
scene.render.engine="CYCLES"
scene.cycles.samples=12
scene.cycles.max_bounces=4
scene.cycles.use_denoising=False
scene.render.resolution_x=1280
scene.render.resolution_y=900
scene.render.resolution_percentage=100
scene.view_settings.view_transform="AgX"
scene.view_settings.look="AgX - Medium High Contrast"
world=bpy.data.worlds.new("Coastal daylight")
scene.world=world
world.use_nodes=True
bg=world.node_tree.nodes.get("Background")
sky=world.node_tree.nodes.new("ShaderNodeTexSky")
sky.sky_type="NISHITA"
sky.sun_elevation=radians(17)
sky.sun_rotation=radians(232)
sky.sun_disc=False
sky.air_density=1
sky.dust_density=1.1
sky.ozone_density=1
sun=bpy.data.lights.new("Raking sun","SUN")
so=bpy.data.objects.new("Raking sun",sun)
bpy.context.collection.objects.link(so)
so.parent=stage
so.rotation_euler=V((.63,.55,-.68)).to_track_quat("-Z","Y").to_euler()
sun.angle=radians(5)
lights=[]
def flood(name,position,target,power,color,size):
    d=bpy.data.lights.new(name,"AREA");d.shape="DISK";d.size=size;d.color=color
    ob=bpy.data.objects.new(name,d);bpy.context.collection.objects.link(ob)
    ob.parent=stage;ob.location=at(position);aim(ob,target)
    lights.append((d,power))
flood("Bow amber wash",(30,-23,5),(17,0,51),69000,(1,.66,.32),13)
flood("Blue hull wash",(-31,-24,3),(-12,0,34),44000,(.37,.75,1),12)
flood("Portico wash",(-14,30,3),(-18,0,23),48000,(1,.74,.49),11)
flood("Dorsal rim",(-10,33,72),(0,0,44),105000,(.45,.69,1),24)
for i in range(12):
    a=2*pi*(i+.5)/12
    flood("Court lantern %02d"%i,(59.5*cos(a),41.3*sin(a),3.6),(59.5*cos(a),41.3*sin(a),0),150,(1,.65,.3),.6)
bpy.ops.object.camera_add()
cam=bpy.context.object;cam.parent=stage;scene.camera=cam;cam.data.clip_end=3000
# Atmospheric perspective applies only to desktop context surfaces.
fog_controls=[]
for m in (gm,haze,haze2,canopy):
    base=tuple(m.diffuse_color)
    used_nodes=m.use_nodes
    m.use_nodes=True;n=m.node_tree.nodes;links=m.node_tree.links
    bs=n.get("Principled BSDF")
    if not used_nodes:bs.inputs["Base Color"].default_value=base
    ray=n.new("ShaderNodeCameraData")
    ramp=n.new("ShaderNodeMapRange");ramp.inputs["From Min"].default_value=85
    ramp.inputs["From Max"].default_value=750;ramp.inputs["To Max"].default_value=.89
    links.new(ray.outputs["View Distance"],ramp.inputs["Value"])
    mix=n.new("ShaderNodeMixShader");em=n.new("ShaderNodeEmission")
    links.new(ramp.outputs[0],mix.inputs[0]);links.new(bs.outputs[0],mix.inputs[1]);links.new(em.outputs[0],mix.inputs[2])
    links.new(mix.outputs[0],n.get("Material Output").inputs[0]);fog_controls.append(em)
shots=[
 ("day",(38,-158,38),(-2,0,32),42),
 ("night",(44,-151,24),(-4,0,33),42),
 ("rear",(85,145,39),(-2,0,32),43),
 ("above",(68,-89,156),(0,0,10),36),
 ("person",(-27,-59,1.7),(-5,0,32),23),
 ("portrait",(48,-72,46),(17,-1,56),51),
 ("shore",(-77,-128,28),(-3,0,33),43),
 ("underneath",(-24,-18,4.1),(-6,0,27),19),
 ("frames",(-53,-45,20),(-15,-1,30),44)]
draft="--draft" in sys.argv
only=next((arg.split("=")[1].split(",") for arg in sys.argv if arg.startswith("--shots=")),None)
for name,position,target,lens in shots:
    if only and name not in only:continue
    night=name=="night"
    sky.sun_elevation=radians(10 if name=="shore" else 21)
    for link in list(bg.inputs["Color"].links):world.node_tree.links.remove(link)
    if night:bg.inputs["Color"].default_value=(.095,.15,.29,1)
    else:world.node_tree.links.new(sky.outputs[0],bg.inputs[0])
    bg.inputs["Strength"].default_value=.46 if night else .25
    sun.energy=.18 if night else 2.6
    sun.color=(.52,.67,1) if night else (1,.87,.69)
    for e in fog_controls:e.inputs[0].default_value=(.024,.041,.077,1) if night else (.63,.66,.60,1)
    for d,p in lights:d.energy=p if night else 0
    cam.location=at(position);aim(cam,target);cam.data.lens=lens
    scene.render.filepath=str((Path("/tmp/inland-draft") if draft else Path("/tmp/inland-sea-raw"))/(name+".png"))
    if not draft and name=="day":
        bpy.context.preferences.filepaths.save_version=0
        bpy.ops.wm.save_as_mainfile(filepath=str(R/"inland-sea.blend"))
    scene.render.resolution_percentage=42 if draft else 100
    scene.cycles.samples=3 if draft else (28 if name in ("day","night","person") else 18)
    bpy.ops.render.render(write_still=True)
