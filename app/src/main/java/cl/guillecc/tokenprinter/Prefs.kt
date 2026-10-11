package cl.guillecc.tokenprinter

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Ajustes de impresión y tokens recientes, guardados en el teléfono. */
object Prefs {
    private const val NAME = "settings"
    private const val MAX_RECENTS = 12

    /** Tamaño del token en el papel: 1 = ancho completo (48 mm), 0.75 = compacto (36 mm). */
    const val SCALE_NORMAL = 1f
    const val SCALE_COMPACT = 0.75f

    private fun p(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun brightness(ctx: Context) = p(ctx).getFloat("brightness", Thermal.DEFAULT_BRIGHTNESS)
    fun setBrightness(ctx: Context, v: Float) = p(ctx).edit().putFloat("brightness", v).apply()

    fun scale(ctx: Context) = p(ctx).getFloat("scale", SCALE_NORMAL)
    fun setScale(ctx: Context, v: Float) = p(ctx).edit().putFloat("scale", v).apply()

    /** Recientes: solo tokens de Scryfall (los personalizados no se guardan). */
    fun recents(ctx: Context): List<TokenEntry> {
        val arr = runCatching { JSONArray(p(ctx).getString("recents", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            TokenEntry(
                name = o.optString("name"),
                setName = o.optString("set"),
                colors = o.optString("colors").map { it.toString() },
                isEmblem = o.optBoolean("emblem"),
                isReminder = o.optBoolean("reminder"),
                thumbUrl = o.optString("thumb").ifEmpty { null },
                fullUrl = o.optString("full").ifEmpty { null } ?: return@mapNotNull null,
            )
        }
    }

    fun addRecent(ctx: Context, e: TokenEntry): List<TokenEntry> {
        if (e.fullUrl == null) return recents(ctx)
        val list = (listOf(e) + recents(ctx).filter { it.fullUrl != e.fullUrl }).take(MAX_RECENTS)
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject()
                .put("name", it.name).put("set", it.setName).put("colors", it.colors.joinToString(""))
                .put("emblem", it.isEmblem).put("reminder", it.isReminder)
                .put("thumb", it.thumbUrl ?: "").put("full", it.fullUrl))
        }
        p(ctx).edit().putString("recents", arr.toString()).apply()
        return list
    }
}
