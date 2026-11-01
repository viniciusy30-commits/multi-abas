# Meu Lar — Plano da personalização modular (BoZo) em etapas

Documento de retomada. Quem for continuar (outra conta/conversa) não tem memória nada do que foi feito: leia tudo antes de começar.
Regra de trabalho: **uma etapa por vez**, entregar, o usuário testa no celular, só então a próxima.

---

## 1. Contexto do projeto

- App Android (Kotlin + OpenGL ES 2.0 próprio, sem Unity), repositório GitHub `meu-lar`. Usuário: Vinicius (fala português).
- Entrega: o usuário sobe um **zip na pasta `_incoming/`** do GitHub. O workflow `unpack-and-commit.yml` extrai o zip **na raiz do repo** (`unzip -o`), apaga o zip e commita. Portanto o zip deve conter caminhos a partir da raiz (`app/src/main/...`), sem pasta externa. O build do APK roda em seguida (GitHub Actions).
- O ambiente de trabalho do Claude **não compila Gradle**: nada é testado no celular pelo Claude. Se o build falhar, corrigir só o erro apontado.
- Personagem atual: **Mary** (BoZo Stylized Modular Characters), montada com peças fixas e **assada** em `app/src/main/assets/cute/f_1.bin` (+ `textures.png` 2048x1024). Carregada por `CuteChar.kt` (pele na CPU por quadro, 55 ossos).
- Arquivos-chave: `CuteChar.kt`, `Renderer3D.kt` (drawPlayer, criador, casa), `Creator.kt` (hoje só botão "Pronto"), `Avatar.kt` (encode/decode salvo — manter compatível), `PLANO-VIVO.md` (histórico).
- **ANIMAÇÕES IDLE, WALK E JUMP ESTÃO FINALIZADAS (v3) E APROVADAS PELO USUÁRIO. NÃO MEXER NELAS EM NENHUMA ETAPA.** Walk com pés pousando para dentro, braços com balanço senoidal e braços um pouco afastados no idle, pés plantados no idle. Qualquer peça nova deve apenas seguir essas animações (mesmo esqueleto e mesmas matrizes de osso). Não regenerar, não "melhorar" e não trocar pelas animações do pack. Ferramentas usadas (só referência histórica): `ferramentas/fixanim2.py`, `parse.py`, `joints.py`.

## 2. Formato `f_1.bin` (CUT1, little-endian)

```
"CUT1" | nv,ni,nb,nc (int32)
por vértice (nv): pos(3f) normal(3f) uv(2f) cor(4 bytes) boneIdx(4 bytes) pesos(4 bytes /255)
índices: ni * uint16
por clip (nc): duração(float) nFrames(int32) e nFrames*nb*12 floats = matriz de pele 3x4 por osso por quadro
```
- Clips: 0 = Idle (3 s, 73 quadros), 1 = Walk (0,9 s, 37 quadros), 2 = Jump (0,71 s, 18 quadros).
- A matriz já inclui a escala 0,008 (modelo ≈ 1,3 m). A malha de repouso está em unidades do FBX (~162 de altura). Frente do personagem = +Z, lateral = X.
- 55 ossos. Índices úteis: 0 pelve, 3/4 coluna/peito, 8 cabeça, 9–12 clavícula/braço/antebraço/mão (lado +X), 13–16 idem (lado −X), 17–20 coxa/canela/pé/dedos (+X), 21–24 idem (−X), 25–39 dedos da mão +X, 40–54 dedos da mão −X.
- Posições das articulações (repouso, unid. FBX): quadril (±9,29; 96,1; 3,8), joelho y≈53,6, tornozelo y≈11, ombro (±13,3; 142,5; −2,8), cotovelo (±29,1; 121,5; −3,0).

## 3. Conteúdo do pack (arquivo `BoZo_Stylized_Modular_Characters_-_Base_Pack_v2_1_5_.rar`)

Como abrir aqui: **não há unrar/7z, nem rede**. Funciona usar `libarchive.so.13` via `ctypes` (script `rx.py`: archive_read_open_filename → next_header → read_data). Dentro há um `.unitypackage` de 132 MB (tar.gz com pastas por GUID contendo `pathname`, `asset`, `asset.meta`). Os FBX são binários (precisa de leitor próprio; o da conta anterior foi `fbx.py`).

Base comum: `Body_BasicBody.fbx` + `BodyRig.fbx` (esqueleto de 55 ossos), cabeças `BSMC_Head`, `BSMC_Head_02`, `Head_SharpHead` (+ jovem), olhos `Eyes_BasicEyes` (íris + pupila), decals (meias tornozelo/joelho/comum/3-4, meia-calça), padrões de roupa de baixo (neutra, boxer, sutiã/calcinha).

Peças (95): 
- HairBack 15: Buzzed, DreadBack, Flare, LongDreads, LongStreight, PonyTailLong, PonyTailShort, Punk, Scrappy, Short, ShortBob, TwinTailLong, TwinTailShort, Wild, WildTail
- HairFront 10: BangSideFlicks, Fohawk, HangingDreads, Hime, LongSweeps, Messy, QuicklyHair, SweaptBack, SweaptDreads, WolfCut
- LowerFace 6: BushyBeard, ChinGoatee, FullBeard, Goatee, Mustashe, MustasheChin
- UpperFace (óculos) 4: RoundGlasses, RoundShaded, ShadedGlasses, SimpleGlasses
- Hat 5: Ballcap, Beanie, Bucket, Fedora, Sunhat
- HeadAcc 2: AlienAttena, Horns
- Top 19: ButtonUp, ButtonUpShort, CasualJacket, DressShirt, Henley, Hoodie, OpenJacket, OpenShoulder, OpenShoulder_Crop, OpenShoulder_Straps, OpenShoulder_Strapsless, Seifuku, SeifukuShort, Shirt, SuitJacket, Suspenders, Sweater, TankTop, TurtleNeck
- Bottom 16: BaggyPantsAnkles, BaggyPantsLong, BaggyPantsShort, BigShorts, CargoPants, CargoPantsShort, DressPants, JeanSkirt, Jeans, LooseJeans, PleatedSkirt, ShortShorts, Shorts, Skirt, StripedPants, YogaPants
- Overall 3: Overall, SmartDress, Sundress
- Feet 12: BigBoots, ComfyShoes, CoolShoes, OfficeShoes, Sandals, SchoolShoes, ShortBoots, ShowerSandal, SlipOns, SportsShoes, StreetShoes, WorkBoots
- Gloves 3: ArmWarmers, Fingerless, WorkGloves
- Categorias definidas mas sem peças: Neck, Waist, FPS Arms.

Customização do pack original:
- Cada peça tem **9 cores** (máscaras recoloridas). Ex.: suéter da Mary usa 2 cores, cabelo usa 2.
- Corpo (3): BodyType, Chest, Weight.
- Rosto (16 blendshapes): BodyType, Weight, Squareness, LashLength, BrowsThickness, NoseBridgeCurve, NoseWidth, NoseTiltDown, NoseTiltUp, MouthWide, MouthThin, EyesOuterCornersLow, EyesOuterCornersHigh, EyesSquare, EarsElf, EarAngle.
- Ajustes por osso (10): root (altura), spine_04 (peito), clavicle_l (ombro), head, eyeRoot_l, calf_twist_01_l, thigh_twist_01_l, upperarm_twist_02_l, lowerarm_twist_02_l, shoulder_twist_l.
- 13 personagens prontos (presets) em `CustomCharacters/Resources/Base/*.asset` (Ben, Chloe, Dave, Galvon, Javi, Jennifer, John, Mary, Sally, Tristian, Yuki, Zulle, padrão F/M): servem de exemplo de combinação de peças e cores.
- Animações do pack (Idle/Walk F e M, Dizzy, ajuste de salto alto): **NÃO usar**, o jogo já tem Idle/Walk/Jump próprios e aprovados. Só as 10 expressões + blink podem interessar (Etapa 8).
- Licença: pack veio de site de redistribuição (GfxFather). Para publicar o jogo é preciso a licença original do BoZo.

## 4. Divisão em etapas

**Etapa 0 — Reconhecimento (FEITA).** Pack aberto, inventário e este plano.

> Status: Etapas 1 e 2 entregues e testadas. Etapas 3 (cabelos) e 4 (roupas) entregues, aguardando teste. Detalhes em `PLANO-VIVO.md`.

**Etapa 1 — Infraestrutura modular com 1 peça de teste. (FEITA, aguardando teste no celular — ver seção 7)**
- Definir o formato novo: um arquivo `base` (corpo + cabeça + olhos + animações, 55 ossos) e arquivos `peça` (só malha, pesos, UVs, para o mesmo esqueleto).
- `CuteChar.kt` passa a pele do conjunto de peças com as mesmas matrizes de osso (uma lista de peças ativas).
- Converter só: corpo base + Sweater + Skirt + um cabelo (reproduzir a Mary em pedaços). Critério: fica igual ao personagem atual.

**Etapa 2 — Cores.**
- Texturas viram máscaras por peça; shader/CPU aplica as cores escolhidas (até 9 slots, começar com 2–3).
- Escolha de cor de pele, olhos, cabelo e roupa; salvar no `Avatar`.

**Etapa 3 — Cabelos (25 peças: 15 traseiros + 10 franjas).**
- Conversão em lote + regra de combinação traseiro + frente + chapéu (chapéu esconde partes do cabelo).

**Etapa 4 — Roupas (19 blusas, 16 calças/saias, 3 vestidos).**
- Esconder partes do corpo coberto (sem atravessar a malha), vestido substitui blusa + calça.
- Roupa de baixo padrão quando não há roupa.

**Etapa 5 — Sapatos, luvas, chapéus, óculos, barba, acessórios (12 + 3 + 5 + 4 + 6 + 2).**
- Salto/ajuste de pé, luva escondendo a mão, barba só em rosto masculino se desejado.

**Etapa 6 — Tela do criador.**
- Menu por categoria (miniaturas ou nomes), seletor de cor, botões de salvar/trocar personagem, câmera do criador.
- Manter compatibilidade do formato salvo (encode/decode do `Avatar`), com migração do save antigo.

**Etapa 7 — Corpo e rosto.**
- Sliders de proporção por osso (altura, ombros, quadril, coxa, braço etc.) e sliders de rosto (blendshapes). Escolha da cabeça (3 formatos) e dos olhos.
- Esta é a etapa mais trabalhosa: exige ler os blendshapes dos FBX.

**Etapa 8 — Acabamento (sem tocar nas animações de corpo).**
- Expressões faciais e piscar (só rosto; não alteram Idle/Walk/Jump), desempenho (CPU skinning com várias peças), revisão final da licença.
- Verificar visualmente que cada peça acompanha o Idle/Walk/Jump existentes sem atravessar o corpo (correção é na peça/pesos, nunca na animação).

## 5. Pontos de atenção

- Cada etapa termina com: zip para a `_incoming` + nota em `PLANO-VIVO.md` + "o que testar no celular".
- **Nunca alterar os clips Idle (0), Walk (1) e Jump (2) do `f_1.bin`.** Se o formato mudar (Etapa 1), copiar essas matrizes exatamente como estão.
- Manter o fallback: se o carregamento modular falhar, voltar ao personagem atual (como já existe).
- Não acumular tudo numa entrega só. Sempre pedir o resultado do teste antes de seguir.

## 6. Texto para colar na outra conta

> Estou continuando o projeto "Meu Lar" (app Android com OpenGL próprio). Leia o arquivo anexo `PLANO-PERSONALIZACAO-ETAPAS.md` por inteiro. Anexo também o zip do repositório e o `.rar` do pack BoZo. Os clips de Idle, Walk e Jump já estão aprovados e não devem ser alterados. Comece pela **Etapa 1** apenas, entregue num zip para a pasta `_incoming` (caminhos a partir da raiz do repo) e espere meu teste antes da Etapa 2.

## 7. Estado após a Etapa 1

- Formato real adotado (ver também `PLANO-VIVO.md`): `cute/base.bin` (CUT1, corpo+boca+cabeça+olhos+clips Idle/Walk/Jump copiados byte a byte), `cute/p_<slot>_<id>.bin` (CUP1: "CUP1", nv, ni, flags, vértices de 44 bytes iguais ao CUT1, índices locais uint16) e `cute/pieces.txt` (`slot|id|arquivo|textura`, mais `outfit=slot/id,...`; a ordem das linhas é a ordem de desenho).
- Em vez de reconverter os FBX, a Mary foi dividida a partir do próprio `f_1.bin` aprovado (garante resultado idêntico; verificado: diferença 0,0 em todos os quadros). Peças: top/Sweater, bottom/Skirt, feet/Sandals, hairBack/Flare, hairFront/LongSweeps (foram incluídas sandália e franja para ficar igual à Mary atual). `f_1.bin` continua nos assets como reserva.
- Código: `CuteChar.kt` com lista de peças ativas (`setOutfit`, `outfit`, `available`); `Renderer3D.kt` não mudou. O campo `flags` do CUP1 está reservado (0) para a Etapa 4 (esconder partes do corpo sob a roupa).
- Peças novas das etapas 3–5 vêm do pack (FBX → CUP1, com UV/textura própria da peça: a coluna "textura" do manifesto já aceita outro PNG em `cute/`). Ferramentas: `ferramentas/etapa1_split.py` e `ferramentas/etapa1_verify.py`.
- Próximo: Etapa 2 (cores por máscara), só depois do teste da Etapa 1.
