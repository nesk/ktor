/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.tests.http.cio

import io.ktor.http.cio.*
import io.ktor.io.*
import io.ktor.io.EOFException
import kotlinx.coroutines.*
import kotlin.test.*
import kotlin.text.toByteArray

class ChunkedTest {
    @Test(expected = EOFException::class)
    fun testEmptyBroken(): Unit = runBlocking {
        val bodyText = ""
        val ch = ByteReadChannel(bodyText.toByteArray())
        ByteReadChannel {
            decodeChunked(ch, this)
        }.toByteArray()
    }

    @Test
    fun testChunkedWithContentLength(): Unit = runBlocking {
        val chunkedContent = listOf(
            "3\r\n",
            "a=1\r\n",
            "0\r\n",
            "\r\n",
        )

        val input = writer {
            chunkedContent.forEach {
                writeString(it)
            }
        }

        val output = writer {
            decodeChunked(input, this)
        }

        val content = output.readRemaining().readString()
        assertEquals("a=1", content)
    }

    @Test(expected = EOFException::class)
    fun testEmptyWithoutCrLf(): Unit = runBlocking {
        val bodyText = "0"
        val ch = ByteReadChannel(bodyText.toByteArray())

        ByteReadChannel {
            decodeChunked(ch, this)
        }.toByteArray()
    }

    @Test
    fun testEmpty(): Unit = runBlocking {
        val bodyText = "0\r\n\r\n"
        val ch = ByteReadChannel(bodyText.toByteArray())

        val parsed = ByteReadChannel {
            decodeChunked(ch, this)
        }

        assertEquals(0, parsed.availableForRead)
        assertFalse { parsed.awaitBytes() }
    }

    @Test
    fun testEmptyWithTrailing(): Unit = runBlocking {
        val bodyText = "0\r\n\r\ntrailing"
        val ch = ByteReadChannel(bodyText.toByteArray())
        val parsed = ByteReadChannel {
            decodeChunked(ch, this)
        }

        assertFalse { parsed.awaitBytes() }
        assertEquals("trailing", ch.readRemaining().readString())
    }

    @Test
    fun testContent(): Unit = runBlocking {
        val bodyText = "3\r\n123\r\n0\r\n\r\n"
        val ch = ByteReadChannel(bodyText.toByteArray())

        ByteReadChannel {
            decodeChunked(ch, this)
        }.stringReader { parsed ->
            assertEquals("123", parsed.readLine())
        }
    }

    @Test
    fun testContentMultipleChunks(): Unit = runBlocking {
        val bodyText = "3\r\n123\r\n2\r\n45\r\n1\r\n6\r\n0\r\n\r\n"
        val ch = ByteReadChannel(bodyText.toByteArray())
        ByteReadChannel {
            decodeChunked(ch, this)
        }.stringReader { parsed ->
            assertEquals("123456", parsed.readLine())
        }
    }

    @Test
    @Ignore
    fun testContentMixedLineEndings(): Unit = runBlocking {
        val bodyText = "3\n123\n2\r\n45\r\n1\r6\r0\r\n\n"
        val ch = ByteReadChannel(bodyText.toByteArray())
        ByteReadChannel {
            decodeChunked(ch, this)
        }.stringReader { parsed ->
            assertEquals("123456", parsed.readLine())
        }
    }

    @Test
    fun testEncodeEmpty() = runBlocking {
        val encoded = ByteReadChannel {
            encodeChunked(this, ByteReadChannel.Empty)
        }

        yield()
        val encodedText = encoded.readRemaining().readString()
        assertEquals("0\r\n\r\n", encodedText)
    }
}
