# Shared construction vocabulary, adapted from Crossfield; geometry is authored by build_model.py.
def collision_group():
    node=parent
    while node:
        if node==LOT:return 'lot'
        node=node.parent
    return 'structure'

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
    if collision:COLL.append({'center':[x,y,z],'size':[w,d,h],'group':collision_group(),'source':name,'attachment':parent.name})
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

def lot_prop(name):
    node=bpy.data.objects.new(name,None);bpy.context.collection.objects.link(node);node.parent=LOT
    PROPS.append(node);return node
