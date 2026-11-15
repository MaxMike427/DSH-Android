package com.dsh.android.runtime

import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal streaming tar reader for the bundled payloads.
 *
 * Android's toybox tar is available, but relying on it couples the install path
 * to a vendor build; a small reader keeps extraction deterministic and lets us
 * restore the POSIX modes and links PRoot needs. Only the ustar/pax features the
 * Ubuntu Base archive actually uses are implemented.
 */
object TarExtractor {

    fun extract(gzip: InputStream, destination: File, onProgress: (Long) -> Unit = {}) {
        extractStream(gzip, destination, onProgress)
    }

    /** Rootfs payload: XZ-compressed tar (much smaller than gzip for this tree). */
    fun extractXz(input: InputStream, destination: File, onProgress: (Long) -> Unit = {}) {
        val counting = CountingInputStream(input)
        val xz = org.tukaani.xz.XZInputStream(counting, null)
        extractTar(xz, counting, destination, onProgress)
    }

    fun extractStream(input: InputStream, destination: File, onProgress: (Long) -> Unit = {}) {
        val counting = CountingInputStream(input)
        val gzip = GZIPInputStream(counting, 1 shl 16)
        extractTar(gzip, counting, destination, onProgress)
    }

    private fun extractTar(
        tarInput: InputStream,
        counting: CountingInputStream,
        destination: File,
        onProgress: (Long) -> Unit,
    ) {
        destination.mkdirs()
        val tar = tarInput
        val header = ByteArray(512)
        var pendingName: String? = null
        var pendingLink: String? = null
        var lastReported = 0L
        var count = 0L

        while (true) {
            if (!readFully(tar, header)) break
            if (header.all { it == 0.toByte() }) break

            val rawName = header.string(0, 100)
            val mode = header.octal(100, 8)
            val size = header.octal(124, 12).toLong()
            val type = header[156].toInt().toChar()
            val rawLink = header.string(157, 100)
            val prefix = header.string(345, 155)
            val magic = header.string(257, 6)

            if (type == 'L') {
                pendingName = readData(tar, size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                continue
            }
            if (type == 'K') {
                pendingLink = readData(tar, size).toString(Charsets.UTF_8).trimEnd('\u0000', '\n')
                continue
            }
            if (type == 'x' || type == 'g') {
                val records = readData(tar, size).toString(Charsets.UTF_8)
                if (type == 'x') {
                    parsePax(records)["path"]?.let { pendingName = it }
                    parsePax(records)["linkpath"]?.let { pendingLink = it }
                }
                continue
            }

            var name = if (magic.startsWith("ustar") && prefix.isNotEmpty()) "$prefix/$rawName" else rawName
            var link = rawLink
            pendingName?.let { name = it; pendingName = null }
            pendingLink?.let { link = it; pendingLink = null }

            val target = safeTarget(destination, name)
            if (target == null) {
                skipFully(tar, padded(size))
                continue
            }

            when (type) {
                '5' -> {
                    target.mkdirs()
                    if (mode > 0) runCatching { Os.chmod(target.absolutePath, mode) }
                }

                '2' -> {
                    target.parentFile?.mkdirs()
                    runCatching { target.delete() }
                    runCatching { Os.symlink(link, target.absolutePath) }
                }

                '1' -> {
                    target.parentFile?.mkdirs()
                    val source = safeTarget(destination, link)
                    if (source != null && source.exists()) {
                        runCatching { Os.link(source.absolutePath, target.absolutePath) }
                            .onFailure { copyFile(source, target) }
                    }
                }

                '0', '\u0000', '7' -> {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> copyExactly(tar, out, size) }
                    if (mode > 0) runCatching { Os.chmod(target.absolutePath, mode) }
                }

                else -> {
                    // Sparse/device/fifo entries are not part of the payload.
                    skipFully(tar, padded(size))
                    continue
                }
            }

            // Every branch above consumed exactly `size` payload bytes.
            skipFully(tar, padded(size) - size)

            count++
            if (counting.count - lastReported >= 1 shl 20) {
                lastReported = counting.count
                onProgress(counting.count)
            }
        }
        onProgress(counting.count)
    }

    /** Counts compressed bytes read so extraction progress tracks the payload size. */
    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var count: Long = 0
            private set

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) count++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val read = delegate.read(buffer, offset, length)
            if (read > 0) count += read
            return read
        }

        override fun close() = delegate.close()
    }

    private fun copyFile(source: File, target: File) {
        source.inputStream().use { input ->
            FileOutputStream(target).use { out -> input.copyTo(out, 1 shl 16) }
        }
    }

    private fun safeTarget(root: File, name: String): File? {
        val cleaned = name.trimStart('/')
        if (cleaned.isEmpty() || cleaned.split('/').any { it == ".." }) return null
        val file = File(root, cleaned)
        val rootPath = root.canonicalPath + File.separator
        return if (file.canonicalPath.startsWith(rootPath)) file else null
    }

    private fun parsePax(records: String): Map<String, String> {
        val result = HashMap<String, String>()
        var index = 0
        while (index < records.length) {
            val space = records.indexOf(' ', index)
            if (space < 0) break
            val length = records.substring(index, space).toIntOrNull() ?: break
            if (length <= 0 || index + length > records.length) break
            val record = records.substring(space + 1, index + length - 1)
            val eq = record.indexOf('=')
            if (eq > 0) result[record.substring(0, eq)] = record.substring(eq + 1)
            index += length
        }
        return result
    }

    private fun padded(size: Long): Long = (size + 511) / 512 * 512

    private fun readData(input: InputStream, size: Long): ByteArray {
        val data = ByteArray(size.toInt())
        var offset = 0
        while (offset < data.size) {
            val read = input.read(data, offset, data.size - offset)
            if (read < 0) break
            offset += read
        }
        skipFully(input, padded(size) - size)
        return data
    }

    private fun copyExactly(input: InputStream, out: FileOutputStream, size: Long) {
        val buffer = ByteArray(1 shl 16)
        var remaining = size
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun skipFully(input: InputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(8192)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) return
            remaining -= read
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return offset == 0
            offset += read
        }
        return true
    }

    private fun ByteArray.string(offset: Int, length: Int): String {
        val end = (offset until minOf(offset + length, size)).firstOrNull { this[it] == 0.toByte() }
            ?: minOf(offset + length, size)
        return String(this, offset, end - offset, Charsets.UTF_8)
    }

    private fun ByteArray.octal(offset: Int, length: Int): Int {
        val text = string(offset, length).trim().trim('\u0000')
        if (text.isEmpty()) return 0
        return text.toIntOrNull(8) ?: 0
    }
}
