package cl.guillecc.tokenprinter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import kotlin.math.max

/** Dibuja un token personalizado de 500×700, igual que la versión web. */
object CustomToken {
    private const val W = 500
    private const val H = 700

    fun draw(name: String, type: String, power: String, toughness: String, art: Bitmap?, isEmblem: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

        c.drawRect(10f, 10f, W - 10f, H - 10f, stroke)

        // Nombre
        fitText(c, text, name, 30f, 60f, W - 60f, 32f, Typeface.create(Typeface.SERIF, Typeface.BOLD))
        c.drawLine(20f, 82f, W - 20f, 82f, stroke)

        // Franja inferior: tipo a la izquierda y F/R o EMBLEM a la derecha
        val footerH = 90f
        val footerY = H - 20f - footerH
        val hasPT = power.isNotEmpty() || toughness.isNotEmpty()
        val boxW = if (isEmblem) 150f else 110f
        val boxH = 56f
        val hasRightBox = isEmblem || hasPT

        // Recuadro de arte entre el nombre y la franja inferior
        val artBox = RectF(20f, 90f, W - 20f, footerY)
        c.drawRect(artBox, stroke)
        if (art != null) {
            // "cover": rellena el recuadro recortando el sobrante
            val scale = max(artBox.width() / art.width, artBox.height() / art.height)
            val dw = art.width * scale
            val dh = art.height * scale
            val dx = artBox.left + (artBox.width() - dw) / 2
            val dy = artBox.top + (artBox.height() - dh) / 2
            c.save()
            c.clipRect(artBox)
            c.drawBitmap(Thermal.tone(art, contrast = 1.3f, brightness = 1f), null,
                RectF(dx, dy, dx + dw, dy + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
        } else {
            val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(0x99, 0x99, 0x99)
                textSize = 18f
                textAlign = Paint.Align.CENTER
            }
            c.drawText("(sin ilustración)", W / 2f, artBox.centerY(), hint)
        }

        c.drawLine(20f, footerY, W - 20f, footerY, stroke)

        val leftMax = if (hasRightBox) W - 60f - boxW - 20f else W - 60f
        fitText(c, text, type, 30f, footerY + footerH / 2 + 8, leftMax, 24f, Typeface.create(Typeface.SERIF, Typeface.ITALIC))

        if (hasRightBox) {
            val bx = W - 30f - boxW
            val by = footerY + (footerH - boxH) / 2
            stroke.strokeWidth = 4f
            c.drawRect(bx, by, bx + boxW, by + boxH, stroke)
            val label = if (isEmblem) "EMBLEM" else "${power.ifEmpty { "0" }}/${toughness.ifEmpty { "0" }}"
            text.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            text.textSize = if (isEmblem) 22f else 28f
            text.textAlign = Paint.Align.CENTER
            c.drawText(label, bx + boxW / 2, by + boxH / 2 - (text.ascent() + text.descent()) / 2, text)
        }
        return bmp
    }

    /** Escribe el texto achicando la fuente hasta que quepa en maxWidth. */
    private fun fitText(c: Canvas, p: Paint, text: String, x: Float, y: Float, maxWidth: Float, start: Float, tf: Typeface) {
        p.typeface = tf
        p.textAlign = Paint.Align.LEFT
        var size = start
        while (size > 12f) {
            p.textSize = size
            if (p.measureText(text) <= maxWidth) break
            size -= 2f
        }
        p.textSize = size
        c.drawText(text, x, y, p)
    }

    /** Carga una imagen de la galería reducida a ~1200 px para no gastar memoria. */
    fun loadImage(ctx: Context, uri: Uri, maxDim: Int = 1200): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
