package com.aharon.message.acoustic

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import com.aharon.message.protocol.ProtocolCodec
import kotlin.math.max

class AudioTransceiver(private val profile: AcousticProfile) : AutoCloseable {
    data class ReceivedFrame(val bytes: ByteArray, val signalDb: Double)

    private val modem = Fsk4Modem(profile)
    private var recorder: AudioRecord? = null

    fun send(frame: ByteArray) {
        stopRecorder()
        val samples = modem.render(frame)
        val format = AudioFormat.Builder()
            .setSampleRate(Fsk4Modem.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val minBuffer = AudioTrack.getMinBufferSize(
            Fsk4Modem.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minBuffer, modem.symbolSamples * 2 * 8))
            .build()
        try {
            track.play()
            var offset = 0
            while (offset < samples.size) {
                val written = track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                offset += written
            }
            val durationMs = samples.size * 1000L / Fsk4Modem.SAMPLE_RATE
            SystemClock.sleep(durationMs + 35L)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    @SuppressLint("MissingPermission")
    fun receiveFrame(searchTimeoutMs: Long = 350L): ReceivedFrame? {
        val record = ensureRecorder()
        val deadline = SystemClock.elapsedRealtime() + searchTimeoutMs
        var consecutiveWake = 0
        var peakDb = -180.0
        while (SystemClock.elapsedRealtime() < deadline) {
            val window = readExact(record, modem.detectorSamples) ?: return null
            peakDb = maxOf(peakDb, Goertzel.dbFs(window))
            if (modem.detectWake(window)) {
                consecutiveWake++
                if (consecutiveWake >= modem.requiredWakeWindows()) break
            } else {
                consecutiveWake = 0
            }
        }
        if (consecutiveWake < modem.requiredWakeWindows()) return null

        // Find the falling edge of the wake tone, then consume the known guard interval.
        var fallingEdgeFound = false
        repeat(80) {
            val window = readExact(record, modem.detectorSamples) ?: return null
            peakDb = maxOf(peakDb, Goertzel.dbFs(window))
            if (!modem.detectWake(window)) {
                fallingEdgeFound = true
                return@repeat
            }
        }
        if (!fallingEdgeFound) return null
        repeat((modem.guardWindowsAfterWake() - 1).coerceAtLeast(0)) {
            readExact(record, modem.detectorSamples) ?: return null
        }

        val syncSymbols = IntArray(8)
        for (index in syncSymbols.indices) {
            val symbol = readExact(record, modem.symbolSamples) ?: return null
            syncSymbols[index] = modem.decodeSymbol(symbol)
            peakDb = maxOf(peakDb, Goertzel.dbFs(symbol))
        }
        if (!modem.validateSync(syncSymbols)) return null

        val headerSymbols = readSymbols(record, ProtocolCodec.HEADER_SIZE * 4) ?: return null
        val header = modem.decodeBytes(headerSymbols)
        val totalBytes = modem.expectedFrameBytesFromHeader(header) ?: return null
        val remainingBytes = totalBytes - ProtocolCodec.HEADER_SIZE
        val tailSymbols = readSymbols(record, remainingBytes * 4) ?: return null
        val bytes = header + modem.decodeBytes(tailSymbols)
        return ReceivedFrame(bytes, peakDb)
    }

    @SuppressLint("MissingPermission")
    private fun ensureRecorder(): AudioRecord {
        recorder?.let { existing ->
            if (existing.recordingState == AudioRecord.RECORDSTATE_RECORDING) return existing
        }
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuffer = AudioRecord.getMinBufferSize(Fsk4Modem.SAMPLE_RATE, channel, encoding)
        val bufferBytes = max(minBuffer, modem.symbolSamples * 2 * 16)
        val format = AudioFormat.Builder()
            .setSampleRate(Fsk4Modem.SAMPLE_RATE)
            .setEncoding(encoding)
            .setChannelMask(channel)
            .build()

        fun build(source: Int): AudioRecord = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferBytes)
            .build()

        val created = runCatching { build(MediaRecorder.AudioSource.UNPROCESSED) }
            .getOrElse { build(MediaRecorder.AudioSource.VOICE_RECOGNITION) }
        check(created.state == AudioRecord.STATE_INITIALIZED) { "Unable to initialize microphone at 48 kHz" }
        created.startRecording()
        recorder = created
        return created
    }

    private fun readSymbols(record: AudioRecord, count: Int): IntArray? {
        val result = IntArray(count)
        for (index in 0 until count) {
            val samples = readExact(record, modem.symbolSamples) ?: return null
            result[index] = modem.decodeSymbol(samples)
        }
        return result
    }

    private fun readExact(record: AudioRecord, count: Int): ShortArray? {
        val output = ShortArray(count)
        var offset = 0
        while (offset < count) {
            val read = record.read(output, offset, count - offset, AudioRecord.READ_BLOCKING)
            if (read <= 0) return null
            offset += read
        }
        return output
    }

    private fun stopRecorder() {
        val active = recorder ?: return
        runCatching {
            if (active.recordingState == AudioRecord.RECORDSTATE_RECORDING) active.stop()
        }
        active.release()
        recorder = null
    }

    override fun close() {
        stopRecorder()
    }
}
