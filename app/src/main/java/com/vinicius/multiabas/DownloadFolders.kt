package com.vinicius.multiabas

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Cada repositório (projeto) ganha uma pasta própria dentro de Downloads.
 * Ex.: Downloads/meu-jogo/arquivo.zip. Sem projeto atual, salva na raiz de Downloads.
 */
object DownloadFolders {

    /** Nome da pasta do projeto (o nome do repositório, limpo para servir de pasta). Vazio = raiz. */
    fun folderName(p: Project?): String {
        if (p == null) return ""
        return p.repo
            .replace(Regex("[^\\p{L}\\p{N}._ -]"), "_")
            .trim('.', ' ')
    }

    /** Pasta do projeto atual (vazio se não houver projeto). */
    fun current(ctx: Context): String = folderName(Projects.current(ctx))

    /** Caminho relativo para o MediaStore: "Download" ou "Download/pasta". */
    fun relPath(folder: String): String =
        if (folder.isEmpty()) Environment.DIRECTORY_DOWNLOADS
        else Environment.DIRECTORY_DOWNLOADS + "/" + folder

    /** Nome com subpasta para o DownloadManager: "pasta/arquivo". */
    fun destName(folder: String, name: String): String =
        if (folder.isEmpty()) name else "$folder/$name"

    /** Texto para mostrar ao usuário: "Downloads/pasta/arquivo". */
    fun label(folder: String, name: String): String =
        if (folder.isEmpty()) name else "$folder/$name"

    /** Raiz pública de Downloads. */
    fun root(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    /** Pasta de destino em disco para Android antigo (sem MediaStore), criando se precisar. */
    fun legacyDir(ctx: Context, folder: String): File {
        val base = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
        return if (folder.isEmpty()) base else File(base, folder).also { it.mkdirs() }
    }
}
