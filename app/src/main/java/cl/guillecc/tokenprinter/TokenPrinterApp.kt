package cl.guillecc.tokenprinter

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.OkHttpClient

class TokenPrinterApp : Application(), ImageLoaderFactory {
    // El CDN de imágenes de Scryfall responde 400 al User-Agent por defecto de OkHttp ("okhttp/x.y"),
    // así que todas las imágenes se piden con uno propio.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        chain.proceed(chain.request().newBuilder().header("User-Agent", "TokenPrinter/1.0").build())
                    }
                    .build()
            }
            .crossfade(true)
            .build()
}
