package com.example.decodersample

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
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

    /**
     * Elapsed real-time since system boot.
     */
    private fun ertNs() = System.nanoTime()

    /**
     * The last position at which [playbackSpeed] is changed or start playback.
     */
    private var startingPositionUs = 0L

    /**
     * The last elapsed real-time at which [playbackSpeed] is changed or start playback.
     */
    private var startingErtNs = 0L

    private val expectedPositionUs: Long
        get() {
            val ertSinceStartUs = (ertNs() - startingErtNs) / 1000
            val predictedPosition = startingPositionUs + (ertSinceStartUs * playbackSpeed).toLong()
            return predictedPosition.coerceAtMost(durationUs)
        }

    // TODO: reflect buffer's presentation time
    /**
     * The position of the latest frame rendered.
     */
    var currentPositionMs: Long = 0L
        private set
    lateinit var videoUri: Uri
        private set
    private var durationUs: Long = 0L
    val durationMs: Long
        get() = durationUs.div(1000)

    // TODO: reverse-playback
    /**
     * Note: actual playback speed depends on low-level implementations. Maybe 0x to 10x is manageable.
     */
    var playbackSpeed: Double = 1.0
        set(value) {
            Log.i(TAG, "New playback speed: $value")
            if (isPlaying()) {
                startingPositionUs = expectedPositionUs
                startingErtNs = ertNs()
            }
            handler.removeCallbacks(render)
            handler.post(render)
            field = value
        }

    private lateinit var decoder: MediaCodec
    private lateinit var extractor: MediaExtractor
    private val bufferInfo = MediaCodec.BufferInfo()

    /**
     * Indicates that decoder is in End-of-Stream sub-state.
     */
    private var isEOS = false
    private val handlerThread = HandlerThread("renderThread").also {
        // It's not legal to start a thread more than once.
        it.start()
    }
    private val handler = Handler(handlerThread.looper)

    /**
     * Since [Player] doesn't have pause state, returns [true] even if playback speed is 0.0.
     */
    fun isPlaying(): Boolean {
        return ::videoUri.isInitialized &&
                ::decoder.isInitialized &&
                ::extractor.isInitialized &&
                handlerThread.isAlive
    }

    private fun checkPlaying() = check(isPlaying()) { "Player is not playing." }

    /**
     * @param seekMode Default is [MediaExtractor.SEEK_TO_NEXT_SYNC]
     */
    fun seekTo(positionMs: Long, seekMode: Int = MediaExtractor.SEEK_TO_CLOSEST_SYNC) {
        // TODO: exact seek
        checkPlaying()
        handler.post {
            Log.i(TAG, "Seek to $positionMs ms.")
            val positionUs = positionMs * 1000
            extractor.seekTo(positionUs, seekMode)
            decoder.flush()
            handler.removeCallbacks(render)
            handler.post(render)
            // Memorize requested position.
            startingPositionUs = positionUs
            startingErtNs = ertNs()
            isEOS = false
        }
    }

    /**
     * Free up all resources. Player instance can't be used no more.
     */
    fun close() {
        handler.post(release)
        handler.post { handlerThread.quit() }
    }

    /**
     * Play the video. This can be called while playing another video.
     */
    fun play(videoUri: Uri) {
        Log.i(TAG, "Play video: $videoUri")
        // Retrieving duration may take some hundreds ms.
        durationUs = videoUri.let {
            val retriever = MediaMetadataRetriever().apply {
                setDataSource(context, it)
            }
            val durationMs =
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!
            retriever.release()
            durationMs.toLong() * 1000
        }
        this.videoUri = videoUri
        // Memorize elapsed real-time.
        startingErtNs = ertNs()
        handler.post(release)
        handler.post(initialize)
        // If we post it directly, it won't be executed.
//        handler.post(render)
        handler.post { handler.post(render) }
    }

    /**
     * Free up resources used by decoder and extractor.
     */
    private val release = Runnable {
        handler.removeCallbacks(render)
        Log.i(TAG, "Release extractor and decoder.")
        if (isPlaying()) {
            decoder.stop()
            decoder.release()
            extractor.release()
        }
    }

    /**
     * Initialize extractor and decoder for [videoUri].
     */
    private val initialize = Runnable {
        Log.i(TAG, "Initialize extractor and decoder.")
        extractor = MediaExtractor().apply {
            setDataSource(context, videoUri, null)
        }
        decoder = extractor.let {
            val (trackIdToFormat, mime) = 0.until(it.trackCount).map { trackId ->
                val format = it.getTrackFormat(trackId)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                Pair(trackId, format) to mime
            }.find { (_, mime) ->
                mime.startsWith("video/")
            }!!
            val (trackId, format) = trackIdToFormat
            // Configure extractor with video track.
            it.selectTrack(trackId)
            MediaCodec.createDecoderByType(mime).apply {
                configure(format, surface, null, 0)
                start()
            }
        }
    }

    var outputBufferId = MediaCodec.INFO_TRY_AGAIN_LATER
    var isGoingToRender = false

    /**
     * Render a video frame by recursion. After rendering, this post itself to handler.
     */
    private val render: Runnable = run {
        Runnable {
            // Retrieve an encoded frame and send it to decoder.
            decoder.also { decoder ->
                if (isEOS) return@also
                // Get ownership of input buffer.
                val inputBufferId = decoder.dequeueInputBuffer(10000)
                // Return early if no input buffer available.
                if (inputBufferId < 0) return@also
                val inputBuffer = decoder.getInputBuffer(inputBufferId)!!
                val sampleSize = extractor.readSampleData(inputBuffer, 0)
                val (size, presentationTimeUs, flags) = if (sampleSize < 0) {
                    Log.d(TAG, "InputBuffer BUFFER_FLAG_END_OF_STREAM")
                    isEOS = true
                    Triple(0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    Triple(sampleSize, extractor.sampleTime, 0)
                }
                decoder.queueInputBuffer(inputBufferId, 0, size, presentationTimeUs, flags)
                if (sampleSize >= 0) extractor.advance()
//                extractor.advance()
            }

            // Calculate the waitingTime for the next rendering. e.g.:
            //
            //  .startingPosition  \\          .expectedPosition        presentationTime
            //     x1.0 | x2.0     //                  |                       |
            // +-------------------\\---------------------------------------------> position
            //          | <- 2.0 * ertSinceSpeedSet -> | <-2.0 * waitingTime-> |
            //          |          \\                  |                       |
            //
            //    # .expectedPosition :=  .startingPosition + 2.0 * ertSinceSpeedSet
            //    # waitingTime := (presentationTime - .expectedPosition) / 2.0

            // Send the output buffer to the surface while returning the buffer to the decoder.
            if (outputBufferId >= 0) {
                decoder.releaseOutputBuffer(outputBufferId, isGoingToRender)
                currentPositionMs = bufferInfo.presentationTimeUs / 1000
            }
            // Retrieve a decoded frame; render it on the surface; update buffer info.
            outputBufferId = decoder.dequeueOutputBuffer(bufferInfo, 10000)
            val waitingTimeMs = when (outputBufferId) {
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                    Log.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED")
                    isGoingToRender = false
                    0L
                }
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.d(TAG, "New format " + decoder.outputFormat)
                    isGoingToRender = false
                    0L
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    Log.d(TAG, "dequeueOutputBuffer timed out")
                    isGoingToRender = false
                    0L
                }
                else -> {
                    // Coerce the value because too large waitingTime value such as 9223372036854775807 is ignored by handler.postDelayed().
                    // TODO: what if remove coercing
                    val timeoutMs =
                        ((bufferInfo.presentationTimeUs - expectedPositionUs) / (1000.0 * playbackSpeed))
                            .toLong().coerceIn(0, 3_600_000)
                    val renderingTimeoutThresholdMs = 20
                    isGoingToRender = timeoutMs >= renderingTimeoutThresholdMs
                    Log.d(
                        TAG,
                        "expPos=${expectedPositionUs / 1000}, curPos=${currentPositionMs}, timeoutMs=$timeoutMs" + if (isGoingToRender) ", render" else ""
                    )
                    if (isGoingToRender) {
//                        currentPositionMs = bufferInfo.presentationTimeUs / 1000
                        timeoutMs
                    } else 0L
                }
            }

            // Stop playing or continue.
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 && isEOS) {
                Log.i(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM")
            } else {
                // Call this method recursively.
                handler.postDelayed(render, waitingTimeMs)
            }
        }
    }

    companion object {
        private val TAG = Player::class.java.simpleName
    }

}

