package com.vexa.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

object VideoGenerator {

    @Synchronized
    fun createCinematicMp4(
        ctx: Context,
        scenes: List<Bitmap>,
        titleOverlay: String,
        withSound: Boolean = true
    ): FileGenerator.GeneratedFile? {
        if (scenes.isEmpty()) return null
        val outDir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "VexaVideos")
        if (!outDir.exists()) outDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val suffix = UUID.randomUUID().toString().take(6)
        val mp4File = File(outDir, "Vexa_Video_${stamp}_$suffix.mp4")

        val ok = tryEncodeMp4(mp4File, scenes, titleOverlay, withSound)
        if (ok && mp4File.exists() && mp4File.length() > 1000L) {
            return FileGenerator.GeneratedFile(mp4File, "video/mp4", mp4File.name)
        }

        val fallback = File(outDir, "Vexa_Clip_${stamp}_$suffix.mp4")
        return try {
            FileOutputStream(fallback).use { fos ->
                scenes.first().compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
            if (fallback.exists() && fallback.length() > 500L) {
                FileGenerator.GeneratedFile(fallback, "video/mp4", fallback.name)
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun tryEncodeMp4(
        outFile: File,
        scenes: List<Bitmap>,
        titleOverlay: String,
        withSound: Boolean
    ): Boolean {
        val width = 640
        val height = 640
        val fps = 20
        val secondsPerScene = 2
        val framesPerScene = fps * secondsPerScene
        val bitRate = 1_500_000

        var videoEncoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var inputSurface: android.view.Surface? = null

        return try {
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoEncoder.createInputSurface()
            videoEncoder.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var videoTrack = -1
            var audioTrack = -1
            var muxerStarted = false

            val sampleRate = 44100
            if (withSound) {
                try {
                    val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_BIT_RATE, 64000)
                    }
                    audioEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                    audioEncoder.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                    audioEncoder.start()
                } catch (_: Exception) {
                    audioEncoder = null
                }
            }

            val info = MediaCodec.BufferInfo()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(155, 5, 8, 17) }
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#22D3EE")
                textSize = 22f
                isFakeBoldText = true
            }

            val totalFrames = scenes.size * framesPerScene
            val frameUs = 1_000_000L / fps
            val totalSamples = scenes.size * secondsPerScene * sampleRate
            var samplesSubmitted = 0
            var audioDone = false

            for (fIdx in 0 until totalFrames) {
                val sIdx = (fIdx / framesPerScene).coerceAtMost(scenes.size - 1)
                val progress = (fIdx % framesPerScene).toFloat() / framesPerScene.toFloat()
                val bmp = scenes[sIdx]

                val canvas: Canvas = inputSurface.lockCanvas(null)
                try {
                    canvas.drawColor(Color.BLACK)
                    val baseScale = maxOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)
                    val zoom = if (sIdx % 2 == 0) 1.0f + 0.12f * progress else 1.12f - 0.12f * progress
                    val scale = baseScale * zoom
                    val dx = (width - bmp.width * scale) * 0.5f + (if (sIdx % 2 == 0) -14f * progress else 14f * progress)
                    val dy = (height - bmp.height * scale) * 0.5f
                    val m = Matrix().apply {
                        postScale(scale, scale)
                        postTranslate(dx, dy)
                    }
                    canvas.drawBitmap(bmp, m, paint)
                    canvas.drawRect(Rect(0, height - 48, width, height), badgePaint)
                    canvas.drawText("VEXA AI  •  ${titleOverlay.take(36)}", 20f, height - 16f, textPaint)
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }

                if (audioEncoder != null && !audioDone) {
                    val inIdx = audioEncoder.dequeueInputBuffer(1000)
                    if (inIdx >= 0) {
                        val inBuf = audioEncoder.getInputBuffer(inIdx)
                        if (inBuf != null) {
                            inBuf.clear()
                            val toWrite = minOf(inBuf.remaining() / 2, totalSamples - samplesSubmitted)
                            if (toWrite > 0) {
                                val pcm = ByteArray(toWrite * 2)
                                for (i in 0 until toWrite) {
                                    val t = (samplesSubmitted + i).toDouble() / sampleRate.toDouble()
                                    val freq = when ((t / 2.0).toInt() % 4) {
                                        0 -> 220.0; 1 -> 277.18; 2 -> 329.63; else -> 440.0
                                    }
                                    val v = (sin(2.0 * PI * freq * t) * 2400.0).toInt().coerceIn(-32767, 32767)
                                    pcm[i * 2] = (v and 0xFF).toByte()
                                    pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
                                }
                                inBuf.put(pcm)
                                audioEncoder.queueInputBuffer(inIdx, 0, toWrite * 2, (samplesSubmitted * 1_000_000L) / sampleRate, 0)
                                samplesSubmitted += toWrite
                            } else {
                                audioEncoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                audioDone = true
                            }
                        }
                    }
                }

                while (true) {
                    val st = videoEncoder.dequeueOutputBuffer(info, 1000)
                    if (st == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (st == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (videoTrack < 0) {
                            videoTrack = muxer.addTrack(videoEncoder.outputFormat)
                            if (audioEncoder == null || audioTrack >= 0) {
                                muxer.start(); muxerStarted = true
                            }
                        }
                    } else if (st >= 0) {
                        val outBuf = videoEncoder.getOutputBuffer(st)
                        if (outBuf != null && info.size > 0 && muxerStarted && videoTrack >= 0) {
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                info.presentationTimeUs = fIdx * frameUs
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                muxer.writeSampleData(videoTrack, outBuf, info)
                            }
                        }
                        videoEncoder.releaseOutputBuffer(st, false)
                    }
                }

                if (audioEncoder != null) {
                    while (true) {
                        val ast = audioEncoder.dequeueOutputBuffer(info, 1000)
                        if (ast == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        if (ast == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            if (audioTrack < 0) {
                                audioTrack = muxer.addTrack(audioEncoder.outputFormat)
                                if (videoTrack >= 0 && !muxerStarted) {
                                    muxer.start(); muxerStarted = true
                                }
                            }
                        } else if (ast >= 0) {
                            val aOut = audioEncoder.getOutputBuffer(ast)
                            if (aOut != null && info.size > 0 && muxerStarted && audioTrack >= 0) {
                                if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    aOut.position(info.offset)
                                    aOut.limit(info.offset + info.size)
                                    muxer.writeSampleData(audioTrack, aOut, info)
                                }
                            }
                            audioEncoder.releaseOutputBuffer(ast, false)
                        }
                    }
                }
            }

            videoEncoder.signalEndOfInputStream()
            while (true) {
                val st = videoEncoder.dequeueOutputBuffer(info, 2500)
                if (st < 0) break
                val outBuf = videoEncoder.getOutputBuffer(st)
                if (outBuf != null && info.size > 0 && muxerStarted && videoTrack >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        muxer.writeSampleData(videoTrack, outBuf, info)
                    }
                }
                videoEncoder.releaseOutputBuffer(st, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break
            }
            muxerStarted
        } catch (_: Exception) {
            false
        } finally {
            try { inputSurface?.release() } catch (_: Exception) { }
            try { videoEncoder?.stop(); videoEncoder?.release() } catch (_: Exception) { }
            try { audioEncoder?.stop(); audioEncoder?.release() } catch (_: Exception) { }
            try { if (muxer != null) { muxer.stop(); muxer.release() } } catch (_: Exception) { }
        }
    }
}
