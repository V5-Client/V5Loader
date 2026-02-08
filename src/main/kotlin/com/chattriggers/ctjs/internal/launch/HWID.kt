package com.chattriggers.ctjs.internal.launch

import java.net.NetworkInterface
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.*
import kotlin.math.abs

object HWID {
    private const val CUSTOM_ALPHABET = "ACDEFGHJKLMNPQRTUVWXY349782"
    private val MIX_PATTERN = intArrayOf(7, 3, 11, 5, 13, 2, 17, 9)

    fun generateHWID(): String {
        return try {
            val primaryData = collectPrimaryFingerprint()
            val secondaryData = collectSecondaryFingerprint()
            val networkData = collectNetworkFingerprint()

            val mixed = mixData(primaryData, secondaryData, networkData)

            val segment1 = encodeSegment(extractBytes(mixed, 0, 4))
            val segment2 = encodeSegment(extractBytes(mixed, 4, 4))
            val segment3 = encodeSegment(extractBytes(mixed, 8, 4))
            val checksum = generateChecksum(segment1, segment2, segment3)

            "V5-$segment1-$segment2-$segment3-$checksum"
        } catch (e: Exception) {
            "V5-${simpleHash()}"
        }
    }

    private fun collectPrimaryFingerprint(): ByteArray {
        val data = StringBuilder()
        data.append(System.getProperty("os.name", ""))
        data.append(System.getProperty("os.arch", ""))
        data.append(System.getProperty("os.version", ""))
        data.append(Runtime.getRuntime().availableProcessors())
        return hashData(data.toString())
    }

    private fun collectSecondaryFingerprint(): ByteArray {
        val data = StringBuilder()
        data.append(System.getProperty("user.name", ""))
        data.append(System.getProperty("user.home", "").hashCode())
        data.append(System.getProperty("file.separator", ""))
        return hashData(data.toString())
    }

    private fun collectNetworkFingerprint(): ByteArray {
        return try {
            val macs = ArrayList<String>()
            val networkInterfaces = NetworkInterface.getNetworkInterfaces()

            while (networkInterfaces.hasMoreElements()) {
                val ni = networkInterfaces.nextElement()
                val mac = ni.hardwareAddress
                if (mac != null && mac.size == 6 && !isVirtualMac(mac)) {
                    val macStr = StringBuilder()
                    for (b in mac) {
                        macStr.append(String.format("%02x", b))
                    }
                    macs.add(macStr.toString())
                }
            }
            macs.sort()

            val macData = StringBuilder()
            for (mac in macs) {
                macData.append(mac)
            }

            hashData(if (macData.isNotEmpty()) macData.toString() else "nomac")
        } catch (e: Exception) {
            hashData("nomac")
        }
    }

    private fun isVirtualMac(mac: ByteArray): Boolean {
        if (mac[0] == 0x00.toByte() && mac[1] == 0x00.toByte() && mac[2] == 0x00.toByte()) return true
        if (mac[0] == 0x00.toByte() && mac[1] == 0x05.toByte() && mac[2] == 0x69.toByte()) return true // VMware
        if (mac[0] == 0x00.toByte() && mac[1] == 0x0c.toByte() && mac[2] == 0x29.toByte()) return true // VMware
        if (mac[0] == 0x00.toByte() && mac[1] == 0x50.toByte() && mac[2] == 0x56.toByte()) return true // VMware
        if (mac[0] == 0x08.toByte() && mac[1] == 0x00.toByte() && mac[2] == 0x27.toByte()) return true // VirtualBox
        return false
    }

    private fun mixData(primary: ByteArray, secondary: ByteArray, network: ByteArray): ByteArray {
        val buffer = ByteBuffer.allocate(32)
        for (i in 0..31) {
            val pattern = MIX_PATTERN[i % MIX_PATTERN.size]
            var mixed = (primary[i % primary.size].toInt() xor
                    secondary[i % secondary.size].toInt() xor
                    network[i % network.size].toInt()).toByte()

            val mInt = mixed.toInt() and 0xFF
            mixed = ((mInt shl (pattern % 8)) or (mInt ushr (8 - (pattern % 8)))).toByte()
            buffer.put(mixed)
        }
        return hashData(String(buffer.array(), StandardCharsets.ISO_8859_1))
    }

    private fun extractBytes(data: ByteArray, offset: Int, length: Int): ByteArray {
        val result = ByteArray(length)
        for (i in 0 until length) {
            if (offset + i < data.size) {
                result[i] = data[offset + i]
            }
        }
        return result
    }

    private fun encodeSegment(data: ByteArray): String {
        var value: Long = 0
        for (b in data) {
            value = (value shl 8) or (b.toInt() and 0xFF).toLong()
        }

        val encoded = StringBuilder()
        val base = CUSTOM_ALPHABET.length

        repeat(5) {
            encoded.append(CUSTOM_ALPHABET[(abs(value) % base).toInt()])
            value /= base
        }
        return encoded.toString()
    }

    private fun generateChecksum(vararg segments: String): String {
        val combined = segments.joinToString("")
        val hash = hashData(combined)
        return encodeSegment(extractBytes(hash, 0, 2)).substring(0, 2)
    }

    private fun hashData(input: String): ByteArray {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.digest(input.toByteArray(StandardCharsets.UTF_8))
        } catch (e: Exception) {
            input.toByteArray(StandardCharsets.UTF_8)
        }
    }

    private fun simpleHash(): String {
        val sb = StringBuilder()
        sb.append(System.getProperty("os.name", ""))
        sb.append(System.getProperty("user.name", ""))
        return sb.toString().hashCode().toString().replace("-", "F")
    }
}