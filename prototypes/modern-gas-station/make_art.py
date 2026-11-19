"""Original station art. Draw at art-pixel resolution, then enlarge exactly 2x."""
from pathlib import Path
import json, random
from PIL import Image, ImageDraw
from pixel_font import FONT

R = Path(__file__).resolve().parent
INK='#3a3a50'; NAVY='#4b5268'; BLUE='#7d7f99'; PALE='#dad4cb'
WHITE='#eee9df'; RED='#a14f44'; RUST='#c97751'; DARKRED='#613242'
REGIONS={}

def text(im, value, x, y, color=PALE, scale=1):
    d=ImageDraw.Draw(im)
    for c in value:
        for j,row in enumerate(FONT.get(c,['00000']*7)):
            for i,p in enumerate(row):
                if p=='1':d.rectangle((x+i*scale,y+j*scale,x+(i+1)*scale-1,y+(j+1)*scale-1),fill=color)
        x+=6*scale

def framed(w,h,base,edge=INK):
    im=Image.new('RGBA',(w,h),base);d=ImageDraw.Draw(im)
    d.rectangle((0,0,w-1,h-1),outline=edge)
    if w>3 and h>3:
        d.line((1,1,w-2,1),fill=WHITE);d.line((1,h-2,w-2,h-2),fill=BLUE)
    return im

def atlas(name,w,h,patches):
    im=Image.new('RGBA',(w,h),INK)
    for key,x,y,p in patches:
        im.paste(p,(x,y));REGIONS[key]={'texture':name,'rect_art':[x,y,p.width,p.height]}
    im.resize((w*2,h*2),Image.Resampling.NEAREST).save(R/'textures'/name)

# Sign faces have a measured physical size: no stretched lettering or logos.
brand=framed(80,12,NAVY);text(brand,'CROSSFIELD',10,3,WHITE)
sub=framed(96,8,RED);text(sub,'CROSSFIELD',18,1,WHITE)
market=framed(64,10,NAVY);text(market,'MARKET',14,2,WHITE)
price=framed(40,64,NAVY);text(price,'FUEL',8,4,WHITE)
small={'R':['110','101','110','101','101'],'E':['111','100','110','100','111'],'G':['111','100','101','101','111'],'P':['110','101','110','100','100'],'L':['100','100','100','100','111'],'S':['111','100','111','001','111'],'D':['110','101','101','101','110']}
for j,grade in enumerate(('REG','PLS','DSL')):
    y=18+j*14;d=ImageDraw.Draw(price)
    for i,c in enumerate(grade):
        for yy,row in enumerate(small[c]):
            for xx,v in enumerate(row):
                if v=='1':d.point((2+i*4+xx,y+1+yy),fill=PALE)
    # Digits are supplied by the dedicated runtime sign_price texture.

number=[]
for i in range(1,5):
    p=framed(8,10,RED);text(p,str(i),2,2,WHITE);number.append(('number'+str(i),i*10,90,p))
air=framed(24,12,NAVY);text(air,'AIR',4,3,WHITE)
exitp=framed(32,12,NAVY);text(exitp,'EXIT',4,3,WHITE)
light=framed(16,4,WHITE)
edge=Image.new('RGBA',(244,2),WHITE);edge_side=Image.new('RGBA',(2,154),WHITE)
atlas('signs.png',256,256,[('edge_front',0,110,edge),('edge_side',248,0,edge_side),('brand',0,0,brand),('canopy_brand',84,0,sub),('market',0,15,market),('price',190,0,price),('air',80,15,air),('exit',110,15,exitp),('lamp',0,104,light)]+number)

# Utility atlas: all shading stays in flat palette steps; no gradients or baked GI.
metal=framed(32,32,BLUE);d=ImageDraw.Draw(metal)
for y in range(6,30,8):d.line((2,y,29,y),fill=NAVY)
for x,y in [(5,6),(22,20),(17,27)]:d.point((x,y),fill=PALE)
white=framed(32,32,PALE);d=ImageDraw.Draw(white)
for x,y in [(5,25),(6,26),(25,13),(15,30)]:d.point((x,y),fill='#b4aea8')
orange=framed(32,32,RED);d=ImageDraw.Draw(orange);d.line((1,2,30,2),fill=RUST)
for x,y in [(4,28),(5,29),(28,10)]:d.point((x,y),fill=DARKRED)
dark=framed(32,32,NAVY);d=ImageDraw.Draw(dark)
for y in (9,19,29):d.line((2,y,29,y),fill=INK)
yellow=framed(16,16,'#cfad67');d=ImageDraw.Draw(yellow);d.line((2,2,13,2),fill='#edcf8e')
rubber=framed(16,16,INK);d=ImageDraw.Draw(rubber)
for i in range(2,14,4):d.line((i,2,i,13),fill=NAVY)
pump=framed(16,20,PALE);d=ImageDraw.Draw(pump);d.rectangle((2,2,13,8),fill=INK);
for x in (3,7,11):d.rectangle((x,3,x+2,7),outline='#bbc3b2')
d.point((10,7),fill='#bbc3b2')
for x in (3,7,11):d.rectangle((x,13,x+2,15),fill=[RED,NAVY,'#cfad67'][(x-3)//4])
d.rectangle((3,17,12,18),fill=INK)
louvre=framed(24,16,NAVY);d=ImageDraw.Draw(louvre)
for y in range(3,14,3):d.line((2,y,21,y),fill=INK);d.line((2,y+1,21,y+1),fill=BLUE)
door=framed(16,32,BLUE);d=ImageDraw.Draw(door);d.rectangle((2,2,13,27),outline=NAVY);d.line((3,21,12,21),fill=INK);d.line((3,20,12,20),fill=PALE)
drain=framed(24,8,NAVY);d=ImageDraw.Draw(drain)
for x in range(2,22,3):d.line((x,1,x,6),fill=INK)
binp=framed(12,16,NAVY);d=ImageDraw.Draw(binp);d.rectangle((3,3,8,7),fill=INK);d.line((1,11,10,11),fill=BLUE)
leaf=framed(16,16,'#479757');d=ImageDraw.Draw(leaf)
for x,y in [(2,3),(7,2),(11,4),(4,8),(10,10),(6,12)]:
    d.rectangle((x,y,x+2,y+1),fill='#64b63b');d.point((x+1,y+2),fill='#268b49')
paint=Image.new('RGBA',(2,16),PALE);paint.putpixel((0,12),(180,174,168,255))
ice=framed(32,24,PALE);text(ice,'ICE',7,2,BLUE);d=ImageDraw.Draw(ice)
for x in (2,17):
    d.rectangle((x,11,x+12,21),fill=BLUE,outline=INK);d.line((x+2,13,x+9,13),fill=PALE);d.line((x+10,15,x+10,18),fill=INK)
cage=framed(32,24,'#cfad67');d=ImageDraw.Draw(cage)
tiny={'P':['110','101','110','100','100'],'R':['110','101','110','101','101'],'O':['111','101','101','101','111'],'A':['010','101','111','101','101'],'N':['101','111','111','111','101'],'E':['111','100','110','100','111']}
for i,c in enumerate('PROPANE'):
    for y,row in enumerate(tiny[c]):
        for x,v in enumerate(row):
            if v=='1':d.point((2+i*4+x,2+y),fill=INK)
for x in (4,11,18,25):
    d.rectangle((x,12,x+4,20),fill=PALE,outline=NAVY);d.rectangle((x+1,10,x+3,12),fill=BLUE)
for x in range(2,31,4):d.line((x,9,x,22),fill=INK)
for y in (9,15,22):d.line((1,y,30,y),fill=INK)
pump_bin=framed(10,16,NAVY);d=ImageDraw.Draw(pump_bin);d.rectangle((2,3,7,7),fill=INK);d.line((1,11,8,11),fill=BLUE)
water=Image.new('RGBA',(8,8),'#7d7f99');d=ImageDraw.Draw(water);d.line((1,2,6,2),fill='#b9c3d5');d.point((3,5),fill='#565972')
atlas('utility.png',128,128,[('ice_chest',0,92,ice),('propane_cage',34,92,cage),('pump_bin',70,92,pump_bin),('bucket_water',84,92,water),('paint_strip',64,70,paint),('leaf',44,70,leaf),('metal',0,0,metal),('white',32,0,white),('orange',64,0,orange),('dark',96,0,dark),('yellow',0,34,yellow),('rubber',18,34,rubber),('pump',36,34,pump),('louvre',56,34,louvre),('service_door',82,34,door),('drain',0,70,drain),('bin',26,70,binp)])

# Transparent panes use painted frames/mullions, rather than many tiny mesh bars.
def glazing(w,h,divisions=1):
    im=Image.new('RGBA',(w,h),(137,159,191,90));d=ImageDraw.Draw(im)
    for x in range(5,w-4,24):
        for y in range(3,h-3):
            xx=x+(h-y)//2
            if xx<w-2:d.line((xx,y,min(xx+2,w-2),y),fill=(185,195,213,115))
    d.rectangle((0,0,w-1,h-1),outline=INK);d.line((1,1,w-2,1),fill=PALE)
    for x in range(w//divisions,w, w//divisions):d.line((x,0,x,h-1),fill=INK);d.line((x+1,1,x+1,h-2),fill=BLUE)
    d.line((1,h-2,w-2,h-2),fill=NAVY)
    return im
front=glazing(90,36,3);hot=front.copy()
poster=framed(20,18,RED);text(poster,'ICE',2,2,WHITE);text(poster,'2/5',2,10,WHITE);front.paste(poster,(7,1))
poster=framed(20,18,PALE);text(poster,'HOT',2,2,RED);text(poster,'CUP',2,10,NAVY);hot.paste(poster,(62,1))
side=glazing(32,28,2);entry=glazing(32,32,2);d=ImageDraw.Draw(entry)
for x in (13,18):d.line((x,16,x,22),fill=PALE)
atlas('glass.png',128,128,[('front_glass',0,0,front),('front_glass_hot',0,40,hot),('side_glass',92,0,side),('entry_glass',0,80,entry)])

# Simple enamel tiles carry seams, outlines, highlights and sparse wear.
for name,p in [('enamel_navy',dark),('enamel_orange',orange)]:
    p.resize((p.width*2,p.height*2),Image.Resampling.NEAREST).save(R/'textures'/(name+'.png'))
membrane=Image.new('RGBA',(64,64),'#d7d6e2');d=ImageDraw.Draw(membrane)
for x in (0,32):d.line((x,0,x,63),fill='#c4c4ce');d.line((x+1,0,x+1,63),fill='#eee9df')
for x,y in [(9,17),(10,18),(47,42),(48,42),(27,59)]:d.point((x,y),fill='#c0beca')
membrane.resize((128,128),Image.Resampling.NEAREST).save(R/'textures/membrane_white.png')
from price_art import write_prices
write_prices(R/'textures/prices.png',2025,'3.49','3.79','3.69')
REGIONS['price_digits']={'texture':'prices.png','rect_art':[0,0,24,42]}
(R/'art-regions.json').write_text(json.dumps(REGIONS,indent=2)+'\n')
print('Station atlases drawn on 2x2 texel grid')

if __name__=='__main__':
    pass
