"""Inspect the published GLB, including its embedded pixels and physical UV scale."""
from glb_reader import R,load,acc,matrices
import io,json
import numpy as np
from PIL import Image

info=json.loads((R/'model-info.json').read_text())
g,b=load(R/'copperwake.glb')
assert len(g['materials'])<=12 and not g.get('cameras')
assert not any('uri' in buffer for buffer in g['buffers'])
sizes=[]
for im in g['images']:
    v=g['bufferViews'][im['bufferView']]
    payload=b[v.get('byteOffset',0):v.get('byteOffset',0)+v['byteLength']]
    image=Image.open(io.BytesIO(payload))
    assert image.format=='PNG'
    pix=np.array(image.convert('RGBA'))
    assert all(np.array_equal(pix[::2,::2],pix[y::2,x::2]) for x,y in [(1,0),(0,1),(1,1)])
    sizes.append(image.size)
for s in g['samplers']:
    assert s['magFilter']==9728 and s['minFilter'] in [9728,9984]
    assert s.get('wrapS',10497)==s.get('wrapT',10497)==10497
world=matrices(g)
node_indices={n['name']:i for i,n in enumerate(g['nodes'])}
uv_error=0
boat_hulls=[]
parts={} 
for i,n in enumerate(g['nodes']):
    if 'mesh' not in n:continue
    arrays=[]
    for prim in g['meshes'][n['mesh']]['primitives']:
        verts=acc(g,b,prim['attributes']['POSITION'])
        verts=verts@world[i][:3,:3].T+world[i][:3,3]
        ids=acc(g,b,prim['indices']).reshape(-1,3)
        tri=verts[ids]
        mat=g['materials'][prim['material']]
        if n['name'].startswith(('lifeboat_','Molded lifeboat')) and mat['name']=='detail_copper':
            boat_hulls.append(tri)
        tex=mat['pbrMetallicRoughness']['baseColorTexture']['index']
        uv=acc(g,b,prim['attributes']['TEXCOORD_0'])*np.array(sizes[g['textures'][tex]['source']])*.0388
        for k,j in [(0,1),(1,2),(2,0)]:
            e=np.linalg.norm(tri[:,k]-tri[:,j],axis=-1)-np.linalg.norm(uv[ids[:,k]]-uv[ids[:,j]],axis=-1)
            uv_error=max(uv_error,float(np.max(np.abs(e))))
        arrays.append(tri)
    if arrays:parts[n['name']]=np.concatenate(arrays)
tri=np.concatenate(list(parts.values()))
crosses=np.cross(tri[:,1]-tri[:,0],tri[:,2]-tri[:,0]);areas=np.linalg.norm(crosses,axis=1)/2
assert areas.min()>1e-11 and np.isfinite(tri).all(), (areas.min(),(areas<=1e-11).sum())
assert len(tri)==info["triangles_visible"]<=30000,(len(tri),info["triangles_visible"])
assert uv_error<.002,uv_error
assert tri[:,:,1].min()>=-.0001
dimensions=info["dimensions_m"]
extents=tri.max(axis=(0,1))-tri.min(axis=(0,1))
assert np.allclose(extents,[dimensions["beam"],dimensions["keel_to_funnel"],dimensions["length_overall"]],atol=.0001)
assert len(g["materials"])<=12 and len(g["images"])>=12

def horizontal_support(height, x, z):
    """Test a point on a particular level, independent of decks overhead."""
    planar=tri[np.max(np.abs(tri[:,:,1]-height),axis=1)<.0001]
    poly=planar[:,:,[0,2]]
    edge=np.roll(poly,-1,axis=1)-poly
    offset=np.array([x,z])-poly
    cross=edge[:,:,0]*offset[:,:,1]-edge[:,:,1]*offset[:,:,0]
    return ((cross.min(axis=1)>=-1e-6)|(cross.max(axis=1)<=1e-6)).any()

for datum in info["doors"]:
    idx=node_indices[datum["node"]]
    assert "mesh" not in g["nodes"][idx]
    assert np.allclose(world[idx][:3,3],datum["position_gltf_m"],atol=1e-5)

# Walk along both exterior promenades at the gangway and lifeboat levels.
for x in (-17.6,17.6):
    for y in (-55,-29,0,45,95):
        assert horizontal_support(21.26,x,-y)
    # The boarding opening is clear across the outboard railing line to deck height.
    outer=tri[((tri[:,:,0]-np.sign(x)*18.8565).__abs__().min(axis=1)<.16)&
              (tri[:,:,1].max(axis=1)>21.4)&(tri[:,:,1].min(axis=1)<22.5)]
    assert not ((outer[:,:,2].min(axis=1)<30.20)&(outer[:,:,2].max(axis=1)>27.80)).any()

assert len(boat_hulls)==16
minimum_lifeboat_clearance=min(surface[:,:,1].min()-21.26 for surface in boat_hulls)
assert minimum_lifeboat_clearance>2.75

treads=0
for (x,y0,lower),(_,y1,upper) in info["stairs_blender_z_up_m"]:
    count=round((upper-lower)/.19)
    for i in range(count):
        height=lower+(i+1)*(upper-lower)/count
        y=y0+(i+.5)*(y1-y0)/count
        assert horizontal_support(height,x,-y),(x,y,height)
        treads+=1

for name,x,y,floor in [("observation_lounge",0,48,41.969),("sample_stateroom",8.8,-120.5,21.385)]:
    assert horizontal_support(floor,x,-y),name

# Check actual openings clear through the two wall planes at walking height.
room_doors=[
    (0,40.65,41.969,2.1),(0,55.35,41.969,2.1),
    (8.8,-123.55,21.385,.42),(8.8,-117.45,21.385,.42)
]
for x,y,floor,half_width in room_doors:
    wall=tri[(np.abs(tri[:,:,2]+y).min(axis=1)<.20)&
              (np.ptp(tri[:,:,1],axis=1)>.04)&(np.ptp(tri[:,:,0],axis=1)>.04)]
    blocked=(wall[:,:,0].min(axis=1)<x+half_width)&(wall[:,:,0].max(axis=1)>x-half_width)&(
             wall[:,:,1].min(axis=1)<floor+2.1)&(wall[:,:,1].max(axis=1)>floor+.12)
    assert not blocked.any(),(x,y)

for mat in g["materials"]:
    pbr=mat["pbrMetallicRoughness"]
    assert pbr.get("roughnessFactor",1)>=.9 and pbr.get("metallicFactor",1)<.01

result={
    "triangles":len(tri),"materials":len(g["materials"]),"pixel_grid_images":len(g["images"]),
    "max_physical_uv_edge_error_m":round(uv_error,7),"stair_treads_checked":treads,
    "checked_room_floors":2,"clear_room_portals":len(room_doors),"boarding_openings":2,"named_anchors":len(info["doors"]),
    "lifeboat_hulls":len(boat_hulls),"clearance_beneath_lifeboats_m":round(float(minimum_lifeboat_clearance),2)
}
(R/"validation.json").write_text(json.dumps(result,indent=2)+"\n")
print(result)
