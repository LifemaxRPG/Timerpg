"""Compose large exterior, deck and interior proof sheets from model renders."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT=Path(__file__).resolve().parent
R=ROOT/"renders"
font_path="DejaVuSans.ttf"


def sheet(target,title,items,columns=2):
    width,photo_height=730,438
    top,row=76,477
    rows=(len(items)+columns-1)//columns
    im=Image.new("RGB",(columns*width+24,top+rows*row+12),"#26343a")
    draw=ImageDraw.Draw(im)
    draw.text((22,24),title,font=ImageFont.truetype(font_path,30),fill="#efdfbd")
    font=ImageFont.truetype(font_path,19)
    for i,(name,label) in enumerate(items):
        x,y=12+(i%columns)*width,top+(i//columns)*row
        with Image.open(R/f"{name}.png") as original:
            image=ImageOps.contain(original.convert("RGB"),(width-15,photo_height))
        im.paste(image,(x+(width-image.width)//2,y+(photo_height-image.height)//2))
        draw.text((x+8,y+photo_height+7),label,font=font,fill="#eee4d0")
    im.save(ROOT/target,quality=94)


sheet("review.jpg","Copperwake · Two generations at sea",[
    ("day","MV Copperwake · 1996 onward"),("1952-day","SS Copperwake · 1952–1978"),
    ("side","MV · Sea-level profile"),("1952-side","SS · Sea-level profile"),
    ("night","MV · Night, masked cabin lights"),("1952-night","SS · Night, promenade lights"),
    ("pier","MV · Pier elevation"),("1952-pier","SS · Pier elevation"),
    ("laid-up-day","SS · Laid up, 1979 onward"),("above","MV · Deck plan")
])
sheet("deck-review.jpg","Copperwake · From the bow to the promenade",[
    ("bow","MV · Bow letters and hawse fittings"),("1952-bow","SS · Raked bow"),
    ("funnels","MV · Twin copper funnels and deck cinema"),("1952-funnels","SS · Three swept funnels"),
    ("pool","MV · Pool deck at walking height"),("1952-pool","SS · Aft pool"),
    ("promenade","MV · Promenade beneath the launch boats"),("stern","MV · Tiered stern stairs"),
    ("filament","MV export in desktop Filament"),("1952-filament","SS export in desktop Filament")
])
sheet("interior-review.jpg","Copperwake · Passenger interiors and wheelhouse",[
    ("room-mv-grand-atrium","MV · Three-deck atrium"),("room-mv-main-dining","MV · Dining room"),
    ("room-mv-theatre","MV · Theatre"),("room-mv-bridge","MV · Wheelhouse"),
    ("room-mv-observation-lounge","MV · Observation lounge"),("room-mv-suite","MV · Suite and balcony"),
    ("room-mv-stateroom","MV · Stateroom"),("room-ss-smoking-room","SS · Smoking room"),
    ("room-ss-grand-staircase","SS · Grand staircase"),("room-ss-first-class-dining","SS · First-class saloon")
])
