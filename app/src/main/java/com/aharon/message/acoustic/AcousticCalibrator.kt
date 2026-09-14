package com.aharon.message.acoustic

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin

class AcousticCalibrator {
    data class FrequencyScore(
        val frequencyHz: Double,
        val snrDb: Double,
    )

    data class Result(
        val strictScores: List<FrequencyScore>,
        val compatibleScores: List<FrequencyScore>,
        val recommendedProfile: AcousticProfile,
        val scoreDb: Double,
        val strictUsable: Boolean,
    )

    companion object {
        private const val SAMPLE_RATE = 48_000
        private const val TONE_MS = 240
        private const val LEAD_IN_MS = 45
        private const val CAPTURE_MS = 330
        private const val AMPLITUDE = 0.22
        private const val MIN_USABLE_SNR_DB = 8.0
        private const val MIN_STRICT_SNR_DB = 10.0
    }

    @SuppressLint("MissingPermission")
    fun run(): Result {
        val strict = scoreProfile(AcousticProfile.STRICT)
        val compatible = scoreProfile(AcousticProfile.COMPATIBLE)
        val strictMin = strict.minOfOrNull { it.snrDb } ?: Double.NEGATIVE_INFINITY
        val compatibleMin = compatible.minOfOrNull { it.snrDb } ?: Double.NEGATIVE_INFINITY
        val strictUsable = strictMin >= MIN_STRICT_SNR_DB
        val recommended = if (strictUsable || strictMin >= compatibleMin) {
            AcousticProfile.STRICT
        } else {
            AcousticProfile.COMPATIBLE
        }
        val score = if (recommended.id == AcousticProfile.STRICT.id) strictMin else compatibleMin
        return Result(
            strictScores = strict,
            compatibleScores = compatible,
            recommendedProfile = recommended,
            scoreDb = score,
            strictUsable = strictUsable,
        )
    }

    @SuppressLint("MissingPermission")
    private fun scoreProfile(profile: AcousticProfile): List<FrequencyScore> {
        val frequencies = (profile.frequencies.toList() + profile.wakeFrequency).distinct()
        return frequencies.map { frequency ->
            SystemClock.sleep(65L)
            FrequencyScore(frequency, measureFrequency(frequency))
        }
    }

    @SuppressLint("MissingPermission")
    private fun measureFrequency(frequency: Double): Double {
        val recorder = createRecorder()
        val captureSamples = SAMPLE_RATE * CAPTURE_MS / 1000
        val capture = ShortArray(captureSamples)
        val tone = makeTone(frequency, SAMPLE_RATE * TONE_MS / 1000)
        val track = createTrack(tone.size)

        try {
            recorder.startRecording()
            var readOffset = 0
            val reader = Thread {
                while (readOffset < capture.size) {
                    val read = recorder.read(
                        capture,
                        readOffset,
                        capture.size - readOffset,
                        AudioRecord.READ_BLOCKING,
                    )
                    if (read <= 0) break
                    readOffset += read
                }
            }
            reader.start()
            SystemClock.sleep(LEAD_IN_MS.toLong())
            track.play()
            var written = 0
            while (written < tone.size) {
                val count = track.write(tone, written, tone.size - written, AudioTrack.WRITE_BLOCKING)
                if (count <= 0) break
                written += count
            }
            reader.join(1_200L)
            if (reader.isAlive) reader.interrupt()

            val usable = if (readOffset > 0) capture.copyOf(readOffset) else capture
            val start = (SAMPLE_RATE * (LEAD_IN_MS + 25) / 1000).coerceAtMost(usable.size)
            val end = (start + SAMPLE_RATE * 150 / 1000).coerceAtMost(usable.size)
            val window = if (end > start) usable.copyOfRange(start, end) else usable

            val target = Goertzel.power(window, frequency, SAMPLE_RATE).coerceAtLeast(1.0)
            val sideA = Goertzel.power(window, frequency - 320.0, SAMPLE_RATE).coerceAtLeast(1.0)
            val sideB = Goertzel.power(window, frequency + 320.0, SAMPLE_RATE).coerceAtLeast(1.0)
            val noise = (sideA + sideB) / 2.0
            return (10.0 * log10(target / noise)).coerceIn(-20.0, 60.0)
        } finally {
            runCatching { if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop() }
            recorder.release()
            runCatching { track.stop() }
            track.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord {
        val min = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        fun build(source: Int): AudioRecord = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, SAMPLE_RATE * 2))
            .build()

        val record = runCatching { build(MediaRecorder.AudioSource.UNPROCESSED) }
            .getOrElse { build(MediaRecorder.AudioSource.VOICE_RECOGNITION) }
        check(record.state == AudioRecord.STATE_INITIALIZED) { "48 kHz microphone input unavailable" }
        return record
    }

    private fun createTrack(sampleCount: Int): AudioTrack {
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(sampleCount * 2, 8_192))
            .build()
    }

    private fun makeTone(frequency: Double, count: Int): ShortArray {
        val scale = Short.MAX_VALUE * AMPLITUDE
        return ShortArray(count) { index ->
            val fade = when {
                index < 96 -> index / 96.0
                count - index < 96 -> (count - index) / 96.0
                else -> 1.0
            }
            (sin(2.0 * PI * frequency * index / SAMPLE_RATE) * scale * fade)
                .toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }
    }

    fun isUsable(result: Result): Boolean = result.scoreDb >= MIN_USABLE_SNR_DB
}
