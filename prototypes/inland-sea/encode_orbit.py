"""Denoise the 84 desktop frames and encode an H.264 loop for browser review."""
import subprocess,tempfile
from pathlib import Path
from finish_renders import denoise
r=Path(__file__).resolve().parent
source=Path('/tmp/inland-orbit')
with tempfile.TemporaryDirectory() as tmp:
    dest=Path(tmp)
    for i in range(1,85):
        p=source/f'frame-{i:04d}.png'
        denoise(p,dest/p.name)
    subprocess.run(['ffmpeg','-v','error','-y','-framerate','12','-start_number','1','-i',str(dest/'frame-%04d.png'),'-frames:v','84','-vf','hqdn3d=1:1:2:2','-c:v','libx264','-threads','2','-crf','18','-pix_fmt','yuv420p','-movflags','+faststart',str(r/'orbit.mp4')],check=True)
