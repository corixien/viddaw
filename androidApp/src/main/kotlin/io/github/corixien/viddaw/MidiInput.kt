package io.github.corixien.viddaw

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper

/**
 * Listens to every connected MIDI device (USB, and Bluetooth once opened) and reports
 * note on/off on the main thread.
 */
class MidiInput(private val context: Context, private val onNote: (pitch: Int, velocity: Int, on: Boolean) -> Unit) {
    private val manager = context.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val main = Handler(Looper.getMainLooper())
    private val devices = HashMap<Int, MidiDevice>()

    private val receiver = object : MidiReceiver() {
        private var status = 0
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            var i = offset
            val end = offset + count
            while (i < end) {
                val b = msg[i].toInt() and 0xFF
                if (b >= 0xF8) { i++; continue }               // real-time bytes may appear anywhere
                if (b and 0x80 != 0) {                          // status byte; system messages clear running status
                    status = if (b >= 0xF0) 0 else b
                    i++
                    continue
                }
                when (status and 0xF0) {
                    0x90, 0x80 -> {
                        if (i + 1 >= end) return
                        val velocity = msg[i + 1].toInt() and 0x7F
                        val on = status and 0xF0 == 0x90 && velocity > 0
                        main.post { onNote(b, if (on) velocity else 0, on) }
                        i += 2
                    }
                    0xA0, 0xB0, 0xE0 -> i += 2
                    else -> i++
                }
            }
        }
    }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = open(device)
        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            devices.remove(device.id)?.close()
        }
    }

    fun start() {
        val m = manager ?: return
        @Suppress("DEPRECATION")
        m.devices.forEach(::open)
        m.registerDeviceCallback(callback, main)
    }

    fun stop() {
        manager?.unregisterDeviceCallback(callback)
        devices.values.forEach { it.close() }
        devices.clear()
    }

    private fun open(info: MidiDeviceInfo) {
        if (info.outputPortCount == 0 || devices.containsKey(info.id)) return
        manager?.openDevice(info, { device -> if (device != null) adopt(device) }, main)
    }

    /** Starts listening to an opened device. Also used for Bluetooth devices opened by address. */
    fun adopt(device: MidiDevice) {
        devices.put(device.info.id, device)?.close()
        for (port in 0 until device.info.outputPortCount) device.openOutputPort(port)?.connect(receiver)
    }

    /** Scans a few seconds for BLE devices advertising the MIDI service. Returns (address, name). */
    @SuppressLint("MissingPermission")
    suspend fun scanBluetooth(): List<Pair<String, String>> {
        val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter?.bluetoothLeScanner ?: return emptyList()
        val found = LinkedHashMap<String, String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found[result.device.address] = result.device.name ?: result.device.address
            }
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")).build()
        return try {
            scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            delay(4000)
            scanner.stopScan(callback)
            found.map { it.key to it.value }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connectBluetooth(address: String): Boolean {
        val m = manager ?: return false
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
        val result = CompletableDeferred<Boolean>()
        return try {
            m.openBluetoothDevice(adapter.getRemoteDevice(address), { device ->
                if (device != null) adopt(device)
                result.complete(device != null)
            }, main)
            withTimeoutOrNull(10_000) { result.await() } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
