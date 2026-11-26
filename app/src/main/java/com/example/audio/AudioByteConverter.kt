package com.example.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.example.data.remote.InvalidAudioStreamException
import com.example.data.remote.StreamUrlResolver
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

enum class AudioContainerKind {
    NATIVE_FLAC,
    NATIVE_MP3,
    NATIVE_WAV,
    NATIVE_OGG_OPUS,
    NATIVE_MP4_M4A,
    NATIVE_AAC_ADTS,
    NATIVE_MATROSKA,
    NATIVE_AMR,
    NATIVE_FLV,
    NATIVE_AC3,
    UNSUPPORTED_AIFF,
    UNSUPPORTED_OTHER,
    INVALID_MARKUP_OR_EMPTY;

    val isDirectlySupportedByExoPlayer: Boolean
        get() = when (this) {
            NATIVE_FLAC,
            NATIVE_MP3,
            NATIVE_WAV,
            NATIVE_OGG_OPUS,
            NATIVE_MP4_M4A,
            NATIVE_AAC_ADTS,
            NATIVE_MATROSKA,
            NATIVE_AMR,
            NATIVE_FLV,
            NATIVE_AC3 -> true
            UNSUPPORTED_AIFF,
            UNSUPPORTED_OTHER,
            INVALID_MARKUP_OR_EMPTY -> false
        }
}

object AudioByteConverter {

    private const val WAV_HEADER_SIZE = 44

    fun sniffContainer(file: File): AudioContainerKind {
        if (!file.exists() || file.length() < 8L) {
            return AudioContainerKind.INVALID_MARKUP_OR_EMPTY
        }
        val header = ByteArray(64)
        val bytesRead = FileInputStream(file).use { input ->
            input.read(header)
        }
        if (bytesRead < 8) return AudioContainerKind.INVALID_MARKUP_OR_EMPTY
        return sniffContainerBytes(header, bytesRead)
    }

    fun sniffContainerBytes(header: ByteArray, length: Int = header.size): AudioContainerKind {
        val len = length.coerceAtMost(header.size)
        if (len < 4) return AudioContainerKind.INVALID_MARKUP_OR_EMPTY

        if (StreamUrlResolver.looksLikeTextMarkupOrJson(header, len)) {
            return AudioContainerKind.INVALID_MARKUP_OR_EMPTY
        }

        val b0 = header[0].toInt() and 0xFF
        val b1 = header[1].toInt() and 0xFF
        val b2 = header[2].toInt() and 0xFF
        val b3 = header[3].toInt() and 0xFF

        // fLaC
        if (b0 == 0x66 && b1 == 0x4C && b2 == 0x61 && b3 == 0x43) {
            return AudioContainerKind.NATIVE_FLAC
        }
        // ID3 tag (MP3/AAC)
        if (b0 == 0x49 && b1 == 0x44 && b2 == 0x33) {
            return AudioContainerKind.NATIVE_MP3
        }
        // OggS (Vorbis / Opus / FLAC-in-Ogg)
        if (b0 == 0x4F && b1 == 0x67 && b2 == 0x67 && b3 == 0x53) {
            return AudioContainerKind.NATIVE_OGG_OPUS
        }
        // RIFF....WAVE
        if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 && len >= 12 &&
            header[8] == 'W'.code.toByte() && header[9] == 'A'.code.toByte() &&
            header[10] == 'V'.code.toByte() && header[11] == 'E'.code.toByte()
        ) {
            return AudioContainerKind.NATIVE_WAV
        }
        // FORM....AIFF / AIFC
        if (b0 == 0x46 && b1 == 0x4F && b2 == 0x52 && b3 == 0x4D && len >= 12 &&
            header[8] == 'A'.code.toByte() && header[9] == 'I'.code.toByte() &&
            header[10] == 'F'.code.toByte() &&
            (header[11] == 'F'.code.toByte() || header[11] == 'C'.code.toByte())
        ) {
            return AudioContainerKind.UNSUPPORTED_AIFF
        }
        // ISO BMFF / MP4 / M4A / ALAC (....ftyp)
        if (len >= 8 &&
            header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
            header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()
        ) {
            return AudioContainerKind.NATIVE_MP4_M4A
        }
        // Matroska / WebM EBML header
        if (b0 == 0x1A && b1 == 0x45 && b2 == 0xDF && b3 == 0xA3) {
            return AudioContainerKind.NATIVE_MATROSKA
        }
        // #!AMR
        if (len >= 5 && b0 == 0x23 && b1 == 0x21 && b2 == 0x41 && b3 == 0x4D && header[4] == 'R'.code.toByte()) {
            return AudioContainerKind.NATIVE_AMR
        }
        // FLV
        if (b0 == 0x46 && b1 == 0x4C && b2 == 0x56) {
            return AudioContainerKind.NATIVE_FLV
        }
        // AC3 sync word 0x0B 0x77
        if (b0 == 0x0B && b1 == 0x77) {
            return AudioContainerKind.NATIVE_AC3
        }
        // Raw ADTS AAC or raw MP3 MPEG audio frame sync (11 bits of 1s)
        if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) {
            val layerBits = (b1 ushr 1) and 0x03
            return if (layerBits == 0) {
                AudioContainerKind.NATIVE_AAC_ADTS
            } else {
                AudioContainerKind.NATIVE_MP3
            }
        }

        return AudioContainerKind.UNSUPPORTED_OTHER
    }

    fun convertToWavPcm(sourceFile: File, targetWavFile: File, kind: AudioContainerKind) {
        when (kind) {
            AudioContainerKind.UNSUPPORTED_AIFF -> convertAiffToWav(sourceFile, targetWavFile)
            AudioContainerKind.UNSUPPORTED_OTHER -> transcodeWithMediaCodec(sourceFile, targetWavFile)
            AudioContainerKind.INVALID_MARKUP_OR_EMPTY ->
                throw InvalidAudioStreamException("Downloaded payload is empty or non-audio text/markup.")
            else -> sourceFile.copyTo(targetWavFile, overwrite = true)
        }
    }

    fun convertAiffToWav(sourceFile: File, targetWavFile: File) {
        BufferedInputStream(FileInputStream(sourceFile)).use { input ->
            val formHeader = ByteArray(12)
            if (input.read(formHeader) != 12 ||
                String(formHeader, 0, 4, Charsets.US_ASCII) != "FORM"
            ) {
                throw InvalidAudioStreamException("Invalid AIFF container header.")
            }
            val formType = String(formHeader, 8, 4, Charsets.US_ASCII)
            if (formType != "AIFF" && formType != "AIFC") {
                throw InvalidAudioStreamException("Unsupported IFF form type: $formType")
            }

            var channels = 2
            var sampleRate = 44100
            var bitsPerSample = 16
            var littleEndianInput = false
            var isFloatPcm = false
            var convertedDataBytes = 0L

            val chunkHeader = ByteArray(8)
            var foundSsnd = false

            while (readExact(input, chunkHeader, 8)) {
                val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val chunkSize = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                when (chunkId) {
                    "COMM" -> {
                        val commBytes = ByteArray(chunkSize.toInt().coerceAtLeast(18))
                        if (!readExact(input, commBytes, chunkSize.toInt())) {
                            throw InvalidAudioStreamException("Truncated AIFF COMM chunk.")
                        }
                        val bb = ByteBuffer.wrap(commBytes).order(ByteOrder.BIG_ENDIAN)
                        channels = bb.short.toInt() and 0xFFFF
                        bb.int // numSampleFrames
                        bitsPerSample = bb.short.toInt() and 0xFFFF
                        val extBytes = ByteArray(10)
                        bb.get(extBytes)
                        sampleRate = parseIeeeExtendedSampleRate(extBytes).coerceIn(8000, 384000)

                        if (formType == "AIFC" && chunkSize >= 22) {
                            val compression = String(commBytes, 18, 4, Charsets.US_ASCII)
                            when (compression) {
                                "NONE" -> Unit
                                "sowt" -> littleEndianInput = true
                                "fl32" -> {
                                    isFloatPcm = true
                                    bitsPerSample = 32
                                }
                                else -> {
                                    // Compressed AIFC not handled purely; let MediaCodec attempt fallback if needed
                                    throw InvalidAudioStreamException("Compressed AIFF codec '$compression' is not supported.")
                                }
                            }
                        }
                        if (chunkSize % 2L == 1L) input.read()
                    }
                    "SSND" -> {
                        if (chunkSize < 8L) {
                            throw InvalidAudioStreamException("Invalid AIFF SSND chunk.")
                        }
                        val ssndMeta = ByteArray(8)
                        if (!readExact(input, ssndMeta, 8)) {
                            throw InvalidAudioStreamException("Truncated AIFF SSND metadata.")
                        }
                        val offset = ByteBuffer.wrap(ssndMeta, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                        skipBytes(input, offset)
                        val audioPayloadSize = (chunkSize - 8L - offset).coerceAtLeast(0L)

                        val bytesPerSample = (bitsPerSample / 8).coerceIn(1, 4)
                        val wavFormatCode = if (isFloatPcm) 3 else 1

                        BufferedOutputStream(FileOutputStream(targetWavFile)).use { out ->
                            out.write(
                                buildWavHeader(
                                    channels = channels,
                                    sampleRate = sampleRate,
                                    bitsPerSample = bitsPerSample,
                                    pcmDataSize = 0L,
                                    audioFormatCode = wavFormatCode
                                )
                            )
                            val buffer = ByteArray(16384 - (16384 % bytesPerSample))
                            var remaining = audioPayloadSize
                            while (remaining > 0L) {
                                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                                val read = input.read(buffer, 0, toRead)
                                if (read <= 0) break
                                if (!littleEndianInput && bytesPerSample > 1) {
                                    swapEndianInPlace(buffer, read - (read % bytesPerSample), bytesPerSample)
                                }
                                out.write(buffer, 0, read)
                                convertedDataBytes += read
                                remaining -= read
                            }
                        }
                        patchWavHeaderSizes(targetWavFile, convertedDataBytes)
                        foundSsnd = true
                        break
                    }
                    else -> {
                        val skipAmount = chunkSize + (chunkSize % 2L)
                        skipBytes(input, skipAmount)
                    }
                }
            }

            if (!foundSsnd || convertedDataBytes == 0L) {
                targetWavFile.delete()
                throw InvalidAudioStreamException("AIFF file contained no playable PCM audio frames.")
            }
        }
    }

    private fun transcodeWithMediaCodec(sourceFile: File, targetWavFile: File) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(sourceFile.absolutePath)
            var audioTrackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/", ignoreCase = true)) {
                    audioTrackIndex = i
                    inputFormat = fmt
                    break
                }
            }
            if (audioTrackIndex < 0 || inputFormat == null) {
                throw InvalidAudioStreamException("No decodable audio track found in downloaded file.")
            }

            extractor.selectTrack(audioTrackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw InvalidAudioStreamException("Missing audio MIME type.")

            var channels = inputFormat.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
            var sampleRate = inputFormat.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 44100)
            var pcmEncoding = inputFormat.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            var totalPcmBytes = 0L
            var sawInputEos = false
            var sawOutputEos = false
            val bufferInfo = MediaCodec.BufferInfo()

            BufferedOutputStream(FileOutputStream(targetWavFile)).use { out ->
                // Reserve 44-byte WAV header
                out.write(ByteArray(WAV_HEADER_SIZE))

                while (!sawOutputEos) {
                    if (!sawInputEos) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)
                            if (inBuf != null) {
                                val sampleSize = extractor.readSampleData(inBuf, 0)
                                if (sampleSize < 0) {
                                    codec.queueInputBuffer(
                                        inIdx,
                                        0,
                                        0,
                                        0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    sawInputEos = true
                                } else {
                                    val pts = extractor.sampleTime.coerceAtLeast(0L)
                                    codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                    extractor.advance()
                                }
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFmt = codec.outputFormat
                            channels = outFmt.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                            sampleRate = outFmt.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                            pcmEncoding = outFmt.getIntegerOrDefault(MediaFormat.KEY_PCM_ENCODING, pcmEncoding)
                        }
                        outIdx >= 0 -> {
                            if (bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                val outBuf = codec.getOutputBuffer(outIdx)
                                if (outBuf != null) {
                                    outBuf.position(bufferInfo.offset)
                                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                    val chunk = ByteArray(bufferInfo.size)
                                    outBuf.get(chunk)
                                    out.write(chunk)
                                    totalPcmBytes += chunk.size
                                }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawOutputEos = true
                            }
                        }
                    }
                }
            }

            if (totalPcmBytes <= 0L) {
                targetWavFile.delete()
                throw InvalidAudioStreamException("Decoder produced 0 audio bytes from downloaded file.")
            }

            val (bitsPerSample, wavFormatCode) = when (pcmEncoding) {
                AudioFormat.ENCODING_PCM_FLOAT -> 32 to 3
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24 to 1
                AudioFormat.ENCODING_PCM_32BIT -> 32 to 1
                AudioFormat.ENCODING_PCM_8BIT -> 8 to 1
                else -> 16 to 1
            }

            val header = buildWavHeader(
                channels = channels,
                sampleRate = sampleRate,
                bitsPerSample = bitsPerSample,
                pcmDataSize = totalPcmBytes,
                audioFormatCode = wavFormatCode
            )
            RandomAccessFile(targetWavFile, "rw").use { raf ->
                raf.seek(0L)
                raf.write(header)
            }
        } catch (e: InvalidAudioStreamException) {
            targetWavFile.delete()
            throw e
        } catch (e: Exception) {
            targetWavFile.delete()
            throw InvalidAudioStreamException(
                "Unsupported audio format could not be converted into playable audio bytes: ${e.localizedMessage ?: "decoder error"}"
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    fun buildWavHeader(
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        pcmDataSize: Long,
        audioFormatCode: Int = 1
    ): ByteArray {
        val safeChannels = channels.coerceIn(1, 8)
        val safeSampleRate = sampleRate.coerceIn(8000, 384000)
        val safeBits = bitsPerSample.coerceIn(8, 32)
        val blockAlign = safeChannels * (safeBits / 8)
        val byteRate = safeSampleRate * blockAlign
        val dataSizeInt = pcmDataSize.coerceIn(0L, 0x7FFFFFFFL).toInt()
        val riffChunkSize = (36L + pcmDataSize).coerceIn(36L, 0x7FFFFFFFL).toInt()

        return ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('R'.code.toByte())
            put('I'.code.toByte())
            put('F'.code.toByte())
            put('F'.code.toByte())
            putInt(riffChunkSize)
            put('W'.code.toByte())
            put('A'.code.toByte())
            put('V'.code.toByte())
            put('E'.code.toByte())
            put('f'.code.toByte())
            put('m'.code.toByte())
            put('t'.code.toByte())
            put(' '.code.toByte())
            putInt(16) // PCM fmt chunk size
            putShort(audioFormatCode.toShort())
            putShort(safeChannels.toShort())
            putInt(safeSampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(safeBits.toShort())
            put('d'.code.toByte())
            put('a'.code.toByte())
            put('t'.code.toByte())
            put('a'.code.toByte())
            putInt(dataSizeInt)
        }.array()
    }

    private fun patchWavHeaderSizes(wavFile: File, pcmDataSize: Long) {
        val riffSize = (36L + pcmDataSize).coerceIn(36L, 0x7FFFFFFFL).toInt()
        val dataSize = pcmDataSize.coerceIn(0L, 0x7FFFFFFFL).toInt()
        RandomAccessFile(wavFile, "rw").use { raf ->
            val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(riffSize)
            raf.seek(4L)
            raf.write(buf.array())
            buf.clear()
            buf.putInt(dataSize)
            raf.seek(40L)
            raf.write(buf.array())
        }
    }

    private fun swapEndianInPlace(data: ByteArray, length: Int, bytesPerSample: Int) {
        var i = 0
        when (bytesPerSample) {
            2 -> while (i + 1 < length) {
                val b0 = data[i]
                data[i] = data[i + 1]
                data[i + 1] = b0
                i += 2
            }
            3 -> while (i + 2 < length) {
                val b0 = data[i]
                data[i] = data[i + 2]
                data[i + 2] = b0
                i += 3
            }
            4 -> while (i + 3 < length) {
                val b0 = data[i]
                val b1 = data[i + 1]
                data[i] = data[i + 3]
                data[i + 1] = data[i + 2]
                data[i + 2] = b1
                data[i + 3] = b0
                i += 4
            }
        }
    }

    private fun parseIeeeExtendedSampleRate(bytes: ByteArray): Int {
        if (bytes.size < 10) return 44100
        val expon = ((bytes[0].toInt() and 0x7F) shl 8) or (bytes[1].toInt() and 0xFF)
        var hiMant = 0L
        for (i in 2..5) hiMant = (hiMant shl 8) or (bytes[i].toLong() and 0xFFL)
        var loMant = 0L
        for (i in 6..9) loMant = (loMant shl 8) or (bytes[i].toLong() and 0xFFL)
        if (expon == 0 && hiMant == 0L && loMant == 0L) return 44100
        val e = expon - 16383
        val value = (hiMant * 2.0.pow(e - 31)) + (loMant * 2.0.pow(e - 63))
        return value.toInt()
    }

    private fun readExact(input: BufferedInputStream, dest: ByteArray, count: Int): Boolean {
        var readTotal = 0
        while (readTotal < count) {
            val n = input.read(dest, readTotal, count - readTotal)
            if (n <= 0) return false
            readTotal += n
        }
        return true
    }

    private fun skipBytes(input: BufferedInputStream, count: Long) {
        var remaining = count
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else {
                if (input.read() < 0) break
                remaining--
            }
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int {
        return if (containsKey(key)) getInteger(key) else default
    }
}
