/*
 * Copyright 2014-2022 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.utils.io

import io.ktor.io.*
import io.ktor.utils.io.charsets.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.errors.*

public val ByteReadChannel.availableForRead: Int get() = readablePacket.availableForRead

/**
 * Discards exactly [n] bytes or fails if not enough bytes in the channel
 */
public suspend inline fun ByteReadChannel.discardExact(n: Long) {
    awaitBytes { availableForRead >= n }
    discard(n)
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
    while (!isClosedForRead && remaining > 0) {
        if (readablePacket.isEmpty) {
            dst.flush()
            awaitBytes()
            continue
        }

        if (remaining >= readablePacket.availableForRead) {
            remaining -= readablePacket.availableForRead
            dst.writePacket(readablePacket)
        } else {
            val packet = readablePacket.readPacket(remaining.toInt())
            dst.writePacket(packet)
            remaining = 0
        }
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
    if (!isClosedForRead && availableForRead == 0) awaitBytes()
    if (isClosedForRead) return ReadableBuffer.Empty

    return readablePacket.readBuffer()
}

/**
 * Reads all available bytes to [dst] buffer and returns immediately or suspends if no bytes available
 * @return number of bytes were read or `-1` if the channel has been closed
 */
public fun ByteReadChannel.readAvailable(dst: ByteArray, offset: Int = 0, length: Int = dst.size): Int {
    if (isClosedForRead) return -1
    if (availableForRead == 0) return 0

    var position = offset
    while (availableForRead > 0 && position < offset + length) {
        dst[position++] = readablePacket.readByte()
    }

    return position - offset
}

public fun ByteReadChannel.readAvailableToArray(limit: Long = Long.MAX_VALUE): ByteArray {
    val result = ByteArray(minOf(limit, availableForRead.toLong()).toInt())
    readAvailable(result, 0, result.size)
    return result
}

public suspend fun ByteReadChannel.readFully(dst: ByteArray) {
    awaitBytes { availableForRead >= dst.size }
    TODO()
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
            result.writeBuffer(buffer.readBuffer(remaining))
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
    while (!isClosedForRead) {
        if (readablePacket.isEmpty) awaitBytes()
        val packet = if (remaining >= readablePacket.availableForRead) {
            readablePacket
        } else {
            readablePacket.readPacket(remaining.toInt())
        }

        remaining -= packet.availableForRead
        writePacket(packet)
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
public suspend fun <A : Appendable> ByteReadChannel.readUTF8LineTo(out: A, limit: Long = Long.MAX_VALUE): Boolean {
    if (isClosedForRead) return false
    if (readablePacket.isEmpty) awaitBytes()
    if (isClosedForRead) return false

    val buffer = readablePacket.peek()
    val oldIndex = buffer.readIndex
    val string = buffer.readString()

    val newLine = string.indexOf('\n')
    if (newLine == -1) {
        if (string.length > limit) {
            throw TooLongLineException("Line limit exceeded: $limit")
        }

        readablePacket.readBuffer()
        return readUTF8LineRemaining(string, out, limit)
    }

    val endIndex = if (newLine > 0 && string[newLine - 1] == '\r') newLine - 1 else newLine

    if (endIndex > limit) {
        throw TooLongLineException("Line length limit exceeded: $endIndex > $limit")
    }

    val bytesLength = string.lengthInUtf8Bytes(newLine + 1)
    buffer.readIndex = oldIndex
    readablePacket.discardExact(bytesLength)
    out.append(string, 0, endIndex)

    return true
}

private suspend fun <A : Appendable> ByteReadChannel.readUTF8LineRemaining(
    prefix: String,
    out: A,
    limit: Long
): Boolean {
    val builder = StringBuilder(prefix)
    var remaining = limit - prefix.length
    while (remaining > 0) {
        if (availableForRead == 0) awaitBytes()
        if (isClosedForRead) {
            out.append(builder)
            return false
        }

        val buffer = readablePacket.peek()
        val oldIndex = buffer.readIndex
        val string = buffer.readString()

        val newLine = string.indexOf('\n')
        if (newLine == -1) {
            readablePacket.readBuffer()

            if (string.length > remaining) {
                throw TooLongLineException("Line limit exceeded: $limit")
            }

            builder.append(string)
            remaining -= string.length
            continue
        }

        val endIndex = if (newLine > 0 && string[newLine - 1] == '\r') newLine - 1 else newLine
        if (endIndex > limit) {
            throw TooLongLineException("Line length limit exceeded: $endIndex > $limit")
        }

        val bytesLength = string.lengthInUtf8Bytes(newLine + 1)
        buffer.readIndex = oldIndex
        readablePacket.discardExact(bytesLength)
        builder.append(string, 0, endIndex)
        out.append(builder)
        return true
    }

    throw TooLongLineException("Line limit exceeded: $limit")
}

private fun String.lengthInUtf8Bytes(endIndex: Int): Int {
    var index = 0
    var result = 0
    while (index < endIndex) {
        val c = this[index++].code
        result += when {
            c < 0x80 -> 1
            c < 0x800 -> 2
            c < 0xd800 || c > 0xdfff -> 3
            else -> 4
        }
    }

    return result
}

public suspend fun ByteReadChannel.readLine(charset: Charset = Charsets.UTF_8, limit: Long = Long.MAX_VALUE): String? {
    if (charset == Charsets.UTF_8) {
        val builder = StringBuilder()
        val result = readUTF8LineTo(builder, limit)
        if (!result && builder.isEmpty()) return null
        return builder.toString()
    }

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
