import gzip, struct, io
class Byte(int): pass
class Short(int): pass
class Int(int): pass
class Long(int): pass
class Float(float): pass
class Double(float): pass
class BArr(list): pass
class IArr(list): pass
class LArr(list): pass
class TList(list):
    def __init__(s, t=0, it=()): super().__init__(it); s.t=t
ID={Byte:1,Short:2,Int:3,Long:4,Float:5,Double:6,BArr:7,str:8,TList:9,dict:10,IArr:11,LArr:12}
def tid(v):
    for c in (Byte,Short,Int,Long,Float,Double,BArr,TList,IArr,LArr): 
        if type(v) is c: return ID[c]
    if isinstance(v,str): return 8
    if isinstance(v,dict): return 10
    if isinstance(v,bool): return 1
    raise TypeError(type(v))
def read(f):
    rb=f.read
    def s(): return rb(struct.unpack('>H',rb(2))[0]).decode('utf8','replace')
    def p(t):
        if t==1: return Byte(struct.unpack('>b',rb(1))[0])
        if t==2: return Short(struct.unpack('>h',rb(2))[0])
        if t==3: return Int(struct.unpack('>i',rb(4))[0])
        if t==4: return Long(struct.unpack('>q',rb(8))[0])
        if t==5: return Float(struct.unpack('>f',rb(4))[0])
        if t==6: return Double(struct.unpack('>d',rb(8))[0])
        if t==7: n=struct.unpack('>i',rb(4))[0]; return BArr(struct.unpack('>%db'%n,rb(n)))
        if t==8: return s()
        if t==9:
            it=rb(1)[0]; n=struct.unpack('>i',rb(4))[0]; return TList(it,[p(it) for _ in range(n)])
        if t==10:
            d={}
            while True:
                tt=rb(1)[0]
                if tt==0: return d
                k=s(); d[k]=p(tt)
        if t==11: n=struct.unpack('>i',rb(4))[0]; return IArr(struct.unpack('>%di'%n,rb(4*n)))
        if t==12: n=struct.unpack('>i',rb(4))[0]; return LArr(struct.unpack('>%dq'%n,rb(8*n)))
    t=rb(1)[0]; s(); return p(t)
def write(root):
    o=io.BytesIO(); w=o.write
    def s(x): b=x.encode('utf8'); w(struct.pack('>H',len(b))); w(b)
    def p(v,t):
        if t==1: w(struct.pack('>b',v))
        elif t==2: w(struct.pack('>h',v))
        elif t==3: w(struct.pack('>i',v))
        elif t==4: w(struct.pack('>q',v))
        elif t==5: w(struct.pack('>f',v))
        elif t==6: w(struct.pack('>d',v))
        elif t==7: w(struct.pack('>i',len(v))); w(struct.pack('>%db'%len(v),*v))
        elif t==8: s(v)
        elif t==9:
            it=v.t if len(v) or v.t else 0
            w(bytes([it])); w(struct.pack('>i',len(v)))
            for x in v: p(x,it)
        elif t==10:
            for k,x in v.items(): tt=tid(x); w(bytes([tt])); s(k); p(x,tt)
            w(b'\0')
        elif t==11: w(struct.pack('>i',len(v))); w(struct.pack('>%di'%len(v),*v))
        elif t==12: w(struct.pack('>i',len(v))); w(struct.pack('>%dq'%len(v),*v))
    w(b'\x0a'); s(''); p(root,10); return o.getvalue()
def load(path): return read(io.BytesIO(gzip.open(path).read()))
