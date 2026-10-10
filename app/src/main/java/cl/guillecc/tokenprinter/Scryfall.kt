package cl.guillecc.tokenprinter

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale

/** Una versión imprimible de un token (o una cara de un token doble). */
data class TokenEntry(
    val name: String,
    val setName: String,
    val colors: List<String>,
    val isEmblem: Boolean,
    val isReminder: Boolean,
    val thumbUrl: String? = null,
    val fullUrl: String? = null,
    /** Solo para tokens personalizados, que se dibujan en el teléfono. */
    val bitmap: Bitmap? = null,
)

/** Cliente mínimo de la API de Scryfall (llamar desde un hilo de IO). */
object Scryfall {
    private const val BASE =
        "(type:token or type:emblem or type:card) -layout:art_series -layout:front_card -is:minigame " +
            "-set:wc97 -set:wc98 -set:wc99 -set:wc00 -set:wc01 -set:wc02 -set:wc03 -set:wc04"

    const val DEFAULT_STATUS = "Busca un token de Magic para ver sus versiones."

    private class NotFound : Exception()

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", "TokenPrinter/1.0")
            setRequestProperty("Accept", "application/json;q=0.9,*/*;q=0.8")
            connectTimeout = 15_000
            readTimeout = 20_000
        }

    private fun getJson(url: String): JSONObject {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code == 404) throw NotFound()
            if (code !in 200..299) throw IOException("HTTP $code")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun searchUrl(q: String, extra: String) =
        "https://api.scryfall.com/cards/search?q=" + URLEncoder.encode(q, "UTF-8") + extra

    /** Mensaje inicial: cuántos tokens hay y cuál es la edición más nueva indexada. */
    fun freshness(): String {
        val data = getJson(searchUrl(BASE, "&order=released&dir=desc&unique=prints"))
        val newest = data.optJSONArray("data")?.optJSONObject(0) ?: return DEFAULT_STATUS
        val total = NumberFormat.getIntegerInstance(Locale("es", "CL")).format(data.optInt("total_cards"))
        return "$DEFAULT_STATUS\nCatálogo: $total tokens/emblemas — última edición: " +
            "${newest.optString("set_name")} (${newest.optString("released_at")})"
    }

    /** Las dos consultas van en paralelo; la de reminder es opcional y no bloquea si falla. */
    suspend fun search(q: String): List<TokenEntry> = coroutineScope {
        val query = "$BASE $q"
        // Cartas etiquetadas "reminder card" en Scryfall Tagger. Si falla, seguimos sin ese dato.
        val reminders = async(Dispatchers.IO) {
            try {
                val r = getJson(searchUrl("$query otag:reminder-card", "&unique=prints")).optJSONArray("data")
                (0 until (r?.length() ?: 0)).map { r!!.getJSONObject(it).optString("id") }.toSet()
            } catch (e: Exception) {
                emptySet()
            }
        }
        val data = try {
            withContext(Dispatchers.IO) { getJson(searchUrl(query, "&unique=prints&order=name")) }
        } catch (e: NotFound) {
            reminders.cancel()
            return@coroutineScope emptyList()
        }
        val reminderIds = reminders.await()
        val cards = data.optJSONArray("data") ?: return@coroutineScope emptyList()
        (0 until cards.length()).flatMap { entries(cards.getJSONObject(it), reminderIds) }
    }

    private fun entries(card: JSONObject, reminderIds: Set<String>): List<TokenEntry> {
        val colorIdentity = card.optJSONArray("color_identity").strings()
        val setName = card.optString("set_name")
        val faces = card.optJSONArray("card_faces")
        // Cartas de recordatorio (City's Blessing, The Monarch...) tienen type_line = "Card".
        val isReminder = card.optString("type_line").isReminderType() || card.optString("id") in reminderIds

        // Doble cara con imagen propia en cada lado: una entrada por cara, cada una con su tipo/colores.
        if (faces != null && faces.length() > 1 &&
            (0 until faces.length()).all { faces.getJSONObject(it).has("image_uris") }
        ) {
            return (0 until faces.length()).map { i ->
                val f = faces.getJSONObject(i)
                val uris = f.getJSONObject("image_uris")
                val type = f.optString("type_line")
                TokenEntry(
                    name = f.optString("name"),
                    setName = setName,
                    colors = if (f.has("colors")) f.optJSONArray("colors").strings() else colorIdentity,
                    isEmblem = type.contains("emblem", ignoreCase = true),
                    isReminder = type.isReminderType(),
                    thumbUrl = uris.pick("small", "normal"),
                    fullUrl = uris.pick("normal", "large", "small"),
                )
            }
        }

        val images = card.optJSONObject("image_uris")
            ?: faces?.optJSONObject(0)?.optJSONObject("image_uris")
            ?: return emptyList()
        val full = images.pick("normal", "large", "small") ?: return emptyList()
        return listOf(
            TokenEntry(
                name = card.optString("name"),
                setName = setName,
                colors = colorIdentity,
                isEmblem = card.optString("type_line").contains("emblem", ignoreCase = true),
                isReminder = isReminder,
                thumbUrl = images.pick("small") ?: full,
                fullUrl = full,
            )
        )
    }

    private fun String.isReminderType() = trim().equals("card", ignoreCase = true)

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    private fun JSONObject.pick(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { k -> optString(k).takeIf { it.isNotEmpty() } }
}
