"""Landscape prototypes, sized for placement rather than maximum wild heights."""
from dataclasses import dataclass


@dataclass(frozen=True)
class Palm:
    key: str
    name: str
    kind: str
    height: float
    radius: float
    span: float
    bark: int
    color: int
    lean: float
    trunk: str = "plain"
    stems: tuple = ((0., 0., 1., 0.),)


PALMS = (
    Palm("mexican-fan","Mexican fan palm","fan",15.0,.31,2.85,0,0,.49,"skirt"),
    Palm("california-fan","California fan palm","fan",10.2,.59,3.16,1,0,.19,"heavy-skirt"),
    Palm("sabal","Sabal palm","sabal",7.3,.43,2.78,1,2,.31,"boots"),
    Palm("windmill","Windmill palm","fan",4.8,.28,1.72,2,0,.17,"fiber"),
    Palm("mediterranean-fan","Mediterranean fan palm","fan",3.0,.23,1.47,1,2,.32,"boots",
         ((0,0,1,0),(-.52,-.27,.70,2.7),(.47,.33,.53,.45))),
    Palm("saw-palmetto","Saw palmetto","sabal",1.10,.14,1.74,1,2,.16,"creeping",
         ((0,0,1,0),(-.62,-.23,.77,2.5),(.63,.20,.84,.63))),
    Palm("true-date","Date palm","feather",10.8,.43,3.64,1,2,.33,"date"),
    Palm("canary-date","Canary Island date palm","feather",6.5,.73,4.25,1,1,.12,"pineapple"),
    Palm("queen","Queen palm","feather",10.7,.30,3.57,0,0,.26,"plain"),
    Palm("royal","Royal palm","feather",13.9,.48,3.65,0,0,.05,"crownshaft"),
    Palm("foxtail","Foxtail palm","foxtail",7.2,.30,2.96,0,1,.20,"crownshaft"),
    Palm("areca","Areca palm","feather",4.9,.092,2.03,3,1,.61,"cane",
         ((-.13,0,1,0),(-.34,.42,.79,2.25),(.48,.26,.90,.35),(.26,-.48,.67,5.1))),
    Palm("pygmy-date","Pygmy date palm","feather",2.48,.155,1.71,1,0,.19,"date"),
)

COCONUT={"name":"Coconut palm","source":"Coastal Palm, draft PR #30",
         "source_url":"https://github.com/LifemaxRPG/Timerpg/pull/30",
         "commit":"2be664833370d6a71c5aa797debac6c96ecc7f86"}
