package com.vinicius.multiabas

import android.content.Context
import java.io.File

/** Guarda o erro que fechou o app para mostrar na próxima abertura. */
object CrashLog {
    private fun file(ctx: Context) = File(ctx.filesDir, "ultimo-erro.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                file(app).writeText("Thread: ${t.name}\n" + android.util.Log.getStackTraceString(e))
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    fun pending(ctx: Context): String? = try {
        val f = file(ctx)
        if (f.exists()) f.readText().ifBlank { null } else null
    } catch (_: Exception) { null }

    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Exception) { } }
}
