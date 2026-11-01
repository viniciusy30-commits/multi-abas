import pickle,sys,struct,numpy as np
sys.path.insert(0,'/home/claude/w')
from fbxr import *
idx=pickle.load(open('/home/claude/w/idx.pkl','rb'))
B='Assets/BoZo_StylizedModularCharacters/Outfits/'
def path(n): return '/home/claude/w/pk/up/'+idx[n]+'/asset'
def load_skinned(f):
    r=parse(f); ob=r.get('Objects'); con=r.get('Connections')
    names={k.props[0]:k.props[1].split('\x00')[0] for k in ob.kids if k.name=='Model'}
    cl={k.props[0]:k for k in ob.kids if k.name=='Deformer' and k.props[2]=='Cluster'}
    geos=[k for k in ob.kids if k.name=='Geometry' and k.get('PolygonVertexIndex') is not None]
    bone_of={}
    for c in con.kids:
        if c.props[0]=='OO' and c.props[1] in names and c.props[2] in cl: bone_of[c.props[2]]=names[c.props[1]]
    return r,ob,geos,cl,bone_of
def mesh_from_geo(g):
    V=g.get('Vertices').props[0].reshape(-1,3).astype(np.float64)
    PVI=g.get('PolygonVertexIndex').props[0]
    cv=np.where(PVI<0,~PVI,PVI)
    polys=[];cur=[]
    for ci,x in enumerate(PVI):
        cur.append(ci)
        if x<0: polys.append(cur); cur=[]
    ln=g.get('LayerElementNormal'); N=ln.get('Normals').props[0].reshape(-1,3)
    mapn=ln.get('MappingInformationType').props[0]
    ni=ln.get('NormalsIndex')
    if mapn in('ByVertice','ByVertex'): cn=N[cv]
    else: cn=N[ni.props[0]] if ni is not None and len(ni.props[0]) else N[np.arange(len(PVI))]
    lu=g.get('LayerElementUV'); UV=lu.get('UV').props[0].reshape(-1,2); UVI=lu.get('UVIndex').props[0]
    mats=None
    lm=g.get('LayerElementMaterial')
    if lm: 
        mm=lm.get('Materials'); 
        if mm is not None: mats=mm.props[0]
    return dict(V=V,corner_v=cv,polys=polys,cn=cn,cuv=UV[UVI],mats=mats)

def geo_weights(r,ob,geos,cl,bone_of):
    """para cada geometria: lista de dict nome_osso->peso por vértice"""
    con=r.get('Connections')
    skin_of_cluster={}; geo_of_skin={}
    geoids={g.props[0]:g for g in geos}
    skins={k.props[0] for k in ob.kids if k.name=='Deformer' and k.props[2]=='Skin'}
    for c in con.kids:
        if c.props[0]!='OO': continue
        a,b=c.props[1],c.props[2]
        if a in cl and b in skins: skin_of_cluster[a]=b
        if a in skins and b in geoids: geo_of_skin[a]=b
    out={}
    for g in geos:
        nv=len(g.get('Vertices').props[0])//3
        out[g.props[0]]=[dict() for _ in range(nv)]
    for cid,c in cl.items():
        nm=bone_of.get(cid); sk=skin_of_cluster.get(cid); gid=geo_of_skin.get(sk)
        if nm is None or gid is None: continue
        ix=c.get('Indexes'); wt=c.get('Weights')
        if ix is None: continue
        W=out[gid]
        for i,w in zip(ix.props[0],wt.props[0]): W[i][nm]=W[i].get(nm,0)+float(w)
    return out

def bone_index(name):
    """nome do osso do FBX -> índice (0..54) do esqueleto de 55 ossos do base.bin"""
    n=name
    fixed={'pelvis':0,'spine_01':1,'spine_02':2,'spine_03':3,'spine_04':4,'spine_05':5,'neck_01':6,'neck_02':7,'head':8,'breast_l':4,'breast_r':4}
    if n in fixed: return fixed[n]
    side=n[-2:]
    if side not in('_l','_r'): return None
    o=0 if side=='_l' else 4
    b=n[:-2]
    arm={'clavicle':9,'shoulder_twist':10,'upperarm':10,'upperarm_twist_01':10,'upperarm_twist_02':10,'lowerarm':11,'lowerarm_twist_01':11,'lowerarm_twist_02':11,
         'hand':12,'index_metacarpal':12,'middle_metacarpal':12,'ring_metacarpal':12,'pinky_metacarpal':12}
    if b in arm: return arm[b]+o
    leg={'thigh':17,'thigh_twist_01':17,'thigh_twist_02':17,'calf':18,'calf_twist_01':18,'calf_twist_02':18,'foot':19,'ball':20}
    if b in leg: return leg[b]+(0 if side=='_l' else 4)
    fing={'index':0,'middle':3,'ring':6,'pinky':9,'thumb':12}
    for f,off in fing.items():
        for k in (1,2,3):
            if b=='%s_0%d'%(f,k): return 25+off+(k-1)+(0 if side=='_l' else 15)
    return None

def skin_arrays(W, nvert):
    """W: lista de dict nome->peso (por vértice) -> bi (n,4) uint8, bw (n,4) uint8 (soma 255)"""
    bi=np.zeros((nvert,4),np.uint8); bw=np.zeros((nvert,4),np.uint8)
    for v in range(nvert):
        acc={}
        for nm,w in W[v].items():
            i=bone_index(nm)
            if i is None: i=None
            else: acc[i]=acc.get(i,0)+w
        if not acc: acc={4:1.0}
        top=sorted(acc.items(),key=lambda x:-x[1])[:4]
        s=sum(w for _,w in top)
        ws=[w/s for _,w in top]
        q=[int(round(w*255)) for w in ws]
        q[0]+=255-sum(q)
        for k,(i,_) in enumerate(top): bi[v,k]=i; bw[v,k]=q[k]
    return bi,bw
