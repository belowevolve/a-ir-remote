#!/usr/bin/env python3
"""Render the Android remote mark as a multi-resolution Windows ICO (stdlib only)."""
from pathlib import Path
import struct

def rounded(x,y,l,t,r,b,radius):
    dx=max(l+radius-x,0,x-(r-radius));dy=max(t+radius-y,0,y-(b-radius))
    return dx*dx+dy*dy <= radius*radius and l<=x<=r and t<=y<=b

def pixel(x,y):
    if not rounded(x,y,2,2,106,106,24):return (0,0,0,0)
    color=(49,83,24,255)
    if rounded(x,y,27,16.5,81,91.5,13.5):color=(188,237,145,255)
    if rounded(x,y,51,27,57,54,3) or rounded(x,y,40.5,37.5,67.5,43.5,3):color=(49,83,24,255)
    if rounded(x,y,39,64.5,48,73.5,3) or rounded(x,y,60,64.5,69,73.5,3):color=(49,83,24,255)
    return color

images=[]
for size in (16,24,32,48,64,128,256):
    data=bytearray(); mask=bytearray()
    for y in reversed(range(size)):
        row=bytearray((size+31)//32*4)
        for x in range(size):
            samples=[pixel((x+(sx+.5)/4)*108/size,(y+(sy+.5)/4)*108/size) for sy in range(4) for sx in range(4)]
            alpha=sum(p[3] for p in samples)
            rgb=[round(sum(p[c]*p[3] for p in samples)/alpha) if alpha else 0 for c in range(3)]
            a=round(alpha/16)
            data.extend([rgb[2],rgb[1],rgb[0],a])
            if a==0:row[x//8]|=128>>(x%8)
        mask.extend(row)
    dib=struct.pack('<IiiHHIIiiII',40,size,size*2,1,32,0,len(data),0,0,0,0)+data+mask
    images.append((size,dib))
offset=6+16*len(images)
out=bytearray(struct.pack('<HHH',0,1,len(images)))
for size,dib in images:
    out.extend(struct.pack('<BBBBHHII',size%256,size%256,0,0,1,32,len(dib),offset));offset+=len(dib)
for _,dib in images:out.extend(dib)
Path(__file__).resolve().parents[1].joinpath('assets/air-remote.ico').write_bytes(out)
