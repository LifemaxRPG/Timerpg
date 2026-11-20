"""Low-facet historic window profiles in art-pixel coordinates."""
import math
def arch(w,h,b=0,pointed=True,steps=3):
 half=w/2
 if pointed:
  spring=b+h-w*math.sqrt(3)/2
  curve=[(-half+w*math.cos(i*math.pi/(3*steps)),spring+w*math.sin(i*math.pi/(3*steps))) for i in range(steps+1)]
  curve += [(half+w*math.cos(2*math.pi/3+i*math.pi/(3*steps)),spring+w*math.sin(2*math.pi/3+i*math.pi/(3*steps))) for i in range(1,steps+1)]
 else:
  spring=b+h-half;curve=[(half*math.cos(i*math.pi/(steps*2)),spring+half*math.sin(i*math.pi/(steps*2))) for i in range(steps*2+1)]
 return [(-half,b),(half,b)]+curve,curve,spring
