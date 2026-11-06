package com.tuissh.app.ssh.key

/**
 * Reader for the SSH wire encoding: length-prefixed strings and big-endian ints.
 * Mirrors `BinaryDataReader` from `SSHKeyFen`.
 */
class SshBinaryReader(private val data: ByteArray, private var offset: Int = 0) {

    val remaining: Int get() = data.size - offset

    fun readUInt32(): Long? {
        if (remaining < 4) return null
        val v = ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
        offset += 4
        return v
    }

    fun readString(): ByteArray? {
        val len = readUInt32()?.toInt() ?: return null
        if (len < 0 || len > remaining) return null
        val out = data.copyOfRange(offset, offset + len)
        offset += len
        return out
    }

    fun readStringAscii(): String? = readString()?.toString(Charsets.US_ASCII)

    fun readByte(): Int? {
        if (remaining < 1) return null
        return data[offset++].toInt() and 0xFF
    }
}

/** Tiny BER/DER reader, enough for RSA/EC/PKCS#8 introspection. */
class DerReader(private val data: ByteArray) {

    private var offset = 0

    fun readSequence(): ByteArray? {
        if (!readTag(0x30)) return null
        val len = readLength() ?: return null
        if (len > data.size - offset) return null
        val out = data.copyOfRange(offset, offset + len)
        offset += len
        return out
    }

    fun readInteger(): ByteArray? {
        if (!readTag(0x02)) return null
        val len = readLength() ?: return null
        if (len > data.size - offset) return null
        val out = data.copyOfRange(offset, offset + len)
        offset += len
        return out
    }

    fun readOctetString(): ByteArray? {
        if (!readTag(0x04)) return null
        val len = readLength() ?: return null
        if (len > data.size - offset) return null
        val out = data.copyOfRange(offset, offset + len)
        offset += len
        return out
    }

    fun readOid(): String? {
        if (!readTag(0x06)) return null
        val len = readLength() ?: return null
        if (len > data.size - offset) return null
        val out = StringBuilder()
        var value = 0
        for (i in 0 until len) {
            val b = data[offset + i].toInt() and 0xFF
            value = (value shl 7) or (b and 0x7F)
            if (b and 0x80 == 0) {
                if (out.isEmpty()) {
                    out.append(value / 40).append('.').append(value % 40)
                } else {
                    out.append('.').append(value)
                }
                value = 0
            }
        }
        offset += len
        return out.toString()
    }

    private fun readTag(expected: Int): Boolean {
        if (offset >= data.size) return false
        if (data[offset].toInt() and 0xFF != expected) return false
        offset++
        return true
    }

    private fun readLength(): Int? {
        if (offset >= data.size) return null
        val first = data[offset++].toInt() and 0xFF
        if (first and 0x80 == 0) return first
        val bytes = first and 0x7F
        if (bytes == 0 || bytes > 4 || offset + bytes > data.size) return null
        var len = 0
        for (i in 0 until bytes) len = (len shl 8) or (data[offset++].toInt() and 0xFF)
        return len
    }
}
