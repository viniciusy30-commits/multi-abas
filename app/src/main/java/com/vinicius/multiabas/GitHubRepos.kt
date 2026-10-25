package com.vinicius.multiabas

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Lista repositórios da conta e tenta descobrir applicationId / branch padrão.
 */
object GitHubRepos {
    private const val API = "https://api.github.com"

    data class RepoInfo(
        val owner: String,
        val name: String,
        val fullName: String,
        val defaultBranch: String,
        val description: String
    )

    fun listMyRepos(token: String): List<RepoInfo> {
        val out = mutableListOf<RepoInfo>()
        var page = 1
        while (page <= 5) {
            val arr = apiGetArray(token, "/user/repos?per_page=100&page=$page&sort=updated&affiliation=owner")
                ?: break
            if (arr.length() == 0) break
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val full = o.optString("full_name")
                val parts = full.split("/")
                if (parts.size != 2) continue
                out.add(
                    RepoInfo(
                        owner = parts[0],
                        name = parts[1],
                        fullName = full,
                        defaultBranch = o.optString("default_branch", "main"),
                        description = o.optString("description", "")
                    )
                )
            }
            if (arr.length() < 100) break
            page++
        }
        return out
    }

    /** Tenta achar applicationId em build.gradle(.kts) comuns. */
    fun detectPackage(token: String, owner: String, repo: String, branch: String): String {
        val candidates = listOf(
            "app/build.gradle.kts",
            "app/build.gradle",
            "android-app/app/build.gradle.kts",
            "android-app/app/build.gradle",
            "build.gradle.kts",
            "build.gradle"
        )
        for (path in candidates) {
            val text = rawFile(token, owner, repo, branch, path) ?: continue
            // applicationId = "com.xxx" ou applicationId 'com.xxx'
            val m = Regex("""applicationId\s*[=:]\s*["']([^"']+)["']""").find(text)
            if (m != null) return m.groupValues[1]
            val m2 = Regex("""applicationId\s+["']([^"']+)["']""").find(text)
            if (m2 != null) return m2.groupValues[1]
        }
        // AndroidManifest package=
        for (path in listOf(
            "app/src/main/AndroidManifest.xml",
            "android-app/app/src/main/AndroidManifest.xml"
        )) {
            val text = rawFile(token, owner, repo, branch, path) ?: continue
            val m = Regex("""package\s*=\s*["']([^"']+)["']""").find(text)
            if (m != null) return m.groupValues[1]
        }
        return ""
    }

    /** Confere se existe pasta _incoming no repo (ou na branch). */
    fun hasIncoming(token: String, owner: String, repo: String, branch: String): Boolean {
        val j = apiGet(token, "/repos/$owner/$repo/contents/_incoming?ref=$branch")
        return j != null
    }

    private fun rawFile(token: String, owner: String, repo: String, branch: String, path: String): String? {
        return try {
            val j = apiGet(token, "/repos/$owner/$repo/contents/$path?ref=$branch") ?: return null
            if (j.optString("type") != "file") return null
            val b64 = j.optString("content").replace("\n", "")
            if (b64.isBlank()) return null
            String(Base64.getDecoder().decode(b64))
        } catch (_: Exception) {
            null
        }
    }

    private fun apiGet(token: String, path: String): JSONObject? {
        return try {
            val c = URL("$API$path").openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (c.responseCode !in 200..299) return null
            JSONObject(c.inputStream.bufferedReader().readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun apiGetArray(token: String, path: String): JSONArray? {
        return try {
            val c = URL("$API$path").openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 25000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            if (c.responseCode !in 200..299) return null
            JSONArray(c.inputStream.bufferedReader().readText())
        } catch (_: Exception) {
            null
        }
    }
}
