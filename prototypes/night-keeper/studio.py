"""A graded sky and a few metre-scale silhouettes for the Blender presentation only."""
def preview_mat(name,rgb):
    m=bpy.data.materials.new(name);m.diffuse_color=(*rgb,1);return m
people=[preview_mat('Review indigo',(.12,.17,.22)),preview_mat('Review russet',(.36,.19,.11)),preview_mat('Review ochre',(.65,.48,.27))]
def propblock(name,pos,dims,m):
    bpy.ops.mesh.primitive_cube_add(size=1,location=at(pos))
    ob=bpy.context.object;ob.parent=stage;ob.name=name;ob.dimensions=dims;ob.data.materials.append(m)
for x,y,z,size,coatcol in [(10.4,-15.9,.12,1.70,1),(11.6,-16.9,.12,1.34,0),(-4.6,-14,.12,1.75,0),(-3.8,-14,.12,1.57,1)]:
    for dx in [-.095,.095]:propblock('Scale figure', (x+dx,y,z+size*.235),(.14,.16,size*.47),people[0])
    propblock('Coat',(x,y,z+size*.61),(.39,.24,size*.36),people[coatcol])
    propblock('Head',(x,y,z+size*.88),(.23,.22,size*.23),people[2])
nodes=world.node_tree.nodes;links=world.node_tree.links
mix=nodes.new('ShaderNodeMixShader');lightpath=nodes.new('ShaderNodeLightPath')
links.new(lightpath.outputs['Is Camera Ray'],mix.inputs[0]);links.new(bg.outputs[0],mix.inputs[1])
skybg=nodes.new('ShaderNodeBackground');links.new(skybg.outputs[0],mix.inputs[2]);links.new(mix.outputs[0],nodes.get('World Output').inputs[0])
direction=nodes.new('ShaderNodeTexCoord');sep=nodes.new('ShaderNodeSeparateXYZ');links.new(direction.outputs['Generated'],sep.inputs[0])
absolute=nodes.new('ShaderNodeMath');absolute.operation='ABSOLUTE';links.new(sep.outputs['Z'],absolute.inputs[0])
ramp=nodes.new('ShaderNodeValToRGB');ramp.color_ramp.elements[1].position=.55;links.new(absolute.outputs[0],ramp.inputs[0]);links.new(ramp.outputs[0],skybg.inputs[0])
m.use_nodes=True
n=m.node_tree.nodes;l=m.node_tree.links;dif=n.get('Principled BSDF');dif.inputs['Base Color'].default_value=(.24,.26,.23,1)
dist=n.new('ShaderNodeCameraData');mapper=n.new('ShaderNodeMapRange')
mapper.inputs['From Min'].default_value=65;mapper.inputs['From Max'].default_value=400
l.new(dist.outputs['View Distance'],mapper.inputs[0]);groundmix=n.new('ShaderNodeMixShader');fog=n.new('ShaderNodeEmission')
l.new(mapper.outputs[0],groundmix.inputs[0]);l.new(dif.outputs[0],groundmix.inputs[1]);l.new(fog.outputs[0],groundmix.inputs[2]);l.new(groundmix.outputs[0],n.get('Material Output').inputs[0])
def sky_colors(night):
    horizon=(.085,.12,.18,1) if night else (.70,.75,.72,1)
    ramp.color_ramp.elements[0].color=horizon
    ramp.color_ramp.elements[1].color=(.021,.037,.091,1) if night else (.31,.52,.69,1)
    fog.inputs[0].default_value=horizon
