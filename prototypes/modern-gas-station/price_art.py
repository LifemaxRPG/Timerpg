"""Runtime-facing price texture writer. No geometry or static sign artwork changes."""
from pathlib import Path
from decimal import Decimal, InvalidOperation
from PIL import Image, ImageDraw, PngImagePlugin
from pixel_font import FONT

ART_SIZE=(24,42)
ROWS=[{'grade':'regular','y_art':0},{'grade':'plus','y_art':14},{'grade':'diesel','y_art':28}]
NARROW={
'0':['111','101','101','101','101','101','111'],
'1':['010','110','010','010','010','010','111'],
'2':['111','001','001','111','100','100','111'],
'3':['111','001','001','111','001','001','111'],
'4':['101','101','101','111','001','001','001'],
'5':['111','100','100','111','001','001','111'],
'6':['111','100','100','111','101','101','111'],
'7':['111','001','001','010','010','010','010'],
'8':['111','101','101','111','101','101','111'],
'9':['111','101','101','111','001','001','111'],
'.':['0','0','0','0','0','1','1']}

def write_prices(path,year,regular,plus,diesel):
    if not 1950<=int(year)<=2030:raise ValueError('Expected simulation year 1950..2030')
    im=Image.new('RGBA',ART_SIZE,'#4b5268');draw=ImageDraw.Draw(im)
    for row,value in zip(ROWS,[regular,plus,diesel]):
        try:number=Decimal(str(value))
        except InvalidOperation:raise ValueError('Prices must be finite numbers')
        if not number.is_finite() or not 0<=number<=Decimal('999.99'):raise ValueError('Price outside 0..999.99')
        if number==0:number=number.copy_abs()
        value=f'{number:.2f}'
        glyphs=FONT if len(value)<=4 else NARROW
        width=sum(len(glyphs[c][0])+1 for c in value)-1
        x=ART_SIZE[0]-width-1;y=row['y_art']
        for c in value:
            for j,line in enumerate(glyphs[c]):
                for k,pixel in enumerate(line):
                    if pixel=='1':draw.point((x+k,y+j),fill='#e0c389')
            x+=len(glyphs[c][0])+1
    metadata=PngImagePlugin.PngInfo();metadata.add_text('simulation_year',str(year))
    im.resize((48,84),Image.Resampling.NEAREST).save(path,pnginfo=metadata)

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--year',type=int,required=True)
    for name in ('regular','plus','diesel'):p.add_argument('--'+name,required=True)
    p.add_argument('--output',type=Path,default=Path(__file__).parent/'textures/prices.png')
    a=p.parse_args();write_prices(a.output,a.year,a.regular,a.plus,a.diesel)
