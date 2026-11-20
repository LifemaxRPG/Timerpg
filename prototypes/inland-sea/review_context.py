"""Muted urban context and six scale figures, exclusively in the desktop review."""
import random
def finish(name,color):
    m=bpy.data.materials.new(name);m.diffuse_color=(*color,1)
    return m
haze=finish("Distant masonry",(.36,.435,.455))
haze2=finish("Distant roof",(.31,.385,.41))
clothes=finish("Scale figure coat",(.18,.235,.24))
warm=finish("Scale figure ochre",(.38,.24,.12))
skin_color=finish("Scale figure head",(.53,.36,.24))
def cube(name,pos,size,mat):
    bpy.ops.mesh.primitive_cube_add(size=1,location=at(pos))
    obj=bpy.context.object;obj.name=name;obj.parent=stage;obj.dimensions=size;obj.data.materials.append(mat)
    return obj
rng=random.Random(1936)
# Four streets of low-contrast massing give the horizon a scale reference.
facade=bpy.data.images.new("Study skyline window repeat",width=16,height=16)
pix=[]
for py in range(16):
    for px in range(16):
        lit=2<=px<=6 and 3<=py<=11 or 9<=px<=13 and 3<=py<=11
        color=(.25,.32,.36) if lit else ((.42,.46,.45) if py!=14 else (.49,.5,.47))
        pix.extend((*color,1))
facade.pixels[:]=pix
facade.pack()
haze.use_nodes=True
nodes=haze.node_tree.nodes
scale=nodes.new("ShaderNodeVectorMath");scale.operation='SCALE';scale.inputs['Scale'].default_value=.22
# World position keeps the windows the same size on every building.
geom=nodes.new("ShaderNodeNewGeometry")
haze.node_tree.links.new(geom.outputs["Position"],scale.inputs[0])
tex=nodes.new("ShaderNodeTexImage");tex.image=facade;tex.projection='BOX';tex.interpolation='Closest'
haze.node_tree.links.new(scale.outputs[0],tex.inputs[0])
haze.node_tree.links.new(tex.outputs[0],nodes.get("Principled BSDF").inputs["Base Color"])
for row in range(4):
    along=-420
    while along<420:
        w=rng.uniform(15,33);d=rng.uniform(13,25);h=rng.uniform(16,36)
        far=rng.uniform(365,395)
        x,y=[(along,far),(far,-along),(-along,-far),(-far,along)][row]
        tower=rng.random()<.16
        if tower:h+=rng.uniform(19,38)
        cube("Street facade",(x,y,h/2),(w,d,h),haze)
        cube("Cornice",(x,y,h-.4),(w+.64,d+.64,.85),haze2)
        if tower:
            cube("Setback",(x,y,h+3.5),(w*.74,d*.78,7),haze)
            cube("Lantern",(x,y,h+9),(w*.45,d*.50,4),haze2)
        along+=w+rng.uniform(1.2,6)
canopy=finish("Distant plane trees",(.18,.255,.224))
for i in range(62):
    a=i*2*pi/62
    x,y=(117+rng.uniform(-3,4))*cos(a),(92+rng.uniform(-4,4))*sin(a)
    h=rng.uniform(5.2,8.2)
    cube("Boulevard trunk",(x,y,h*.3),(.48,.48,h*.6),haze2)
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=1,radius=1,location=at((x,y,h*.75)))
    ob=bpy.context.object;ob.name="Boulevard canopy";ob.parent=stage;ob.scale=(3.4,3.8,h*.38);ob.data.materials.append(canopy)
for x,y,z,size,mat in [(3,-42,.16,1.74,clothes),(4,-42.5,.16,1.61,warm),(-24,-24,1.89,1.80,clothes),(-24.7,-23.2,1.94,1.55,warm),(47,23,.16,1.7,clothes),(48,23,.16,1.65,warm)]:
    for dx in (-.10,.10):cube("Scale figure leg",(x+dx,y,z+size*.24),(.14,.19,size*.48),clothes)
    cube("Scale figure shoulder",(x,y,z+size*.65),(.42,.25,size*.36),mat)
    bpy.ops.mesh.primitive_ico_sphere_add(subdivisions=1,radius=.15,location=at((x,y,z+size*.90)))
    ob=bpy.context.object;ob.parent=stage;ob.name="Scale figure head";ob.scale=(.83,.87,1.14);ob.data.materials.append(skin_color)
