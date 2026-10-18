package com.example.remotescreen

import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Sends scrcpy v1.18 control messages over the control socket.
 * Wire format verified against ControlMessageReader.java (v1.18):
 *
 * TYPE_INJECT_KEYCODE (type=0): action(1) keycode(4) repeat(4) metaState(4)  -> payload 13, total 14
 * TYPE_INJECT_TOUCH_EVENT (type=2): action(1) pointerId(8) x(4) y(4) sw(2) sh(2) pressure(2) buttons(4) -> payload 27, total 28
 * TYPE_BACK_OR_SCREEN_ON (type=4): action(1) -> payload 1, total 2
 */
class ControlChannel(private val out: OutputStream) {

    companion object {
        // MotionEvent actions
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2

        const val TYPE_INJECT_KEYCODE = 0
        const val TYPE_INJECT_TOUCH_EVENT = 2
        const val TYPE_BACK_OR_SCREEN_ON = 4

        const val NORMAL_PRESSURE = 0xffff
        const val NO_PRESSURE = 0

        private fun putInt(b: ByteArray, off: Int, v: Int) {
            b[off] = (v ushr 24).toByte(); b[off + 1] = (v ushr 16).toByte()
            b[off + 2] = (v ushr 8).toByte(); b[off + 3] = v.toByte()
        }

        private fun putShort(b: ByteArray, off: Int, v: Int) {
            b[off] = (v ushr 8).toByte(); b[off + 1] = v.toByte()
        }

        private fun putLong(b: ByteArray, off: Int, v: Long) {
            val bb = ByteBuffer.allocate(8).putLong(v)
            bb.flip()
            System.arraycopy(bb.array(), 0, b, off, 8)
        }
    }

    private val writeLock = Any()
    private var dead = false

    private fun send(bytes: ByteArray) {
        synchronized(writeLock) {
            if (dead) return
            try {
                out.write(bytes)
                out.flush()
            } catch (e: Exception) {
                dead = true
                throw e
            }
        }
    }

    /** Inject a keycode (down or up). action: 0=down 1=up */
    fun keycode(action: Int, keycode: Int) {
        val b = ByteArray(14)
        b[0] = TYPE_INJECT_KEYCODE.toByte()
        b[1] = action.toByte()
        putInt(b, 2, keycode)
        putInt(b, 6, 0) // repeat
        putInt(b, 10, 0) // metaState
        send(b)
    }

    /** Full key press: down + up */
    fun tapKey(keycode: Int) {
        keycode(ACTION_DOWN, keycode)
        keycode(ACTION_UP, keycode)
    }

    /** Inject touch event. Coordinates are in the (sw, sh) video frame. */
    fun touch(action: Int, x: Int, y: Int, sw: Int, sh: Int, pressure: Int) {
        val b = ByteArray(28)
        b[0] = TYPE_INJECT_TOUCH_EVENT.toByte()
        b[1] = action.toByte()
        putLong(b, 2, 0) // pointerId = 0
        putInt(b, 10, x)
        putInt(b, 14, y)
        putShort(b, 18, sw)
        putShort(b, 20, sh)
        putShort(b, 22, pressure)
        putInt(b, 24, if (action == ACTION_UP) 0 else 1) // buttons
        send(b)
    }

    /** Wake device / go back. Server: if screen off -> wake, else -> back key. */
    fun wakeOrBack() {
        val b = ByteArray(2)
        b[0] = TYPE_BACK_OR_SCREEN_ON.toByte()
        b[1] = ACTION_DOWN.toByte()
        send(b)
        b[1] = ACTION_UP.toByte()
        send(b)
    }

    fun isDead(): Boolean = dead
}
