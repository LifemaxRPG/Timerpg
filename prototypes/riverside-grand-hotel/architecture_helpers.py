# Architectural primitives reused from our original church construction.
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
 m=m or wall;rects=[];coords=sorted(set([a,b]+[v for h in holes for v in h[:2] if a<v<b]))
 for aa,bb in zip(coords,coords[1:]):
  cuts=sorted(set([z0,z1]+[v for h in holes if h[0]<(aa+bb)/2<h[1] for v in h[2:] if z0<v<z1]))
  for lo,hi in zip(cuts,cuts[1:]):
   if not any(h[0]<(aa+bb)/2<h[1] and h[2]<(lo+hi)/2<h[3] for h in holes):rects.append((aa,bb,lo,hi))
 def subinterval(lo,hi,covers):
  out=[];cursor=lo
  for x,y in sorted(covers):
   if y<=cursor or x>=hi:continue
   if x>cursor:out.append((cursor,min(x,hi)))
   cursor=max(cursor,y)
  if cursor<hi:out.append((cursor,hi))
  return out
 def face(label,pts,material):return mesh(label,[local_point(u,ff,z,axis) for u,ff,z in pts],[(0,1,2,3)],material)
 # Only the exterior skins and genuinely exposed aperture/edge returns survive.
 # Adjacent masonry cells share no buried faces, without simplifying any opening.
 for aa,bb,lo,hi in rects:
  for normal in [-1,1]:
   ff=f+normal*depth/2;pts=[(aa,ff,lo),(bb,ff,lo),(bb,ff,hi),(aa,ff,hi)]
   if (axis=='x' and normal>0) or (axis=='y' and normal<0):pts.reverse()
   face(n,pts,plaster if inside==normal else m)
  for u,edge in [(aa,0),(bb,1)]:
   cov=[(r[2],r[3]) for r in rects if abs(r[1 if edge==0 else 0]-u)<1e-8]
   for l,h in subinterval(lo,hi,cov):face(n+' jamb',[(u,f-depth/2,l),(u,f+depth/2,l),(u,f+depth/2,h),(u,f-depth/2,h)],m)
  for z,edge in [(lo,0),(hi,1)]:
   cov=[(r[0],r[1]) for r in rects if abs(r[3 if edge==0 else 2]-z)<1e-8]
   for l,h in subinterval(aa,bb,cov):face(n+' return',[(l,f-depth/2,z),(h,f-depth/2,z),(h,f+depth/2,z),(l,f+depth/2,z)],m)
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
 # Sills are continuous facade stone courses, not buried per-window boxes.
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

