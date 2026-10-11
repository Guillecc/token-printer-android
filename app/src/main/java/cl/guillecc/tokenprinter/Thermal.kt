package cl.guillecc.tokenprinter

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

/** Prepara imágenes para una térmica de 58 mm (203 dpi ≈ 8 px/mm) y las pasa a ESC/POS. */
object Thermal {
    private const val DPMM = 8
    const val WIDTH = 48 * DPMM          // 384 px = ancho imprimible
    private const val SIDE_MARGIN = DPMM / 2   // 0,5 mm
    private const val TOP_MARGIN = 1 * DPMM    // 1 mm
    private const val BOTTOM_MARGIN = 11 * DPMM // 11 mm, para cortar cómodo
    private const val LABEL_H = 30
    const val DEFAULT_BRIGHTNESS = 1.05f

    /** Identidad de color legible, siempre en orden W-U-B-R-G. */
    fun colorLabel(colors: List<String>): String {
        val names = linkedMapOf("W" to "White", "U" to "Blue", "B" to "Black", "R" to "Red", "G" to "Green")
        return names.filterKeys { it in colors }.values.joinToString(" ").ifEmpty { "Colorless" }
    }

    fun labelFor(e: TokenEntry) = when {
        e.isReminder -> "Reminder Card"
        e.isEmblem -> "Emblem"
        else -> colorLabel(e.colors)
    }

    /**
     * Escala la imagen al ancho del papel (o a una fracción, centrada, con [scale] < 1),
     * la pasa a gris con contraste y agrega la etiqueta abajo.
     */
    fun compose(src: Bitmap, label: String?, scale: Float = 1f, brightness: Float = DEFAULT_BRIGHTNESS): Bitmap {
        val contentW = ((WIDTH - SIDE_MARGIN * 2) * scale).roundToInt()
        val left = ((WIDTH - contentW) / 2).toFloat()
        val contentH = (src.height * contentW.toFloat() / src.width).roundToInt()
        val labelH = if (label != null) LABEL_H else 0
        val out = Bitmap.createBitmap(WIDTH, TOP_MARGIN + contentH + labelH + BOTTOM_MARGIN, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)

        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val scaled = Bitmap.createScaledBitmap(soft, contentW, contentH, true)
        c.drawBitmap(tone(scaled, contrast = 1.5f, brightness = brightness), left, TOP_MARGIN.toFloat(), null)

        if (label != null) {
            val lineY = (TOP_MARGIN + contentH).toFloat()
            val line = Paint().apply { color = Color.BLACK; strokeWidth = 1f }
            c.drawLine(left, lineY + 4, left + contentW, lineY + 4, line)
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            val cy = lineY + LABEL_H / 2f + 4
            c.drawText(label, WIDTH / 2f, cy - (text.ascent() + text.descent()) / 2, text)
        }
        return out
    }

    /** Escala de grises + contraste + brillo (como los filtros CSS de la versión web). */
    fun tone(src: Bitmap, contrast: Float, brightness: Float): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val p = px[i]
            val a = Color.alpha(p) / 255f
            var lum = (0.2126f * Color.red(p) + 0.7152f * Color.green(p) + 0.0722f * Color.blue(p)) / 255f
            lum = lum * a + (1 - a) // transparente = papel blanco
            val v = (((lum - 0.5f) * contrast + 0.5f) * brightness).coerceIn(0f, 1f)
            val g = (v * 255).roundToInt()
            px[i] = Color.rgb(g, g, g)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Floyd–Steinberg a blanco y negro puro: las térmicas solo imprimen 1 bit. */
    fun dither(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val lum = FloatArray(w * h) { i ->
            val p = px[i]
            0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val old = lum[i]
                val new = if (old < 128f) 0f else 255f
                val err = old - new
                px[i] = if (new == 0f) Color.BLACK else Color.WHITE
                if (x + 1 < w) lum[i + 1] += err * 7 / 16
                if (y + 1 < h) {
                    if (x > 0) lum[i + w - 1] += err * 3 / 16
                    lum[i + w] += err * 5 / 16
                    if (x + 1 < w) lum[i + w + 1] += err * 1 / 16
                }
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** ESC @ + imagen raster (GS v 0) en bandas + avance de papel. Espera un bitmap ya en B/N. */
    fun escPos(bw: Bitmap): ByteArray {
        val w = bw.width
        val h = bw.height
        val wBytes = (w + 7) / 8
        val px = IntArray(w * h)
        bw.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @: reinicia la impresora
        val band = 128 // filas por comando: varias térmicas baratas no aceptan imágenes muy altas de una vez
        var y0 = 0
        while (y0 < h) {
            val rows = min(band, h - y0)
            out.write(byteArrayOf(0x1D, 0x76, 0x30, 0x00,
                (wBytes and 0xFF).toByte(), (wBytes shr 8).toByte(),
                (rows and 0xFF).toByte(), (rows shr 8).toByte()))
            for (y in y0 until y0 + rows) {
                for (xb in 0 until wBytes) {
                    var b = 0
                    for (bit in 0 until 8) {
                        val x = xb * 8 + bit
                        if (x < w && Color.red(px[y * w + x]) < 128) b = b or (0x80 shr bit)
                    }
                    out.write(b)
                }
            }
            y0 += rows
        }
        out.write(byteArrayOf(0x1B, 0x64, 0x02)) // ESC d 2: avanza 2 líneas
        return out.toByteArray()
    }

    /** Ticket de prueba: texto, bordes y una barra de degradado para revisar el tramado. */
    fun testPage(printerName: String): Bitmap {
        val h = 300
        val bmp = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val stroke = Paint().apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 3f }
        c.drawRect(4f, 4f, WIDTH - 4f, 210f, stroke)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
        }
        text.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        text.textSize = 40f
        c.drawText("Token Printer", WIDTH / 2f, 60f, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 22f
        c.drawText("Impresión de prueba OK", WIDTH / 2f, 100f, text)
        text.textSize = 18f
        c.drawText(printerName, WIDTH / 2f, 135f, text)
        c.drawText("58 mm · 384 px · ESC/POS", WIDTH / 2f, 165f, text)
        val fill = Paint()
        for (x in 0 until WIDTH - 16) {
            val g = (255 * x / (WIDTH - 17).toFloat()).roundToInt()
            fill.color = Color.rgb(g, g, g)
            c.drawRect(8f + x, 225f, 9f + x, 265f, fill)
        }
        return bmp
    }
}
