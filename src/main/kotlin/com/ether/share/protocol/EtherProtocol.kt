package com.ether.share.protocol

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32

const val MAGIC = 0x45544852u // "ETHR"
const val VERSION = 1
const val FLAG_SINGLE = 0x01
const val CHUNK_SIZE = 8 * 1024
const val MAX_MIME = 64
const val MAX_NAME = 255
const val MAX_PAYLOAD = 64 * 1024 * 1024L

const val FIXED_HEADER_LEN = 32 // bytes 0..31
const val CRC_OFFSET = 32
const val HEADER_MIN = 36 // fixed + CRC

data class MotionVector(
    val velocity: Float = 0f,
    val angle: Float = 0f,
    val exitX: Float = 0f,
)

data class FrameHeader(
    val version: Int,
    val flags: Int,
    val mime: String,
    val name: String,
    val payloadLen: Long,
    val motion: MotionVector,
)

// CRC-32 (IEEE 802.3, poly 0xEDB88320)
fun crc32(data: ByteArray, offset: Int = 0, len: Int = data.size): Long {
    val crc = CRC32()
    crc.update(data, offset, len)
    return crc.value
}

fun encodeHeader(mime: String, name: String, payloadLen: Long, motion: MotionVector): ByteArray {
    require(mime.length <= MAX_MIME) { "mime too long" }
    require(name.length <= MAX_NAME) { "name too long" }
    require(payloadLen in 0..MAX_PAYLOAD) { "payload out of range" }

    val mimeBytes = mime.toByteArray(Charsets.US_ASCII)
    val nameBytes = name.toByteArray(Charsets.UTF_8)

    val head = ByteBuffer.allocate(HEADER_MIN)
    head.putInt(MAGIC.toInt())
    head.put(VERSION.toByte())
    head.put(FLAG_SINGLE.toByte())
    head.putShort(mimeBytes.size.toShort())
    head.putShort(nameBytes.size.toShort())
    head.putShort(0) // reserved
    head.putLong(payloadLen)
    head.putFloat(motion.velocity)
    head.putFloat(motion.angle)
    head.putFloat(motion.exitX)

    val headerArray = head.array()
    val headerCrc = crc32(headerArray, 0, FIXED_HEADER_LEN)
    val crcBuf = ByteBuffer.allocate(4).apply { putInt(headerCrc.toInt()) }
    crcBuf.array().forEachIndexed { i, b -> headerArray[CRC_OFFSET + i] = b }

    return headerArray + mimeBytes + nameBytes
}

class FrameParser {
    sealed class Event
    data class HeaderEvent(val header: FrameHeader) : Event()
    data class PayloadEvent(val chunk: ByteArray) : Event()
    data class EndEvent(val payloadLen: Long, val received: Long) : Event()
    data class RejectEvent(val reason: String) : Event()

    private var state = State.FIXED
    private var buffer = ByteArray(0)
    private var header: FrameHeader? = null
    private var received = 0L
    private var pending: Pending? = null

    private data class Pending(
        val flags: Int,
        val mimeLen: Int,
        val nameLen: Int,
        val payloadLen: Long,
        val motion: MotionVector,
    )

    private enum class State { FIXED, STRINGS, PAYLOAD, DONE }

    val events = mutableListOf<Event>()

    fun push(chunk: ByteArray) {
        buffer += chunk
        drain()
    }

    private fun drain() {
        while (true) {
            when (state) {
                State.FIXED -> {
                    if (buffer.size < HEADER_MIN) return
                    val h = buffer

                    if (h.sliceArray(0..3).contentEquals(MAGIC.toByteArray())) {
                        return reject("bad magic")
                    }
                    if (h[4].toInt() != VERSION) return reject("version mismatch")

                    val wantCrc = ByteBuffer.wrap(h, CRC_OFFSET, 4).int.toLong() and 0xFFFFFFFFL
                    val actualCrc = crc32(h, 0, FIXED_HEADER_LEN)
                    if (wantCrc != actualCrc) return reject("header corrupt")

                    val flags = h[5].toInt() and 0xFF
                    if ((flags and FLAG_SINGLE) == 0) return reject("multi-file not supported")

                    val mimeLen = ByteBuffer.wrap(h, 6, 2).short.toInt() and 0xFFFF
                    val nameLen = ByteBuffer.wrap(h, 8, 2).short.toInt() and 0xFFFF
                    if (mimeLen > MAX_MIME) return reject("mime too long")
                    if (nameLen > MAX_NAME) return reject("name too long")

                    val payloadLen = ByteBuffer.wrap(h, 12, 8).long
                    if (payloadLen > MAX_PAYLOAD) return reject("payload too large")

                    pending = Pending(
                        flags = flags,
                        mimeLen = mimeLen,
                        nameLen = nameLen,
                        payloadLen = payloadLen,
                        motion = MotionVector(
                            velocity = ByteBuffer.wrap(h, 20, 4).float,
                            angle = ByteBuffer.wrap(h, 24, 4).float,
                            exitX = ByteBuffer.wrap(h, 28, 4).float,
                        ),
                    )
                    buffer = buffer.drop(HEADER_MIN).toByteArray()
                    state = State.STRINGS
                }

                State.STRINGS -> {
                    val p = pending ?: return
                    val need = p.mimeLen + p.nameLen
                    if (buffer.size < need) return

                    val mime = buffer.sliceArray(0 until p.mimeLen).toString(Charsets.US_ASCII)
                    val name = buffer.sliceArray(p.mimeLen until need).toString(Charsets.UTF_8)
                    if (!mime.startsWith("image/")) return reject("non-image declared")

                    header = FrameHeader(
                        version = VERSION,
                        flags = p.flags,
                        mime = mime,
                        name = name,
                        payloadLen = p.payloadLen,
                        motion = p.motion,
                    )
                    events.add(HeaderEvent(header!!))
                    buffer = buffer.drop(need).toByteArray()
                    state = State.PAYLOAD
                }

                State.PAYLOAD -> {
                    val h = header ?: return
                    val remaining = h.payloadLen - received
                    if (remaining <= 0) {
                        finishPayload()
                        return
                    }
                    if (buffer.isEmpty()) return
                    val take = kotlin.math.min(remaining, buffer.size.toLong()).toInt()
                    events.add(PayloadEvent(buffer.sliceArray(0 until take)))
                    received += take
                    buffer = buffer.drop(take).toByteArray()
                    if (received >= h.payloadLen) finishPayload()
                }

                State.DONE -> return
            }
        }
    }

    private fun finishPayload() {
        state = State.DONE
        val h = header ?: return
        events.add(EndEvent(h.payloadLen, received))
    }

    private fun reject(reason: String) {
        state = State.DONE
        events.add(RejectEvent(reason))
    }

    fun processStream(input: InputStream, onEvent: (Event) -> Unit) {
        val buf = ByteArray(CHUNK_SIZE)
        while (state != State.DONE) {
            val n = input.read(buf)
            if (n <= 0) break
            push(buf.sliceArray(0 until n))
            events.forEach(onEvent)
            events.clear()
        }
    }
}

private fun UInt.toByteArray(): ByteArray {
    return ByteBuffer.allocate(4).putInt(this.toInt()).array()
}
