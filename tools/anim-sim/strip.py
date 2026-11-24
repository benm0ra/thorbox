# SPDX-License-Identifier: GPL-3.0-or-later
# Renders the rat frame by frame (every 50 ms) from sim.py's rat.json, as strip.svg: squash, bounce, pop, settle.
# Run after sim.py: python3 tools/anim-sim/strip.py && rsvg-convert strip.svg -o strip.png
import json, math
d=json.load(open('rat.json'))
R=d['R']
def rat(sq,nod,ear,tilt,tailv,key='#9DFF3C',cup='#2FD1C5'):
    o=[];A=o.append
    A('<rect x="0" y="0" width="240" height="200" fill="#E8E2D2"/><rect x="0" y="150" width="240" height="32" fill="#8D8A84"/><rect x="0" y="178" width="240" height="7" fill="#E9C21B"/><rect x="0" y="185" width="240" height="20" fill="#141417"/>')
    fx,fy=110,178; sq=max(-0.45,min(1.15,sq))
    sy=1-0.36*sq; sx=min(1.3,max(0.9,1/math.sqrt(sy)))
    bodyH=58*sy; bodyW=40*sx; hip=fy-14; shoulderY=hip-bodyH*0.92
    headY=shoulderY-16*(1-0.15*max(sq,0))+nod*3; headX=fx+6+nod*1.2
    A(f'<path d="M{fx-bodyW*0.42},{hip-6} C{fx-48},{hip+6} {fx-62},{hip-30+tailv*0.5} {fx-74},{hip-18-tailv}" stroke="#F2A0B0" stroke-width="3.2" fill="none" stroke-linecap="round"/>')
    spread=9+7*max(sq,0)
    for s in (-1,1):
        bx=fx+s*spread; A(f'<rect x="{bx-8}" y="{fy-14}" width="17" height="13" rx="4" fill="#C98E3A"/><rect x="{bx-9}" y="{fy-3}" width="19" height="4" rx="1.5" fill="#3B2A1A"/>')
    A(f'<ellipse cx="{fx}" cy="{hip-bodyH/2}" rx="{bodyW/2}" ry="{bodyH/2}" fill="{key}"/>')
    sliceY=headY-33+9*max(sq,0)-4*max(-sq,0); eo=18+10*max(sq,0)
    for s in (-1,1):
        sx_=fx+s*bodyW*0.42; sy_=shoulderY+6; ex=fx+s*(bodyW*0.42+eo); ey=(sy_+sliceY)/2+6*max(sq,0); px=fx+s*22; py=sliceY+4
        A(f'<line x1="{sx_}" y1="{sy_}" x2="{ex}" y2="{ey}" stroke="{key}" stroke-width="7" stroke-linecap="round"/><line x1="{ex}" y1="{ey}" x2="{px}" y2="{py}" stroke="#8E8C95" stroke-width="5" stroke-linecap="round"/>')
    for k,er in ((-1,9),(1,10)):
        ex=headX+(-11 if k<0 else 5); ey=headY-13+max(sq,0)*2
        A(f'<g transform="rotate({k*ear*0.9} {ex} {ey+er*0.6})"><circle cx="{ex}" cy="{ey}" r="{er}" fill="#6E6C76"/><circle cx="{ex}" cy="{ey}" r="{er-3.2}" fill="#F2A0B0"/></g>')
    A(f'<circle cx="{headX}" cy="{headY}" r="15" fill="#8E8C95"/><circle cx="{headX+8}" cy="{headY-4}" r="3" fill="#111"/>')
    A(f'<g transform="rotate({tilt} {fx} {sliceY})"><path d="M{fx-36},{sliceY+1} L{fx+30},{sliceY-10} L{fx+32},{sliceY+9} Z" fill="#F6C445"/><rect x="{fx+27}" y="{sliceY-12}" width="9" height="23" rx="3.5" fill="#C0772E"/></g>')
    return ''.join(o)
frames=list(range(0,48,3))  # 0..0.8 s every 50 ms
W=120;H=100
svg=f'<svg xmlns="http://www.w3.org/2000/svg" width="{len(frames)//2*W*2}" height="{2*H*2}" viewBox="0 0 {len(frames)//2*W} {2*H}">'
for n,i in enumerate(frames):
    x=(n%(len(frames)//2))*W; y=(n//(len(frames)//2))*H
    svg+=f'<g transform="translate({x},{y}) scale(0.5)">'+rat(R['sq'][i],R['nod'][i],R['ear'][i],R['tilt'][i],R['tail'][i])+f'<text x="6" y="16" font-size="14" font-family="monospace">{i/60*1000:.0f}ms sq{R["sq"][i]:+.2f}</text></g>'
svg+='</svg>'
open('strip.svg','w').write(svg)
