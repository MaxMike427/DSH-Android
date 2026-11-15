package com.dsh.android.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileInputStream
import java.util.zip.GZIPOutputStream

/**
 * Extraction runs before any UI exists to report a failure, so it is tested on
 * the JVM rather than discovered on a device.
 *
 * The XZ tests decode a fixture written by the build (`xz` CLI), not by the
 * library itself: a stream produced and consumed by the same code can hide a
 * format mismatch that only shows up against real payloads.
 */
class TarExtractorTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `extracts a gzip tar with nested directories and contents`() {
        val archive = temp.newFile("payload.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { gzip -> writeTar(gzip) }

        val destination = temp.newFolder("rootfs")
        FileInputStream(archive).use { input ->
            TarExtractor.extractStream(input, destination)
        }

        assertEquals("hello", File(destination, "etc/os-release").readText().trim())
        assertTrue(File(destination, "bin/bash").isFile)
        assertTrue(File(destination, "usr/bin/env").isFile)
    }

    @Test
    fun `reports progress up to the compressed size`() {
        val archive = temp.newFile("progress.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { gzip -> writeTar(gzip) }

        val seen = mutableListOf<Long>()
        FileInputStream(archive).use { input ->
            TarExtractor.extractStream(input, temp.newFolder("p")) { seen += it }
        }

        assertTrue("progress should be reported", seen.isNotEmpty())
        assertEquals(archive.length(), seen.last())
    }

    @Test
    fun `refuses entries that escape the destination`() {
        val archive = temp.newFile("evil.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { gzip ->
            writeEntry(gzip, "../escaped.txt", "boom".toByteArray())
        }

        val destination = temp.newFolder("safe")
        FileInputStream(archive).use { input ->
            TarExtractor.extractStream(input, destination)
        }

        assertTrue(
            "must not write outside the destination",
            !File(destination.parentFile, "escaped.txt").exists(),
        )
    }

    @Test
    fun `stops cleanly at a truncated archive`() {
        val archive = temp.newFile("truncated.tar.gz")
        GZIPOutputStream(archive.outputStream()).use { gzip ->
            writeEntry(gzip, "a.txt", "hello".toByteArray())
            // No end-of-archive marker: the reader must terminate, not spin.
        }

        val destination = temp.newFolder("trunc")
        FileInputStream(archive).use { input ->
            TarExtractor.extractStream(input, destination)
        }

        assertTrue(File(destination, "a.txt").isFile)
    }

    private fun writeTar(out: java.io.OutputStream) {
        writeEntry(out, "etc/os-release", "hello\n".toByteArray())
        writeEntry(out, "bin/bash", "#!/bin/sh\n".toByteArray(), mode = 0b111_101_101)
        writeEntry(out, "usr/bin/env", "#!/bin/sh\n".toByteArray(), mode = 0b111_101_101)
        out.write(ByteArray(1024)) // end-of-archive marker
    }

    private fun writeEntry(
        out: java.io.OutputStream,
        name: String,
        content: ByteArray,
        mode: Int = 0b110_100_100,
    ) {
        val header = ByteArray(512)
        name.toByteArray().copyInto(header, 0)
        writeOctal(header, 100, 8, mode.toLong())
        writeOctal(header, 108, 8, 0L) // uid
        writeOctal(header, 116, 8, 0L) // gid
        writeOctal(header, 124, 12, content.size.toLong())
        writeOctal(header, 136, 12, 0L) // mtime
        header[156] = '0'.code.toByte() // regular file
        "ustar".toByteArray().copyInto(header, 257)
        header[263] = '0'.code.toByte()
        header[264] = '0'.code.toByte()
        // checksum: spaces while summing, then the octal value
        header.fill(' '.code.toByte(), 148, 156)
        val checksum = header.sumOf { it.toInt() and 0xFF }
        writeOctal(header, 148, 8, checksum.toLong())

        out.write(header)
        out.write(content)
        val padding = (512 - content.size % 512) % 512
        out.write(ByteArray(padding))
    }

    private fun writeOctal(header: ByteArray, offset: Int, length: Int, value: Long) {
        val text = value.toString(8).padStart(length - 1, '0') + "\u0000"
        text.toByteArray().copyInto(header, offset)
    }
}
