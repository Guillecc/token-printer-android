package cl.guillecc.tokenprinter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.util.UUID
import kotlin.math.min

/** Impresión por Bluetooth clásico (SPP) a la impresora emparejada que se eligió. */
object Printer {
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private const val PREFS = "printer"
    private const val KEY_ADDRESS = "address"
    private const val KEY_NAME = "name"

    data class Saved(val address: String, val name: String)

    fun saved(ctx: Context): Saved? {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val address = p.getString(KEY_ADDRESS, null) ?: return null
        return Saved(address, p.getString(KEY_NAME, null) ?: address)
    }

    fun save(ctx: Context, address: String, name: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ADDRESS, address)
            .putString(KEY_NAME, name)
            .apply()
    }

    private fun adapter(ctx: Context): BluetoothAdapter? =
        ctx.getSystemService(BluetoothManager::class.java)?.adapter

    /** Dispositivos ya emparejados en Ajustes > Bluetooth (nombre, dirección). */
    @SuppressLint("MissingPermission")
    fun bonded(ctx: Context): List<Saved> {
        val a = adapter(ctx) ?: return emptyList()
        if (!a.isEnabled) return emptyList()
        return a.bondedDevices.orEmpty()
            .map { Saved(it.address, it.name ?: it.address) }
            .sortedBy { it.name.lowercase() }
    }

    /** Bloqueante: llamar desde un hilo de IO. */
    @SuppressLint("MissingPermission")
    fun print(ctx: Context, data: ByteArray) {
        val target = saved(ctx) ?: throw IOException("Elige una impresora primero")
        val a = adapter(ctx) ?: throw IOException("Este teléfono no tiene Bluetooth")
        if (!a.isEnabled) throw IOException("Activa el Bluetooth")
        connect(a.getRemoteDevice(target.address)).use { socket ->
            val out = socket.outputStream
            var i = 0
            // En trozos y con pausas cortas: muchas térmicas tienen buffer chico y pierden datos si se satura.
            while (i < data.size) {
                val n = min(1024, data.size - i)
                out.write(data, i, n)
                out.flush()
                i += n
                Thread.sleep(25)
            }
            Thread.sleep(800) // deja que termine de imprimir antes de cortar la conexión
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(device: BluetoothDevice): BluetoothSocket {
        val attempts = listOf<() -> BluetoothSocket>(
            { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
            // Último recurso para impresoras chinas que no publican el servicio SPP correctamente
            {
                device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 1) as BluetoothSocket
            },
        )
        var last: Exception? = null
        for (attempt in attempts) {
            val socket = try {
                attempt()
            } catch (e: Exception) {
                last = e
                continue
            }
            try {
                socket.connect()
                return socket
            } catch (e: IOException) {
                last = e
                runCatching { socket.close() }
            }
        }
        throw IOException("No se pudo conectar a la impresora. ¿Está encendida y cerca? (${last?.message})", last)
    }
}
