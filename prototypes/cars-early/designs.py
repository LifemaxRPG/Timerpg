"""Body sections and fitment dimensions in glTF metres."""
from dataclasses import dataclass


@dataclass(frozen=True)
class Design:
    slug: str
    name: str
    years: tuple
    length: float
    wheelbase: float
    track: float
    radius: float
    tyre: float
    sill: float
    belt: float
    width: float
    height: float
    # (windshield foot, windshield header, rear header, rear glass foot)
    glass: tuple
    # Forward hinge to trailing edge, ordered front to rear.
    doors: tuple
    # (longitudinal position, width, lower edge, shoulder, hood/deck crown)
    front: tuple
    rear: tuple
    colours: tuple
    accent: str
    mass_kg: int
    speed_kmh: int


DESIGNS=(
    Design("alderwick-1928","Alderwick Six",(1927,1930),4.52,2.78,1.41,.388,.145,.61,1.135,.696,1.89,
           (.65,.53,-1.51,-1.68),((.57,-.445),(-.47,-1.44)),
           ((.61,.69,.39,1.10,1.19),(.82,.53,.42,1.10,1.19),(1.75,.40,.48,1.00,1.115),(1.99,.395,.49,.99,1.11)),
           ((-1.97,.494,.36,.83,.90),(-1.80,.637,.34,1.015,1.07),(-1.45,.696,.33,1.135,1.135)),
           ("#353d51","#2e4138","#484754","#5a3a34","#323333"),"#343934",1280,103),
    Design("fenmere-1938","Fenmere Club Coupe",(1937,1940),4.73,2.94,1.52,.366,.168,.51,1.035,.813,1.665,
           (.62,.285,-.93,-1.68),((.57,-.71),),
           ((.58,.783,.40,1.04,1.13),(.86,.64,.37,1.00,1.12),(1.62,.474,.37,.91,1.04),
            (2.04,.391,.41,.82,.962),(2.17,.26,.43,.78,.974)),
           ((-2.31,.584,.34,.60,.67),(-2.14,.75,.32,.76,.90),(-1.78,.81,.32,.87,1.025),
            (-1.359,.80,.32,1.02,1.051),(-.71,.811,.32,1.035,1.035)),
           ("#81443f","#3c6054","#434c68","#5e5353","#86735a"),"#413a39",1465,132),
    Design("northcairn-1956","Northcairn Terrace",(1955,1957),5.08,3.06,1.59,.361,.181,.47,1.015,.939,1.54,
           (.84,.368,-1.20,-1.61),((.71,-.38),(-.402,-1.44)),
           ((.73,.936,.40,.99,1.04),(1.15,.878,.35,.964,1.005),(2.16,.871,.34,.93,.973),
            (2.42,.817,.36,.887,.932),(2.48,.714,.39,.875,.917)),
           ((-2.56,.714,.36,.849,.89),(-2.409,.907,.32,.909,.946),(-1.91,.932,.32,.936,.983),
            (-1.44,.939,.32,1.015,1.015)),
           ("#38666d","#984d43","#4b617e","#596e4b","#707276","#754256"),"#d2c4a5",1685,153),
)
