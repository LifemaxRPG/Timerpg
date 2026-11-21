"""Original marine pixel textures and Timerpg deck swatches."""
import json
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw
from pixel_font import FONT

root = Path(__file__).resolve().parent
textures = root/"textures"
textures.mkdir(exist_ok=True)


def save(name, im):
    im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(textures/f"{name}.png")


palette = {
    "navy":("#30444e","#3e5660","#263943","#485a60"),
    "white":("#ccc7b4","#ded7c5","#aead9d","#bcb8a6"),
    "copper":("#ab6146","#bd7553","#88513e","#c68963"),
    "teal":("#527970","#698f85","#456a62","#7b9b88"),
    "water":("#48888a","#559699","#38787b","#7aa8a3"),
    "rubber":("#494f49","#626861","#343c3b","#535956"),
    "brass":("#b6965b","#d6b47a","#8d764d","#c5a975"),
    "amber":("#c5a474","#efce91","#7d6950","#d7b989"),
}
for index,(name,(base,light,dark,mid)) in enumerate(palette.items()):
    rng=Random(926+index)
    im=Image.new("RGB",(128,128),base)
    draw=ImageDraw.Draw(im)
    for _ in range(220):
        x,y=rng.randrange(123),rng.randrange(128)
        draw.line((x,y,x+rng.randrange(1,5),y),fill=rng.choice((light,dark,mid,mid)))
    if name in ("navy","white","copper"):
        for y in range(31,128,32):
            draw.line((0,y,127,y),fill=mid)
    if name=="water":
        for _ in range(120):
            x,y=rng.randrange(114),rng.randrange(128)
            draw.line((x,y,x+rng.randrange(3,14),y),fill=rng.choice((light,mid,base)))
    save(name,im)

atlas_dir=root.parents[1]/"kotlin-street/tools/swatches/atlas"
atlas=Image.open(atlas_dir/"atlas.png").convert("RGB")
items=json.loads((atlas_dir/"atlas.json").read_text())
x,y,w,h=[items["plank_horizontal"][f] for f in ("x","y","w","h")]
save("teak",atlas.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST))

# Repeat at exactly 40 art pixels horizontally and vertically per cabin.
im=Image.new("RGB",(40,40),"#c9c2ac")
draw=ImageDraw.Draw(im)
draw.rectangle((2,3,37,36),fill="#787f75")
draw.rectangle((4,4,35,26),fill="#344e54")
draw.rectangle((7,6,17,25),fill="#516267")
draw.rectangle((20,6,31,25),fill="#688080")
draw.rectangle((8,12,16,24),fill="#7f8272")
draw.line((18,5,18,26),fill="#c6c0a5",width=2)
draw.line((4,8,35,8),fill="#87958b")
draw.rectangle((3,27,36,35),fill="#5e746d")
draw.line((2,27,37,27),fill="#d1c9ad")
for x in (5,14,25,34):
    draw.line((x,28,x,35),fill="#8f9b8d")
draw.line((0,37,39,37),fill="#e4d7b9")
draw.line((0,38,39,38),fill="#a39881")
save("balconies",im)

im=Image.new("RGB",(32,32),"#344e56")
draw=ImageDraw.Draw(im)
for y,c in ((0,"#536e73"),(7,"#678186"),(14,"#405e65"),(26,"#304a51")):
    draw.rectangle((0,y,31,min(31,y+7)),fill=c)
draw.line((0,0,31,0),fill="#96aaa3")
draw.line((0,31,31,31),fill="#1e343a")
draw.line((0,1,0,30),fill="#263a40",width=2)
draw.line((2,1,2,30),fill="#87968e")
draw.line((11,18,23,13),fill="#6e8788")
save("glazing",im)

im=Image.new("RGB",(192,32),"#314752")
draw=ImageDraw.Draw(im)
draw.line((0,0,191,0),fill="#b89b66")
draw.line((0,31,191,31),fill="#9d8154")
text="COPPERWAKE"
x=(192-len(text)*12)//2
for letter in text:
    for y,row in enumerate(FONT[letter]):
        for i,value in enumerate(row):
            if value=="1":
                draw.rectangle((x+i*2,8+y*2,x+i*2+1,9+y*2),fill="#e0c693")
    x+=12
save("name",im)

# Five different cabins share a 15.52 m repeat. The separate mask lights glass only.
im=Image.new("RGB",(200,40),"#c9c2ac")
mask=Image.new("RGB",im.size)
d=ImageDraw.Draw(im)
m=ImageDraw.Draw(mask)
for bay,curtain in enumerate(("#b4a282","#6b8390","#959385","#ccb79c","#728381")):
    x=bay*40
    d.rectangle((x+2,3,x+37,36),fill="#777f75")
    d.rectangle((x+4,4,x+35,28),fill="#344e54")
    d.rectangle((x+6,6,x+16,24),fill=curtain)
    d.rectangle((x+21,6,x+33,25),fill=curtain if bay!=3 else "#304149")
    d.line((x+18,5,x+18,27),fill="#d4cbb6",width=2)
    d.line((x+7,11,x+32,11),fill="#7c8c89")
    if bay!=2:
        m.rectangle((x+7,7,x+15,24),fill="#eec48a")
        if bay!=3:
            m.rectangle((x+22,7,x+32,24),fill="#d6a76d")
    for chair in (9,28):
        d.line((x+chair,24,x+chair+4,24),fill="#c5bfa5",width=2)
        d.line((x+chair,20,x+chair,29),fill="#c5bfa5")
        d.line((x+chair+3,25,x+chair+3,29),fill="#504f44")
    d.rectangle((x+3,30,x+36,35),fill="#698780")
    d.line((x+2,29,x+37,29),fill="#dad1b3")
    for pos in (5,18,34):
        d.line((x+pos,30,x+pos,35),fill="#a5a89b")
    d.line((x,37,x+39,37),fill="#e6dac0")
    d.line((x+1,3,x+1,37),fill="#ece0ca")
save("balconies",im)
save("balcony_emission",mask)

im=Image.new("RGB",(124,80),"#344853")
mask=Image.new("RGB",im.size)
d=ImageDraw.Draw(im)
m=ImageDraw.Draw(mask)
for x in range(0,124,31):
    d.line((x,0,x,79),fill="#263b45")
    d.line((x+1,0,x+1,79),fill="#42545b")
    for y in (22,52):
        d.ellipse((x+12,y-3,x+18,y+3),fill="#253944",outline="#767d70")
        if (x//31+y//30)%3!=2:
            d.ellipse((x+14,y-1,x+16,y+1),fill="#b49b72")
            m.ellipse((x+14,y-1,x+16,y+1),fill="#e8bd83")
d.line((0,0,123,0),fill="#4f5e63")
d.line((0,41,123,41),fill="#293f49")
save("plating",im)
save("plating_emission",mask)

def title_card(text, second=""):
    im=Image.new("RGB",(192,48),"#344851")
    draw=ImageDraw.Draw(im)
    for word,y,size in ((text,5,3),(second,32,1)):
        x=(192-(len(word)*6-1)*size)//2
        for ch in word:
            for j,row in enumerate(FONT.get(ch,[])):
                for i,bit in enumerate(row):
                    if bit=="1":
                        draw.rectangle((x+i*size,y+j*size,x+(i+1)*size-1,y+(j+1)*size-1),fill="#e2dcca")
            x+=6*size
    return im

save("stern_name",title_card("COPPERWAKE","PORT MERIDIAN"))

im=Image.new("RGB",(128,64),"#354b60")
d=ImageDraw.Draw(im)
d.rectangle((0,36,127,63),fill="#263e4a")
d.ellipse((91,7,103,19),fill="#dfb47a")
d.polygon([(0,39),(27,21),(46,38),(58,26),(81,40),(127,31),(127,53),(0,56)], fill="#6c7971")
d.polygon([(0,49),(25,31),(39,44),(66,41),(80,49),(104,38),(127,48),(127,63),(0,63)],fill="#496b65")
for i in range(28):
    x=(i*53)%125
    y=50+(i*7)%14
    d.line((x,y,min(127,x+3),y),fill="#819186")
save("movie",im)

# Three fixed strips in the same accent atlas preserve the material budget.
im=Image.new("RGB",(64,64),"#666255")
d=ImageDraw.Draw(im)
for y,c in ((0,"#bd5546"),(16,"#79a16c"),(32,"#eadcb6"),(48,"#d4b579")):
    d.rectangle((0,y,63,y+15),fill=c)
    d.line((0,y,63,y),fill="#e7d1a2")
save("navigation",im)

im=Image.new("RGB",(128,128),"#3b5260")
d=ImageDraw.Draw(im)
for x in range(0,128,16):
    for y in range(0,128,16):
        if (x+y)//16%2==0:
            d.polygon([(x+7,y+2),(x+12,y+7),(x+7,y+12),(x+2,y+7)],fill="#5d6e6e")
            d.point((x+7,y+7),fill="#ab9b76")
save("carpet",im)

# The movie and navigation lamp faces use one emissive material.
movie=Image.open(textures/"movie.png").resize((128,64),Image.Resampling.NEAREST)
signal=Image.new("RGB",(128,128),"#293e49")
signal.paste(movie,(0,0))
d=ImageDraw.Draw(signal)
for i,color in enumerate(("#c75046","#72ae73","#eee0b8","#ba925a")):
    d.rectangle((0,64+16*i,127,79+16*i),fill=color)
save("signals",signal)

im=Image.new("RGB",(128,128),"#a96043")
d=ImageDraw.Draw(im)
for x,w,c in ((0,11,"#784c3b"),(11,19,"#975842"),(30,40,"#ae6547"),
              (70,22,"#b87352"),(92,21,"#b0714f"),(113,15,"#97553d")):
    d.rectangle((x,0,x+w-1,127),fill=c)
for x in (0,39,83):
    d.line((x,0,x,127),fill="#9e6048")
save("painted_steel",im)

im=Image.open(textures/"plating.png").convert("RGB")
art=im.resize((124,80),Image.Resampling.NEAREST)
d=ImageDraw.Draw(art)
for x,y,h in ((3,2,26),(31,4,34),(61,11,19),(94,0,39),(119,3,31)):
    d.line((x,y,x,y+h),fill="#905e49",width=2)
    d.line((x+2,y+5,x+2,y+h-9),fill="#776152")
    d.line((x+1,y+h-7,x+1,y+h+4),fill="#435153")
save("laid_up_hull",art)

art=im.resize((124,80),Image.Resampling.NEAREST)
pixels=art.load()
for y in range(80):
    for x in range(124):
        r,g,b=pixels[x,y]
        if b>=r:
            pixels[x,y]=(round(r*.66),round(g*.61),round(b*.57))
save("classic_hull",art)

im=Image.new("RGB",(128,48),"#c4c0aa")
mask=Image.new("RGB",im.size)
draw=ImageDraw.Draw(im)
emit=ImageDraw.Draw(mask)
for i in range(8):
    x=i*16
    draw.rectangle((x+1,10,x+13,29),fill="#7a7e71")
    draw.rectangle((x+3,12,x+11,27),fill="#42575d")
    draw.line((x+3,14,x+11,14),fill="#708481")
    draw.line((x+6,12,x+6,28),fill="#a7a18b")
    if i%4!=2:
        emit.rectangle((x+7,15,x+10,25),fill="#d8a66e")
for y,c in ((0,"#e5dcc1"),(33,"#e4dbc2"),(34,"#a29e8d"),(47,"#858679")):
    draw.line((0,y,127,y),fill=c)
save("classic_windows",im)
save("classic_windows_emission",mask)
