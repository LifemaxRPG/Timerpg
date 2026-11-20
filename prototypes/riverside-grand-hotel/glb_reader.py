"""Small glTF binary reader and geometric aperture helpers shared by local validation."""
from pathlib import Path
import json,struct,io,math
import numpy as np
from PIL import Image
R=Path(__file__).resolve().parent;U=.0776
DT={5126:'<f4',5125:'<u4',5123:'<u2',5121:'u1'};W={'SCALAR':1,'VEC2':2,'VEC3':3,'VEC4':4}
def load(path):
 raw=path.read_bytes();assert struct.unpack_from('<4sII',raw)==(b'glTF',2,len(raw));n,t=struct.unpack_from('<II',raw,12);assert t==0x4e4f534a;g=json.loads(raw[20:20+n]);binary=raw[28+n:];return g,binary

def acc(g,b,i):
 a=g['accessors'][i];v=g['bufferViews'][a['bufferView']];offset=v.get('byteOffset',0)+a.get('byteOffset',0);dt=np.dtype(DT[a['componentType']]);w=W[a['type']]
 out=np.ndarray((a['count'],w),dtype=dt,buffer=b,offset=offset,strides=(v.get('byteStride',dt.itemsize*w),dt.itemsize)).copy()
 if a.get('normalized'):out=out/np.iinfo(dt).max
 return out

def matrices(g):
 result={}
 def visit(i,parent):
  n=g['nodes'][i];m=np.eye(4)
  if 'matrix' in n:m=np.array(n['matrix']).reshape(4,4).T
  else:
   x,y,z,w=n.get('rotation',[0,0,0,1]);m[:3,:3]=np.array([[1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w)],[2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w)],[2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y)]])@np.diag(n.get('scale',[1,1,1]));m[:3,3]=n.get('translation',[0,0,0])
  result[i]=parent@m
  for c in n.get('children',[]):visit(c,result[i])
 for i in g['scenes'][g.get('scene',0)]['nodes']:visit(i,np.eye(4))
 return result

def cross(a,b):return float(a[0]*b[1]-a[1]*b[0])
def clipped_area(a,b):
 poly=[p.copy() for p in a];sign=1 if cross(b[1]-b[0],b[2]-b[0])>0 else -1
 for i in range(len(b)):
  u,v=b[i],b[(i+1)%len(b)];edge=v-u;out=[]
  for j,p in enumerate(poly):
   q=poly[(j+1)%len(poly)];fp=sign*cross(edge,p-u);fq=sign*cross(edge,q-u);pi=fp>=-1e-8;qi=fq>=-1e-8
   if pi:out.append(p)
   if pi!=qi and abs(fp-fq)>1e-12:out.append(p+(q-p)*fp/(fp-fq))
  poly=out
  if len(poly)<3:return 0
 return abs(sum(cross(poly[i],poly[(i+1)%len(poly)]) for i in range(len(poly))))/2
