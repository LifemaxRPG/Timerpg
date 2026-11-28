"""Paint original billboard art at 128 × 44 and export at the existing face size."""
from pathlib import Path
from random import Random
import json

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
ART = (128, 44)
FACE = (256, 88)
FONT = dict(zip("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789", (
    "010101111101101","110101110101110","011100100100011","110101101101110",
    "111100110100111","111100110100100","011100101101011","101101111101101",
    "111010010010111","001001001101010","101101110101101","100100100100111",
    "101111111101101","101111111111101","010101101101010","110101110100100",
    "010101101111011","110101110101101","011100010001110","111010010010010",
    "101101101101111","101101101101010","101101111111101","101101010101101",
    "101101010010010","111001010100111","111101101101111","010110010010111",
    "110001010100111","110001010001110","101101111001001","111100110001110",
    "011100110101010","111001010010010","010101010101010","010101011001110"
)))

def text(draw, words, x, y, color, size=1):
    for i, char in enumerate(words):
        if char == " ":
            continue
        for k, pixel in enumerate(FONT[char]):
            if pixel == "1":
                px,py=x+i*4*size+(k%3)*size,y+(k//3)*size
                draw.rectangle((px,py,px+size-1,py+size-1),fill=color)

def centered(draw, words, center, y, color, size=1):
    text(draw,words,center-(len(words)*4-1)*size//2,y,color,size)

def crest(draw,x,y,col):
    draw.polygon([(x,y+3),(x+4,y+4),(x+6,y+2),(x+6,y),(x+8,y+2),
                  (x+10,y+1),(x+12,y+2),(x+10,y+3),(x+8,y+6),(x+4,y+6)],fill=col)
    for i in range(3):
        draw.line([(x,y+9+i*2),(x+3,y+8+i*2),(x+7,y+9+i*2),
                   (x+10,y+8+i*2),(x+12,y+9+i*2)],fill=col)

ERA={
    "rail": {"years":[1920,1939],"paper":"#e8d6a5","dark":"#294748","accent":"#9c4834"},
    "motor": {"years":[1950,1969],"paper":"#f3dfb3","dark":"#234b52","accent":"#ad493e"},
    "modern": {"years":[1990,2029],"paper":"#e9ecdc","dark":"#203e54","accent":"#bf665d"},
}
SCENES={
    "head-light":("BY RAIL","HEAD LIGHT","light"),
    "fountain":("BY RAIL","FOUNTAIN CIRCUS","fountain"),
    "pier-dusk":("BY RAIL","PIER AT DUSK","pier"),
    "family-drive":("THE COAST ROAD","FAMILY DAY OUT","car"),
    "sandcastles":("THE COAST ROAD","SAND AND SUN","family"),
    "strand-treats":("THE COAST ROAD","ICE CREAM","ice"),
    "surf-break":("TAKE THE EXIT","CATCH A WAVE","surf"),
    "shrimp-dock":("TAKE THE EXIT","OLD HARBOUR","boat"),
    "weekend-pier":("TAKE THE EXIT","MEET AT THE PIER","daypier")
}
CAMPAIGNS={
    "rail":("head-light","fountain","pier-dusk"),
    "motor":("family-drive","sandcastles","strand-treats"),
    "modern":("surf-break","shrimp-dock","weekend-pier")
}

def person(d,x,y,shirt,skin="#b77e5d",small=False):
    ink="#303b46"
    height=1 if small else 2
    d.rectangle((x-1,y-3-height,x+1,y-2),fill=shirt)
    d.rectangle((x-1,y-6-height,x+1,y-4-height),fill=skin)
    d.line((x-1,y-6-height,x+1,y-6-height),fill=ink)
    d.line((x-1,y-2,x-1,y),fill=ink)
    d.line((x+1,y-2,x+1,y),fill=ink)

def seaside(which,era):
    im=Image.new("RGB",(65,35),"#8fc3c3")
    d=ImageDraw.Draw(im)
    ink="#28474c"
    foam="#e8e4c8"
    sea="#558f98"
    sand="#dac08a"
    if which=="pier":
        d.rectangle((0,0,64,34),fill="#d88468")
        d.rectangle((0,8,64,18),fill="#e8ac77")
        sea="#727c89"
        sand="#a7846e"
    elif era=="modern":
        d.rectangle((0,0,64,34),fill="#87bfd0")
        sea="#4d92a4"
    d.ellipse((43,4,56,17),fill="#f5de9b")
    d.polygon([(0,16),(10,11),(20,13),(29,17),(38,18),(64,18),(64,27),(0,26)],fill="#699089")
    d.rectangle((0,18,64,34),fill=sea)
    for j in range(3):
        for x in range(4+j*5,63,17):
            d.line((x,20+j*4,min(x+8,64),20+j*4),fill=foam if j==2 else "#97bfb6")
    d.polygon([(0,23),(18,25),(31,29),(46,30),(64,33),(64,34),(0,34)],fill=sand)
    if which=="light":
        d.polygon([(0,26),(4,22),(14,20),(28,24),(39,27),(32,32),(0,34)],fill="#496b60")
        d.polygon([(13,23),(14,7),(21,7),(23,24)],fill=foam)
        d.rectangle((14,10,21,12),fill=ink)
        d.rectangle((14,16,21,18),fill=ink)
        d.rectangle((14,5,21,7),fill=ink)
        d.rectangle((16,5,19,6),fill="#edbd73")
        d.polygon([(12,5),(17,2),(23,5)],fill="#ad493e")
    elif which=="fountain":
        d.rectangle((0,25,64,34),fill="#c9ad79")
        d.ellipse((9,21,56,33),fill="#8f795c")
        d.ellipse((12,21,53,29),fill="#82b8b4",outline=foam,width=2)
        d.polygon([(28,27),(31,20),(31,16),(36,15),(36,22),(39,27)],fill="#527760")
        d.polygon([(32,19),(23,15),(15,6),(23,8),(32,13),(43,9),(52,4),
                   (49,12),(41,17),(37,18),(36,13),(38,12),(40,13),(37,15)],fill="#426b58")
        d.line([(26,17),(21,18),(17,23)],fill=foam)
        d.line([(40,17),(46,19),(50,23)],fill=foam)
    elif which in ("pier","daypier"):
        d.polygon([(0,24),(52,20),(64,22),(64,26),(0,28)],fill=ink)
        for x in (4,16,29,42,53):
            d.line((x,24,x-1,33),fill=ink,width=2)
        d.polygon([(47,17),(54,12),(62,17)],fill="#744d47" if which=="pier" else "#b2574c")
        d.rectangle((48,17,61,21),fill=foam)
        d.line((51,18,51,21),fill=ink)
        d.line((58,18,58,21),fill=ink)
        person(d,22,25,"#f4ca8f")
        person(d,27,25,"#d3a5a0","#875b4d")
    elif which=="car":
        d.polygon([(0,26),(26,25),(65,30),(65,34),(0,34)],fill="#826e6a")
        d.line((2,33,50,33),fill="#e7bd8e")
        d.polygon([(7,26),(12,22),(22,21),(30,15),(42,15),(49,21),
                   (56,21),(61,24),(60,29),(8,29)],fill="#bb5548")
        d.polygon([(25,21),(31,17),(41,17),(46,21)],fill="#bfd3be")
        d.line((36,17,36,21),fill=ink)
        d.line((9,26,59,26),fill="#f1d3aa")
        for x in (17,51):
            d.ellipse((x-4,24,x+4,31),fill=ink)
            d.ellipse((x-1,26,x+1,29),fill=foam)
    elif which=="family":
        d.polygon([(0,24),(22,25),(64,30),(64,34),(0,34)],fill=sand)
        d.line((15,14,15,31),fill=ink,width=1)
        d.polygon([(2,20),(12,11),(18,11),(29,20)],fill="#bc5c50")
        d.polygon([(9,20),(14,12),(18,12),(23,20)],fill=foam)
        person(d,34,32,"#b55c50")
        person(d,41,32,"#385e6c","#825b4c")
        person(d,53,33,"#ae8751",small=True)
        person(d,59,33,"#778f75",small=True)
        d.rectangle((47,31,49,33),fill="#bc5945")
    elif which=="ice":
        d.rectangle((0,25,64,34),fill="#ad8971")
        for x,color in ((4,"#d77c6b"),(21,"#78a69e"),(39,"#e0b467")):
            d.rectangle((x,9,x+15,28),fill=color)
            d.rectangle((x+2,18,x+13,25),fill=ink)
            for j in range(0,16,4):
                d.rectangle((x+j,14,x+j+1,17),fill=foam)
        d.polygon([(43,12),(51,12),(47,20)],fill="#9c6343")
        d.ellipse((42,6,51,14),fill="#f2a199")
        person(d,25,33,"#cf8661")
        person(d,34,33,"#d3ba80","#845c49")
    elif which=="surf":
        d.polygon([(0,29),(9,23),(19,20),(32,20),(45,23),(58,24),(64,27),
                   (64,34),(0,34)],fill="#327c8b")
        d.line([(0,26),(11,21),(21,19),(30,19),(43,22),(53,23),(62,25)],fill=foam,width=2)
        for x,y in ((23,24),(47,28)):
            d.line((x-5,y+1,x+5,y+1),fill="#f2ba73",width=2)
            d.line((x-2,y,x,y-3,x+1,y),fill=ink,width=1)
            d.rectangle((x-1,y-6,x+1,y-4),fill="#c48666")
            d.line((x-4,y-3,x+3,y-4),fill="#c76756")
    elif which=="boat":
        d.polygon([(7,25),(14,30),(48,30),(60,24),(46,25)],fill=ink)
        d.polygon([(25,24),(28,16),(38,16),(42,24)],fill=foam)
        d.rectangle((31,18,38,20),fill="#588e8e")
        d.line((32,17,32,6),fill=ink)
        d.line([(11,23),(32,8),(53,23)],fill=ink)
        d.line([(11,24),(19,14),(31,17),(46,14),(54,24)],fill="#d5b67b")
        d.line((14,27,49,27),fill="#b85e4c")
    return im

def make_face(era,key):
    cfg=ERA[era]
    lead,caption,which=SCENES[key]
    paper,ink,accent=(cfg[k] for k in ("paper","dark","accent"))
    im=Image.new("RGB",ART,ink)
    d=ImageDraw.Draw(im)
    d.rectangle((1,1,126,42),fill=paper)
    d.rectangle((2,2,125,41),outline=accent)
    if era=="rail":
        d.rectangle((4,4,59,39),fill=ink)
        centered(d,lead,32,6,paper)
        centered(d,"DOVE",28,13,paper,2)
        centered(d,"BEACH",28,24,paper,2)
        d.line((9,36,54,36),fill=accent)
    elif era=="modern":
        d.rectangle((4,4,59,39),fill=ink)
        d.rectangle((5,4,58,10),fill="#a0c9bf")
        centered(d,lead,32,5,ink)
        centered(d,"DOVE",28,13,"#eec879",2)
        centered(d,"BEACH",28,24,paper,2)
        d.line((6,37,57,37),fill="#a0c9bf",width=2)
    else:
        centered(d,lead,31,5,accent)
        centered(d,"DOVE",28,12,ink,2)
        centered(d,"BEACH",28,23,ink,2)
        d.line((7,36,55,36),fill=accent,width=2 if era=="modern" else 1)
    im.paste(seaside(which,era),(61,4))
    d.rectangle((61,33,124,39),fill=ink)
    centered(d,caption,93,34,paper)
    crest(d,47,11,accent if era=="motor" else paper)
    return im

def distance(era,value):
    cfg=ERA[era]
    paper,ink,accent=(cfg[k] for k in ("paper","dark","accent"))
    im=Image.new("RGB",ART,ink)
    d=ImageDraw.Draw(im)
    d.rectangle((1,1,126,42),outline=paper)
    d.rectangle((3,3,124,40),fill=paper)
    d.rectangle((5,5,122,38),outline=accent)
    d.rectangle((8,8,25,35),fill=ink)
    crest(d,10,14,paper)
    centered(d,"DOVE BEACH",74,7,ink,2)
    centered(d,"NEXT EXIT" if value=="next-exit" else f"{value} MILES",74,24,accent,2)
    if era=="modern":
        d.rectangle((5,5,122,38),fill=ink)
        d.rectangle((8,8,25,35),fill="#335c63")
        crest(d,10,14,paper)
        centered(d,"DOVE BEACH",74,7,paper,2)
        centered(d,"NEXT EXIT" if value=="next-exit" else f"{value} MILES",74,24,"#eec879",2)
        d.line((32,36,112,36),fill="#a0c9bf",width=1)
    elif era=="rail":
        d.line((34,20,114,20),fill=ink)
    return im

def aged(im,seed):
    washed=Image.blend(im,Image.new("RGB",ART,"#dbbb90"),.25)
    d=ImageDraw.Draw(washed)
    rng=Random(seed)
    for x,y,w,h in ((3,3,13,2),(112,5,9,2),(102,38,19,3),(4,36,8,2),(64,38,15,2)):
        for n in range(10):
            px=rng.randrange(x,x+w)
            py=rng.randrange(y,y+h)
            d.line((px,py,min(x+w,px+rng.randrange(1,4)),py),fill=rng.choice(("#a99577","#c5ae8c","#786e61")))
    for _ in range(20):
        x,y=rng.randrange(7,121),rng.randrange(4,40)
        d.point((x,y),fill="#c6b193")
    return washed

def main():
    output=ROOT/"faces"
    output.mkdir(exist_ok=True)
    records=[]
    small={}
    def add(key,era,kind,image, **meta):
        small[key]=image
        image.resize(FACE,Image.Resampling.NEAREST).save(output/(key+".png"))
        records.append(dict(id=key,file=f"faces/{key}.png",campaign=era,kind=kind,**meta))
    for era,keys in CAMPAIGNS.items():
        for key in keys:
            add(key,era,"campaign",make_face(era,key))
        for value in ("120","45","12","next-exit"):
            add(f"{era}-{value}",era,"distance",distance(era,value),
                miles=int(value) if value.isdigit() else None,next_exit=value=="next-exit")
    for key,era in (("head-light","rail"),("family-drive","motor")):
        add(key+"-weathered",era,"weathered",aged(small[key],len(key)),source=key)
    sheet=Image.new("RGB",(812, len(range(0,len(records),3))*113+22),"#27383a")
    draw=ImageDraw.Draw(sheet)
    for i,record in enumerate(records):
        x=14+(i%3)*264
        y=10+(i//3)*113
        sheet.paste(Image.open(ROOT/record["file"]),(x,y))
        draw.text((x,y+90),record["id"],fill="#f0e6cd")
    sheet.save(ROOT/"all-faces.png")
    info=dict(board_face_px=list(FACE),art_grid_px=list(ART),texels_per_art_pixel=[2,2],
              board_face_m=[10,3.44],meters_per_texel=[10/256,3.44/88],nearest=True,
              campaigns=ERA,faces=records)
    (ROOT/"model-info.json").write_text(json.dumps(info,indent=2)+"\n")

if __name__=="__main__":
    main()
