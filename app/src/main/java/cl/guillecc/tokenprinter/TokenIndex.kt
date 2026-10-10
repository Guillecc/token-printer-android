package cl.guillecc.tokenprinter

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.Normalizer
import java.util.zip.GZIPInputStream

/**
 * Índice local de tokens (lo genera .github/workflows/index.yml desde el bulk de Scryfall).
 * Se descarga al iniciar si la copia tiene más de 24 h; mientras no exista, se usa la búsqueda en vivo.
 */
object TokenIndex {
    private const val INDEX_URL =
        "https://github.com/Guillecc/token-printer-android/releases/download/index/tokens.json.gz"
    private const val MAX_AGE_MS = 24 * 60 * 60 * 1000L
    private const val IMG = "https://cards.scryfall.io/"

    private class Card(val search: String, val entries: List<TokenEntry>)
    private class Index(val cards: List<Card>, val status: String)

    @Volatile private var index: Index? = null

    val ready get() = index != null

    private fun file(ctx: Context) = File(ctx.filesDir, "tokens.json.gz")

    /** Carga la copia guardada (si hay). Llamar desde un hilo de IO. */
    fun load(ctx: Context) {
        val f = file(ctx)
        if (f.exists()) index = runCatching { parse(f) }.getOrNull()
    }

    /** Descarga el índice si falta o tiene más de 24 h. */
    fun refreshIfStale(ctx: Context) {
        val f = file(ctx)
        if (index != null && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) return
        val tmp = File(ctx.filesDir, "tokens.json.gz.tmp")
        val conn = Scryfall.open(INDEX_URL)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        val parsed = parse(tmp) // si viene corrupto, falla aquí y se conserva la copia anterior
        if (!tmp.renameTo(f)) throw IOException("No se pudo guardar el índice")
        index = parsed
    }

    /** Mensaje de "última edición" según el índice, o null si no hay índice. */
    fun freshness(): String? = index?.status

    /** Búsqueda local por nombre/tipo (sin acentos ni mayúsculas), ordenada por nombre. Null si no hay índice. */
    fun search(q: String): List<TokenEntry>? {
        val idx = index ?: return null
        val words = normalize(q).split(' ').filter { it.isNotEmpty() }
        return idx.cards.filter { c -> words.all { it in c.search } }.flatMap { it.entries }
    }

    private fun normalize(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    private fun parse(f: File): Index {
        val root = JSONObject(GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() })
        val arr = root.getJSONArray("cards")
        val cards = (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val faces = c.getJSONArray("f")
            Card(
                search = normalize(c.optString("n") + " " + c.optString("t")),
                entries = (0 until faces.length()).map { j ->
                    val e = faces.getJSONObject(j)
                    val u = e.optString("u")
                    TokenEntry(
                        name = e.optString("n"),
                        setName = c.optString("s"),
                        colors = e.optString("c").map { it.toString() },
                        isEmblem = e.optInt("e") == 1,
                        isReminder = e.optInt("m") == 1,
                        thumbUrl = if (u.isNotEmpty()) "${IMG}small/$u" else e.optString("th"),
                        fullUrl = if (u.isNotEmpty()) "${IMG}normal/$u" else e.optString("fu"),
                    )
                },
            )
        }
        val newest = root.getJSONObject("newest")
        return Index(cards, Scryfall.catalogStatus(root.getInt("total"), newest.optString("s"), newest.optString("r")))
    }
}
