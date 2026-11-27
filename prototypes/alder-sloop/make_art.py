"""Generate Alder's matte pixel-art textures."""
from pathlib import Path
from random import Random

from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parent
OUT=ROOT/"textures"
OUT.mkdir(exist_ok=True)


def save(name,im):
    im.resize((im.width*2,im.height*2),Image.Resampling.NEAREST).save(OUT/f"{name}.png")


palettes={
    "navy":("#395454","#486360","#2f4646","#405a58"),
    "boot":("#995b4a","#aa6850","#874e43","#9b6250"),
    "ivory":("#c7bda4","#d9ceb5","#aaa38b","#bfb498"),
    "teak":("#a27d56","#b59064","#755d45","#95714e"),
    "steel":("#626c63","#839087","#495950","#6f746a"),
    "cord":("#74664b","#8a7b58","#564c39","#70604c"),
    "cloth":("#d2c7ab","#e2d7b9","#b7ab92","#c6bc9f"),
    "glass":("#425d59","#648079","#354c4d","#516e69"),
    "cushion":("#687964","#7e8d70","#526454","#737e68"),
    "lamp":("#d5b480","#efcc92","#a38966","#ccb17d"),
}
for i,(name,colors) in enumerate(palettes.items()):
    rng=Random(1858+i)
    im=Image.new("RGB",(64,64),colors[0])
    d=ImageDraw.Draw(im)
    for _ in range(75):
        x,y=rng.randrange(57),rng.randrange(64)
        d.line((x,y,x+rng.randrange(1,6),y),fill=rng.choice(colors))
    if name=="teak":
        for y in range(0,64,3):
            d.line((0,y,63,y),fill=colors[2])
            d.line((0,y+1,63,y+1),fill=colors[1])
            for x in range(-24+(y*5)%24,64,24):
                d.line((x,y,x,y+3),fill=colors[2])
    if name=="cloth":
        d.line((0,0,63,0),fill="#b5ab91")
        d.line((0,1,63,1),fill="#dfd4b5")
        for x in range(1,64,3):
            d.point((x,0),fill="#a79c85")
    if name=="glass":
        d.line((0,0,63,0),fill="#344646")
        d.line((0,4,63,4),fill="#617970")
        d.line((2,9,22,9),fill="#789189")
    save(name,im)

im=Image.new("RGB",(22,6),"#395454")
d=ImageDraw.Draw(im)
letters={
    "A":(".#.","#.#","###","#.#","#.#"),
    "L":("#..","#..","#..","#..","###"),
    "D":("##.","#.#","#.#","#.#","##."),
    "E":("###","#..","##.","#..","###"),
    "R":("##.","#.#","##.","#.#","#.#"),
}
for i,ch in enumerate("ALDER"):
    for y,row in enumerate(letters[ch]):
        for x,bit in enumerate(row):
            if bit=="#":
                d.point((2+i*4+x,y),fill="#e9dbb5")
save("name",im)
