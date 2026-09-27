package il.nave.sleep

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Feed {
    const val FEED_URL = "https://vfxngbpmtvtjyxonfwws.supabase.co/functions/v1/sleep"
    const val PAGE_URL = "https://nave-sleep.vercel.app/"
    private const val PREFS = "nave_sleep"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(ctx: Context): String = prefs(ctx).getString("token", "") ?: ""
    fun test(ctx: Context): Boolean = prefs(ctx).getBoolean("test", false)

    fun save(ctx: Context, token: String, test: Boolean) {
        prefs(ctx).edit().putString("token", token).putBoolean("test", test).remove("last").apply()
    }

    /** Accepts the full page link (…#t=KEY&test=1) or the bare key. */
    fun parseInput(input: String): Pair<String, Boolean>? {
        val s = input.trim()
        val frag = s.substringAfter('#', "")
        if (frag.isNotEmpty()) {
            val parts = frag.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val t = parts["t"] ?: return null
            return if (t.matches(Regex("[0-9a-f]{32,128}"))) t to (parts["test"] == "1") else null
        }
        return if (s.matches(Regex("[0-9a-f]{32,128}"))) s to false else null
    }

    fun pageUrl(ctx: Context): String =
        PAGE_URL + "#t=" + token(ctx) + if (test(ctx)) "&test=1" else ""

    /** Fetches the status JSON. Returns null on any error. Must run off the main thread. */
    fun fetch(ctx: Context): JSONObject? {
        val t = token(ctx)
        if (t.isEmpty()) return null
        val url = URL("$FEED_URL?t=$t" + if (test(ctx)) "&test=1" else "")
        val c = url.openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 10000
            c.readTimeout = 10000
            c.setRequestProperty("Cache-Control", "no-cache")
            if (c.responseCode != 200) return null
            val body = c.inputStream.bufferedReader().use { it.readText() }
            prefs(ctx).edit().putString("last", body).putLong("last_at", System.currentTimeMillis()).apply()
            JSONObject(body)
        } catch (e: Exception) {
            null
        } finally {
            c.disconnect()
        }
    }

    /** Last good response, used when offline. */
    fun cached(ctx: Context): JSONObject? =
        prefs(ctx).getString("last", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun cachedAt(ctx: Context): Long = prefs(ctx).getLong("last_at", 0L)
}
