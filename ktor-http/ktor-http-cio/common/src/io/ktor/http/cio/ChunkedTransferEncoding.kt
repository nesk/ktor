/*
* Copyright 2014-2021 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
*/

package io.ktor.http.cio

import io.ktor.http.cio.internals.*
import io.ktor.io.*
import io.ktor.io.pool.*
import kotlinx.coroutines.*
import kotlin.coroutines.*
import kotlin.native.concurrent.*

private const val MAX_CHUNK_SIZE_LENGTH = 128
private const val CHUNK_BUFFER_POOL_SIZE = 2048

private val ChunkSizeBufferPool: ObjectPool<StringBuilder> =
    object : DefaultPool<StringBuilder>(CHUNK_BUFFER_POOL_SIZE) {
        override fun produceInstance(): StringBuilder = StringBuilder(MAX_CHUNK_SIZE_LENGTH)
        override fun clearInstance(instance: StringBuilder) = instance.apply { clear() }
    }

/**
 * Start a chunked stream decoder coroutine
 */
@Deprecated(
    "Specify content length if known or pass -1L",
    ReplaceWith("decodeChunked(input, -1L)")
)
public fun CoroutineScope.decodeChunked(input: ByteReadChannel): ByteReadChannel =
    decodeChunked(input, -1L)

/**
 * Start a chunked stream decoder coroutine
 */
@Suppress("UNUSED_PARAMETER")
public fun CoroutineScope.decodeChunked(
    input: ByteReadChannel,
    contentLength: Long
): ByteReadChannel = writer(coroutineContext) {
    decodeChunked(input, this)
}

/**
 * Decode chunked transfer encoding from the [input] channel and write the result in [out].
 *
 * @throws EOFException if stream has ended unexpectedly.
 * @throws ParserException if the format is invalid.
 */
public suspend fun decodeChunked(input: ByteReadChannel, out: ByteWriteChannel) {
    @Suppress("DEPRECATION_ERROR")
    return decodeChunked(input, out, -1L)
}

/**
 * Chunked stream decoding loop
 */
@Deprecated(
    "The contentLength is ignored for chunked transfer encoding",
    level = DeprecationLevel.ERROR,
    replaceWith = ReplaceWith("decodeChunked(input, out)")
)
@Suppress("UNUSED_PARAMETER")
public suspend fun decodeChunked(input: ByteReadChannel, out: ByteWriteChannel, contentLength: Long) {
    val chunkSizeBuffer = ChunkSizeBufferPool.borrow()
    var totalBytesCopied = 0L
    input.stringReader { reader ->
        try {
            while (true) {
                chunkSizeBuffer.clear()
                if (!reader.readLineTo(chunkSizeBuffer, MAX_CHUNK_SIZE_LENGTH.toLong())) {
                    throw EOFException("Chunked stream has ended unexpectedly: no chunk size")
                } else if (chunkSizeBuffer.isEmpty()) {
                    throw EOFException("Invalid chunk size: empty")
                }

                val chunkSize = if (chunkSizeBuffer.length == 1 && chunkSizeBuffer[0] == '0') {
                    0
                } else {
                    chunkSizeBuffer.parseHexLong()
                }

                if (chunkSize > 0) {
                    reader.copyTo(out, chunkSize)
                    out.flush()
                    totalBytesCopied += chunkSize
                }

                chunkSizeBuffer.clear()
                if (!reader.readLineTo(chunkSizeBuffer, 2)) {
                    throw EOFException("Invalid chunk: content block of size $chunkSize ended unexpectedly")
                }
                if (chunkSizeBuffer.isNotEmpty()) {
                    throw EOFException("Invalid chunk: content block should end with CR+LF")
                }

                if (chunkSize == 0L) break
            }
        } catch (cause: Throwable) {
            out.close(cause)
            throw cause
        } finally {
            ChunkSizeBufferPool.recycle(chunkSizeBuffer)
            out.close()
        }
    }
}

/**
 * Start chunked stream encoding coroutine
 */
@OptIn(DelicateCoroutinesApi::class)
public suspend fun encodeChunked(
    output: ByteWriteChannel,
    coroutineContext: CoroutineContext
): ByteWriteChannel = GlobalScope.reader(coroutineContext) {
    encodeChunked(output, this)
}

/**
 * Chunked stream encoding loop
 */
public suspend fun encodeChunked(output: ByteWriteChannel, input: ByteReadChannel) {
    try {
        while (input.awaitBytes()) {
            val buffer = input.readBuffer()
            if (buffer.isEmpty) continue

            output.writeChunk(buffer)
        }

        output.writeByteArray(LastChunkBytes)
        input.rethrowCloseCause()
    } catch (cause: Throwable) {
        output.close(cause)
        input.cancel(cause)
    } finally {
        output.flush()
    }
}

private fun ByteReadChannel.rethrowCloseCause() {
    closedCause?.let { throw it }
}

private const val CrLfShort: Short = 0x0d0a
private val CrLf = "\r\n".toByteArray()
private val LastChunkBytes = "0\r\n\r\n".toByteArray()

private suspend fun ByteWriteChannel.writeChunk(buffer: ReadableBuffer) {
    val size = buffer.availableForRead
    writeIntHex(size)
    writeShort(CrLfShort)

    writeBuffer(buffer)
    writeByteArray(CrLf)
    flush()
}
