import struct, sys
RATES=[96000,88200,64000,48000,44100,32000,24000,22050,16000,12000,11025,8000,7350]
def scan(b):
    """ADTS frames: list of (payload_start, payload_len), plus (aot, freqIdx, chan). Stops at the first thing that is not a whole frame."""
    i=0; frames=[]; cfg=None
    n=len(b)
    while i+7<=n:
        if b[i]!=0xFF or (b[i+1]&0xF6)!=0xF0: break
        prot_absent=b[i+1]&1
        aot=((b[i+2]>>6)&3)+1
        fi=(b[i+2]>>2)&15
        ch=((b[i+2]&1)<<2)|((b[i+3]>>6)&3)
        flen=((b[i+3]&3)<<11)|(b[i+4]<<3)|((b[i+5]>>5)&7)
        hdr=7 if prot_absent else 9
        if flen<hdr or i+flen>n or fi>=len(RATES): break
        c=(aot,fi,ch)
        if cfg is None: cfg=c
        elif c!=cfg: break
        frames.append((i+hdr,flen-hdr))
        i+=flen
    return frames,cfg
def box(t,*parts):
    body=b''.join(parts)
    return struct.pack('>I',8+len(body))+t+body
def full(t,ver,flags,*parts):
    return box(t,struct.pack('>I',(ver<<24)|flags),*parts)
MATRIX=struct.pack('>9I',0x10000,0,0,0,0x10000,0,0,0,0x40000000)
PER=32
def mux(b):
    frames,cfg=scan(b)
    if not frames: return None
    aot,fi,ch=cfg
    rate=RATES[fi]; n=len(frames); dur=n*1024
    total=sum(l for _,l in frames)
    bitrate=int(total*8*rate//dur)
    asc=struct.pack('>H',(aot<<11)|(fi<<7)|(ch<<3))
    dsi=b'\x05'+bytes([len(asc)])+asc
    dcd_body=bytes([0x40,0x15])+b'\x00\x00\x00'+struct.pack('>II',bitrate,bitrate)+dsi
    dcd=b'\x04'+bytes([len(dcd_body)])+dcd_body
    slc=b'\x06\x01\x02'
    es_body=struct.pack('>HB',0,0)+dcd+slc
    es=b'\x03'+bytes([len(es_body)])+es_body
    esds=full(b'esds',0,0,es)
    mp4a=box(b'mp4a',b'\x00'*6,struct.pack('>H',1),b'\x00'*8,struct.pack('>HHHH',ch,16,0,0),struct.pack('>I',rate<<16),esds)
    stsd=full(b'stsd',0,0,struct.pack('>I',1),mp4a)
    stts=full(b'stts',0,0,struct.pack('>III',1,n,1024))
    chunks=(n+PER-1)//PER
    rem=n-(chunks-1)*PER
    if rem==PER or chunks==1:
        stsc=full(b'stsc',0,0,struct.pack('>I',1),struct.pack('>III',1,rem if chunks==1 else PER,1))
    else:
        stsc=full(b'stsc',0,0,struct.pack('>I',2),struct.pack('>III',1,PER,1),struct.pack('>III',chunks,rem,1))
    stsz=full(b'stsz',0,0,struct.pack('>II',0,n),b''.join(struct.pack('>I',l) for _,l in frames))
    def moov(offsets):
        stco=full(b'stco',0,0,struct.pack('>I',len(offsets)),b''.join(struct.pack('>I',o) for o in offsets))
        stbl=box(b'stbl',stsd,stts,stsc,stsz,stco)
        dinf=box(b'dinf',full(b'dref',0,0,struct.pack('>I',1),full(b'url ',0,1)))
        minf=box(b'minf',full(b'smhd',0,0,struct.pack('>HH',0,0)),dinf,stbl)
        mdhd=full(b'mdhd',0,0,struct.pack('>IIIIHH',0,0,rate,dur,0x55C4,0))
        hdlr=full(b'hdlr',0,0,struct.pack('>I',0),b'soun',b'\x00'*12,b'SoundHandler\x00')
        mdia=box(b'mdia',mdhd,hdlr,minf)
        tkhd=full(b'tkhd',0,7,struct.pack('>IIIII',0,0,1,0,dur),b'\x00'*8,struct.pack('>HHHH',0,0,0x0100,0),MATRIX,struct.pack('>II',0,0))
        mvhd=full(b'mvhd',0,0,struct.pack('>IIII',0,0,rate,dur),struct.pack('>IH',0x10000,0x0100),b'\x00'*10,MATRIX,b'\x00'*24,struct.pack('>I',2))
        return box(b'moov',mvhd,box(b'trak',tkhd,mdia))
    ftyp=box(b'ftyp',b'M4A ',struct.pack('>I',0),b'M4A ',b'mp42',b'isom')
    head=len(ftyp)+len(moov([0]*chunks))+8
    offsets=[]; pos=head
    for k,(s,l) in enumerate(frames):
        if k%PER==0: offsets.append(pos)
        pos+=l
    out=bytearray(ftyp+moov(offsets)+struct.pack('>I',8+total)+b'mdat')
    for s,l in frames: out+=b[s:s+l]
    return bytes(out)
if __name__=='__main__':
    b=open(sys.argv[1],'rb').read()
    if len(sys.argv)>3: b=b[:int(sys.argv[3])]
    open(sys.argv[2],'wb').write(mux(b))
    fr,cfg=scan(b); print(len(fr),cfg)
