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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val Ink = Color(0xFF1B1B1F)
private val Field = Color(0xFF2A2A30)
private val Parchment = Color(0xFFF2EDE1)
private val Crimson = Color(0xFF7A1F2B)
private val Muted = Color(0xFF8B8578)
private val CardText = Color(0xFF2A2622)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Crimson, onPrimary = Parchment,
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

@Composable
fun App() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var query by remember { mutableStateOf("") }
    var lastSearched by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Cargando información...") }
    var results by remember { mutableStateOf<List<TokenEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<TokenEntry?>(null) }
    var showCustom by remember { mutableStateOf(false) }
    var showPrinters by remember { mutableStateOf(false) }
    var printer by remember { mutableStateOf(Printer.saved(ctx)) }
    var busy by remember { mutableStateOf<String?>(null) }

    // Permiso "Dispositivos cercanos" (Android 12+): se pide justo antes de usar Bluetooth.
    var afterPermission by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val action = afterPermission
        afterPermission = null
        if (granted) action?.invoke()
        else scope.launch { snackbar.showSnackbar("Sin el permiso de Dispositivos cercanos no se puede imprimir") }
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
            val r = withContext(Dispatchers.IO) { Scryfall.search(q) }
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
        status = withContext(Dispatchers.IO) {
            runCatching { Scryfall.freshness() }.getOrDefault(Scryfall.DEFAULT_STATUS)
        }
    }
    // Búsqueda mientras escribe (desde 3 letras, 450 ms después de la última tecla)
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 3) return@LaunchedEffect
        delay(450)
        if (q != lastSearched) doSearch(q)
    }

    fun sendToPrinter(bw: Bitmap, label: String) = scope.launch {
        busy = "Imprimiendo..."
        try {
            withContext(Dispatchers.IO) { Printer.print(ctx, Thermal.escPos(bw)) }
            snackbar.showSnackbar("$label enviado a la impresora")
        } catch (e: Exception) {
            snackbar.showSnackbar(e.message ?: "Error al imprimir")
        } finally {
            busy = null
        }
    }

    fun print(entry: TokenEntry, share: Boolean) {
        if (!share && printer == null) {
            withBluetooth { showPrinters = true }
            return
        }
        val run = {
            scope.launch {
                busy = "Preparando..."
                try {
                    val bw = withContext(Dispatchers.IO) {
                        val src = entry.bitmap ?: Scryfall.downloadBitmap(entry.fullUrl!!)
                        Thermal.dither(Thermal.compose(src, Thermal.labelFor(entry)))
                    }
                    if (share) {
                        shareImage(ctx, bw, entry.name)
                        busy = null
                    } else {
                        sendToPrinter(bw, entry.name)
                    }
                } catch (e: Exception) {
                    busy = null
                    snackbar.showSnackbar(e.message ?: "No se pudo preparar la imagen")
                }
            }
            Unit
        }
        if (share) run() else withBluetooth(run)
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
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val q = query.trim()
                        if (q.isNotEmpty()) scope.launch { doSearch(q) }
                    }),
                )
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showCustom = true }, modifier = Modifier.weight(1f)) {
                        Text("Token personalizado", maxLines = 1)
                    }
                    OutlinedButton(onClick = { withBluetooth { showPrinters = true } }, modifier = Modifier.weight(1f)) {
                        Text(printer?.name ?: "Elegir impresora", maxLines = 1)
                    }
                }
            }
            Text(
                status, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(results) { entry -> TokenCard(entry) { selected = entry } }
            }
        }
    }

    selected?.let { entry ->
        Viewer(
            entry = entry,
            busy = busy,
            onPrint = { print(entry, share = false) },
            onShare = { print(entry, share = true) },
            onClose = { selected = null },
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
                sendToPrinter(Thermal.dither(Thermal.testPage(p.name)), "Prueba")
            },
            onDismiss = { showPrinters = false },
        )
    }
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
            Text(entry.setName.uppercase(), color = Muted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun Viewer(entry: TokenEntry, busy: String?, onPrint: () -> Unit, onShare: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxSize().background(Color(0xF00A0A0C)).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                TextButton(onClick = onClose) { Text("✕", fontSize = 22.sp, color = Parchment) }
            }
            val imageModifier = Modifier.widthIn(max = 420.dp).heightIn(max = 480.dp)
            if (entry.bitmap != null) {
                Image(entry.bitmap.asImageBitmap(), entry.name, modifier = imageModifier)
            } else {
                AsyncImage(model = entry.fullUrl, contentDescription = entry.name, modifier = imageModifier)
            }
            Text(
                entry.name, color = Parchment, fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
            )
            Text(entry.setName, color = Muted, fontSize = 13.sp)
            Spacer(Modifier.size(20.dp))
            Row(Modifier.widthIn(max = 420.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onPrint, enabled = busy == null, modifier = Modifier.weight(2f)) {
                    Text(busy ?: "Imprimir", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(onClick = onShare, enabled = busy == null, modifier = Modifier.weight(1f)) {
                    Text("Compartir")
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
                        val active = c in colors
                        Box(
                            Modifier.size(38.dp)
                                .background(if (active) Crimson else Field, CircleShape)
                                .clickable { if (active) colors.remove(c) else colors.add(c) },
                            contentAlignment = Alignment.Center,
                        ) { Text(c, color = Parchment, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) }
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
            }) { Text("Generar") }
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
            Button(onClick = onTest, enabled = current != null && busy == null) {
                Text(busy ?: "Imprimir prueba")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
    )
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
