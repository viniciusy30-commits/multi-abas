# PLANO-VIVO — personagem fofinho (meu-lar)

Objetivo (não muda): customização de personagem bonita, fofinha e bem feita; sem polígonos estranhos.
Feedback mais recente do usuário: corpo parecia "boneco de feira" (peças coladas), rosto feio, mãos as piores; quer o mais profissional e natural possível.

## Passos
- [x] 1. Cute.kt/AvatarModel (elipsoides) — primeira versão, agora usada só como reserva (Avatar.buildCute)
- [x] 2. Motor de escultura em Java (pacote sculpt): campos de distância (SDF) com união suave + malha por surface nets
  - Prims (elipsoide, cone arredondado, ruído), Mesher, Layer (malha + tags de material por vértice)
  - Body (corpo contínuo, cabeça com maxilar/bochechas/nariz/lábios, mãos com dedos), Eyes
  - Gear (cabelos 1..8, barbas 1..3, camisa/regata/vestido/saia/shorts/calça, sapatos, óculos), Figure (monta tudo, cores, cache, pedaços indexados)
- [x] 3. Integração no app: SculptAvatar.kt (ponte + AvatarBuilder em segundo plano, com ticket), Gfx.Part com índices curtos, Renderer3D com glDrawElements, Avatar.build com reserva caso dê erro
- [x] 4. Câmera do criador (Creator.kt) para o corpo mais alto (≈1,57 m); pinça aceita zoom mais perto (GameView.kt)
- [x] 5. Testes no PC: 156 combinações (gênero × corpo × cabelo/roupa/calça/óculos/barba) sem erro nem NaN; 150–175 mil triângulos, ~1,4 s a frio / ~0,2 s com cache
- [x] 6. novidades.txt

## Decisões
- Formato salvo do Avatar (encode/decode) não mudou: saves antigos continuam valendo.
- Se o motor novo falhar por qualquer motivo (Throwable), cai no modelo antigo de elipsoides.
- Montagem fora da thread de desenho; a thread GL só troca a malha quando ela fica pronta (o personagem anterior continua na tela enquanto isso).
- Shader/iluminação do app não mudaram; lente dos óculos usa alfa por vértice (sem passada de vidro).

## Pontos a olhar com o APK no celular
- Tempo da primeira montagem e FPS (≈85 mil vértices, 145–175 mil triângulos).
- Rosto: estilizado e fofo, mas pode precisar de ajuste fino (nariz pequeno, cabeça meio oval).
- Moicano (cabelo 6): laterais podem parecer carecas demais.

## Arquivos alterados
app/src/main/java/com/vinicius/meular/{SculptAvatar.kt (novo), sculpt/*.java (novos), Gfx.kt, Renderer3D.kt, Avatar.kt, Creator.kt, GameView.kt}, app/src/main/assets/novidades.txt

## Rodada final (proporções/mãos/rosto)
- [x] Torso mais alto/largo, braços mais finos e curtos, pernas e quadril reajustados; roupas acompanham
- [x] Mãos refeitas (palma, polegar, 4 dedos) e caixa de malha da mão ampliada (pontas dos dedos não são mais cortadas)
- [x] Rosto mais fino estilo Sims (mandíbula, queixo, maçãs do rosto mais altas, lábios mais cheios)
- [x] Teste PC: 324 combinações sem erro/NaN

## PRÓXIMO PASSO
Se o build falhar, corrigir só o erro apontado. Se passar: ver o personagem no celular e ajustar rosto/moicano/desempenho conforme o que aparecer.

## Pack Cute Characters (importação de teste)
- [x] Pack (RAR5, unitypackage armazenado) extraído: 25 personagens (f_1–f_12, m_1–m_13), 7 animações, textura 128–1024, material
- [x] f_1.fbx (binário) convertido por script Python (fora do app) para assets/cute/f_1.bin: malha 6550 vértices / 8986 triângulos, 64 ossos, pesos (4 por vértice), animações Idle e Walk em matrizes por quadro (24 fps), altura normalizada 1,5 m, frente em +Z
- [x] CuteChar.kt: carrega o .bin, pele na CPU por quadro, textura própria (Part.tex); Renderer3D usa no criador (Idle) e na casa (Idle/Walk); se falhar, volta ao personagem antigo
- [ ] Ainda NÃO compilado/testado no celular (sem Gradle no ambiente). Se o build falhar, corrigir só o erro apontado
- [ ] Próximo: peças modulares (cabelo/roupa separáveis) e importar mais personagens; painel antigo de personalização REMOVIDO (Creator.kt só tem o botão Pronto)

## Câmera 3ª pessoa + pulo
- [x] Renderer3D: `third` (padrão ligado), câmera orbitando o personagem (2,7 m, encurta dentro da casa), drawPlayer (Walk/Idle, vira para a direção do movimento), sombra, pulo (py/vy, jumpReq)
- [x] Ui.kt: ícones CAMERA e JUMP; MainActivity: botão de câmera no topo, botão de pular (só no modo andar)
- [ ] Não compilado nem testado no celular. Pular não passa por cima de móveis (colisão é só no plano)

- [x] Animação de pulo: clip Jump = primeiros 17 quadros do "Song Jump" do pack (assado no f_1.bin, 3º clip); drawPlayer usa jumpT (tempo no ar) para sincronizar

## Rodada Mary (BoZo Stylized Modular Characters)
- [x] Extraído o unitypackage do RAR e lidos os FBX (leitor próprio); montada a Mary: corpo, cabeça, olhos, suéter, saia, sandálias, cabelo traseiro (Flare) e franja (LongSweeps) = 14.691 vértices, 24.062 triângulos, 55 ossos (com dedos)
- [x] Textura única 2048x1024 (assada das máscaras do pack com as cores da Mary.asset); mesmo formato CUT1 lido por CuteChar.kt (arquivos cute/f_1.bin e cute/textures.png, nomes mantidos; CuteChar.kt não mudou)
- [x] Animações criadas à mão nos ossos: Idle 3 s (laço), Walk 1 s (laço), Jump 0,71 s (sem agachar; o jogo já começa no ar)
- Limitações: cabelo e olhos presos à cabeça (sem física); mãos com dedos abertos como no modelo original; saia segue as coxas
- Licença: o pack veio de site de redistribuição; para publicar o jogo, ter a licença original do BoZo
## Rodada animação do andar (Mary)
- [x] Walk refeito (2ª versão, após vídeo do usuário: "robô"): curvas de passada com splines periódicas (quadril, joelho, tornozelo: calcanhar→rolagem→impulso), ciclo 0,72 s, braços opostos às pernas com cotovelo atrasado, torção de pelve/coluna/cabeça, dedos relaxados (curvatura via rz nos ossos dos dedos), ground-lock da pelve; velocidade de solo medida ≈1,66 m/s
- [x] Velocidade do jogador 2,6→1,9 m/s e da avatar da casa 2,2→1,9; CuteChar.WALK_RATE=1,14 casa o passo com 1,9 m/s (sem deslizar)
- [x] CuteChar.kt otimizado: os 55 ossos são interpolados uma vez por quadro (antes por vértice, ~4x mais caro) — causa provável das travadinhas
- [x] CuteChar.frame(clip, t, slot): fase do laço reinicia ao trocar de clip e transição suave de ~0,15 s (0,06 s para o pulo); slots 0 = jogador, 1 = avatar da casa, 2 = criador (Renderer3D passa slot nas chamadas 1 e 2)
- [ ] Ainda não compilado nem testado no celular
- Ferramentas de geração (fora do repo): scripts Python em /home/claude (fbx.py, ext.py, asm.py, anim.py, build.py, render.py); a animação é gerada em anim.py (walk/idle/jump) e assada em f_1.bin
## PRÓXIMO PASSO
Ver no APK: se o andar ainda parecer rápido/lento demais, ajustar só o comprimento do ciclo (0,8 s em build.py) e a amplitude S em anim.py; mãos ainda ficam com dedos abertos (possível curvar dedos).

- [x] 3ª versão do walk (feedback "driblando"): curvas de perna simplificadas (joelho máx ~42°, quadril ±22°), ciclo 0,9 s, altura da pelve suavizada por FFT (2 harmônicas; o ground-lock cru dava soluço); velocidade de solo medida 1,18 m/s → jogo 1,4 m/s com CuteChar.WALK_RATE=1,19 (ciclo efetivo ≈0,76 s)

- [x] 4ª versão do walk (feedback "deslizando"): perna guiada por pé (IK de 2 ossos no plano sagital, anim.py: foot_target/_ik): tornozelo anda linear no apoio (60% do ciclo, curso 70 unid) e faz arco no balanço; pelve baixa só o necessário (bob ≈5 unid). Pé de apoio varia ≈2 unid no chão (medido). Ciclo 0,9 s; WALK_RATE=1,2; velocidade do jogo 1,28 m/s (=clip 1,065 m/s × 1,2). Para mudar a velocidade: mudar juntos Renderer3D (1,28) e WALK_RATE

## Personalização modular BoZo — Etapa 1 (infraestrutura com a Mary em pedaços)
- [x] Formato novo em `assets/cute/`: `base.bin` (CUT1: corpo + boca + cabeça + olhos + os 3 clips Idle/Walk/Jump copiados **byte a byte** do f_1.bin aprovado), uma peça por arquivo `p_<slot>_<id>.bin` (CUP1: "CUP1", nv, ni, flags, vértices de 44 bytes iguais ao CUT1, índices locais uint16) e `pieces.txt` (manifesto `slot|id|arquivo|textura` + linha `outfit=` com a roupa padrão; a ordem das linhas é a ordem de desenho).
- [x] Peças geradas (a Mary inteira, igual à de antes): top/Sweater, bottom/Skirt, feet/Sandals, hairBack/Flare (inclui 2 tufos pequenos que usam a textura do cabelo), hairFront/LongSweeps. Além disso foram descartados ~800 vértices órfãos (nenhum triângulo os usava).
- [x] `CuteChar.kt`: mesma interface de antes (`frame`, `clipSeconds`, `ready`, `initGl`, `assets`; Renderer3D NÃO mudou). Os 55 ossos são interpolados uma vez por quadro e as mesmas matrizes vestem o corpo-base e cada peça ativa (lista de peças ativas, uma `Part` por peça, todas com `textures.png`). Novos: `setOutfit(chaves "slot/id")`, `outfit()`, `available()`, `modular`.
- [x] Reserva: se base.bin/pieces.txt/peças falharem, carrega o `f_1.bin` inteiro como antes (mantido nos assets, intocado); se também falhar, `frame` devolve null e o app usa o personagem esculpido.
- [x] Verificado no PC (`ferramentas/etapa1_verify.py`): clips idênticos byte a byte; malha base+peças × f_1.bin com diferença 0,0 (posição e normal) nos 128 quadros dos 3 clips; mesmos UVs e cores; 24.062 triângulos preservados, mesma ordem de desenho.
- [ ] Ainda NÃO compilado nem testado no celular (sem Gradle/Kotlin no ambiente). Se o build falhar, corrigir só o erro apontado.
- Ferramentas (agora no repo): `ferramentas/etapa1_split.py` (divide f_1.bin em base + peças) e `ferramentas/etapa1_verify.py`.
- Para testar: a Mary deve aparecer IGUAL (criador, casa e câmera 3ª pessoa), com Idle, andar e pulo iguais. Detalhe técnico: agora são 6 desenhos por personagem em vez de 1.
- Observação: existe uma pasta antiga `viniciusy30-commits-meu-lar-46a5cc2/` na raiz do repositório (cópia velha, não é usada pelo app).
- PRÓXIMO: Etapa 2 (cores por máscara), só depois do teste.

## Personalização modular BoZo — Etapa 2 (cores)
- [x] Recoloração por máscara do pack, **sem mexer em malha, ossos nem animações** e sem mexer no shader: o atlas `cute/textures.png` continua sendo a base; `CuteColor.kt` gera uma textura recolorida numa thread de fundo e a thread GL só sobe ela (glTexSubImage2D) no começo de `CuteChar.frame`.
- [x] Dados gerados no PC por `ferramentas/etapa2_masks.py` a partir das máscaras do pack (canais R/G/B = cor 1/cor 2/cavidades): `cute/recolor.png` (R=peso do slot 0, G=peso do slot 1, B=id da região), `cute/recolor_s.png` (sombra do pixel) e `cute/recolor.txt` (regiões, slots e cor de referência de cada uma = cor com que a Mary foi assada). Regiões: corpo(pele), blusa(2 cores), saia, cabelo trás/frente (cor + pontas rosa), olhos (só a íris, detectada pela cor assada), sandália (2 cores).
- [x] Modelo por pixel/canal (sRGB): `saida = (base + S*(w0*max(D0,0)+w1*max(D1,0))) * (1 + w0*min(D0/ref0,0)+w1*min(D1/ref1,0))`, D = cor escolhida − referência. Clarear soma, escurecer multiplica (mantém o detalhe). Com as cores originais a saída é idêntica ao textures.png (diferença 0, verificado em `ferramentas/etapa2_sim.py`).
- [x] 9 slots (`CuteColor.SLOT_NAMES`): Pele, Olhos, Cabelo, Pontas, Blusa, Detalhe, Saia, Sandália, Tiras; paletas de 8 a 14 cores; -1 = original. Pontas acompanham o cabelo (25% mais claro), Detalhe acompanha a blusa (25% mais claro) e Tiras acompanham a sandália, enquanto não escolhidos.
- [x] `Avatar.cc` (IntArray de 9) salvo no `encode()` DEPOIS dos 13 números antigos; `decode()` aceita saves antigos (sem cc = tudo original). GameState aplica `CuteColor.setColors(avatar.cc)` ao carregar; se o contexto GL for recriado a textura recolorida sobe de novo.
- [x] `Creator.kt`: fileira de partes + fileira de cores + "Pronto"; a câmera do criador sobe um pouco (alvo 0,7) para o painel não cobrir os pés.
- [ ] Ainda NÃO compilado nem testado no celular (sem Gradle/Kotlin no ambiente). Se o build falhar, corrigir só o erro apontado.
- Limitações conhecidas: cor da peça é a cor MÉDIA da região (as sombras do pack acompanham); cabelo branco/preto mostra um resto leve das pontas rosa; cores só valem para as 5 peças atuais (as próximas peças das Etapas 3–5 entram no mesmo atlas/mesmo esquema com novas regiões).
- PRÓXIMO: Etapa 3 (cabelos), só depois do teste.


## Etapa 3 — Cabelos (entregue, aguardando teste)
- [x] 23 cabelos convertidos do pack (FBX -> CUP1) por `ferramentas/etapa3_hair.py` (+ `fbxr.py`, `fbxmesh.py`): 14 traseiros + 9 franjas, somando Flare e LongSweeps da Mary = 15 + 10.
- Pesos: osso 8 (cabeça) acima de y=150, misturando com o osso 4 (peito) até y=140, para o cabelo longo seguir o peito e não atravessar o corpo.
- Textura: `cute/hair.png` (1024x1024, 20 texturas do pack assadas em castanho) com máscaras `hair_recolor.png`, `hair_recolor_s.png`, `hair_recolor.txt` (região 1 = cabelo, slots Cabelo/Pontas). Flare e LongSweeps seguem em `textures.png`.
- `CuteColor.kt`: agora com 2 camadas (Mary e cabelos); `CuteChar.setHair(trás, frente)` e hair.png subida/recolorida igual ao atlas da Mary.
- `Avatar`: 2 números novos no fim do save (hb, hf = índices em AvaPal.HB_IDS / HF_IDS); saves antigos ficam com o cabelo da Mary.
- `Creator.kt`: partes "Cabelo atrás" e "Franja" (estilos) antes das cores.
- Chapéu escondendo cabelo: fica para a Etapa 5 (chapéus). Cabelo atrás/frente são independentes.


## Etapa 4 — Roupas (entregue, aguardando teste)
- [x] 18 blusas + 15 calças/saias + 3 vestidos convertidos do pack por `ferramentas/etapa4_extra.py` (+ `clothes_lib.py`, `fbxr.py`, `fbxmesh.py`). Pesos de osso vêm dos clusters do FBX: conferido que Sweater e Skirt da Mary saem IDÊNTICOS (diferença 0). Sweater e Skirt continuam em `textures.png`.
- Mapeamento nome do osso -> índice de 55 ossos em `clothes_lib.bone_index` (twists vão para o osso pai).
- Atlas único `cute/extra.png` (2016x1344, tiles 224) com cabelos + roupas, máscaras `extra_recolor.png`/`extra_recolor_s.png`/`extra_recolor.txt`. Regiões: 1 cabelo (Cabelo/Pontas), 2 cima e vestidos (Blusa/Detalhe), 3 baixo (Saia/Detalhe baixo = slot novo nº 9). Cores "originais" das peças novas: cinza-claro (cima) e cinza-azulado (baixo), mudam pela paleta. `hair.png`/`hair_recolor*` viraram placeholders de 1 pixel (podem ser apagados do repositório).
- CUP1 com flags&1: depois dos índices vem `nHide` + ids (uint16) de triângulos do corpo-base cobertos pela peça (distância < 1,5 e mesma orientação). `CuteChar.rebuildActive` monta o corpo sem esses triângulos.
- Roupa de baixo: `top/Underwear` e `bottom/Underwear` (faixa e shorts curtos gerados do próprio corpo, 0,35 acima da pele), aparecem quando não há blusa/calça/vestido.
- `Avatar`: salvos ctop, cbot, cov (índices em CT_IDS, CB_IDS, CO_IDS), cor do slot 9; formato: 13 antigos | 9 cores | hb hf | cor 9 | ctop cbot cov. `wearKeys()` decide as peças. `CuteChar.setWear` substitui `setHair`.
- `Creator.kt`: partes Cabelo atrás, Franja, Blusa, Calça/saia, Vestido (estilos) e depois as cores.
- Limites conhecidos: roupas largas (moletom, calça baggy) podem atravessar braço/perna em poses extremas do Walk; correção seria nos pesos da peça. Sapatos, luvas, chapéus, óculos e barba ficam para a Etapa 5.
- PRÓXIMO PASSO: esperar o teste das Etapas 3 e 4; depois Etapa 5.
