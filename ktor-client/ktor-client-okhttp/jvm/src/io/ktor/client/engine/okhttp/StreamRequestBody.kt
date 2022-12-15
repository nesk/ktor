/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.client.engine.okhttp

import io.ktor.io.*
import io.ktor.io.jvm.javaio.*
import okhttp3.*
import okio.*
import io.ktor.io.use

internal class StreamRequestBody(
    private val contentLength: Long?,
    private val block: () -> ByteReadChannel
) : RequestBody() {

    override fun contentType(): MediaType? = null

    override fun writeTo(sink: BufferedSink) {
        block().toInputStream().source().use {
            sink.writeAll(it)
        }
    }

    override fun contentLength(): Long = contentLength ?: -1

    override fun isOneShot(): Boolean = true
}
