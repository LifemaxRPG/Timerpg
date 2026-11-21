"""Exterior fittings and markings for the modern Copperwake."""
from math import pi,cos,sin
from pixel_font import FONT
from revision_common import block, collider, marker
from sculpture_tools import T, beam, box, group, mesh, oval, plaque


def ink(parent, label, text, center, along, out, pixel, material, raised=.053, surface=None):
    """Build raised pixel letters from horizontal runs, in a local hull face."""
    cx,cy,cz=center
    ax,ay=along
    ox,oy=out
    count=(len(text)*6-1)*pixel
    for j,ch in enumerate(text):
        for r,row in enumerate(FONT.get(ch,())):
            first=-1
            for i,b in enumerate(row+"0"):
                if b=="1" and first<0:
                    first=i
                if b=="0" and first>=0:
                    lo=(j*6+first)*pixel-count/2
                    hi=(j*6+i)*pixel-count/2
                    z=cz+(3-r)*pixel
                    corners=[(cx+ax*u+ox*v,cy+ay*u+oy*v,z+dz)
                             for v in (0,raised) for dz in (0,pixel) for u in (lo,hi)]
                    if surface:
                        corners=[(x,surface(abs(x),h)+.01+(i//4)*raised,h)
                                 for i,(x,y,h) in enumerate(corners)]
                    fs=[(0,2,3,1),(4,5,7,6),(0,1,5,4),(2,6,7,3),(0,4,6,2),(1,3,7,5)]
                    if ax*oy-ay*ox<0:
                        fs=[tuple(reversed(f)) for f in fs]
                    mesh(label,parent,corners,fs,material)
                    first=-1


def fittings(ctx):
    g=ctx["hardware"]
    h=ctx["hull"]
    lot=ctx["lot"]
    public=ctx["public"]
    hull_ring=ctx["hull_ring"]
    z=ctx["top"]+.11
    navy,white,teal,copper,rubber,brass,glow,signal,glass=(ctx[i] for i in
         ("navy","white","teal","copper","rubber","brass","glow","signal","glass"))
    box_at=block

    for side in (-1,1):
        # The forward lettering follows a straight hull plate between two stations.
        panel_x=side*13.15
        direction=(side*.306,.952)
        normal=(side*.952,-.306)
        if side<0:
            direction=tuple(-u for u in direction)
        ink(h,"Raised bow lettering","COPPERWAKE",(panel_x,-119.85,17.7),
            direction,normal,.2328,white,.14)
        oval("Dark hawse pocket",h,[(side*11.6,-124.2,11.83,.69,1.1),(side*11.94,-124.2,13.59,.61,.99)],rubber,n=8)
        beam("Anchor shank",h,(side*12.30,-122.8,12.14),(side*12.24,-123.1,14.31),(.16,.19),(.14,.18),navy,n=6)
        for dy in (-.65,.65):
            beam("Anchor fluke",h,(side*12.34,-123.02,12.2),(side*12.2,-123.02+dy,12.78),
                 (.17,.16),(.12,.29),navy,n=6)
        for y,xx in [(-120,13.12),(135,17.43)]:
            for k in range(10):
                box_at("White draft mark",h,(side*xx,y,9.2+k*.42),(.13,.68 if k%2==0 else .41,.078),white)
        for dx,dz in ((-.64,0),(.64,0),(0,-.64),(0,.64)):
            box_at("Thruster marking",h,(side*14.60,-114+dx,10.72+dz),(.10,.29,.12),white)
        box_at("Thruster crosshair",h,(side*14.66,-114,10.72),(.12,1.27,.10),white)
        box_at("Thruster crosshair",h,(side*14.66,-114,10.22),(.12,.10,1.1),white)
        for y in (-133.5,-128.5,134,140):
            x=side*(3.4 if y<0 else 8.2)
            box_at("Mooring winch foundation",g,(x,y,21.26),(2.53,2.1,.32),rubber)
            beam("Winch drum",g,(x-1.0,y,22.08),(x+1.0,y,22.08),(.55,.55),(.55,.55),teal,n=8)
            for offset in (-.9,.9):
                beam("Winch flange",g,(x+offset-.1,y,22.08),(x+offset+.1,y,22.08),(.69,.69),(.69,.69),rubber,n=8)
            box_at("Chain pipe",g,(x,y-1.47,21.26),(.67,.56,.29),brass)
        for y in range(-89,113,20):
            x=side*18.35
            beam("Life ring station",g,(x,y,21.26),(x,y,22.63),(.10,.10),(.10,.10),white,n=4)
            points=[(x,y+r*cos(i*pi/4),22.44+r*sin(i*pi/4))
                    for r in (.25,.405) for i in range(8)]
            mesh("Painted life ring",g,points,[(i,8+i,8+(i+1)%8,(i+1)%8) for i in range(8)],
                 [copper if i%2 else white for i in range(8)])
        for y in (-86,80,101):
            box_at("Promenade bench",g,(side*17.45,y,21.26),(.73,2.3,.51),teal)
    # Fit each glyph to the changing stern section before offsetting it.
    def stern_surface(x,z):
        for lower,upper in zip(ctx["hull_levels"],ctx["hull_levels"][1:]):
            if lower[0]<=z<=upper[0]:
                t=(z-lower[0])/(upper[0]-lower[0])
                points=[tuple(h+(k-h)*t for h,k in zip(lo,hi))
                        for lo,hi in zip(hull_ring(*lower)[9:13],hull_ring(*upper)[9:13])]
                for a,b in zip(points,points[1:]):
                    if b[0]<=x<=a[0]:
                        return a[1]+(b[1]-a[1])*(x-a[0])/(b[0]-a[0])
                return points[-1][1]
        raise ValueError(z)
    ink(h,"Raised stern name","COPPERWAKE",(0,0,17.3),(-1,0),(0,1),.2328,white,.075,stern_surface)
    ink(h,"Stern home port","PORT MERIDIAN",(0,0,15.63),(-1,0),(0,1),.1164,white,.075,stern_surface)
    # Bulbous extension remains below the declared 9.35 m waterline.
    oval("Submerged bulb",h,[(0,-135.5,3.2,.9,5.6),(0,-138.2,5.7,2.05,10.0),
         (0,-137.1,8.0,1.61,8.7)],navy,n=12)
    beam("House flag staff",g,(0,149,21.26),(0,149,27.8),(.13,.13),(.078,.078),brass,n=6)
    mesh("Copperwake house flag",g,[(0,149,25.65),(3.25,149.2,25.85),(3.1,149.5,27.55),(0,149,27.42)],
         [(0,1,2,3)],copper)
    mesh("House flag diagonal",g,[(.27,148.98,25.87),(2.86,149.17,26.99),(2.85,149.19,27.28),
         (.27,148.99,26.19)],[(0,1,2,3)],white)

    # Foredeck running lanes, miniature golf and shuffleboard use thin painted meshes.
    for x in (-12.05,-11.65,11.65,12.05):
        box_at("Jogging lane paint",public,(x,-50,z+.008),(.078,36,.009),white)
    for y in (-68,-32):
        for x in (-10,10):
            beam("Lane return paint",public,(x,y,z+.03),(x+(-2.05 if x<0 else 2.05),y+2,z+.03),
                 (.039,.008),(.039,.008),white,n=4)
    for x,y in ((-7,-20),(7,-18)):
        oval("Mini golf green",public,[(x,y,z,3.12,4.4),(x,y,z+.05,3.12,4.4)],teal,n=10)
        beam("Golf pin",public,(x,y+.9,z+.03),(x,y+.9,z+1.02),(.033,.033),(.027,.027),brass,n=5)
        mesh("Golf flag",public,[(x,y+.9,z+.69),(x+.68,y+.91,z+.86),(x,y+.91,z+1.0)],[(0,1,2)],copper)
    for x in (-9,9):
        box_at("Shuffleboard lane",public,(x,33,z),(2.7,8.4,.058),teal)
        for dy in (-2.2,0,2.2):
            box_at("Shuffleboard marking",public,(x,33+dy,z+.058),(2.2,.078,.009),white)
    for s in (-1,1):
        for y in (-66,-56):
            box_at("Basketball wind screen",g,(s*6.2,y,z),(.095,8.2,3.8),glass)
            beam("Basketball cage post",g,(s*6.25,y-4.0,z),(s*6.25,y-4.0,z+4.12),
                 (.10,.10),(.10,.10),rubber,n=4)

    ctx["pool"]("kids_splash_pool",-3.1,22,z,4.5,4.6)
    ctx["pool"]("stern_spa",0,137,21.26,5.8,6.2)
    beam("Kids slide",public,(-3.1,26.2,z+1.75),(-3.1,23,z+.63),
         (.60,.08),(.60,.08),copper,n=4)
    box_at("Slide steps",public,(-3.1,26.4,z),(1.0,.68,1.48),white)
    for y in (11,16):
        for s in (-1,1):
            box_at("Pool windbreak",g,(s*12.98,y,z),(.12,4.96,1.5),glass)
    box_at("Towel stand",public,(-10.2,19.8,z),(.87,.56,1.47),white)
    for dz in (.8,1.04,1.28):
        box_at("Folded towels",public,(-10.2,19.8,z+dz),(.69,.44,.11),teal)
    for name,y,zbar in (("Pool bar",17,z),("Stern terrace bar",79,z-3.414)):
        box_at(name,public,(0,y,zbar),(7.5,1.53,1.05),teal)
        box_at("Bar counter",public,(0,y,zbar+1),(7.8,1.80,.16),white)
        box_at("Bar canopy",public,(0,y,zbar+2.74),(8.4,3.35,.18),copper)
        for x in (-3.85,3.85):
            box_at("Canopy column",public,(x,y-.38,zbar),(.14,.18,2.82),white)
        for x in (-2.7,-1.35,0,1.35,2.7):
            oval("Fixed bar stool",public,[(x,y+1.48,zbar,.09,.09),(x,y+1.48,zbar+.69,.15,.15),
                 (x,y+1.48,zbar+.82,.34,.34)],brass,n=6)
    for s in (-1,1):
        for y in (35,67):
            x=s*10.8
            palm=group(f"tree_palm_{s}_{y}",lot)
            oval("Planter",palm,[(x,y,z,.78,.78),(x,y,z+.64,.89,.88)],white,n=8)
            beam("Palm stem",palm,(x,y,z+.62),(x+.36,y+.23,z+4.03),(.16,.16),(.10,.10),brass,n=6)
            for i in range(7):
                a=2*pi*i/7
                p=(x+.35,y+.23,z+3.97)
                m=(p[0]+1.52*cos(a),p[1]+1.52*sin(a),z+4.48)
                t=(p[0]+2.6*cos(a),p[1]+2.6*sin(a),z+3.74)
                mesh("Palm frond",palm,[p,(m[0]+.39*sin(a),m[1]-.39*cos(a),m[2]),t,
                     (m[0]-.39*sin(a),m[1]+.39*cos(a),m[2])],[(0,1,2,3)],teal)

    movie_y=-27
    x0,screen_w=0,256*T
    screen_h=128*T
    box_at("Outdoor cinema frame",public,(x0,movie_y,z+1.3),(screen_w+.42,.64,screen_h+.42),rubber)
    for x in (-4.6,4.6):
        box_at("Cinema bracket",g,(x,movie_y,z),(.20,.35,2),rubber)
    quad=[(-screen_w/2,movie_y+.33,z+1.51),(screen_w/2,movie_y+.33,z+1.51),
          (screen_w/2,movie_y+.33,z+1.51+screen_h),(-screen_w/2,movie_y+.33,z+1.51+screen_h)]
    plaque("Movie screen pixels",public,[quad[i] for i in (1,0,3,2)],signal,(0,0,256,128),(256,256))
    for y in (-6,9,25):
        for x in (-10.5,10.5):
            box_at("String light post",g,(x,y,z),(.095,.095,3.42),rubber)
        beam("String-light span",g,(-10.5,y,z+3.35),(10.5,y,z+3.35),(.025,.025),(.025,.025),rubber,n=4)
        for x in range(-9,10,2):
            box_at("Amber string-light head",public,(x,y,z+3.21),(.13,.13,.16),glow)

    # Colour-specific lamp faces are the only small navigation emitters.
    lamps=[("navigation_port",(-17.12,-94.2,z+1.5),0),("navigation_starboard",(17.12,-94.2,z+1.5),1),
           ("navigation_stern",(0,153,22.4),2),("navigation_masthead",(0,-80,z+10.2),2),
           ("navigation_top",(0,-79.4,z+11.9),2)]
    for name,(x,y,hz),color in lamps:
        if color<2:
            corners=[(x,y-.22,hz),(x,y+.22,hz),(x,y+.22,hz+.38),(x,y-.22,hz+.38)]
        else:
            corners=[(x-.22,y,hz),(x+.22,y,hz),(x+.22,y,hz+.38),(x-.22,y,hz+.38)]
        ob=mesh(name,g,corners,[(0,1,2,3)],signal)
        u,v=.125,1-(128+32*color+20)/256
        w,hh=.44/(T*256),.38/(T*256)
        for j,uv in enumerate(((u,v),(u+w,v),(u+w,v+hh),(u,v+hh))):
            ob.data.uv_layers.active.data[j].uv=uv
        ob["keep"]=True
    # The copper wave is painted on both sides of each funnel shell.
    for fx,fy in ((-5.8,-44),(5.8,-37)):
        for side in (-1,1):
            for j in range(8):
                y=fy-1.56+j*.58
                z0=z+6.73+.34*sin(j*.79)
                x=fx+side*(3.04-.09*abs(y-fy)/2.3)
                block("Copperwake funnel wave",g,(x,y,z0),(.14,.60,.26),brass)
    for x,y in ((-8,-51),(8,-45)):
        marker(ctx["ship"],f"flood_funnel_{x}",(x,y,z+3))
