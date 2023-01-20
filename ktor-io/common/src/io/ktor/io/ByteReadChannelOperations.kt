/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.io

import io.ktor.io.charsets.*
import kotlinx.coroutines.*
import kotlin.contracts.*

public val ByteReadChannel.availableForRead: Int get() = readablePacket.availableForRead

/**
 * Read until [buffer] and discard it.
 *
 * @return consumed packet before [boundary] or `null` if EOF reached and no boundary found.
 *
 * If no boundary found, bytes are not consumed and available for reading.
 */
public suspend fun ByteReadChannel.readUntil(buffer: ReadableBuffer): Packet? {
    var boundaryStart: Int = -1
    awaitBytes {
        boundaryStart = readablePacket.indexOf(buffer)
        boundaryStart > 0
    }

    if (boundaryStart < 0) return null

    val result = readPacket(boundaryStart)
    discardExact(buffer.availableForRead.toLong())
    return result
}

/**
 * Discards exactly [n] bytes or fails if not enough bytes in the channel
 */
public suspend inline fun ByteReadChannel.discardExact(count: Long) {
    awaitBytes { availableForRead >= count }
    discard(count)
}

/**
 * Reads up to [limit] bytes from receiver channel and writes them to [dst] channel.
 * Closes [dst] channel if fails to read or write with cause exception.
 * @return a number of copied bytes
 */
public suspend fun ByteReadChannel.copyTo(dst: ByteWriteChannel, limit: Long = Long.MAX_VALUE): Long {
    if (limit == 0L) {
        return 0L
    }

    var remaining = limit
    while (remaining > 0 && awaitBytes()) {
        if (remaining >= readablePacket.availableForRead) {
            remaining -= readablePacket.availableForRead
            dst.writePacket(readablePacket)
        } else {
            val packet = readablePacket.readPacket(remaining.toInt())
            dst.writePacket(packet)
            remaining = 0
        }

        dst.flush()
    }

    dst.flush()
    return limit - remaining
}

/**
 * Reads all the bytes from receiver channel and writes them to [dst] channel and then closes it.
 * Closes [dst] channel if fails to read or write with cause exception.
 * @return a number of copied bytes
 */
public suspend fun ByteReadChannel.copyAndClose(dst: ByteWriteChannel, limit: Long = Long.MAX_VALUE): Long {
    val count = copyTo(dst, limit)
    dst.close()
    return count
}

public suspend fun ByteReadChannel.readBuffer(): ReadableBuffer {
    if (!awaitBytes()) return ReadableBuffer.Empty
    return readablePacket.readBuffer()
}

/**
 * Reads a long number (suspending if not enough bytes available) or fails if channel has been closed
 * and not enough bytes.
 */
public suspend fun ByteReadChannel.readLong(): Long {
    awaitBytes { availableForRead >= 8 }
    return readablePacket.readLong()
}

/**
 * Reads an int number (suspending if not enough bytes available) or fails if channel has been closed
 * and not enough bytes.
 */
public suspend fun ByteReadChannel.readInt(): Int {
    awaitBytes { availableForRead >= 4 }
    return readablePacket.readInt()
}

/**
 * Reads a short number (suspending if not enough bytes available) or fails if channel has been closed
 * and not enough bytes.
 */
public suspend fun ByteReadChannel.readShort(): Short {
    awaitBytes { availableForRead >= 2 }
    return readablePacket.readShort()
}

/**
 * Reads a byte (suspending if no bytes available yet) or fails if channel has been closed
 * and not enough bytes.
 */
public suspend fun ByteReadChannel.readByte(): Byte {
    awaitBytes()
    return readablePacket.readByte()
}

/**
 * Read [ByteArray] of provided [size].
 *
 * @throws EOFException if channel is closed and not enough bytes available.
 */
public suspend fun ByteReadChannel.readByteArray(size: Int): ByteArray {
    require(size >= 0)
    if (size == 0) return ByteArray(0)

    awaitBytes { availableForRead >= size }
    if (availableForRead < size) throw EOFException("Not enough bytes available: $size, available: $availableForRead")
    return readablePacket.readByteArray(size)
}

/**
 * Reads a boolean value (suspending if no bytes available yet) or fails if channel has been closed
 * and not enough bytes.
 */
public suspend fun ByteReadChannel.readBoolean(): Boolean = readByte().toInt() != 0

/**
 * Reads double number (suspending if not enough bytes available) or fails if channel has been closed
 * and not enough bytes.
 */
public suspend fun ByteReadChannel.readDouble(): Double {
    awaitBytes { availableForRead >= 8 }
    return Double.fromBits(readablePacket.readLong())
}

/**
 * Reads float number (suspending if not enough bytes available) or fails if channel has been closed
 * and not enough bytes.
 */
public fun ByteReadChannel.readFloat(): Float {
    check(availableForRead >= 4) { "Not enough bytes available for readFloat: $availableForRead" }
    return Float.fromBits(readablePacket.readInt())
}

/**
 * Reads the specified amount of bytes and makes a byte packet from them. Fails if channel has been closed
 * and not enough bytes available.
 */
public suspend fun ByteReadChannel.readPacket(size: Int): Packet {
    if (availableForRead < size) {
        awaitBytes { availableForRead >= size }
    }

    check(availableForRead >= size)

    val result = Packet()
    var remaining = size

    while (remaining > 0) {
        val buffer = readablePacket.peek()
        if (buffer.availableForRead < remaining) {
            remaining -= buffer.availableForRead
            result.writeBuffer(readablePacket.readBuffer())
        } else {
            result.writePacket(readablePacket.readPacket(remaining))
            remaining = 0
        }
    }

    return result
}

/**
 * Reads up to [limit] bytes and makes a byte packet or until end of stream encountered.
 */
public suspend fun ByteReadChannel.readRemaining(limit: Long = Long.MAX_VALUE): Packet = buildPacket {
    var remaining = limit
    while (awaitBytes()) {
        val packet = if (remaining >= readablePacket.availableForRead) {
            readablePacket
        } else {
            readablePacket.readPacket(remaining.toInt())
        }

        remaining -= packet.availableForRead
        writePacket(packet)
    }

    if (remaining > 0) {
        closedCause?.let { throw it }
    }
}

/**
 * Reads a line of UTF-8 characters to the specified [out] buffer up to [limit] characters.
 * Supports both CR-LF and LF line endings. No line ending characters will be appended to [out] buffer.
 * Throws an exception if the specified [limit] has been exceeded.
 *
 * @return `true` if line has been read (possibly empty) or `false` if channel has been closed
 * and no characters were read.
 */
@Deprecated(
    "Read line is deprecated",
    replaceWith = ReplaceWith("this.stringReader().use { it.readLineTo(out, limit) }"),
    level = DeprecationLevel.ERROR
)
public suspend fun <A : Appendable> ByteReadChannel.readUTF8LineTo(out: A, limit: Long = Long.MAX_VALUE): Boolean {
    TODO()
}

@Deprecated(
    "Read line is deprecated",
    replaceWith = ReplaceWith("this.stringReader(charset).use { it.readLine(limit) }"),
    level = DeprecationLevel.ERROR
)
public suspend fun ByteReadChannel.readLine(charset: Charset = Charsets.UTF_8, limit: Long = Long.MAX_VALUE): String? {
    TODO("Unsupported charset $charset")
}

/**
 * Discard up to [max] bytes
 *
 * @return number of bytes were discarded
 */
public fun ByteReadChannel.discard(max: Long = Long.MAX_VALUE): Long {
    return readablePacket.discard(max.toInt()).toLong()
}

public suspend fun ByteReadChannel.readString(charset: Charset = Charsets.UTF_8): String {
    return readRemaining().readString(charset)
}

/**
 * Convert [ByteReadChannel] to [ByteArray]
 */
public suspend fun ByteReadChannel.toByteArray(limit: Int = Int.MAX_VALUE): ByteArray =
    readRemaining(limit.toLong()).toByteArray()

/**
 * Executes [block] on [ByteWriteChannel] and close it down correctly whether an exception
 */
@OptIn(ExperimentalContracts::class)
public inline fun ByteWriteChannel.use(block: ByteWriteChannel.() -> Unit) {
    contract {
        callsInPlace(block, InvocationKind.EXACTLY_ONCE)
    }

    try {
        block()
    } catch (cause: Throwable) {
        close(cause)
        throw cause
    } finally {
        close()
    }
}

/**
 * Split source [ByteReadChannel] into 2 new one.
 * Cancel of one channel in split(input or both outputs) cancels other channels.
 */
public fun ByteReadChannel.split(coroutineScope: CoroutineScope): Pair<ByteReadChannel, ByteReadChannel> {
    val first = ConflatedByteChannel()
    val second = ConflatedByteChannel()

    coroutineScope.writer {
        copyToBoth(first, second)
    }

    return first to second
}

/**
 * Copy source channel to both output channels chunk by chunk, close output channels on completion.
 * If the source channel is closed with an exception, the first and second channels are closed with the same exception.
 */
public suspend fun ByteReadChannel.copyToBoth(first: ByteWriteChannel, second: ByteWriteChannel) {
    try {
        while (awaitBytes()) {
            val packet = readablePacket
            first.writePacket(packet.clone())
            first.flush()
            second.writePacket(packet)
            second.flush()
        }
    } catch (cause: Throwable) {
        first.close(cause)
        second.close(cause)
    } finally {
        first.close()
        second.close()
    }
}

/**
 * Read channel to byte array.
 */
public suspend fun ByteReadChannel.toByteArray(): ByteArray = readRemaining().toByteArray()
