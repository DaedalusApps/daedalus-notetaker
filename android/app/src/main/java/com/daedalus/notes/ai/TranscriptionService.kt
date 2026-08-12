package com.daedalus.notes.ai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "Transcription"
private const val TARGET_SAMPLE_RATE = 16000

/** Whisper's encoder operates on a fixed ~30s window; longer audio must be chunked. */
private const val CHUNK_DURATION_SECONDS = 30
private const val CHUNK_SAMPLES = CHUNK_DURATION_SECONDS * TARGET_SAMPLE_RATE

class TranscriptionService(private val context: Context) {

    suspend fun transcribe(audioFile: File): String = withContext(Dispatchers.IO) {
        if (!isWhisperReady(context)) return@withContext ""
        Log.i(TAG, "Using Whisper for ${audioFile.name}")
        val pcm = decodeToPcmFloat(audioFile)
        Log.i(TAG, "Decoded ${pcm.size} float samples, feeding to Whisper")
        val text = transcribePcm(pcm)
        Log.i(TAG, "Whisper complete: ${text.length} chars")
        text
    }

    /**
     * Transcribe only the audio in the time window [startMs, endMs), then chunk it into
     * 30-second Whisper segments as usual. Only the window is kept in memory — decoding a
     * whole hour-long file to PCM and slicing afterwards exhausts the heap.
     */
    suspend fun transcribeRange(audioFile: File, startMs: Long, endMs: Long): String =
        withContext(Dispatchers.IO) {
            if (!isWhisperReady(context)) return@withContext ""
            Log.i(TAG, "Transcribing ${audioFile.name} range ${startMs}ms–${endMs}ms")

            val pcm = decodeToPcmFloat(audioFile, startMs, endMs)
            Log.i(TAG, "Sliced to ${pcm.size} samples")

            val text = transcribePcm(pcm)
            Log.i(TAG, "Range transcription complete: ${text.length} chars")
            text
        }

    /** Feeds PCM to Whisper in 30-second windows and joins the recognized text. */
    private fun transcribePcm(pcm: FloatArray): String {
        val dir = whisperModelDir(context)
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = File(dir, WHISPER_ENCODER_FILE).absolutePath,
                    decoder = File(dir, WHISPER_DECODER_FILE).absolutePath,
                    language = "en",
                    task = "transcribe",
                ),
                tokens = File(dir, WHISPER_TOKENS_FILE).absolutePath,
                numThreads = 4,
            )
        )
        val recognizer = OfflineRecognizer(config = config)
        return try {
            val parts = mutableListOf<String>()
            var offset = 0
            while (offset < pcm.size) {
                val end = minOf(offset + CHUNK_SAMPLES, pcm.size)
                val chunk = pcm.copyOfRange(offset, end)
                val stream = recognizer.createStream()
                stream.acceptWaveform(samples = chunk, sampleRate = TARGET_SAMPLE_RATE)
                recognizer.decode(stream)
                val chunkText = recognizer.getResult(stream).text.trim()
                stream.release()
                if (chunkText.isNotEmpty()) parts.add(chunkText)
                offset = end
            }
            Log.i(TAG, "Decoded ${parts.size} chunk(s)")
            parts.joinToString(" ")
        } finally {
            recognizer.release()
        }
    }

    /**
     * Decodes the audio in [startMs, endMs) to 16 kHz mono float PCM. Only that window is
     * retained, and decoding stops once it has been read, so peak memory is proportional to
     * the window rather than to the whole file.
     */
    private fun decodeToPcmFloat(
        file: File,
        startMs: Long = 0,
        endMs: Long = Long.MAX_VALUE
    ): FloatArray {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i
                format = fmt
                break
            }
        }
        check(trackIndex >= 0) { "No audio track found in ${file.name}" }

        extractor.selectTrack(trackIndex)
        val mime = format!!.getString(MediaFormat.KEY_MIME)!!
        var srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        // Absolute sample indices of the requested window, in 16 kHz mono samples.
        val startSample = (startMs.coerceAtLeast(0) * TARGET_SAMPLE_RATE / 1000)
        val endSample = if (endMs == Long.MAX_VALUE) Long.MAX_VALUE
            else (endMs.coerceAtLeast(0) * TARGET_SAMPLE_RATE / 1000)

        // Write floats straight into a primitive buffer — a ShortArray staging copy plus the
        // float conversion would double peak memory (both live at once for the whole file).
        // Size it to the requested window up front: growing by doubling from one minute to a
        // 15-minute part copies the whole buffer four times on the way.
        val expectedSamples = if (endSample == Long.MAX_VALUE) TARGET_SAMPLE_RATE.toLong() * 60
            else (endSample - startSample).coerceIn(1L, Int.MAX_VALUE.toLong())
        var pcmBuffer = FloatArray(expectedSamples.toInt())
        var pcmSize = 0
        if (startMs > 0) {
            val startUs = startMs * 1000L
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        }
        var decodedSamples = if (startMs > 0 && extractor.sampleTime >= 0) {
            (extractor.sampleTime * TARGET_SAMPLE_RATE / 1_000_000L)
        } else 0L
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        // Loop until the codec reports END_OF_STREAM on its *output*, not merely when the last
        // input was queued: MediaCodec runs several buffers deep, so stopping at input EOS
        // discarded the final seconds of every decode.
        while (!outputDone && decodedSamples < endSample) {
            val inputIdx = if (inputDone) -1 else codec.dequeueInputBuffer(10_000)
            if (inputIdx >= 0) {
                val inputBuf = codec.getInputBuffer(inputIdx)!!
                val sampleSize = extractor.readSampleData(inputBuf, 0)
                if (sampleSize < 0) {
                    codec.queueInputBuffer(inputIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                } else {
                    codec.queueInputBuffer(inputIdx, 0, sampleSize, extractor.sampleTime, 0)
                    extractor.advance()
                }
            }

            var outputIdx = codec.dequeueOutputBuffer(info, 10_000)
            while (outputIdx >= 0 || outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (outputIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channelCount = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        srcSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                } else {
                    val outputBuf = codec.getOutputBuffer(outputIdx)!!
                    val shortBuf = outputBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                val samples = ShortArray(shortBuf.remaining())
                shortBuf.get(samples)

                val mono = if (channelCount > 1) {
                    ShortArray(samples.size / channelCount) { i ->
                        var sum = 0L
                        for (ch in 0 until channelCount) sum += samples[i * channelCount + ch]
                        (sum / channelCount).toShort()
                    }
                } else samples

                val resampled = if (srcSampleRate != TARGET_SAMPLE_RATE) {
                    resample(mono, srcSampleRate, TARGET_SAMPLE_RATE)
                } else mono

                // Keep only the part of this buffer that falls inside the requested window.
                val bufStart = decodedSamples
                val bufEnd = bufStart + resampled.size
                decodedSamples = bufEnd

                val from = maxOf(startSample, bufStart)
                val to = minOf(endSample, bufEnd)
                if (to > from) {
                    val srcOffset = (from - bufStart).toInt()
                    val count = (to - from).toInt()
                    val needed = pcmSize + count
                    if (needed > pcmBuffer.size) {
                        pcmBuffer = pcmBuffer.copyOf(maxOf(needed, pcmBuffer.size * 2))
                    }
                    for (i in 0 until count) {
                        pcmBuffer[pcmSize + i] = resampled[srcOffset + i] / 32768f
                    }
                    pcmSize += count
                }

                codec.releaseOutputBuffer(outputIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                    break
                }
                }
                outputIdx = codec.dequeueOutputBuffer(info, 0)
            }
        }

        codec.stop()
        codec.release()
        extractor.release()
        return if (pcmSize == pcmBuffer.size) pcmBuffer else pcmBuffer.copyOf(pcmSize)
    }

    private fun resample(input: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        if (fromRate == toRate) return input
        val ratio = fromRate.toDouble() / toRate
        val outLen = (input.size / ratio).toInt()
        return ShortArray(outLen) { i ->
            val srcPos = i * ratio
            val lo = srcPos.toInt().coerceIn(0, input.size - 1)
            val hi = (lo + 1).coerceIn(0, input.size - 1)
            val frac = srcPos - lo
            ((input[lo] * (1 - frac) + input[hi] * frac).toInt().toShort())
        }
    }
}
