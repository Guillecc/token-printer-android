# Token Printer (Android)

App para el Xiaomi 14T que busca tokens de Magic en Scryfall (o crea uno personalizado) y los imprime
**directo** en una impresora térmica Bluetooth de 58 mm (ESC/POS), sin pasar por RawBT.
Versión nativa de [token-printer-app](https://github.com/Guillecc/token-printer-app).

## 1. Descargar e instalar

1. En el celular abre:
   **https://github.com/Guillecc/token-printer-android/releases/latest/download/TokenPrinter.apk**
2. Toca el archivo descargado. HyperOS va a pedir permiso para instalar apps desde el navegador:
   **Ajustes → activar "Permitir desde esta fuente"** y vuelve atrás.
3. Si aparece el aviso de seguridad de Xiaomi ("app no verificada"), elige **Instalar de todas formas**.
   (Si tienes activo "Protección de seguridad/escaneo de apps" puede pedirte esperar unos segundos.)

Cada versión nueva se instala encima de la anterior con el mismo link.

## 2. Emparejar la impresora (una sola vez)

1. Enciende la impresora.
2. En el teléfono: **Ajustes → Bluetooth → buscar dispositivos** y empareja la impresora
   (si pide PIN, normalmente es `0000` o `1234`).

## 3. Primera impresión de prueba

1. Abre **Token Printer** y toca **Elegir impresora**.
2. Acepta el permiso **Dispositivos cercanos** (lo necesita Android para usar Bluetooth).
3. Toca tu impresora en la lista y luego **Imprimir prueba**.
   Debe salir un ticket con "Token Printer — Impresión de prueba OK" y una barra degradada.
4. Luego busca un token (ej: `Zombie`), tócalo y presiona **Imprimir**.

## Si algo falla

| Problema | Solución |
|---|---|
| La lista de impresoras sale vacía | Activa el Bluetooth y empareja la impresora en Ajustes → Bluetooth. |
| "No se pudo conectar a la impresora" | Que esté encendida, cerca y **no conectada a otra app** (cierra RawBT u otras apps de impresión). |
| Imprime a medias o se corta | Ajustes → Apps → Token Printer → **Ahorro de batería → Sin restricciones**. |
| Salen caracteres raros en vez de la imagen | La impresora no soporta ESC/POS raster; usa el botón **Compartir** → RawBT como respaldo. |
| Permiso negado por error | Ajustes → Apps → Token Printer → Permisos → **Dispositivos cercanos → Permitir**. |

## Desarrollo

- Kotlin + Jetpack Compose, minSdk 26, targetSdk 35.
- El APK lo compila GitHub Actions en cada push a `main` (`.github/workflows/build.yml`) y lo publica como Release.
- Para compilar local: JDK 17 + Android SDK, luego `gradle assembleRelease`
  (no hay wrapper en el repo; el CI usa Gradle 8.11.1).
- `app/debug.keystore` es una llave de prueba incluida a propósito (no es secreta) para que las actualizaciones
  se instalen encima sin desinstalar.

Archivos principales en `app/src/main/java/cl/guillecc/tokenprinter/`:
`Scryfall.kt` (búsqueda), `CustomToken.kt` (token personalizado), `Thermal.kt` (escala a 384 px,
tramado Floyd–Steinberg y ESC/POS), `Printer.kt` (Bluetooth SPP), `MainActivity.kt` (UI).
