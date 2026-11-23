"""Original motorcycle dimensions and placement in glTF metres."""
from dataclasses import dataclass


@dataclass(frozen=True)
class Design:
    slug: str
    name: str
    years: tuple
    wheelbase: float
    front_radius: float
    rear_radius: float
    front_tyre: float
    rear_tyre: float
    head: tuple
    grip: tuple
    seats: tuple
    colours: tuple
    accent: str
    mass_kg: int
    speed_kmh: int


DESIGNS=(
    Design("brackenton-1947","Brackenton Quarry Twin",(1941,1949),1.575,.345,.352,.119,.138,
           (0,.938,.455),(.453,1.145,.440),((0,.756,-.309),(0,.788,-.694)),
           ("#713f43","#353b38","#3f5161","#60664a","#685343"),"#c5ad81",253,130),
    Design("marrowfield-1974","Marrowfield Standard 550",(1971,1978),1.414,.323,.332,.104,.126,
           (0,.912,.463),(.381,1.117,.409),((0,.813,-.301),(0,.829,-.601)),
           ("#a27440","#496d63","#805142","#4c5871","#565744"),"#e0c48a",193,158),
    Design("kelveran-2005","Kelveran Slipstream",(2002,2008),1.410,.302,.308,.127,.185,
           (0,.931,.518),(.359,.970,.535),((0,.838,-.269),(0,.944,-.579)),
           ("#3c647b","#9d603a","#6b444a","#4e5656","#797b68","#536452"),"#e6b475",198,241),
)
