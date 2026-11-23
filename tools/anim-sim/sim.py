# SPDX-License-Identifier: GPL-3.0-or-later
# Offline motion check for the three characters: the same springs as AnimKit.kt, fed a 90 BPM boom-bap bar
# (kick 1, 2&, 3 · snare 2, 4 · 8th hats) and the DUCK envelope (BOOM BAP POCKET). Plots every joint over
# time (curves.png) so timing and amplitudes can be tuned before a build. Run: python3 tools/anim-sim/sim.py
# Keep the constants in step with DuckScreen.kt / Drummer.kt / BiscuitCat.kt.
import math, numpy as np, matplotlib
matplotlib.use('Agg'); import matplotlib.pyplot as plt
class Spring:
    def __init__(s,f,z): s.f=f; s.z=z; s.x=0.; s.v=0.
    def step(s,t,dt):
        w=2*math.pi*s.f; n=max(1,math.ceil(dt/0.004)); h=dt/n
        for _ in range(n):
            a=-w*w*(s.x-t)-2*s.z*w*s.v; s.v+=a*h; s.x+=s.v*h
        return s.x
    def kick(s,i): s.v+=i
FPS=60; dt=1/FPS; BPM=90; spb=60/BPM; T=4*spb  # one bar
t=np.arange(0,T*2,dt)
# boom bap: kick on 1, and-of-2 ("1 . . 2&"), 3 ; snare on 2,4; hats 8ths
kicks=[0,1.5,2.0]; snares=[1,3]; hats=[i*0.5 for i in range(8)]
def evs(lst): return sorted([ (b+bar*4)*spb for bar in range(2) for b in lst])
K=evs(kicks); S=evs(snares); H=evs(hats)
# ---- ducker GR (BOOM BAP POCKET: thr -28, ratio 6, attack 2ms, release 160ms, mix .8) with a kick-shaped key
sr=48000
def gr_curve():
    out=[]; lvl=0; gr=0; aC=1-math.exp(-1/(0.002*sr)); rC=1-math.exp(-1/(0.160*sr)); fall=math.exp(-1/(0.005*sr))
    ks=np.array(K)
    for i in range(len(t)):
        # simulate per frame with substeps of 1ms
        for j in range(16):
            tt=t[i]+j/ (16*FPS)
            prev=ks[ks<=tt]; key=0.0
            if len(prev): d=tt-prev[-1]; key=0.9*math.exp(-d/0.09)
            lvl=max(key,lvl*fall**(sr/(16*FPS)))
            db=20*math.log10(lvl+1e-9); over=db+28; slope=1-1/6
            tgt=0 if 2*over<=-6 else (slope*(over+3)**2/12 if 2*over<6 else slope*over)
            c=(aC if tgt>gr else rC); c=1-(1-c)**(sr/(16*FPS)); gr+= (tgt-gr)*c
        out.append(gr*0.8)
    return np.array(out)
GR=gr_curve()
# rat
body=Spring(6.5,.34); pop=Spring(3.5,.35); head=Spring(7,.45); ears=Spring(4.5,.25); tail=Spring(2.4,.3); slic=Spring(3.2,.18)
R={k:[] for k in 'target sq nod ear tail tilt brace'.split()}
flip=False; trigd=set(); peak=0; popped=True
for i,tt in enumerate(t):
    for k in K:
        if k<=tt and k not in trigd: trigd.add(k); slic.kick(140 if flip else -140); flip=not flip; ears.kick(80); peak=0; popped=False
    nxt=[k-tt for k in K if k>tt]; toGo=min(nxt) if nxt else 9
    brace= (lambda u: u*u*(3-2*u))(1-toGo/0.12) if toGo<=0.12 else 0
    beat=(tt/spb)%1; gr=GR[i]
    tgt=min(gr/24,1.1)+0.12*brace
    peak=max(peak,tgt)
    if not popped and peak>0.15 and tgt<0.35*peak: pop.kick(-18*peak); popped=True
    sq=body.step(tgt,dt)+pop.step(0,dt); nodc=math.exp(-beat*spb*9)*(1-0.6*min(gr/24,1)); nod=head.step(nodc,dt)
    R['target'].append(tgt); R['sq'].append(sq); R['nod'].append(nod); R['ear'].append(ears.step(sq*14,dt)); R['tail'].append(tail.step(nod*8+sq*10,dt)); R['tilt'].append(slic.step(0,dt)); R['brace'].append(brace)
# dog
bodyS=Spring(4.5,.42); headS=Spring(5.5,.4); jowlS=Spring(2.6,.2); earS=Spring(3.2,.22); capS=Spring(6,.35); chainS=Spring(1.3,.15)
D={k:[] for k in 'body head jowl ear cap chain'.split()}
done=set(); cf=False
for i,tt in enumerate(t):
    for k in K:
        if k<=tt and ('k',k) not in done: done.add(('k',k)); bodyS.kick(110); chainS.kick(40 if cf else -40); cf=not cf
    for s_ in S:
        if s_<=tt and ('s',s_) not in done: done.add(('s',s_)); headS.kick(260)
    for h in H:
        if h<=tt and ('h',h) not in done: done.add(('h',h)); headS.kick(30)
    beat=(tt/spb)%1; base=abs(math.sin(math.pi*beat))*1.5
    b=bodyS.step(0,dt); hn=headS.step(0,dt); tot=b+base+hn
    D['body'].append(b); D['head'].append(base+hn); D['jowl'].append(max(-6,min(6,jowlS.step(tot,dt)-tot))); D['ear'].append(max(-7,min(7,earS.step(tot,dt)-tot)))
    D['cap'].append(max(0,min(5,tot-capS.step(tot,dt)))); D['chain'].append(chainS.step(0,dt))
# cat: press curve approx (on-beat pulse), lean, head, ears, tail
def press(ph,atk=.2,rel=.3):
    # approx: rises quickly at 0, holds, releases
    return max(0, min(1, (ph/ max(0.05,atk*0.5)) if ph<atk*0.5 else 1-max(0,(ph-0.5))/max(0.05,rel)))
lean=Spring(4.5,.45); hd=Spring(5,.38); er=Spring(4,.35); tl=Spring(1.8,.3)
C={k:[] for k in 'dl lean head ear tail'.split()}
for tt in t:
    beat=(tt/spb)%1; dl=press(beat); dr=press((beat+.5)%1)
    L=lean.step((dl*.6+dr*.4)*2.6*1.2,dt); H_=hd.step(L*1.3,dt); E=er.step(hd.v*-0.12,dt); TL=tl.step(L*5+abs(math.sin(math.pi*beat))*3,dt)
    for k,v in zip(C,[dl,L,H_,E,TL]): C[k].append(v)
fig,ax=plt.subplots(3,1,figsize=(14,11),sharex=True)
for k in R: ax[0].plot(t,R[k],label='rat '+k)
ax[0].plot(t,GR/24,'k--',label='GR/24'); ax[0].legend(ncol=4,fontsize=8)
for k in D: ax[1].plot(t,D[k],label='dog '+k)
ax[1].legend(ncol=6,fontsize=8)
for k in C: ax[2].plot(t,C[k],label='cat '+k)
ax[2].legend(ncol=5,fontsize=8)
for a in ax:
    for k in K: a.axvline(k,color='r',alpha=.25)
    for s_ in S: a.axvline(s_,color='b',alpha=.25)
plt.tight_layout(); plt.savefig('curves.png',dpi=70)
import json; json.dump({'t':t.tolist(),'R':R,'GR':GR.tolist()},open('rat.json','w'))
print('max sq',max(R['sq']),'min sq',min(R['sq']),'max tilt',max(map(abs,R['tilt'])),'max ear',max(map(abs,R['ear'])))
#print('dog jowl',max(map(abs,D['jowl'])),'ear',max(map(abs,D['ear'])),'cap',max(D['cap']),'chain',max(map(abs,D['chain'])),'head',max(D['head']),min(D['head']),'body',max(D['body']),min(D['body']))
#print('cat lean',max(C['lean']),'head',max(C['head']),'ear',max(map(abs,C['ear'])),'tail',max(map(abs,C['tail'])))
