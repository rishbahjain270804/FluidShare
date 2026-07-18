package com.ether.share.network

import android.util.Log
import com.ether.share.protocol.CHUNK_SIZE
import com.ether.share.protocol.FrameParser
import com.ether.share.protocol.MotionVector
import com.ether.share.protocol.encodeHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

data class CatchEvent(
    val motion: MotionVector,
    val mime: String,
    val name: String,
    val payloadLen: Long,
)

data class CompleteEvent(
    val buffer: ByteArray,
    val mime: String,
    val name: String,
    val motion: MotionVector,
)

class EtherReceiver(val port: Int = 0) {
    private val serverSocket = ServerSocket(port)
    val actualPort get() = serverSocket.localPort
    private val listeners = CopyOnWriteArrayList<(Event) -> Unit>()

    sealed class Event
    data class Catch(val info: CatchEvent) : Event()
    data class Complete(val info: CompleteEvent) : Event()
    data class Reject(val reason: String) : Event()

    fun onEvent(listener: (Event) -> Unit) {
        listeners.add(listener)
    }

    fun start() {
        Thread {
            while (!serverSocket.isClosed) {
                try {
                    val conn = serverSocket.accept()
                    handleConnection(conn)
                } catch (e: Exception) {
                    Log.e("EtherReceiver", "Accept error", e)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun handleConnection(conn: Socket) {
        Thread {
            try {
                val parser = FrameParser()
                var header: com.ether.share.protocol.FrameHeader? = null
                var caught = false
                val buffers = mutableListOf<ByteArray>()
                var sniff = ByteArray(0)
                var settled = false

                parser.processStream(conn.getInputStream()) { event ->
                    when (event) {
                        is FrameParser.RejectEvent -> {
                            if (!settled) {
                                settled = true
                                listeners.forEach { it(Reject(event.reason)) }
                                conn.close()
                            }
                        }

                        is FrameParser.HeaderEvent -> {
                            header = event.header
                        }

                        is FrameParser.PayloadEvent -> {
                            if (settled) return@processStream
                            buffers.add(event.chunk)
                            if (!caught) {
                                sniff += event.chunk
                                if (sniff.size >= 12 || sniff.size >= (header?.payloadLen ?: Long.MAX_VALUE)) {
                                    val mime = header?.mime ?: return@processStream
                                    if (imageSignatureMatches(sniff, mime)) {
                                        caught = true
                                        header?.let { h ->
                                            listeners.forEach {
                                                it(Catch(CatchEvent(
                                                    motion = h.motion,
                                                    mime = h.mime,
                                                    name = h.name,
                                                    payloadLen = h.payloadLen,
                                                )))
                                            }
                                        }
                                    } else {
                                        settled = true
                                        listeners.forEach { it(Reject("payload is not a valid image")) }
                                        conn.close()
                                    }
                                }
                            }
                        }

                        is FrameParser.EndEvent -> {
                            if (!settled) {
                                if (!caught || event.received != event.payloadLen) {
                                    settled = true
                                    listeners.forEach { it(Reject("incomplete transfer")) }
                                } else {
                                    settled = true
                                    val buffer = buffers.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
                                    header?.let { h ->
                                        listeners.forEach {
                                            it(Complete(CompleteEvent(
                                                buffer = buffer,
                                                mime = h.mime,
                                                name = h.name,
                                                motion = h.motion,
                                            )))
                                        }
                                    }
                                }
                            }
                            conn.close()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("EtherReceiver", "Connection error", e)
            }
        }.apply { isDaemon = true }.start()
    }

    fun close() {
        serverSocket.close()
    }
}

class EtherSender {
    suspend fun send(
        host: String,
        port: Int,
        buffer: ByteArray,
        mime: String,
        name: String,
        motion: MotionVector,
    ): Result<Int> = withContext(Dispatchers.IO) {
        try {
            Socket().use { sock ->
                sock.connect(InetSocketAddress(host, port), 1500)
                sock.tcpNoDelay = true
                val out = sock.outputStream
                val header = encodeHeader(mime, name, buffer.size.toLong(), motion)
                out.write(header)
                out.flush()

                var offset = 0
                while (offset < buffer.size) {
                    val end = kotlin.math.min(offset + CHUNK_SIZE, buffer.size)
                    out.write(buffer, offset, end - offset)
                    offset = end
                }
                out.flush()
                sock.shutdownOutput()
                Result.success(buffer.size)
            }
        } catch (e: Exception) {
            Log.e("EtherSender", "Send failed", e)
            Result.failure(e)
        }
    }
}

// Image magic-number detection
fun imageSignatureMatches(head: ByteArray, declaredMime: String): Boolean {
    if (head.size < 3) return false
    val sigs = listOf(
        Triple(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), "image/jpeg", 0),
        Triple(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte()), "image/png", 0),
        Triple(byteArrayOf(0x47.toByte(), 0x49.toByte(), 0x46.toByte(), 0x38.toByte()), "image/gif", 0),
    )
    return sigs.any { (magic, expectedMime, offset) ->
        if (offset + magic.size > head.size) return@any false
        head.sliceArray(offset until offset + magic.size).contentEquals(magic) &&
            declaredMime.lowercase().contains("image")
    }
}
