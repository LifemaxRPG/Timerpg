"""Paint the store's signs and season-independent textures on a 2×2 texel grid."""
from pathlib import Path
from random import Random
import json

from PIL import Image, ImageDraw
from pixel_font import FONT

ROOT=Path(__file__).resolve().parent
OUT=ROOT/"textures"
OUT.mkdir(exist_ok=True)

def save(im,name):
    im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(OUT/f"{name}.png")

path=ROOT.parents[1]/"kotlin-street/tools/swatches/atlas"
catalog=json.loads((path/"atlas.json").read_text())
atlas=Image.open(path/"atlas.png").convert("RGB")
for key,out in (("brick_red","brick"),("plank_horizontal","wood"),("roof_membrane_grey","membrane"),
                ("pavement","walk"),("block_courses","block")):
    x,y,w,h=(catalog[key][a] for a in ("x","y","w","h"))
    save(atlas.crop((x,y,x+w,y+h)).resize((w//2,h//2),Image.Resampling.NEAREST),out)

# Keep the shared slate-grey undertone, with roofing seams and low-contrast wear.
with Image.open(OUT/"membrane.png") as source:
    base = max(source.getcolors(), key=lambda entry: entry[0])[1]
roof_palette = [tuple(channel - shade for channel in base) for shade in (35, 46, 40, 30)]
roof = Image.new("RGB", (64, 64), roof_palette[0])
draw = ImageDraw.Draw(roof)
rng = Random(921)
for _ in range(115):
    x, y = rng.randrange(64), rng.randrange(64)
    tone = rng.choice(roof_palette[1:])
    for dx, dy in ((0, 0), (1, 0), (2, 0), (0, 1)):
        draw.point(((x + dx) % 64, (y + dy) % 64), fill=tone)
for y in (0, 32):
    draw.line((0, y, 63, y), fill=roof_palette[1])
    draw.line((0, y + 1, 63, y + 1), fill=roof_palette[2])
    x = 17 if y == 0 else 49
    draw.line((x, y + 2, x, y + 31), fill=roof_palette[2])
save(roof, "membrane")

pal={
    "limestone":("#b6ae94","#d0c6a7","#929482","#aaa58e"),
    "iron":("#444d4a","#5c6257","#353c3d","#4e5650"),
    "oxblood":("#844d46","#a06350","#633b39","#8f5547"),
    "tile":("#b9b8a2","#d2cab0","#929583","#a9a996"),
    "warm":("#e0b878","#f1d291","#ba955b","#d4b17b"),
    "fluoro":("#c0c7ab","#dce2c9","#9fa98e","#ccd0b9"),
}
for i,(name,c) in enumerate(pal.items()):
    im=Image.new("RGB",(64,64),c[0]); d=ImageDraw.Draw(im); rng=Random(680+i)
    for _ in range(180):
        x,y=rng.randrange(60),rng.randrange(63)
        d.line((x,y,x+rng.randint(0,4),y),fill=rng.choice(c))
    if name in ("limestone","tile"):
        for y in range(0,64,8 if name=="tile" else 16):
            d.line((0,y,63,y),fill=c[2])
            d.line((0,y+1,63,y+1),fill=c[1])
            for x in range(0,64,8 if name=="tile" else 16):
                d.line((x,y,x,min(y+7 if name=="tile" else y+15,63)),fill=c[2])
    save(im,name)

for state in ("1925","1970","2000","closed"):
    dark=state=="closed"
    im=Image.new("RGBA",(64,64),(55,67,70,224) if dark else (103,133,129,43))
    e=Image.new("RGB",(64,64),(0,0,0)); a=ImageDraw.Draw(im); b=ImageDraw.Draw(e)
    if not dark:
        b.rectangle((0,0,63,63),fill=(224,190,153))
    for x,y in ((5,24),(15,29),(34,50),(52,22)):
        for k in range(9):
            a.line((x+k,y-k,x+k+1,y-k),fill=(161,183,170,126 if dark else 102))
    # Obscure edge seals and keep them unlit in every state.
    for x in (0,63):
        a.line((x,0,x,63),fill=(52,56,50,255)); b.line((x,0,x,63),fill=(0,0,0))
    for y in (0,63):
        a.line((0,y,63,y),fill=(52,56,50,255)); b.line((0,y,63,y),fill=(0,0,0))
    save(im,"glass-"+state); save(e,"pane-mask-"+state)

signs={
    "name-1925":(0,115,19,"MARLOW DIME","#814c43",(233,200,133),2),
    "name-1970":(20,113,15,"MARLOW VARIETY","#d1ccb0",(62,86,81),1),
    "name-2000":(36,116,17,"DOLLAR COVE","#4d7464",(238,224,174),2),
    "name-closed":(54,113,16,"MARLOW VARIETY","#716d60",(190,182,151),1),
    "lease":(71,38,19,"FOR\nLEASE","#d1b992",(103,65,53),1),
    "value":(91,34,19,"VALUE","#b98857",(242,217,155),1),
    "sale":(111,31,17,"SALE","#816459",(236,210,155),1),
    "receiving":(129,67,11,"RECEIVING","#53615e",(230,218,185),1),
    "candy":(141,67,11,"SODA CANDY","#7e4e46",(230,203,144),1),
}
im=Image.new("RGB",(128,256),("#55483c")); d=ImageDraw.Draw(im)
e=Image.new("RGB",im.size); m=ImageDraw.Draw(e)
for name,(y,w,h,line,bg,text,scale) in signs.items():
    d.rectangle((0,y,w-1,y+h-1),fill="#ab946f")
    d.rectangle((1,y+1,w-2,y+h-2),fill=bg)
    columns = (0, 1, 1, 2, 3, 3, 4) if scale == 2 else (0, 1, 2, 3, 4)
    lines = line.splitlines()
    gap = 2 if scale == 2 else 1
    line_height = 7 * scale
    text_height = len(lines) * line_height + (len(lines) - 1) * gap
    assert text_height <= h - 2, name
    for line_index, words in enumerate(lines):
        assert all(ch == " " or ch in FONT for ch in words), name
        stride = len(columns) + gap
        text_width = len(words) * stride - gap
        start = (w - text_width) // 2
        assert min(start, w - start - text_width) >= 2, name
        for letter, ch in enumerate(words):
            for j, row in enumerate(FONT.get(ch, ())):
                for k, column in enumerate(columns):
                    if row[column] == "1":
                        x = start + letter * stride + k
                        z = y + (h - text_height) // 2 + line_index * (line_height + gap) + j * scale
                        rect = (x, z, x, z + scale - 1)
                        d.rectangle(rect, fill=text)
                        if name in ("name-1925", "name-1970", "name-2000"):
                            m.rectangle(rect, fill=(232, 208, 169))
save(im,"signs"); save(e,"sign-mask")
(OUT/"sign-regions.json").write_text(json.dumps({key:list(v[:3]) for key,v in signs.items()},indent=2)+"\n")
