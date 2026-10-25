package com.vinicius.multiabas

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Projeto = repositório de destino dos ZIPs do Claude. */
data class Project(
    val id: String,
    var name: String,
    var owner: String,
    var repo: String,
    var branch: String = "",
    var folder: String = "_incoming",
    /** applicationId do jogo, ex: com.vinicius.masmorras — usado para abrir o app */
    var packageName: String = ""
) {
    fun fullRepo() = "$owner/$repo"
    fun pathFor(file: String): String {
        // descarta partes vazias, "." e ".." — o GitHub recusa caminhos assim (HTTP 422)
        val parts = folder.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }
        return (parts + file).joinToString("/")
    }
}

object Projects {
    private const val PREF = "projects"
    private const val KEY_LIST = "list"
    private const val KEY_CUR = "cur"

    fun load(ctx: Context): MutableList<Project> {
        val out = mutableListOf<Project>()
        try {
            val a = JSONArray(ctx.getSharedPreferences(PREF, 0).getString(KEY_LIST, "[]"))
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(
                    Project(
                        o.getString("id"),
                        o.getString("name"),
                        o.optString("owner", "viniciusy30-commits"),
                        o.getString("repo"),
                        o.optString("branch", ""),
                        o.optString("folder", "_incoming"),
                        o.optString("packageName", "")
                    )
                )
            }
        } catch (_: Exception) { }
        return out
    }

    fun save(ctx: Context, list: List<Project>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("owner", it.owner)
                    .put("repo", it.repo)
                    .put("branch", it.branch)
                    .put("folder", it.folder)
                    .put("packageName", it.packageName)
            )
        }
        ctx.getSharedPreferences(PREF, 0).edit().putString(KEY_LIST, a.toString()).apply()
    }

    fun currentId(ctx: Context): String =
        ctx.getSharedPreferences(PREF, 0).getString(KEY_CUR, "") ?: ""

    fun setCurrentId(ctx: Context, id: String) {
        ctx.getSharedPreferences(PREF, 0).edit().putString(KEY_CUR, id).apply()
    }

    fun current(ctx: Context): Project? {
        val id = currentId(ctx)
        return load(ctx).firstOrNull { it.id == id }
    }

    fun token(ctx: Context): String =
        ctx.getSharedPreferences(PREF, 0).getString("token", "") ?: ""

    fun setToken(ctx: Context, t: String) {
        ctx.getSharedPreferences(PREF, 0).edit().putString("token", t.trim()).apply()
    }
}
