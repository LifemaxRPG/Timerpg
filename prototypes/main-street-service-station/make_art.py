"""Original period station artwork: all marks are painted in art pixels, enlarged 2x."""
from pathlib import Path
from PIL import Image,ImageDraw
import json
from pixel_font import FONT
R=Path(__file__).resolve().parent;REG={}
INK='#3a3a50';IVORY='#dad4cb';HI='#eee9df';MID='#b4aea8';TEAL='#46756a';DEEP='#3d6f63';RED='#a14f44';RUST='#b18a74'
def text(im,s,x,y,c=INK):
    d=ImageDraw.Draw(im)
    for ch in s:
        for j,row in enumerate(FONT.get(ch,['00000']*7)):
            for i,p in enumerate(row):
                if p=='1':d.point((x+i,y+j),fill=c)
        x+=6
def frame(w,h,c):
    im=Image.new('RGBA',(w,h),c);d=ImageDraw.Draw(im);d.rectangle((0,0,w-1,h-1),outline=INK)
    d.line((1,1,w-2,1),fill=HI);d.line((1,h-2,w-2,h-2),fill=MID);return im
def tile(name,base,dark):
    im=Image.new('RGBA',(32,32),base);d=ImageDraw.Draw(im)
    for y in (0,16):d.line((0,y,31,y),fill=dark);d.line((0,y+1,31,y+1),fill=HI if base==IVORY else RUST)
    for x,y in [(5,14),(6,14),(27,29),(19,7),(3,27),(4,28)]:d.point((x,y),fill=dark)
    im.resize((64,64),Image.Resampling.NEAREST).save(R/'textures'/(name+'.png'))
tile('porcelain',IVORY,MID);tile('teal',TEAL,DEEP);tile('red',RED,'#943c3f')
concrete=Image.new('RGBA',(64,64),'#8a7a77');d=ImageDraw.Draw(concrete)
for x,y in [(12,18),(40,35),(26,49)]:
    d.rectangle((x,y,x+5,y+3),fill='#7c6e6f');d.rectangle((x+2,y+1,x+6,y+5),fill='#716467')
d.line((0,0,63,0),fill='#b4aea8');d.line((0,0,0,63),fill='#b4aea8')
concrete.resize((128,128),Image.Resampling.NEAREST).save(R/'textures/service_concrete.png')
def atlas(name,w,h,patches):
    im=Image.new('RGBA',(w,h),INK)
    for key,x,y,p in patches:
        im.paste(p,(x,y));REG[key]={'texture':name,'rect_art':[x,y,p.width,p.height]}
    im.resize((w*2,h*2),Image.Resampling.NEAREST).save(R/'textures'/name)
main=frame(96,14,TEAL);text(main,'MAIN STREET',15,4,HI)
service=frame(56,12,RED);text(service,'SERVICE',7,3,HI)
full=frame(72,10,IVORY);text(full,'FULL SERVICE',1,2,RED)
gas=frame(24,12,RED);text(gas,'GAS',3,3,HI)
air=frame(36,12,IVORY);text(air,'AIR',9,3,TEAL)
water=frame(36,12,IVORY);text(water,'WATER',3,3,TEAL)
bay1=frame(8,10,TEAL);text(bay1,'1',2,2,HI)
bay2=frame(8,10,TEAL);text(bay2,'2',2,2,HI)
lamp=frame(16,4,HI);globe=Image.new('RGBA',(16,16),HI)
atlas('signs.png',256,128,[('main',0,0,main),('service',100,0,service),('full_service',0,18,full),('gas',184,0,gas),('air',80,18,air),('water',120,18,water),('bay1',164,18,bay1),('bay2',174,18,bay2),('lamp',0,34,lamp),('globe',20,34,globe)])
metal=frame(32,32,'#7d7f99');d=ImageDraw.Draw(metal)
for y in (7,15,23):d.line((2,y,29,y),fill='#565972')
rubber=frame(32,32,INK);d=ImageDraw.Draw(rubber)
for y in range(3,31,5):d.line((2,y,29,y),fill='#565972')
wood=frame(32,32,'#9b766a');d=ImageDraw.Draw(wood)
for y in range(6,31,7):d.line((1,y,30,y),fill='#716467')
for x,y in [(4,10),(17,24),(20,15)]:d.line((x,y,x+6,y),fill='#b99e86')
dial=frame(10,10,IVORY);d=ImageDraw.Draw(dial)
for y in (2,6):
    d.rectangle((2,y,7,y+2),fill=INK)
    for x in (3,5,7):d.point((x,y+1),fill=HI)
door=frame(16,32,TEAL);d=ImageDraw.Draw(door);d.rectangle((2,3,13,14),fill='#899fbf',outline=INK);d.line((11,20,13,20),fill=HI)
oil=frame(24,24,IVORY);text(oil,'OIL',3,2,RED);d=ImageDraw.Draw(oil)
for x in (3,9,15):
    for y in (12,18):d.rectangle((x,y,x+4,y+4),fill=RED,outline=INK);d.line((x+1,y-1,x+3,y-1),fill='#7d7f99')
grille=frame(24,12,'#7d7f99');d=ImageDraw.Draw(grille)
for x in range(3,22,3):d.line((x,2,x,9),fill=INK)
price_labels=frame(32,36,IVORY);text(price_labels,'GAS',7,3,RED)
# Labels only; the full-UV prices are a separate material and node.
tiny={'R':['110','101','110','101','101'],'E':['111','100','110','100','111'],'G':['111','100','101','101','111'],'P':['110','101','110','100','100']}
d=ImageDraw.Draw(price_labels)
for yy,label in [(14,'REG'),(26,'PRE')]:
    for k,c in enumerate(label):
        for y,row in enumerate(tiny[c]):
            for x,v in enumerate(row):
                if v=='1':d.point((2+k*4+x,yy+y),fill=INK)
atlas('utility.png',128,128,[('metal',0,0,metal),('rubber',32,0,rubber),('wood',64,0,wood),('pump_dial',0,36,dial),('door_back',16,36,door),('oil_cabinet',36,36,oil),('grille',64,36,grille),('price_labels',88,40,price_labels)])
def pane(w,h):
    im=Image.new('RGBA',(w,h),(137,159,191,110));d=ImageDraw.Draw(im)
    for y in range(2,h-2):
        x=(h-y)//2+2
        if x<w-2:d.line((x,y,min(x+1,w-2),y),fill=(185,195,213,130))
    d.rectangle((0,0,w-1,h-1),outline=INK);d.line((1,1,w-2,1),fill=HI);return im
left=pane(14,26);right=pane(16,26);side=pane(32,26);entry=pane(16,32)
d=ImageDraw.Draw(side);d.line((16,1,16,24),fill=INK)
d=ImageDraw.Draw(entry);d.rectangle((1,19,14,30),fill=TEAL);d.line((11,18,13,18),fill=HI)
bay=frame(52,44,IVORY);d=ImageDraw.Draw(bay)
for y in range(0,44,7):d.line((1,y,50,y),fill=MID);d.line((1,y+1,50,y+1),fill=HI)
for x in (4,16,28,40):d.rectangle((x,8,x+8,16),fill='#899fbf',outline=INK);d.line((x+1,9,x+6,9),fill='#b9c3d5')
atlas('glass.png',128,128,[('office_left',0,0,left),('office_right',16,0,right),('office_side',34,0,side),('entry',68,0,entry)])
# Opaque slatted garage door art lives with the utility panels, not glass shading.
base=Image.open(R/'textures/utility.png').resize((128,128),Image.Resampling.NEAREST);base.paste(bay,(0,78));base.resize((256,256),Image.Resampling.NEAREST).save(R/'textures/utility.png')
REG['garage_door']={'texture':'utility.png','rect_art':[0,78,52,44]}
from price_art import write_prices
write_prices(R/'textures/prices.png',1959,'0.29','0.32')
REG['prices']={'texture':'prices.png','rect_art':[0,0,16,20]}
(R/'art-regions.json').write_text(json.dumps(REG,indent=2)+'\n')
print('Period art generated at 2x2 texel scale')
