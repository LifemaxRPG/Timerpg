"""Survey coordinates and playable surfaces for the revised Thimble Trails parcel."""
from math import hypot

# Each point is (x, north, finished turf height, half-width) in metres.
# The first and last points describe the tee and the cup.
ROUTES = (
    ("Long crescent", ((-5.5,8.1,.12,1.08),(-9.0,6.9,.12,1.38),(-13.5,6.8,.12,1.00),(-18.0,8.8,.12,1.42))),
    ("J-hook", ((-22.0,8.4,.13,1.06),(-25.5,10.7,.13,1.18),(-26.6,13.7,.13,.91),(-24.9,16.8,.13,1.38))),
    ("windmill", ((-20.4,17.8,.13,1.16),(-17.4,17.0,.13,1.38),(-13.8,15.9,.13,1.30),(-9.1,13.2,.13,1.54))),
    ("S-curve", ((-3.8,10.7,.13,1.12),(-.8,10.3,.13,1.07),(1.8,12.5,.13,.93),(2.0,15.5,.13,1.11),(5.7,16.8,.13,1.30))),
    ("lighthouse", ((10.0,9.6,.13,1.08),(14.2,8.8,.13,1.38),(18.3,10.1,.13,1.98),(23.2,12.4,.13,1.15))),
    ("Banked turn", ((25.3,17.2,.18,1.12),(25.2,20.2,.28,1.18),(23.1,22.8,.34,1.28),(19.5,24.1,.22,1.56))),
    ("volcano", ((18.6,28.6,.14,1.19),(21.3,28.1,.22,1.10),(25.1,30.2,.26,1.31),(26.0,34.7,.18,1.22))),
    ("Raised headland", ((24.8,39.5,.44,1.12),(24.5,42.6,.67,1.24),(21.6,44.9,.95,1.11),(16.9,45.8,.95,1.38))),
    ("pond bridge", ((13.0,47.8,.19,1.16),(10.3,45.8,.20,1.27),(6.5,44.2,.18,1.11),(1.8,44.1,.15,1.32))),
    ("Figure eight", ((-4.7,49.2,.40,1.10),(-7.8,50.3,.64,1.86),(-10.2,51.7,.89,.83),(-12.2,53.4,1.14,1.96),(-15.3,54.0,1.38,1.13))),
    ("Downhill", ((-20.1,54.9,1.66,1.42),(-22.9,52.3,1.47,1.19),(-24.6,48.6,.91,1.31),(-24.3,43.4,.20,1.41))),
    ("covered bridge", ((-25.0,38.5,.21,1.12),(-24.5,35.6,.22,1.38),(-23.0,32.5,.17,1.20),(-21.2,29.1,.12,1.23))),
    ("Split green", ((-17.3,28.8,.14,1.02),(-15.8,31.0,.22,1.73),(-13.5,33.3,.38,1.94),(-10.3,36.6,.38,1.50))),
    ("Pipe drop", ((-12.0,42.5,.94,1.20),(-10.0,41.4,.94,1.31),(-8.7,40.8,.94,1.14),(-6.4,39.3,.29,1.10),(-4.6,38.2,.25,1.11),(-2.4,37.7,.25,1.25))),
    ("Blue narrows", ((1.2,33.4,.14,1.24),(3.3,30.9,.13,.86),(3.9,27.5,.13,1.17),(6.6,24.5,.14,1.30))),
    ("castle", ((-1.3,23.0,.14,1.15),(-3.5,23.0,.14,1.46),(-6.6,24.4,.14,1.71),(-9.7,25.7,.14,1.45))),
    ("Switchback", ((-8.0,29.7,.15,1.24),(-5.1,30.3,.25,1.09),(-3.3,32.0,.31,1.09),(-4.9,34.1,.40,1.36))),
    ("Giant ball", ((9.6,38.1,.20,1.15),(10.2,34.5,.20,1.45),(11.9,30.8,.18,1.78),(12.4,26.8,.14,1.86),(12.6,22.2,.13,1.47))),
)

BLUE_HOLES = frozenset((9, 15))
ROCK_GREENS = frozenset((8, 10, 11, 13, 14))
SPLIT_ISLAND = ((-16.0,31.6),(-15.7,31.0),(-14.6,31.6),(-13.0,33.66),(-13.1,34.18),(-13.7,34.0))
# A dogleg creek, with a rock drop at its upstream end and an oval catch pond.
CREEK = ((-15.4,47.4,.24,.63),(-12.1,46.1,.15,.83),(-8.1,45.4,.145,.72),(-5.1,44.0,.13,.8),
         (-1.4,43.6,.11,.70),(.1,46.9,.097,.78),(2.7,49.9,.09,.82),(6.0,51.5,.085,1.22))
POND = (9.1,54.35,4.95,3.38)
PAD = (-13.5,-2.85)
BOUNDS = (-30,30,-9,61)


def heading(a,b):
    dx,dy=b[0]-a[0],b[1]-a[1]
    d=hypot(dx,dy)
    return dx/d,dy/d


def outline(route, extra=0, caps=(.80,1.05)):
    """Bank edges with bevelled caps and gentle mitres at the waypoint turns."""
    left,right=[],[]
    for i,(x,y,z,w) in enumerate(route):
        before=heading(route[max(0,i-1)],route[max(1,i)])
        after=heading(route[min(i,len(route)-2)],route[min(i+1,len(route)-1)])
        tx,ty=before[0]+after[0],before[1]+after[1]
        scale=hypot(tx,ty)
        tx,ty=tx/scale,ty/scale
        nx,ny=-ty,tx
        w=(w+extra)/max(.80,tx*after[0]+ty*after[1])
        if i==0 or i==len(route)-1:
            ext=-caps[0]-extra if i==0 else caps[1]+extra
            for t,factor in ((ext,.66),(0,1)) if i==0 else ((0,1),(ext,.66)):
                left.append((x+t*tx+w*factor*nx,y+t*ty+w*factor*ny,z))
                right.append((x+t*tx-w*factor*nx,y+t*ty-w*factor*ny,z))
        else:
            left.append((x+w*nx,y+w*ny,z))
            right.append((x-w*nx,y-w*ny,z))
    return right+left[::-1]


def track_sections(hole, route):
    if hole != 14:
        return ((route,(.80,1.05)),)
    return ((route[:3],(.80,0.0)),(route[3:],(0.0,1.05)))


def surface_height(route, point, bank=False):
    """Interpolate longitudinal grade and optional crossfall from the nearest segment."""
    nearest=None
    for a,b in zip(route,route[1:]):
        dx,dy=b[0]-a[0],b[1]-a[1]
        x,y=point[0]-a[0],point[1]-a[1]
        k=max(0,min(1,(x*dx+y*dy)/(dx*dx+dy*dy)))
        d=hypot(x-k*dx,y-k*dy)
        cross=(dx*y-dy*x)/hypot(dx,dy)
        crown=(.10*cross if bank else 0)
        if nearest is None or d<nearest[0]:
            nearest=(d,a[2]+k*(b[2]-a[2])+crown)
    return nearest[1]
