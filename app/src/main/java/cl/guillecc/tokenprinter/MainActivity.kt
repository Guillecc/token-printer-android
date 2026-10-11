package cl.guillecc.tokenprinter

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

private val Ink = Color(0xFF1B1B1F)
private val Field = Color(0xFF2A2A30)
private val Parchment = Color(0xFFF2EDE1)
private val Crimson = Color(0xFF7A1F2B)
private val Muted = Color(0xFF8B8578)
private val CardText = Color(0xFF2A2622)
private val CardSubtext = Color(0xFF5E584C)

/** Botón principal en carmesí (el primary del tema es claro para que textos y bordes se lean sobre fondo oscuro). */
@Composable
private fun crimsonButton() = ButtonDefaults.buttonColors(containerColor = Crimson, contentColor = Parchment)

private val CompactPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Parchment, onPrimary = Ink,
                    background = Ink, onBackground = Parchment,
                    surface = Ink, onSurface = Parchment,
                    surfaceVariant = Field, onSurfaceVariant = Parchment,
                    surfaceContainerHigh = Field,
                    outline = Color(0xFF55555F),
                )
            ) { App() }
        }
    }
}

/** Serie de copias en curso: se imprime una, el usuario la corta y toca Siguiente. */
private class Series(val bw: Bitmap, val name: String, val total: Int, val printed: Int)

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun toast(msg: String) {
        scope.launch { snackbar.showSnackbar(msg) }
    }

    var query by remember { mutableStateOf("") }
    var lastSearched by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Cargando información...") }
    var results by remember { mutableStateOf<List<TokenEntry>>(emptyList()) }
    var recents by remember { mutableStateOf(Prefs.recents(ctx)) }
    val colorFilter = remember { mutableStateListOf<String>() }
    var selected by remember { mutableStateOf<TokenEntry?>(null) }
    var showCustom by remember { mutableStateOf(false) }
    var showPrinters by remember { mutableStateOf(false) }
    var printer by remember { mutableStateOf(Printer.saved(ctx)) }
    var busy by remember { mutableStateOf<String?>(null) }
    var series by remember { mutableStateOf<Series?>(null) }
    var brightness by remember { mutableStateOf(Prefs.brightness(ctx)) }
    var scale by remember { mutableStateOf(Prefs.scale(ctx)) }

    // Permiso "Dispositivos cercanos" (Android 12+): se pide justo antes de usar Bluetooth.
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = afterPermission
        afterPermission = null
        if (granted) action?.invoke() else toast("Sin el permiso de Dispositivos cercanos no se puede imprimir")
    }
    fun withBluetooth(action: () -> Unit) {
        if (Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
        } else {
            afterPermission = action
            permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    suspend fun doSearch(q: String) {
        lastSearched = q
        status = "Buscando..."
        results = emptyList()
        try {
            // Índice local si está; en vivo si no hay índice o si la consulta usa sintaxis de Scryfall (t:, c:...)
            val r = withContext(Dispatchers.IO) {
                (if (':' in q) null else TokenIndex.search(q)) ?: Scryfall.search(q)
            }
            results = r
            status = when (r.size) {
                0 -> "Sin resultados para \"$q\"."
                1 -> "1 versión encontrada — toca una para imprimir"
                else -> "${r.size} versiones encontradas — toca una para imprimir"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            status = "No se pudo conectar con Scryfall. Revisa tu conexión."
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { TokenIndex.load(ctx) }
        TokenIndex.freshness()?.let { status = it }
        // Actualiza el índice si tiene más de 24 h (o no existe); si falla, sigue con la copia anterior o en vivo
        withContext(Dispatchers.IO) { runCatching { TokenIndex.refreshIfStale(ctx) } }
        val fresh = TokenIndex.freshness()
            ?: withContext(Dispatchers.IO) { runCatching { Scryfall.freshness() }.getOrDefault(Scryfall.DEFAULT_STATUS) }
        if (lastSearched.isEmpty()) status = fresh // no pisar el resultado de una búsqueda ya hecha
    }
    // Búsqueda mientras escribe (desde 3 letras; con índice local casi sin espera, en vivo 450 ms)
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 3) return@LaunchedEffect
        delay(if (TokenIndex.ready) 150 else 450)
        if (q != lastSearched) doSearch(q)
    }

    /** Imagen lista para la térmica (B/N), con el tamaño y brillo elegidos. */
    suspend fun prepare(entry: TokenEntry): Bitmap = withContext(Dispatchers.IO) {
        val src = entry.bitmap ?: loadCached(ctx, entry.fullUrl!!)
        Thermal.dither(Thermal.compose(src, Thermal.labelFor(entry), scale, brightness))
    }

    /** Imprime una copia; devuelve false si falló (ya avisado al usuario). */
    suspend fun printOne(bw: Bitmap): Boolean {
        busy = "Imprimiendo..."
        return try {
            withContext(Dispatchers.IO) { Printer.print(ctx, Thermal.escPos(bw)) }
            true
        } catch (e: Exception) {
            toast(e.message ?: "Error al imprimir")
            false
        } finally {
            busy = null
        }
    }

    fun print(entry: TokenEntry, copies: Int) {
        if (printer == null) {
            withBluetooth { showPrinters = true }
            return
        }
        withBluetooth {
            scope.launch {
                busy = "Preparando..."
                val bw = try {
                    prepare(entry)
                } catch (e: Exception) {
                    busy = null
                    toast(e.message ?: "No se pudo preparar la imagen")
                    return@launch
                }
                if (!printOne(bw)) return@launch
                recents = Prefs.addRecent(ctx, entry)
                if (copies > 1) series = Series(bw, entry.name, copies, 1)
                else toast("${entry.name} enviado a la impresora")
            }
        }
    }

    fun printNext() {
        val s = series ?: return
        withBluetooth {
            scope.launch {
                if (!printOne(s.bw)) return@launch
                series = if (s.printed + 1 >= s.total) {
                    toast("Listas las ${s.total} copias de ${s.name}")
                    null
                } else {
                    Series(s.bw, s.name, s.total, s.printed + 1)
                }
            }
        }
    }

    fun share(entry: TokenEntry) = scope.launch {
        busy = "Preparando..."
        try {
            shareImage(ctx, prepare(entry), entry.name)
        } catch (e: Exception) {
            toast(e.message ?: "No se pudo preparar la imagen")
        } finally {
            busy = null
        }
    }

    // Sin búsqueda se muestran los recientes; el filtro de color aplica a lo que se esté mostrando
    val showingRecents = query.isBlank()
    val source = if (showingRecents) recents else results
    val shown = source.filter { e ->
        colorFilter.all { c -> if (c == "C") e.colors.isEmpty() else c in e.colors }
    }

    Scaffold(
        containerColor = Ink,
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "Buscador de Tokens",
                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 24.sp,
                    color = Parchment,
                )
                Spacer(Modifier.size(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Nombre del token, ej: Zombie, Soldier...") },
                    trailingIcon = if (query.isNotEmpty()) {
                        { TextButton(onClick = { query = "" }) { Text("✕", color = Muted) } }
                    } else null,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val q = query.trim()
                        if (q.isNotEmpty()) scope.launch { doSearch(q) }
                    }),
                )
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { showCustom = true },
                        modifier = Modifier.weight(1f),
                        contentPadding = CompactPadding,
                    ) {
                        Text("Token personalizado", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(
                        onClick = { withBluetooth { showPrinters = true } },
                        modifier = Modifier.weight(1f),
                        contentPadding = CompactPadding,
                    ) {
                        Text("🖨 " + (printer?.name ?: "Elegir impresora"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.size(10.dp))
                // Filtro rápido por color (C = incoloro)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("W", "U", "B", "R", "G", "C").forEach { c ->
                        ColorPip(c, active = c in colorFilter, size = 34) {
                            if (c in colorFilter) colorFilter.remove(c) else colorFilter.add(c)
                        }
                    }
                }
            }
            Text(
                when {
                    source.isNotEmpty() && shown.isEmpty() -> "Ningún resultado con esos colores."
                    showingRecents && recents.isNotEmpty() -> "Recientes"
                    else -> status
                },
                color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(shown) { entry -> TokenCard(entry) { selected = entry } }
            }
        }
    }

    selected?.let { entry ->
        Viewer(
            entry = entry,
            busy = busy,
            series = series,
            brightness = brightness,
            scale = scale,
            onBrightness = { brightness = it; Prefs.setBrightness(ctx, it) },
            onScale = { scale = it; Prefs.setScale(ctx, it) },
            preparePreview = { prepare(entry) },
            onPrint = { copies -> print(entry, copies) },
            onNext = { printNext() },
            onStopSeries = { series = null },
            onShare = { share(entry) },
            onClose = {
                selected = null
                series = null
            },
        )
    }

    if (showCustom) {
        CustomTokenDialog(
            onDismiss = { showCustom = false },
            onCreate = { entry ->
                showCustom = false
                selected = entry
            },
        )
    }

    if (showPrinters) {
        PrinterDialog(
            current = printer,
            busy = busy,
            onPick = {
                Printer.save(ctx, it.address, it.name)
                printer = it
            },
            onTest = {
                val p = printer ?: return@PrinterDialog
                scope.launch { if (printOne(Thermal.dither(Thermal.testPage(p.name)))) toast("Prueba enviada a la impresora") }
            },
            onDismiss = { showPrinters = false },
        )
    }
}

@Composable
private fun ColorPip(c: String, active: Boolean, size: Int = 38, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp)
            .background(if (active) Crimson else Field, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(c, color = Parchment, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) }
}

@Composable
private fun TokenCard(entry: TokenEntry, onClick: () -> Unit) {
    Card(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Parchment),
    ) {
        AsyncImage(
            model = entry.thumbUrl,
            contentDescription = entry.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(5f / 7f).background(Color(0xFFE6DED0)),
        )
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp)) {
            Text(entry.name, color = CardText, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(entry.setName.uppercase(), color = CardSubtext, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Viewer(
    entry: TokenEntry,
    busy: String?,
    series: Series?,
    brightness: Float,
    scale: Float,
    onBrightness: (Float) -> Unit,
    onScale: (Float) -> Unit,
    preparePreview: suspend () -> Bitmap,
    onPrint: (copies: Int) -> Unit,
    onNext: () -> Unit,
    onStopSeries: () -> Unit,
    onShare: () -> Unit,
    onClose: () -> Unit,
) {
    var copies by remember { mutableStateOf(1) }
    var showPreview by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    // Recalcula la vista previa al activarla o al cambiar brillo/tamaño (con una pequeña espera mientras se arrastra)
    LaunchedEffect(showPreview, brightness, scale) {
        if (!showPreview) return@LaunchedEffect
        delay(150)
        preview = runCatching { preparePreview() }.getOrNull()
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color(0xF00A0A0C)).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                TextButton(onClick = onClose) { Text("✕", fontSize = 22.sp, color = Parchment) }
            }
            val imageModifier = Modifier.widthIn(max = 420.dp).heightIn(max = 400.dp)
            when {
                showPreview -> {
                    val p = preview
                    Box(imageModifier.aspectRatio(5f / 7f).background(Color.White), contentAlignment = Alignment.Center) {
                        if (p != null) {
                            // Sin suavizado: así se ven los puntos tal como los imprime la térmica
                            Image(
                                p.asImageBitmap(), "Vista previa térmica",
                                filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Text("Generando vista previa...", color = CardText)
                        }
                    }
                }
                entry.bitmap != null -> Image(entry.bitmap.asImageBitmap(), entry.name, modifier = imageModifier)
                else -> {
                    // La miniatura ya está en caché: se ve al instante y la grande la reemplaza al llegar
                    Box(imageModifier.aspectRatio(5f / 7f)) {
                        AsyncImage(model = entry.thumbUrl, contentDescription = null, modifier = Modifier.fillMaxSize())
                        AsyncImage(model = entry.fullUrl, contentDescription = entry.name, modifier = Modifier.fillMaxSize())
                    }
                }
            }
            Text(
                entry.name, color = Parchment, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
            )
            Text(entry.setName, color = Muted, fontSize = 13.sp)
            Spacer(Modifier.size(12.dp))

            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (series != null) {
                    // Pausa entre copias para cortar con el cortador de la impresora
                    Text(
                        "Copia ${series.printed} de ${series.total} impresa. Córtala y toca Siguiente.",
                        color = Parchment, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onNext, enabled = busy == null, colors = crimsonButton(),
                            modifier = Modifier.weight(1f), contentPadding = CompactPadding,
                        ) {
                            Text(
                                busy ?: "Siguiente (${series.printed + 1}/${series.total})",
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = onStopSeries, enabled = busy == null,
                            modifier = Modifier.weight(1f), contentPadding = CompactPadding,
                        ) { Text("Terminar", maxLines = 1) }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Copias", color = Muted, modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { if (copies > 1) copies-- }, contentPadding = CompactPadding) { Text("−") }
                        Text(
                            "$copies", color = Parchment, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 28.dp),
                        )
                        OutlinedButton(onClick = { if (copies < 20) copies++ }, contentPadding = CompactPadding) { Text("+") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onPrint(copies) }, enabled = busy == null, colors = crimsonButton(),
                            modifier = Modifier.weight(1f), contentPadding = CompactPadding,
                        ) {
                            Text(
                                busy ?: if (copies > 1) "Imprimir ×$copies" else "Imprimir",
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = onShare, enabled = busy == null,
                            modifier = Modifier.weight(1f), contentPadding = CompactPadding,
                        ) {
                            Text("Compartir", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // Ajustes de impresión (se guardan y aplican a todos los tokens)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Vista previa térmica", color = Parchment, modifier = Modifier.weight(1f))
                    Switch(checked = showPreview, onCheckedChange = { showPreview = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Tamaño", color = Muted, modifier = Modifier.weight(1f))
                    listOf("Normal" to Prefs.SCALE_NORMAL, "Compacto" to Prefs.SCALE_COMPACT).forEach { (label, value) ->
                        OutlinedButton(
                            onClick = { onScale(value) },
                            contentPadding = CompactPadding,
                            colors = if (scale == value) crimsonButton() else ButtonDefaults.outlinedButtonColors(),
                        ) { Text(label, maxLines = 1) }
                    }
                }
                Column {
                    Row {
                        Text("Brillo", color = Muted, modifier = Modifier.weight(1f))
                        Text("más oscuro ← → más claro", color = Muted, fontSize = 12.sp)
                    }
                    Slider(value = brightness, onValueChange = onBrightness, valueRange = 0.8f..1.4f)
                }
            }
        }
    }
}

@Composable
private fun CustomTokenDialog(onDismiss: () -> Unit, onCreate: (TokenEntry) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("") }
    var power by remember { mutableStateOf("") }
    var toughness by remember { mutableStateOf("") }
    val colors = remember { mutableStateListOf<String>() }
    var image by remember { mutableStateOf<Uri?>(null) }
    var working by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { image = it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Token personalizado", fontFamily = FontFamily.Serif) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, placeholder = { Text("Nombre") }, singleLine = true)
                OutlinedTextField(type, { type = it }, placeholder = { Text("Tipo (ej: Creature — Goblin)") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val numeric = KeyboardOptions(keyboardType = KeyboardType.Number)
                    OutlinedTextField(power, { power = it }, placeholder = { Text("Fuerza") }, singleLine = true,
                        keyboardOptions = numeric, modifier = Modifier.weight(1f))
                    Text("/", color = Muted, fontSize = 18.sp)
                    OutlinedTextField(toughness, { toughness = it }, placeholder = { Text("Resist.") }, singleLine = true,
                        keyboardOptions = numeric, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("W", "U", "B", "R", "G").forEach { c ->
                        ColorPip(c, active = c in colors) { if (c in colors) colors.remove(c) else colors.add(c) }
                    }
                }
                OutlinedButton(
                    onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth(),
                    border = BorderStroke(1.dp, Color(0xFF55555F)),
                ) { Text(if (image != null) "Imagen elegida ✓" else "Subir imagen (opcional)") }
            }
        },
        confirmButton = {
            Button(enabled = !working, onClick = {
                working = true
                scope.launch {
                    val n = name.trim().ifEmpty { "Token" }
                    val t = type.trim().ifEmpty { "Token" }
                    val isEmblem = t.contains("emblem", ignoreCase = true)
                    val bmp = withContext(Dispatchers.IO) {
                        // si la imagen falla al cargar, se genera igual sin ilustración
                        val art = image?.let { runCatching { CustomToken.loadImage(ctx, it) }.getOrNull() }
                        CustomToken.draw(n, t, power.trim(), toughness.trim(), art, isEmblem)
                    }
                    onCreate(TokenEntry(n, "Token personalizado", colors.toList(), isEmblem, false, bitmap = bmp))
                }
            }, colors = crimsonButton()) { Text("Generar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun PrinterDialog(
    current: Printer.Saved?,
    busy: String?,
    onPick: (Printer.Saved) -> Unit,
    onTest: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val devices = remember { Printer.bonded(ctx) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Impresora Bluetooth") },
        text = {
            if (devices.isEmpty()) {
                Text(
                    "No hay dispositivos emparejados. Enciende la impresora, actívale el Bluetooth al teléfono " +
                        "y emparéjala en Ajustes > Bluetooth (PIN típico: 0000 o 1234). Luego vuelve aquí.",
                )
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(devices) { d ->
                        val active = d.address == current?.address
                        Column(
                            Modifier.fillMaxWidth()
                                .background(if (active) Crimson else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { onPick(d) }
                                .padding(12.dp),
                        ) {
                            Text(d.name, fontWeight = FontWeight.SemiBold)
                            Text(d.address, color = if (active) Parchment else Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onTest, enabled = current != null && busy == null, colors = crimsonButton()) {
                Text(busy ?: "Imprimir prueba")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
    )
}

/** Usa la caché de Coil (la misma imagen que se ve en el visor) en vez de descargarla de nuevo. */
private suspend fun loadCached(ctx: Context, url: String): Bitmap {
    val request = ImageRequest.Builder(ctx).data(url).allowHardware(false).build()
    val result = ctx.imageLoader.execute(request)
    return (result as? SuccessResult)?.drawable?.toBitmap()
        ?: throw IOException("No se pudo descargar la imagen. Revisa tu conexión.")
}

/** Respaldo: comparte el PNG ya procesado (por ejemplo a RawBT). */
private suspend fun shareImage(ctx: Context, bmp: Bitmap, name: String) {
    val file = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        File(dir, name.replace(Regex("[^A-Za-z0-9]"), "_").ifEmpty { "token" } + ".png").also { f ->
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType("image/png")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Compartir token"))
}
