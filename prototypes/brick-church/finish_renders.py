"""Standard offline OIDN cleanup of rendered light-sampling noise; never invents geometry."""
import sys,ctypes
from pathlib import Path
R=Path(__file__).parent
sys.path.insert(0,str(R/'vendor'))
import oidn,numpy as np
from PIL import Image
def denoise(path):
 src=np.asarray(Image.open(path).convert('RGB'),dtype=np.float32)/255
 dst=np.empty_like(src);h,w=src.shape[:2]
 dev=oidn.NewDevice();oidn.CommitDevice(dev);f=oidn.NewFilter(dev,'RT')
 oidn.SetSharedFilterImage(f,'color',src,oidn.FORMAT_FLOAT3,w,h)
 oidn.SetSharedFilterImage(f,'output',dst,oidn.FORMAT_FLOAT3,w,h)
 lib=ctypes.CDLL(str(Path(oidn.__file__).parent/'lib.linux.x64/libOpenImageDenoise.so.1.4.3'))
 lib.oidnSetFilter1b.argtypes=[ctypes.c_void_p,ctypes.c_char_p,ctypes.c_bool];lib.oidnSetFilter1b(f,b'srgb',True)
 oidn.CommitFilter(f);oidn.ExecuteFilter(f);err=oidn.GetDeviceError(dev)
 if err:raise RuntimeError(err)
 oidn.ReleaseFilter(f);oidn.ReleaseDevice(dev)
 out=R/'renders'/path.name;Image.fromarray(np.uint8(np.clip(dst,0,1)*255)).save(out);print(out)
if __name__=='__main__':
 for path in (sys.argv[1:] or sorted(Path('/tmp/church-raw').glob('*.png'))):denoise(Path(path))
