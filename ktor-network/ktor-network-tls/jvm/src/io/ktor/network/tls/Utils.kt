/*
 * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package io.ktor.network.tls

import io.ktor.io.*
import java.security.*

internal fun Digest(): Digest = Digest(Packet())

@JvmInline
internal value class Digest(val state: Packet) : Closeable {

    fun update(packet: Packet) = synchronized(state) {
        if (packet.isEmpty) return
        state.writePacket(packet.clone())
    }

    fun doHash(hashName: String): ByteArray = synchronized(state) {
        val data = state.clone()
        val digest = MessageDigest.getInstance(hashName)!!
        while (data.isNotEmpty) {
            val array = data.readBuffer().toByteArray()
            digest.update(array)
        }

        digest.digest()
    }

    override fun close() {
        state.close()
    }
}

internal operator fun Digest.plusAssign(record: TLSHandshake) {
    check(record.type != TLSHandshakeType.HelloRequest)

    update(
        buildPacket {
            writeTLSHandshakeType(record.type, record.packet.availableForRead)
            if (record.packet.isNotEmpty) writePacket(record.packet.clone())
        }
    )
}
