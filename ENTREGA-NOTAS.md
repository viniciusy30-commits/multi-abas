# Entrega: pasta automática por repositório + favoritos e navegação nos downloads

Este arquivo na raiz existe de propósito: com ele o ZIP tem duas entradas no topo
(app/ e este arquivo), então o ZipNormalizer antigo não tira a pasta "app/" como se fosse embrulho.

Novo: DownloadFolders.kt (nome da pasta = nome do repositório do Projeto atual), ic_star_outline.xml.
Mudou: DownloadsPicker.kt (pastas, Voltar, Favoritos, seleção entre pastas), MainActivity.kt e LinkBrowser.kt
(todo download vai para Downloads/<repositório>/), novidades.txt.
