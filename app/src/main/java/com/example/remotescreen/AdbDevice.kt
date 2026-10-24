package com.example.remotescreen

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64 as JavaBase64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * Minimal ADB protocol client speaking directly to the device's adbd over TCP
 * (CNXN/AUTH/OPEN/WRTE/OKAY/CLSE), the same way scrcpy does. This replaces the bundled
 * adb binary: the old static build's server kept wedging transports on these ROMs
 * (offline state never recovered, `adb connect` could hang for 30s), and every spawned
 * process added hundreds of ms of latency.
 *
 * The first connection to a device shows the standard "allow USB debugging" dialog on
 * the target; the RSA keypair is generated once and persisted.
 */
class AdbDevice(val host: String, val port: Int) : Closeable {

    companion object {
        private const val TAG = "RemoteScreen"

        private const val A_CNXN = 0x4E584E43 // "CNXN"
        private const val A_AUTH = 0x48545541 // "AUTH"
        private const val A_OPEN = 0x4E45504F // "OPEN"
        private const val A_OKAY = 0x59414B4F // "OKAY"
        private const val A_WRTE = 0x45545257 // "WRTE"
        private const val A_CLSE = 0x45534C43 // "CLSE"

        private const val A_VERSION = 0x01000001
        private const val MAX_PAYLOAD = 256 * 1024
        private const val AUTH_TIMEOUT_MS = 120_000L
        private const val WRTE_CHUNK = 4096 // legacy-safe adb payload cap

        private val keyLock = Any()

        /** Key fingerprint (first 8 bytes of the pubkey struct, hex) for logging. */
        fun fingerprint(pair: KeyPair): String {
            val b = adbPubkeyLine(pair)
            val sb = StringBuilder()
            for (i in 0 until 8) sb.append(String.format("%02x", b[i]))
            return sb.toString()
        }

        /**
         * Loads (or creates) the persisted RSA keypair. Class-level lock: connect
         * threads from multiple controller instances may race here, and two concurrent
         * generations would silently replace each other. commit() (synchronous) so the
         * key cannot be lost to a pending async write at process death.
         */
        fun loadOrCreateKey(context: android.content.Context): KeyPair {
            synchronized(keyLock) {
                val prefs = context.getSharedPreferences("adb_keys", android.content.Context.MODE_PRIVATE)
                val privB64 = prefs.getString("priv_pkcs8", null)
                val factory = KeyFactory.getInstance("RSA")
                if (privB64 != null) {
                    try {
                        val priv = factory.generatePrivate(PKCS8EncodedKeySpec(JavaBase64.getDecoder().decode(privB64)))
                        val pub = factory.generatePublic(java.security.spec.X509EncodedKeySpec(
                            JavaBase64.getDecoder().decode(prefs.getString("pub_x509", ""))))
                        val loaded = KeyPair(pub, priv)
                        Log.i(TAG, "adb key loaded, fp=${fingerprint(loaded)}")
                        return loaded
                    } catch (e: Exception) {
                        Log.w(TAG, "stored adb key unreadable, regenerating", e)
                    }
                }
                val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                prefs.edit()
                    .putString("priv_pkcs8", JavaBase64.getEncoder().encodeToString(kp.private.encoded))
                    .putString("pub_x509", JavaBase64.getEncoder().encodeToString(kp.public.encoded))
                    .commit()
                Log.i(TAG, "adb key GENERATED, fp=${fingerprint(kp)}")
                return kp
            }
        }

        /**
         * Encodes the public key in adb's AndroidPubkey wire format: base64 of
         * {len=64; n0inv; modulus LE; rr = R^2 mod n LE; exponent} + " name\0". The
         * n0inv/rr fields are required — without them the framework silently drops the
         * key and no authorization dialog ever appears.
         */
        fun adbPubkeyLine(pair: KeyPair): ByteArray {
            val pub = pair.public as RSAPublicKey
            val n = pub.modulus
            var nBE = n.toByteArray() // big-endian, possibly with a leading zero
            if (nBE.size > 256) nBE = nBE.copyOfRange(nBE.size - 256, nBE.size)
            val nLE = ByteArray(256)
            for (i in nBE.indices) nLE[nBE.size - 1 - i] = nBE[i]

            val two32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = n.modInverse(two32).negate().mod(two32)

            val rr = BigInteger("2").pow(2 * n.bitLength()).mod(n)
            var rrBE = rr.toByteArray()
            if (rrBE.size > 256) rrBE = rrBE.copyOfRange(rrBE.size - 256, rrBE.size)
            val rrLE = ByteArray(256)
            for (i in rrBE.indices) rrLE[rrBE.size - 1 - i] = rrBE[i]

            val buf = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(64) // 2048 / 32
            buf.putInt(n0inv.toInt())
            buf.put(nLE)
            buf.put(rrLE)
            buf.putInt(pub.publicExponent.toInt())
            val line = JavaBase64.getEncoder().encodeToString(buf.array()) + " twinmirror@android\u0000"
            return line.toByteArray(Charsets.UTF_8)
        }
    }

    class UnauthorizedException(message: String) : IOException(message)

    private var socket: Socket? = null
    private var out: java.io.OutputStream? = null
    private val outLock = Any()
    private val streams = ConcurrentHashMap<Int, ShellStream>()
    private var nextLocalId = 1
    @Volatile private var pumpAlive = false
    private var keyPair: KeyPair? = null

    /** Blocks until the device is connected and authorized. */
    fun connect(keyPair: KeyPair, onWaitingAuth: (() -> Unit)? = null) {
        this.keyPair = keyPair
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 5_000)
            s.soTimeout = 10_000
            socket = s
            out = s.getOutputStream()

            send(
                Message(
                    A_CNXN, A_VERSION, MAX_PAYLOAD,
                    "host::features=shell_v2,cmd,stat_v2,ls_v2".toByteArray(Charsets.UTF_8)
                )
            )

            var sentPubkey = false
            var loggedWaiting = false
            var tokenCount = 0
            val deadline = System.currentTimeMillis() + AUTH_TIMEOUT_MS
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0) throw UnauthorizedException("目标设备授权超时（未在目标手机上允许调试）")
                s.soTimeout = remaining.coerceAtMost(10_000).toInt()
                val msg = try { readMessage(s.getInputStream()) } catch (e: java.net.SocketTimeoutException) {
                    if (loggedWaiting && sentPubkey) continue
                    throw IOException("连接目标设备无响应: ${e.message}")
                }
                when (msg.cmd) {
                    A_CNXN -> break
                    A_AUTH -> {
                        tokenCount++
                        // Signature-only on the first token (an authorized key connects
                        // without any dialog); pubkey on the second, after rejection.
                        if (respondToAuth(msg, sentPubkey, tokenCount >= 2, onWaitingAuth)) sentPubkey = true
                    }
                    else -> Log.w(TAG, "unexpected message during handshake: ${msg.cmd}")
                }
            }
        } catch (e: Exception) {
            pumpAlive = false
            try { s.close() } catch (_: Exception) {}
            socket = null
            out = null
            throw e
        }
        s.soTimeout = 0
        pumpAlive = true
        thread(name = "adb-pump", isDaemon = true) { pump() }
        Log.i(TAG, "adb connected to $host:$port")
    }

    /**
     * Legacy adb auth signature: PKCS#1 v1.5 over the SHA-1 DigestInfo prefix
     * concatenated with the RAW token — NO actual hashing. This mirrors the reference
     * client's RSA_sign(NID_sha1, token, ...) semantics (verified byte-identical
     * against a real adb client's signature on-device). A real SHA-1 digest here is
     * silently rejected by adbd, which makes every key look unauthorized.
     */
    private fun sign(pair: KeyPair, token: ByteArray): ByteArray {
        val digestInfo = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        ) + token
        val sig = Signature.getInstance("NONEwithRSA")
        sig.initSign(pair.private)
        sig.update(digestInfo)
        return sig.sign()
    }

    /**
     * Answers an A_AUTH token with our signature (and the public key once, which is what
     * triggers the authorization dialog on the target). Returns true when the public key
     * was newly sent. Some ROMs re-challenge with a fresh token even after CNXN, so the
     * pump uses this too.
     */
    private fun respondToAuth(
        msg: Message,
        pubkeySent: Boolean,
        allowPubkey: Boolean,
        onWaitingAuth: (() -> Unit)?
    ): Boolean {
        if (msg.arg0 != 1) return pubkeySent // A_AUTH token expected
        val pair = keyPair ?: return pubkeySent
        send(Message(A_AUTH, 2, 0, sign(pair, msg.data))) // SIGNATURE first, like the reference client
        if (pubkeySent || !allowPubkey) return pubkeySent
        val keyLine = adbPubkeyLine(pair)
        send(Message(A_AUTH, 3, 0, keyLine)) // RSAPUBLICKEY — only after a rejection
        onWaitingAuth?.invoke()
        Log.i(TAG, "waiting for user to authorize on the target device")
        return true
    }

    /**
     * Opens a raw connection to a device-local service (e.g. "localabstract:name") —
     * no shell, no v2 framing, bytes pass through untouched.
     */
    fun openRawStream(destination: String, timeoutMs: Long = 5_000): ShellStream {
        val localId = nextLocalId++
        val stream = ShellStream(localId, parseV2 = false)
        streams[localId] = stream
        try {
            send(Message(A_OPEN, localId, 0, destination.toByteArray(Charsets.UTF_8)))
            if (!stream.openAck.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw IOException("adb open timed out: $destination")
            }
            return stream
        } catch (e: Exception) {
            streams.remove(localId)
            throw e
        }
    }

    /**
     * Opens a stream over the shell v2 service (`shell,v2,TERM=dumb,raw:<command>`),
     * byte-identical to what the real adb client sends (verified on-device): raw stdout,
     * no PTY, and a trailing NUL on the destination — plain `exec:` opens were silently
     * ignored by modern adbd. The returned stream unwraps the v2 framing and yields the
     * command's raw stdout; EOF arrives with the exit message.
     */
    fun openStream(command: String, timeoutMs: Long = 5_000): ShellStream {
        val localId = nextLocalId++
        val stream = ShellStream(localId, parseV2 = true)
        streams[localId] = stream
        val destination = "shell,v2,TERM=dumb,raw:$command\u0000".toByteArray(Charsets.UTF_8)
        try {
            send(Message(A_OPEN, localId, 0, destination))
            if (!stream.openAck.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw IOException("adb open timed out: $command")
            }
            return stream
        } catch (e: Exception) {
            streams.remove(localId)
            throw e
        }
    }

    /** Runs a command to completion and returns its stdout. */
    fun exec(command: String, timeoutMs: Long = 5_000): String {
        val input = openStream(command, timeoutMs).input
        val sb = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        val deadline = System.currentTimeMillis() + timeoutMs
        try {
            while (System.currentTimeMillis() < deadline) {
                val n = input.read(buf)
                if (n < 0) break
                sb.write(buf, 0, n)
            }
        } finally {
            input.close()
        }
        return sb.toString(Charsets.UTF_8).trim()
    }

    override fun close() {
        pumpAlive = false
        for (st in streams.values) st.markClosed()
        streams.clear()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    private class Message(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun send(msg: Message) {
        val out = this.out ?: throw IOException("adb connection closed")
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        var checksum = 0L
        for (b in msg.data) checksum += (b.toInt() and 0xFF)
        header.putInt(msg.cmd).putInt(msg.arg0).putInt(msg.arg1)
            .putInt(msg.data.size).putInt(checksum.toInt())
            .putInt(msg.cmd.inv()) // magic: command ^ 0xffffffff
        synchronized(outLock) {
            out.write(header.array())
            out.write(msg.data)
            out.flush()
        }
    }

    private fun readMessage(input: InputStream): Message {
        val header = ByteArray(24)
        readFully(input, header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val dataLen = bb.int
        bb.int // data checksum — informational only
        bb.int // magic — not verified on receive
        val data = ByteArray(dataLen)
        if (dataLen > 0) readFully(input, data)
        return Message(cmd, arg0, arg1, data)
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var read = 0
        while (read < buf.size) {
            val n = input.read(buf, read, buf.size - read)
            if (n < 0) throw IOException("adb connection closed by device")
            read += n
        }
    }

    /** Dispatch loop: routes WRTE payloads to their streams, acks flow control. */
    private fun pump() {
        val s = socket ?: return
        try {
            s.soTimeout = 0
            while (pumpAlive) {
                val msg = readMessage(s.getInputStream())
                when (msg.cmd) {
                    A_WRTE -> {
                        val st = streams[msg.arg1]
                        if (st != null) {
                            st.enqueue(msg.data)
                            send(Message(A_OKAY, st.localId, msg.arg0, ByteArray(0)))
                        }
                    }
                    A_OKAY -> streams[msg.arg1]?.let {
                        it.remoteId = msg.arg0
                        it.openAck.countDown()
                        it.writeGateOpen()
                    }
                    A_CLSE -> {
                        val st = streams.remove(msg.arg1)
                        st?.markClosed()
                    }
                    // ROMs re-challenge mid-session; if the key was revoked this also
                    // re-triggers the authorization dialog so the user can re-allow it
                    A_AUTH -> respondToAuth(msg, false, true, null)
                    else -> Log.w(TAG, "pump: unexpected cmd ${msg.cmd}")
                }
            }
        } catch (e: Exception) {
            if (pumpAlive) Log.w(TAG, "adb pump died", e)
        } finally {
            pumpAlive = false
            for ((id, st) in streams) { st.markClosed(); streams.remove(id) }
        }
    }

    inner class ShellStream(val localId: Int, private val parseV2: Boolean = true) {
        val queue = LinkedBlockingQueue<ByteArray>()
        @Volatile var remoteId = -1
        @Volatile private var closed = false
        val openAck = CountDownLatch(1)
        val stderrBuf = java.io.ByteArrayOutputStream()
        val input: InputStream by lazy { inputStream() }

        // ADB flow control: one unacknowledged WRTE per stream. Closed before each
        // write, opened by the pump on OKAY.
        private var writeGate = java.util.concurrent.CountDownLatch(0)

        fun writeGateOpen() { writeGate.countDown() }

        private fun writeOne(payload: ByteArray) {
            val r = remoteId
            if (r <= 0) return
            synchronized(this) {
                // Bounded wait: an adbd that skips acks must not hard-block us
                try { writeGate.await(5, TimeUnit.SECONDS) } catch (_: InterruptedException) { return }
                val gate = java.util.concurrent.CountDownLatch(1)
                writeGate = gate
                try {
                    send(Message(A_WRTE, localId, r, payload))
                } catch (e: Exception) {
                    gate.countDown()
                    throw e
                }
            }
        }

        /** Writes data to the command's stdin. In shell v2, client->device payloads are
         *  v2-framed too: [id=0 STDIN][len u32 LE][data]. Data is chunked to the legacy
         *  4KB payload cap and paced one WRTE per OKAY. */
        fun writeStdin(data: ByteArray) {
            var off = 0
            while (off < data.size) {
                val n = minOf(WRTE_CHUNK, data.size - off)
                val chunk = data.copyOfRange(off, off + n)
                val payload: ByteArray = if (!parseV2) {
                    chunk
                } else {
                    val frame = ByteBuffer.allocate(5 + chunk.size).order(ByteOrder.LITTLE_ENDIAN)
                    frame.put(0) // ID STDIN
                    frame.putInt(chunk.size)
                    frame.put(chunk)
                    frame.array()
                }
                writeOne(payload)
                off += n
            }
        }

        /** Tells the shell stdin is finished (v2 close-stdin message). */
        fun closeStdin() {
            val r = remoteId
            if (r <= 0 || !parseV2) return
            val frame = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
            frame.put(4) // ID CLOSE_STDIN
            frame.putInt(0)
            writeOne(frame.array())
        }

        fun output(): java.io.OutputStream = object : java.io.OutputStream() {
            override fun write(b: Int) { writeStdin(byteArrayOf(b.toByte())) }
            override fun write(b: ByteArray) { writeStdin(b) }
            override fun write(b: ByteArray, off: Int, len: Int) { writeStdin(b.copyOfRange(off, off + len)) }
            override fun flush() {}
            override fun close() { this@ShellStream.close() }
        }

        fun enqueue(data: ByteArray) { queue.offer(data) }
        fun markClosed() { closed = true; queue.offer(ByteArray(0)) }

        fun close() {
            if (remoteId > 0) {
                try { send(Message(A_CLSE, localId, remoteId, ByteArray(0))) } catch (_: Exception) {}
            }
            markClosed()
            streams.remove(localId)
        }

        val stderrText: String
            get() = synchronized(stderrBuf) { stderrBuf.toString(Charsets.UTF_8).trim() }

        /** Unwraps the shell v2 protocol: [id u8][len u32 LE][payload], id 1 = stdout,
         *  2 = stderr, 3 = exit code (EOF). v2 messages may split across wire frames, so
         *  parsing is a byte-wise state machine over the reassembled queue. */
        fun inputStream(): InputStream = object : InputStream() {
            val pending = java.io.ByteArrayOutputStream()
            var pos = 0
            var eof = false
            var phase = 0 // 0 = id, 1 = length, 2 = payload
            var id = 0
            var need = 0
            val lenBuf = ByteArray(4)
            var lenPos = 0

            fun feed(b: Int) {
                when (phase) {
                    0 -> { id = b; phase = 1; lenPos = 0 }
                    1 -> {
                        lenBuf[lenPos++] = b.toByte()
                        if (lenPos == 4) {
                            need = ByteBuffer.wrap(lenBuf).order(ByteOrder.LITTLE_ENDIAN).int
                            phase = if (need > 0) 2 else 0
                        }
                    }
                    else -> {
                        when (id) {
                            1 -> pending.write(b) // stdout
                            2 -> synchronized(stderrBuf) { stderrBuf.write(b) }
                            3 -> eof = true       // exit code message
                        }
                        need--
                        if (need <= 0) phase = 0
                    }
                }
            }

            override fun read(): Int {
                val b = ByteArray(1)
                val n = read(b)
                return if (n < 0) -1 else (b[0].toInt() and 0xFF)
            }

            var rawRemainder: ByteArray? = null

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                while (true) {
                    if (!parseV2) {
                        // Partial chunks stay local: requeueing to the tail would put
                        // them behind chunks that arrived later and reorder the stream.
                        val rem = rawRemainder
                        if (rem != null) {
                            val n = minOf(len, rem.size)
                            System.arraycopy(rem, 0, b, off, n)
                            rawRemainder = if (n < rem.size) rem.copyOfRange(n, rem.size) else null
                            return n
                        }
                        if (eof && queue.isEmpty()) return -1
                        val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                        if (chunk.isEmpty()) return -1
                        val n = minOf(len, chunk.size)
                        System.arraycopy(chunk, 0, b, off, n)
                        if (n < chunk.size) rawRemainder = chunk.copyOfRange(n, chunk.size)
                        return n
                    }
                    if (pos < pending.size()) {
                        val bytes = pending.toByteArray()
                        val n = minOf(len, bytes.size - pos)
                        System.arraycopy(bytes, pos, b, off, n)
                        pos += n
                        return n
                    }
                    pending.reset()
                    pos = 0
                    if (eof && queue.isEmpty()) return -1
                    val chunk = queue.poll(200, TimeUnit.MILLISECONDS)
                        ?: continue // re-check eof/closed after waiting
                    if (chunk.isEmpty()) return -1 // close marker
                    for (byte in chunk) feed(byte.toInt() and 0xFF)
                }
            }

            override fun close() {
                eof = true
                if (remoteId > 0) {
                    try { send(Message(A_CLSE, localId, remoteId, ByteArray(0))) } catch (_: Exception) {}
                }
                streams.remove(localId)
            }
        }
    }
}
