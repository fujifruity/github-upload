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
 * Simple media player. Contrary to androidx.media.MediaPlayer, this is controlled synchronously.
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

    // TODO: reverse-playback
    /**
     * Note: actual playback speed depends on low-level implementations. Maybe 0x to 10x is manageable.
     */
    var playbackSpeed: Double = 1.0
        set(value) {
            // Memorize position and elapsed real-time.
            speedSetPositionUs = currentPositionUs
            speedSetErtNs = ertNs
            field = value
        }

    private var decoder: MediaCodec? = null
    private var extractor: MediaExtractor? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var isEOS = false
    private val handlerThread = HandlerThread("renderThread").also {
        // It's not legal to start a thread more than once.
        it.start()
    }
    private val handler = Handler(handlerThread.looper)

    fun seekTo(positionMs: Long) {
        // TODO: exact seek
        extractor?.also {
            val positionUs = positionMs * 1000
            speedSetPositionUs = positionUs
            speedSetErtNs = ertNs
            it.seekTo(positionUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        } ?: throw IllegalStateException()
    }

    /**
     * Free up all resources. Player instance can't be used no more.
     */
    fun close() {
        handler.post(release)
        handlerThread.quit()
    }

    /**
     * Play the video. This can be called while playing another video.
     */
    fun play(videoUri: Uri) {
        this.videoUri = videoUri
        handler.post(release)
        handler.post(initialize)
        handler.post { render() }
    }

    /**
     * Free up resources used by decoder and extractor.
     */
    private val release = Runnable {
        handler.removeCallbacks({ render() }, null)
        decoder?.also {
            Log.i(TAG, "Release decoder and extractor.")
            it.stop()
            it.release()
            extractor?.release()
        }
    }

    /**
     * Initialize extractor and decoder for [videoUri].
     */
    private val initialize = Runnable {
        extractor = MediaExtractor().apply {
            setDataSource(context, videoUri!!, null)
        }
        decoder = extractor!!.let {
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
    }

    /**
     * Render a video frame by recursion. After rendering, this post itself to handler.
     */
    private fun render() {
        // Retrieve an encoded frame and send it to decoder.
        run {
            if (isEOS) return@run
            decoder!!.also { decoder ->
                // Get ownership of input buffer.
                val inputBufferId = decoder.dequeueInputBuffer(10000)
                // Return early if no input buffer available.
                if (inputBufferId < 0) return@run
                val inputBuffer = decoder.getInputBuffer(inputBufferId)!!
                extractor!!.also { extractor ->
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    val (size, presentationTimeUs, flags) = if (sampleSize < 0) {
                        Log.d(TAG, "InputBuffer BUFFER_FLAG_END_OF_STREAM")
                        isEOS = true
                        Triple(0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        Triple(sampleSize, extractor.sampleTime, 0)
                    }
                    decoder.queueInputBuffer(inputBufferId, 0, size, presentationTimeUs, flags)
                    extractor.advance()
                }
            }

        }

        // Calculate the timeout for the next rendering. e.g.:
        //
        //  .speedSetPosition  \\          .currentPosition    presentationTime
        //     x1.0 | x2.0     //                  |                   |
        // +-------------------\\-----------------------------------------> position
        //          | <- 2.0 * ertSinceSpeedSet -> | <-2.0 * timeout-> |
        //          |          \\                  |                   |
        //
        //    # .currentPosition := .speedSetPosition + 2.0 * ertSinceSpeedSet
        //    # timeout := (presentationTime - .currentPosition) / 2.0
        val timeoutMs =
            ((bufferInfo.presentationTimeUs - currentPositionUs) / (1000.0 * playbackSpeed)).toLong()

        Log.d(TAG, "timeoutMs=$timeoutMs")
        val renderingThresholdMs = 20
        val render = timeoutMs >= renderingThresholdMs

        // Retrieve a decoded frame and render it on the surface.
        decoder!!.also { decoder ->
            when (val outputBufferId = decoder.dequeueOutputBuffer(bufferInfo, 10000)) {
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
                    Log.v(TAG, "timeoutMs=$timeoutMs" + if (render) ", render" else "")
                    // Send the output buffer to the surface while returning the buffer to the decoder.
                    decoder.releaseOutputBuffer(outputBufferId, render)
                }
            }
        }

        // Stop playing or continue.
        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
            Log.i(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM")
        } else {
            // Call this method recursively.
            val timeout = if (render) timeoutMs else 0L
            handler.postDelayed({ render() }, timeout)
        }
    }

    fun isPlaying(): Boolean {
        return decoder != null && extractor != null && handlerThread.isAlive
    }

    companion object {
        private val TAG = Player::class.java.simpleName
    }

}

