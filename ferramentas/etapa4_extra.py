#!/usr/bin/env python3
"""
Etapas 3+4 do Meu Lar: converte cabelos e roupas do pack BoZo (FBX) em peças CUP1 e monta UM atlas "extra" recolorível.
Uso: python3 etapa4_extra.py <pasta 'up' do unitypackage extraído> <assets/cute>
Pesos de osso vêm dos clusters do próprio FBX (conferido: a blusa e a saia da Mary saem idênticas, diferença 0).
Regiões de cor do atlas (extra_recolor.txt): 1 = cabelo (slots Cabelo/Pontas), 2 = parte de cima e vestidos (Blusa/Detalhe),
3 = parte de baixo (Saia/Detalhe baixo). Máscara do pack: R = cor 1, G+B = cor 2.
Saídas: p_<slot>_<id>.bin, extra.png, extra_recolor.png, extra_recolor_s.png, extra_recolor.txt, extra_pieces.txt
"""
import sys, os, re, struct, pickle, collections
import numpy as np
from PIL import Image
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from clothes_lib import *
from scipy.spatial import cKDTree

HEAD = np.array([0.0, 157.3375, -0.4809])
AW, AH, T, COLS = 2016, 1344, 224, 9
BACK = ['Buzzed','DreadBack','LongDreads','LongStreight','PonyTailLong','PonyTailShort','Punk','Scrappy','Short','ShortBob','TwinTailLong','TwinTailShort','Wild','WildTail']
FRONT = ['BangSideFlicks','Fohawk','HangingDreads','Hime','Messy','QuicklyHair','SweaptBack','SweaptDreads','WolfCut']
# (slot, id, pasta, arquivo fbx)  — Sweater e Skirt já existem (Mary)
TOPS = [('ButtonUp','Top_ButtonUp','BSMC_Outfit_Top_ButtonUp.fbx'),('ButtonUpShort','Top_ButtonUp','BSMC_Outfit_Top_ButtonUpShort.fbx'),
 ('CasualJacket','Top_CasualJacket','BSMC_Outfit_Top_CasualJacket.fbx'),('DressShirt','Top_DressShirt','BSMC_Top_DressShirt.fbx'),
 ('Henley','Top_Henley','BSMC_Outfit_Top_Henley.fbx'),('Hoodie','Top_Hoodie','BSMC_Outfit_Top_Hoodie.fbx'),
 ('OpenJacket','Top_OpenJacket','BSMC_Outfit_Top_OpenJacket.fbx'),('OpenShoulder','Top_OpenShoulder','BSMC_Outfit_Top_OpenShoulder.fbx'),
 ('OpenShoulderCrop','Top_OpenShoulder','BSMC_Outfit_Top_OpenShoulder_Crop.fbx'),('OpenShoulderStraps','Top_OpenShoulder','BSMC_Outfit_Top_OpenShoulder_Straps.fbx'),
 ('OpenShoulderStrapless','Top_OpenShoulder','BSMC_Outfit_Top_OpenShoulder_Strapsless.fbx'),('Seifuku','Top_Seifuku','BSMC_Outfit_Top_Seifuku.fbx'),
 ('SeifukuShort','Top_Seifuku','BSMC_Outfit_Top_SeifukuShort.fbx'),('Shirt','Top_Shirt','Outfit_Top_Shirt.fbx'),
 ('SuitJacket','Top_SuitJacket','BSMC_Outfit_Top_SuitJacket.fbx'),('Suspenders','Top_Suspenders','BSMC_Outfit_Top_Suspenders.fbx'),
 ('TankTop','Top_TankTop','BSMC_TankTop.fbx'),('TurtleNeck','Top_TurtleNeck','BSMC_Outfit_Top_TurtleNeck.fbx')]
BOTS = [('BaggyPantsAnkles','Bottom_BaggyPantsAnkles','BSMC_Outfit_Bottom_BaggyPantsAnkles.fbx'),('BaggyPantsLong','Bottom_BaggyPantsLong','BMSC_Outfit_Bottom_BaggyPantsLong.fbx'),
 ('BaggyPantsShort','Bottom_BaggyPantsShort','Outfit_Bottom_BaggyPantsShort.fbx'),('BigShorts','Bottom_BigShorts','BSMC_Outfit_Bottom_BigShorts.fbx'),
 ('CargoPants','Bottom_CargoPants','BSMC_Outfit_Bottom_CargoPants.fbx'),('CargoPantsShort','Bottom_CargoPantsShort','BSMC_Outfit_Bottom_CargoPantsShort.fbx'),
 ('DressPants','Bottom_DressPants','BSMC_Outfit_Bottom_DressPants.fbx'),('JeanSkirt','Bottom_JeanSkirt','BSMC_Outfit_Bottom_JeanSkirt.fbx'),
 ('Jeans','Bottom_Jeans','BSMC_Bottom_Jeans.fbx'),('LooseJeans','Bottom_LooseJeans','BSMC_Outfit_Bottom_LooseJeans.fbx'),
 ('PleatedSkirt','Bottom_PleatedSkirt','BSMC_Bottom_PleatedSkirt.fbx'),('ShortShorts','Bottom_ShortShorts','BSMC_Outfit_Bottom_ShortShorts.fbx'),
 ('Shorts','Bottom_Shorts','BSMC_Outfit_Bottom_Shorts.fbx'),('StripedPants','Bottom_StripedPants','BMSC_Outfit_Bottom_StripedPants.fbx'),
 ('YogaPants','Bottom_YogaPants','BMSC_Outfit_Bottom_YogaPants.fbx')]
OVERS = [('Overall','Overall','BSMC_Outfit_Overall_Overall.fbx'),('SmartDress','SmartDress','BSMC_Outfit_Overall_SmartDress.fbx'),('Sundress','Sundress','BSMC_Outfit_Overall_Sundress.fbx')]
REFS = {1:((112,76,50),(160,116,74)), 2:((200,200,206),(112,112,122)), 3:((120,128,150),(70,74,90))}
SLOTS = {1:(2,3), 2:(4,5), 3:(6,9)}
NAMES = {1:'cabelo', 2:'cima', 3:'baixo'}

def find_fbx(slotdir, folder, fname):
    for p in idx:
        if p.endswith('/Base/%s/%s'%(slotdir, folder+'/'+fname)) or p.endswith('/Outfit_%s/%s'%(folder, fname)) and '/Base/%s/'%slotdir in p: return p
    for p in idx:
        if '/Base/%s/'%slotdir in p and p.endswith('/'+fname): return p
    raise KeyError((slotdir, folder, fname))

def tex_in(fbxpath):
    folder = os.path.dirname(fbxpath)
    ds = [p for p in idx if os.path.dirname(p) == folder and p.lower().endswith('_d.png')]
    assert len(ds) == 1, (folder, ds)
    return ds[0]

def convert(m, W, tile, HEADOFF):
    x0, y0 = tile
    V = m['V']; cv = m['corner_v']
    uv = np.clip(m['cuv'], 0.0, 1.0)
    tris = []
    for poly in m['polys']:
        for k in range(1, len(poly)-1): tris.append((poly[0], poly[k], poly[k+1]))
    key = {}; verts = []; ix = []
    for tri in tris:
        for c in tri:
            kk = (int(cv[c]), round(float(uv[c][0]), 5), round(float(uv[c][1]), 5), tuple(np.round(m['cn'][c], 4)))
            if kk not in key: key[kk] = len(verts); verts.append(c)
            ix.append(key[kk])
    verts = np.array(verts); ix = np.array(ix, np.uint16)
    pos = V[cv[verts]] + HEADOFF
    nor = m['cn'][verts].astype(np.float64); nor /= np.maximum(np.linalg.norm(nor, axis=1, keepdims=True), 1e-9)
    u = (x0 + 1 + uv[verts][:, 0]*(T-2)) / AW; v = (y0 + 1 + (1-uv[verts][:, 1])*(T-2)) / AH
    return pos, nor, np.stack([u, v], 1), ix, cv[verts]

REC = np.dtype([('pos','<f4',3),('nor','<f4',3),('uv','<f4',2),('col','u1',4),('bi','u1',4),('bw','u1',4)])
def write_cup1(path_, pos, nor, uv, ix, bi, bw, hide=None):
    n = len(pos)
    r = np.zeros(n, REC); r['pos'] = pos; r['nor'] = nor; r['uv'] = uv; r['col'] = 255; r['bi'] = bi; r['bw'] = bw
    hide = np.zeros(0, np.uint16) if hide is None else np.asarray(hide, np.uint16)
    flags = 1 if len(hide) else 0
    with open(path_, 'wb') as f:
        f.write(b'CUP1' + struct.pack('<3i', n, len(ix), flags)); f.write(r.tobytes()); f.write(ix.astype('<u2').tobytes())
        if flags: f.write(struct.pack('<i', len(hide))); f.write(hide.astype('<u2').tobytes())

def hair_weights(pos):
    t = np.clip((150.0-pos[:, 1])/10.0, 0, 1)
    bi = np.zeros((len(pos), 4), np.uint8); bw = np.zeros((len(pos), 4), np.uint8)
    bi[:, 0] = 8; bi[:, 1] = 4
    w1 = np.round(t*255).astype(np.uint8); bw[:, 0] = 255-w1; bw[:, 1] = w1
    return bi, bw

BASE_BIN = '/home/claude/proj/codigo/app/src/main/assets/cute/base.bin'
HIDE_BONES = [0, 3, 4, 6, 9, 10, 11, 13, 14, 15, 17, 18, 21, 22]   # tronco, braços, pernas (não mãos, pés nem cabeça)
HIDE_DIST = 1.5

def load_body():
    b = open(BASE_BIN, 'rb').read(); nv, ni, nb, nc = struct.unpack_from('<4i', b, 4)
    v = np.frombuffer(b, REC, nv, 20); t = np.frombuffer(b, '<u2', ni, 20+nv*44).reshape(-1, 3).astype(int)
    return v, t

def samples(pos, ix, nor, step=0.6):
    P = []; N = []
    tr = ix.reshape(-1, 3).astype(int)
    for a, b, c in tr:
        pa, pb, pc = pos[a], pos[b], pos[c]
        k = int(np.ceil(max(np.linalg.norm(pb-pa), np.linalg.norm(pc-pb), np.linalg.norm(pa-pc))/step)); k = max(1, min(k, 12))
        for i in range(k+1):
            for j in range(k+1-i):
                u, v_ = i/k, j/k; w = 1-u-v_
                P.append(pa*w+pb*u+pc*v_); N.append(nor[a]*w+nor[b]*u+nor[c]*v_)
    return np.array(P), np.array(N)

def hide_list(body, pos, nor, ix):
    v, t = body
    bp = v['pos'].astype(float); bn = v['nor'].astype(float)
    dom = v['bi'][np.arange(len(v)), np.argmax(v['bw'], 1)]
    allowed = np.isin(dom, HIDE_BONES)
    P, N = samples(pos, ix, nor)
    tree = cKDTree(P)
    d, j = tree.query(bp)
    cov = allowed & (d < HIDE_DIST) & (np.einsum('ij,ij->i', N[j], bn) > 0.2)
    sel = np.where(cov[t].all(1))[0]
    return sel.astype(np.uint16)

def underwear(body, out, tile):
    v, t = body
    P = v['pos'].astype(float); Nn = v['nor'].astype(float)
    dom = v['bi'][np.arange(len(v)), np.argmax(v['bw'], 1)]
    x, y = P[:, 0], P[:, 1]
    preds = {'bottom': np.isin(dom, [0, 17, 21]) & (y <= 101.5) & (y >= 90.0),
             'top': np.isin(dom, [3, 4]) & (y >= 122.5) & (y <= 135.5)}
    x0, y0 = tile
    res = []
    for slot, ok in preds.items():
        sel = np.where(ok[t].all(1))[0]
        used = np.unique(t[sel]); remap = -np.ones(len(v), int); remap[used] = np.arange(len(used))
        nv = v[used].copy()
        nn = Nn[used]/np.maximum(np.linalg.norm(Nn[used], axis=1, keepdims=True), 1e-9)
        nv['pos'] = (P[used] + 0.35*nn).astype(np.float32)
        nv['uv'] = ((x0+T/2)/AW, (y0+T/2)/AH); nv['col'] = 255
        ix = remap[t[sel]].astype(np.uint16).reshape(-1)
        fn = 'p_%s_underwear.bin' % slot
        with open(os.path.join(out, fn), 'wb') as f:
            f.write(b'CUP1' + struct.pack('<3i', len(nv), len(ix), 0)); f.write(nv.tobytes()); f.write(ix.astype('<u2').tobytes())
        res.append('%s|Underwear|%s|extra.png' % (slot, fn)); print('underwear', slot, len(nv), len(ix)//3)
    return res

def main(out):
    body = load_body()
    atlas = np.zeros((AH, AW, 3)); a0 = np.zeros((AH, AW)); a1 = np.zeros((AH, AW)); sh = np.zeros((AH, AW)); rid = np.zeros((AH, AW), np.uint8)
    texmap = {}
    def tile_for(tp, region):
        if tp in texmap: return texmap[tp][0]
        n = len(texmap); assert n < COLS*(AH//T)
        x0, y0 = (n % COLS)*T, (n//COLS)*T
        texmap[tp] = ((x0, y0), region)
        im = Image.open(path(tp)).convert('RGB').resize((T, T), Image.LANCZOS)
        M = np.asarray(im).astype(float)/255
        R, G, Bc = M[..., 0], M[..., 1], M[..., 2]
        tot = R+G+Bc; w1 = np.clip((G+Bc)/np.maximum(tot, 1e-4), 0, 1); w1 = np.where(tot > 0.03, w1, 0.0); w0 = 1-w1
        shade = np.clip(0.35+0.75*M.max(-1), 0.3, 1.1)
        c0 = np.array(REFS[region][0], float); c1 = np.array(REFS[region][1], float)
        ref = w0[..., None]*c0 + w1[..., None]*c1
        sl = (slice(y0, y0+T), slice(x0, x0+T))
        atlas[sl] = np.clip(ref*shade[..., None], 0, 255); a0[sl] = w0; a1[sl] = w1; sh[sl] = shade; rid[sl] = region
        return (x0, y0)
    man = []; info = []
    jobs = [('hairBack','HairBack',p,None,1) for p in BACK] + [('hairFront','HairFront',p,None,1) for p in FRONT]
    jobs += [('top','Top',a,(b,c),2) for a,b,c in TOPS] + [('bottom','Bottom',a,(b,c),3) for a,b,c in BOTS] + [('overall','Overall',a,(b,c),2) for a,b,c in OVERS]
    for slot, sdir, pid, fb, region in jobs:
        if slot in ('hairBack','hairFront'):
            suf = 'BSMC_Outfit_%s_%s.fbx' % (sdir, pid)
            cands = [p for p in idx if p.endswith('.fbx') and '/Base/%s/' % sdir in p and os.path.basename(p).lower().endswith('_'+pid.lower()+'.fbx')]
            assert len(cands) == 1, (slot, pid, cands); fp = cands[0]
        else:
            folder, fname = fb
            cands = [p for p in idx if '/Base/%s/' % sdir in p and p.endswith('/'+fname)]
            assert len(cands) == 1, (slot, pid, cands); fp = cands[0]
        tp = tex_in(fp); tile = tile_for(tp, region)
        if slot in ('hairBack','hairFront'):
            from fbxmesh import load_mesh
            m = load_mesh(path(fp)); off = HEAD
            pos, nor, uvv, ix, vsrc = convert(m, None, tile, off)
            bi, bw = hair_weights(pos)
        else:
            r, ob, geos, cl, bone_of = load_skinned(path(fp)); GW = geo_weights(r, ob, geos, cl, bone_of)
            g = geos[0]; m = mesh_from_geo(g); skb, skw = skin_arrays(GW[g.props[0]], len(m['V']))
            pos, nor, uvv, ix, vsrc = convert(m, None, tile, 0.0)
            bi, bw = skb[vsrc], skw[vsrc]
        fn = 'p_%s_%s.bin' % (slot.lower(), pid.lower())
        hide = hide_list(body, pos, nor, ix) if slot in ('top', 'bottom', 'overall') else None
        write_cup1(os.path.join(out, fn), pos, nor, uvv, ix, bi, bw, hide)
        man.append('%s|%s|%s|extra.png' % (slot, pid, fn))
        info.append((slot, pid, len(pos), len(ix)//3))
        if hide is not None: print('   oculta', len(hide), 'triângulos do corpo')
        print(slot, pid, len(pos), 'v', len(ix)//3, 'tris', pos.min(0).round(1), pos.max(0).round(1))
    # tile sólido (roupa de baixo etc.): região 0, cor fixa
    n = len(texmap); x0, y0 = (n % COLS)*T, (n//COLS)*T
    atlas[y0:y0+T, x0:x0+T] = (222, 214, 220); rid[y0:y0+T, x0:x0+T] = 0
    open(os.path.join(out, 'solid_tile.txt'), 'w').write('%d %d %d\n' % (x0, y0, T))
    man += underwear(body, out, (x0, y0))
    unused = rid == 0; 
    # fora dos tiles usados: cinza neutro
    mask_used = np.zeros((AH, AW), bool)
    for (xy, reg) in texmap.values(): mask_used[xy[1]:xy[1]+T, xy[0]:xy[0]+T] = True
    mask_used[y0:y0+T, x0:x0+T] = True
    atlas[~mask_used] = (128, 128, 128)
    Image.fromarray(np.round(atlas).astype(np.uint8)).save(os.path.join(out, 'extra.png'), optimize=True)
    img = np.zeros((AH, AW, 3), np.uint8); img[..., 0] = np.round(a0*255); img[..., 1] = np.round(a1*255); img[..., 2] = rid
    Image.fromarray(img).save(os.path.join(out, 'extra_recolor.png'), optimize=True)
    g_ = np.round(np.clip(sh, 0, 2)/2*255).astype(np.uint8)
    Image.fromarray(np.stack([g_]*3, -1)).save(os.path.join(out, 'extra_recolor_s.png'), optimize=True)
    L = ['# id|nome|slot0|slot1|ref0 (sRGB 0..255)|ref1']
    for k in (1, 2, 3): L.append('%d|%s|%d|%d|%s|%s' % (k, NAMES[k], SLOTS[k][0], SLOTS[k][1], ' '.join('%.1f' % x for x in REFS[k][0]), ' '.join('%.1f' % x for x in REFS[k][1])))
    open(os.path.join(out, 'extra_recolor.txt'), 'w').write('\n'.join(L) + '\n')
    open(os.path.join(out, 'extra_pieces.txt'), 'w').write('\n'.join(man) + '\n')
    print(len(texmap), 'texturas;', len(man), 'peças')
if __name__ == '__main__':
    pickle_idx = None
    main(sys.argv[1])
