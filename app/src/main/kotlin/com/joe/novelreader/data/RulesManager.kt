package com.joe.novelreader.data

import android.content.Context
import com.joe.novelreader.core.source.RulesFile
import com.joe.novelreader.core.source.SourceRules
import com.joe.novelreader.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Request
import java.io.File

/**
 * Source fixes ("rules") that can change without a new app version.
 *
 * - Remote: `sources/rules.json` in the GitHub repo, downloaded by the app.
 * - Local: a fix typed on the phone for one source. It wins over the remote one.
 * Anything not covered by a rule uses the code built into the app.
 */
class RulesManager(context: Context, private val network: Network, private val sources: SourceManager) {

    data class State(
        val remote: RulesFile? = null,
        val local: Map<String, String> = emptyMap(),
        val lastChecked: Long = 0,
        val lastMessage: String? = null,
        val checking: Boolean = false,
    ) {
        fun remoteFor(id: String): SourceRules? = remote?.sources?.get(id)?.takeIf { !it.isEmpty }
    }

    private val remoteFile = File(context.filesDir, "rules-remote.json")
    private val localDir = File(context.filesDir, "rules-local")
    private val prefs = context.getSharedPreferences("rules", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        val remote = runCatching { if (remoteFile.exists()) RulesFile.parse(remoteFile.readText()) else null }.getOrNull()
        val local = localDir.listFiles()?.filter { it.extension == "json" }
            ?.associate { it.nameWithoutExtension to it.readText() }.orEmpty()
        _state.value = State(remote, local, prefs.getLong("last_checked", 0), prefs.getString("last_message", null))
        apply()
    }

    val url: String get() = prefs.getString("url", null)?.ifBlank { null } ?: DEFAULT_URL

    fun setUrl(value: String) {
        prefs.edit().putString("url", value.trim().ifBlank { null }).apply()
    }

    /** Which rules are active for a source and where they came from. */
    fun activeFor(id: String): Pair<SourceRules, String?> {
        val s = _state.value
        s.local[id]?.let { text -> parseOne(text).getOrNull()?.let { return it to "your own fix" } }
        s.remoteFor(id)?.let { return it to "fix from GitHub" }
        return SourceRules() to null
    }

    private fun apply() {
        sources.all.forEach { it.rules = activeFor(it.id).first }
    }

    fun shouldAutoCheck(): Boolean = System.currentTimeMillis() - _state.value.lastChecked > 6 * 60 * 60 * 1000L

    /** Downloads the latest fixes. Returns a short message for the user. */
    suspend fun refresh(): String = withContext(Dispatchers.IO) {
        _state.value = _state.value.copy(checking = true)
        val message = try {
            val req = Request.Builder()
                .url(url + (if (url.contains('?')) "&" else "?") + "t=" + System.currentTimeMillis() / 60_000)
                .cacheControl(CacheControl.FORCE_NETWORK)
                .header("User-Agent", network.userAgent)
                .build()
            val text = network.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) error("GitHub answered ${r.code}")
                r.body?.string().orEmpty()
            }
            val parsed = RulesFile.parse(text)
            remoteFile.writeText(text)
            _state.value = _state.value.copy(remote = parsed)
            apply()
            val n = parsed.sources.count { !it.value.isEmpty }
            if (n == 0) "Up to date. No fixes needed right now." else "Up to date. $n source fix${if (n == 1) "" else "es"} active."
        } catch (e: Exception) {
            "Couldn't check for fixes: ${e.message ?: e.javaClass.simpleName}"
        }
        val now = System.currentTimeMillis()
        prefs.edit().putLong("last_checked", now).putString("last_message", message).apply()
        _state.value = _state.value.copy(checking = false, lastChecked = now, lastMessage = message)
        message
    }

    /** Saves a fix typed on the phone. Blank text removes it. Returns an error message, or null when saved. */
    fun setLocal(id: String, text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) {
            File(localDir, "$id.json").delete()
            _state.value = _state.value.copy(local = _state.value.local - id)
            apply()
            return null
        }
        parseOne(t).exceptionOrNull()?.let { return "Not valid: ${it.message?.lineSequence()?.firstOrNull()}" }
        localDir.mkdirs()
        File(localDir, "$id.json").writeText(t)
        _state.value = _state.value.copy(local = _state.value.local + (id to t))
        apply()
        return null
    }

    fun template(id: String): String {
        val active = activeFor(id).first
        return if (active.isEmpty) EXAMPLE else SourceRules.json.encodeToString(SourceRules.serializer(), active)
    }

    private fun parseOne(text: String): Result<SourceRules> =
        runCatching { SourceRules.json.decodeFromString(SourceRules.serializer(), text) }

    companion object {
        const val DEFAULT_URL = "https://raw.githubusercontent.com/mistajoe83/novel-reader/main/sources/rules.json"

        val EXAMPLE = """
            {
              "baseUrl": null,
              "chapter": {
                "content": null,
                "remove": [],
                "dropParagraphs": []
              }
            }
        """.trimIndent()
    }
}
