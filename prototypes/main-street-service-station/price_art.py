"""Two-grade hand-lettered price board; the simulation supplies prices and year."""
from pathlib import Path
from decimal import Decimal
from PIL import Image,ImageDraw,PngImagePlugin
FONT={'0':['111','101','101','101','111'],'1':['010','110','010','010','111'],'2':['111','001','111','100','111'],'3':['111','001','111','001','111'],'4':['101','101','111','001','001'],'5':['111','100','111','001','111'],'6':['111','100','111','101','111'],'7':['111','001','010','010','010'],'8':['111','101','111','101','111'],'9':['111','101','111','001','111'],'.':['0','0','0','0','1']}
def write_prices(path,year,regular,premium):
    if not 1950<=int(year)<=2030:raise ValueError('Year outside simulation range')
    im=Image.new('RGBA',(16,20),'#dad4cb');d=ImageDraw.Draw(im)
    for y,value in [(0,regular),(12,premium)]:
        n=Decimal(str(value))
        if not n.is_finite() or not 0<=n<=9.99:raise ValueError('Price outside sign format 0..9.99')
        value=f'{abs(n):.2f}';x=1
        for c in value:
            for j,row in enumerate(FONT[c]):
                for i,p in enumerate(row):
                    if p=='1':d.point((x+i,y+j),fill='#a14f44')
            x+=len(FONT[c][0])+1
    meta=PngImagePlugin.PngInfo();meta.add_text('simulation_year',str(year));im.resize((32,40),Image.Resampling.NEAREST).save(path,pnginfo=meta)
if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('--year',type=int,required=True);p.add_argument('--regular',required=True);p.add_argument('--premium',required=True);p.add_argument('--output',type=Path,default=Path(__file__).parent/'textures/prices.png');a=p.parse_args();write_prices(a.output,a.year,a.regular,a.premium)
