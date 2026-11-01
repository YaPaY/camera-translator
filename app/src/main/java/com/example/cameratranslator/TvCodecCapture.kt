package com.example.cameratranslator

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Surface

/**
 * "Codec" yakalama yolu: ekran → H.264 kodlayıcı → H.264 çözücü → kare.
 *
 * Bazı TV çipleri (Amlogic, MediaTek…) ayrı donanım katmanındaki videoyu (HDMI girişi dahil) ekran
 * yakalamada birleştirmez, ama hedef bir video KODLAYICI yüzeyi olduğunda (ekran kaydı) birleştirir.
 * Normal yol siyah veriyorsa bu yol denenir. `adb shell screenrecord` da aynı yolu kullanır.
 *
 * Tüm geri çağrılar [handler] iş parçacığında çalışır; bu yüzden kuyruklar kilitsizdir.
 */
class TvCodecCapture(
    private val width: Int,
    private val height: Int,
    private val handler: Handler,
    /** Çözülmüş kare (YUV_420_888). Image yalnızca çağrı süresince geçerlidir; çağıran kapatmaz. */
    private val onFrame: (Image) -> Unit,
    private val onError: (String) -> Unit
) {
    private val encoder: MediaCodec = MediaCodec.createEncoderByType(MIME)
    private var decoder: MediaCodec? = null
    lateinit var inputSurface: Surface
        private set

    private val pendingPackets = ArrayDeque<Packet>()
    private val freeDecoderInputs = ArrayDeque<Int>()
    private var waitingForKeyFrame = false
    @Volatile private var released = false

    private class Packet(val data: ByteArray, val ptsUs: Long, val flags: Int)

    fun start() {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            // Yazı keskin kalsın diye yüksek bit hızı (OCR, sıkıştırma bozulmalarına hassas)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // KEY_REPEAT_PREVIOUS_FRAME_AFTER bilerek YOK: HDMI'ya geçince sanal ekrana kare gelmiyorsa
            // kodlayıcı son kareyi (uygulama ekranı) tekrarlıyor, teşhis yanlışlıkla "çalışıyor" diyordu.
        }
        encoder.setCallback(encoderCallback, handler)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = encoder.createInputSurface()
        encoder.start()
        Log.d(TAG, "Kodlayıcı başladı ${width}x$height (${encoder.name})")
    }

    fun release() {
        released = true
        runCatching { encoder.stop() }
        runCatching { encoder.release() }
        runCatching { decoder?.stop() }
        runCatching { decoder?.release() }
        runCatching { inputSurface.release() }
        decoder = null
        pendingPackets.clear()
        freeDecoderInputs.clear()
    }

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit // giriş yüzeyden gelir

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // Kodlayıcının çıkış biçimi SPS/PPS'i (csd-0/csd-1) içerir: çözücü bununla kurulur
            if (released || decoder != null) return
            try {
                val decFormat = MediaFormat.createVideoFormat(MIME, width, height).apply {
                    format.getByteBuffer("csd-0")?.let { setByteBuffer("csd-0", it) }
                    format.getByteBuffer("csd-1")?.let { setByteBuffer("csd-1", it) }
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                }
                val dec = MediaCodec.createDecoderByType(MIME)
                dec.setCallback(decoderCallback, handler)
                dec.configure(decFormat, null, null, 0)
                dec.start()
                decoder = dec
                Log.d(TAG, "Çözücü başladı (${dec.name})")
            } catch (e: Exception) {
                Log.e(TAG, "Çözücü başlatılamadı", e)
                onError("Video çözücü başlatılamadı: ${e.message}")
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (released) return
            try {
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (!isConfig && info.size > 0) {
                    val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (waitingForKeyFrame && !isKey) {
                        // Çözücü geride kaldı; bozuk kare çözülmesin diye bir sonraki anahtar kareye kadar atla
                    } else {
                        waitingForKeyFrame = false
                        val buffer = codec.getOutputBuffer(index)
                        if (buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            pendingPackets.addLast(Packet(bytes, info.presentationTimeUs, if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0))
                        }
                        if (pendingPackets.size > MAX_PENDING) dropUntilKeyFrame(codec)
                    }
                }
                codec.releaseOutputBuffer(index, false)
                pump()
            } catch (e: IllegalStateException) {
                if (!released) Log.e(TAG, "Kodlayıcı çıkış hatası", e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Kodlayıcı hatası", e)
            onError("Video kodlayıcı hatası: ${e.diagnosticInfo}")
        }
    }

    private val decoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (released) return
            freeDecoderInputs.addLast(index)
            pump()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.d(TAG, "Çözücü çıkış biçimi: $format")
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (released) return
            try {
                if (info.size > 0) {
                    val image = codec.getOutputImage(index)
                    if (image != null) {
                        try {
                            onFrame(image)
                        } finally {
                            image.close()
                        }
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                if (!released) Log.e(TAG, "Çözücü çıkış hatası", e)
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Çözücü hatası", e)
            onError("Video çözücü hatası: ${e.diagnosticInfo}")
        }
    }

    /** Bekleyen paketleri boş çözücü girişlerine aktarır. */
    private fun pump() {
        val dec = decoder ?: return
        while (pendingPackets.isNotEmpty() && freeDecoderInputs.isNotEmpty()) {
            val packet = pendingPackets.removeFirst()
            val index = freeDecoderInputs.removeFirst()
            try {
                val input = dec.getInputBuffer(index) ?: continue
                input.clear()
                input.put(packet.data)
                dec.queueInputBuffer(index, 0, packet.data.size, packet.ptsUs, packet.flags)
            } catch (e: IllegalStateException) {
                if (!released) Log.e(TAG, "Çözücüye paket verilemedi", e)
                return
            }
        }
    }

    private fun dropUntilKeyFrame(encoder: MediaCodec) {
        Log.w(TAG, "Çözücü geride kaldı (${pendingPackets.size} paket); anahtar kareye atlanıyor")
        pendingPackets.clear()
        waitingForKeyFrame = true
        runCatching {
            encoder.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    companion object {
        private const val TAG = "TV_CODEC"
        private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val BIT_RATE = 12_000_000
        private const val FRAME_RATE = 30
        private const val MAX_PENDING = 45

        /**
         * Çözülmüş YUV karesinin parlaklık (Y) düzleminden gri tonlu bitmap üretir.
         * OCR için renk gerekmez; Y düzlemi kopyalamak, tam YUV→RGB dönüşümünden çok daha ucuz.
         */
        fun lumaToBitmap(image: Image, outWidth: Int, outHeight: Int): Bitmap {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val w = minOf(outWidth, image.width)
            val h = minOf(outHeight, image.height)
            val pixels = IntArray(w * h)
            val row = ByteArray(rowStride)
            for (y in 0 until h) {
                buffer.position(y * rowStride)
                val toRead = minOf(rowStride, buffer.remaining())
                buffer.get(row, 0, toRead)
                val base = y * w
                var x = 0
                var src = 0
                while (x < w) {
                    val l = row[src].toInt() and 0xFF
                    pixels[base + x] = (0xFF shl 24) or (l shl 16) or (l shl 8) or l
                    x++
                    src += pixelStride
                }
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }
    }
}
