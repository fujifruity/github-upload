package com.example.decodersample

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * A media player. Contrary to androidx.media.MediaPlayer, this is controlled synchronously.
 * When an object is no longer being used, call [close()] as soon as possible to release the resources being used.
 */
class Player(private val context: Context, private val surface: Surface) {

    // Elapsed real-time since system boot.
    private val ertNs: Long
        get() = System.nanoTime()

    // The last position at which [playbackSpeed] is changed. The setter of [playbackSpeed] must update this.
    private var speedSetPositionUs = 0L

    // The last elapsed real-time at which [playbackSpeed] is changed. The setter of [playbackSpeed] must update this.
    private var speedSetErtNs = ertNs
    private val currentPositionUs: Long
        get() {
            val ertUsSinceSpeedSet = (ertNs - speedSetErtNs) / 1000
            return speedSetPositionUs + (ertUsSinceSpeedSet * playbackSpeed).toLong()
        }
    val currentPositionMs: Long
        get() = currentPositionUs / 1000
    var videoUri: Uri? = null

    /**
     * Actual playback speed depends on low-level implementations. Maybe 0x to 10x is manageable.
     * // TODO: reverse-playback
     */
    var playbackSpeed: Double = 1.0
        set(value) {
            // Memorize position and elapsed real-time.
            speedSetPositionUs = currentPositionUs
            speedSetErtNs = ertNs
            field = value
        }

    private lateinit var decoder: MediaCodec
    private lateinit var extractor: MediaExtractor
    private val bufferInfo = MediaCodec.BufferInfo()
    private var isEOS = false
    private val handlerThread = HandlerThread("renderThread").also {
        // It's not legal to start a thread more than once.
        it.start()
    }
    private val handler = Handler(handlerThread.looper)

    fun seekTo(positionMs: Long) {
        // TODO: exact seek
        val positionUs = positionMs * 1000
        speedSetPositionUs = positionUs
        speedSetErtNs = ertNs
        extractor.seekTo(positionUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
    }

    private fun release() {
        handler.removeCallbacks({ renderCallback() }, null)
        handler.post {
            try {
                Log.i(TAG, "decoder stop.")
                decoder.stop()
                Log.i(TAG, "decoder release.")
                decoder.release()
                Log.i(TAG, "extractor release.")
                extractor.release()
            } catch (e: UninitializedPropertyAccessException) {
            }
        }
    }

    /**
     * Free up resources.
     */
    fun close() {
        release()
        Log.i(TAG, "Quit handler thread's looper.")
        handlerThread.quit()
    }

    fun play(videoUri: Uri) {
        release()
        this.videoUri = videoUri

        extractor = MediaExtractor().apply {
            setDataSource(context, videoUri, null)
        }
        decoder = extractor.let {
            val (trackIdToformat, mime) = 0.until(it.trackCount).map { trackId ->
                val format = it.getTrackFormat(trackId)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                trackId to format to mime
            }.find { (_, mime) ->
                mime.startsWith("video/")
            }!!
            val (trackId, format) = trackIdToformat
            // Configure extractor with video track.
            it.selectTrack(trackId)
            MediaCodec.createDecoderByType(mime).apply {
                configure(format, surface, null, 0)
                start()
            }
        }

        handler.post { renderCallback() }

    }

    private fun renderCallback() {
        // Retrieve an encoded frame and send it to decoder.
        if (!isEOS) {
            // Get ownership of input buffer.
            val inputBufferId = decoder.dequeueInputBuffer(10000)
            if (inputBufferId >= 0) {
                val inputBuffer = decoder.getInputBuffer(inputBufferId)!!
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                if (sampleSize < 0) {
                    Log.d(TAG, "InputBuffer BUFFER_FLAG_END_OF_STREAM")
                    decoder.queueInputBuffer(
                        inputBufferId,
                        0,
                        0,
                        0,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    isEOS = true
                } else {
                    decoder.queueInputBuffer(
                        inputBufferId,
                        0,
                        sampleSize,
                        extractor.sampleTime,
                        0
                    )
                    extractor.advance()
                }
            }
        }

        // Calculate the timeout for the next rendering. e.g.:
        //
        //  .speedSetPosition  //          .currentPosition    presentationTime
        //          +          \\                  +                   +
        //     x1.0 | x2.0     //                  |                   |
        // +-------------------\\-----------------------------------------> position
        //          |          //                  |                   |
        //          | <- 2.0 * ertSinceSpeedSet -> | <-2.0 * timeout-> |
        //          |          \\                  |                   |
        //          +          //                  +                   +
        //
        //    # .currentPosition := .speedSetPosition + 2.0 * ertSinceSpeedSet
        //    # timeout := (presentationTime - .currentPosition) / 2.0
        val timeoutMs = playbackSpeed.let {
            ((bufferInfo.presentationTimeUs - currentPositionUs) / (1000.0 * it)).toLong()
        }
        Log.d(TAG, "timeoutMs=$timeoutMs")
        val renderingThresholdMs = 20
        val render = timeoutMs >= renderingThresholdMs

        // Retrieve a decoded frame and render it on the surface.
        val outputBufferId = decoder.dequeueOutputBuffer(bufferInfo, 10000)
        when (outputBufferId) {
            MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                Log.i(TAG, "INFO_OUTPUT_BUFFERS_CHANGED")
            }
            MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                Log.i(TAG, "New format " + decoder.outputFormat)
            }
            MediaCodec.INFO_TRY_AGAIN_LATER -> {
                Log.i(TAG, "dequeueOutputBuffer timed out")
            }
            else -> {
                Log.d(TAG, "timeoutMs=$timeoutMs" + if (render) ", rendering" else "")
                // Send the output buffer to the surface while returning the buffer to the decoder.
                decoder.releaseOutputBuffer(outputBufferId, render)
            }
        }

        // Stop playing or continue.
        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            Log.i(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM")
            close()
//            release()

//            isEOS=false
//            extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
//            decoder.flush()
//            handler.postDelayed({ renderCallback() }, 1000)
        } else {
            // Call this method recursively.
            val timeout = if (render) timeoutMs else 0L
            handler.postDelayed({ renderCallback() }, timeout)
        }
    }

    fun isPlaying(): Boolean {
        return decoder != null && extractor != null && handlerThread.isAlive
    }

    companion object {
        private val TAG = Player::class.java.simpleName
    }

}

