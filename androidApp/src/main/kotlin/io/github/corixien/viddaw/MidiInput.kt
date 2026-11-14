package io.github.corixien.viddaw

import android.content.Context
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
class MidiInput(context: Context, private val onNote: (pitch: Int, velocity: Int, on: Boolean) -> Unit) {
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
}
