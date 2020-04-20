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
import kotlin.math.absoluteValue

/**
 * Simple video player. When an object is no longer being used, call [close] as soon as possible to release the resources being used.
 * ```
 * val holder: SurfaceHolder = ...
 * Player(applicationContext, holder.surface).also {
 *     val videoUrl: String = ...
 *     it.play(videoUrl)
 *     Thread.sleep(3000)
 *     it.playbackSpeed = 2.0
 *     Thread.sleep(3000)
 *     it.seekTo(7000)
 *     Thread.sleep(3000)
 *     it.close()
 * }
 * ```
 */
class VideoPlayer(private val context: Context, private val surface: Surface) {

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

    /**
     * The position of the latest rendered frame.
     */
    var currentPositionMs: Long = 0L
        private set
    lateinit var videoUri: Uri
        private set
    private var durationUs: Long = 0L
    val durationMs: Long
        get() = durationUs.div(1000)

    private lateinit var decoder: MediaCodec
    private lateinit var extractor: MediaExtractor

    private val invalidInputBufferIds = mutableListOf<Int>()
    private val invalidOutputBufferIds = mutableListOf<Int>()

    private val handlerThread = HandlerThread("renderThread").also {
        // It's not legal to start a thread more than once.
        it.start()
    }
    private val handler = Handler(handlerThread.looper, Handler.Callback {
        if (it.what != WHAT_RELEASE) return@Callback false
        // Release an output buffer according to message arguments.
        val bufIdAndRenderFlag = it.arg1
        val isGoingToRender = bufIdAndRenderFlag and 1 == 1
        val bufId = bufIdAndRenderFlag shr 1
        // Simultaneously render the buffer onto the surface.
        decoder.releaseOutputBuffer(bufId, isGoingToRender)
        val presentationTimeMs = it.arg2.toLong()
        if (isGoingToRender) currentPositionMs = presentationTimeMs
        Log.d(
            TAG,
            "ob$bufId released, exp=${expectedPositionUs / 1000}, cur=${currentPositionMs}${if (isGoingToRender) ", rendered" else ""}"
        )
        true
    })

    // TODO: reverse-playback
    /**
     * Note: actual playback speed depends on low-level implementations. Maybe 0x to 6x is manageable.
     */
    var playbackSpeed: Double = 1.0
        set(value) {
            handler.post {
                Log.i(TAG, "New playback speed: $value")
                if (isPlaying()) {
                    // expectedPositionUs needs old playbackSpeed.
                    startingPositionUs = expectedPositionUs
                    startingErtNs = ertNs()
                }
                field = value
                intermissionUs = (30_000 * playbackSpeed).toLong()
                Log.d(TAG, "intermissionMs=${intermissionUs / 1000}")
                // recreate WHAT_RELEASE messages from posted ones that are invalid now, then post them.
                val messageTriplets = parseMessageQueue()
                handler.removeMessages(WHAT_RELEASE)
                messageTriplets.filter { it.what == WHAT_RELEASE }.forEach {
                    val bufIdAndRenderFlag = it.arg1
                    val presentationTimeUs = it.arg2 * 1000L
                    val outputBufferId = bufIdAndRenderFlag shr 1
                    sendReleaseMessage(outputBufferId, presentationTimeUs, "spd setter")
                }
            }
        }

    private data class MessageTriple(val what: Int, val arg1: Int, val arg2: Int)

    private fun parseMessageQueue(): List<MessageTriple> {
        val dump = mutableListOf<String>()
        handler.looper.dump({ s -> dump.add(s) }, "")
//        Log.d(TAG, "message queue=\n${dump.joinToString("\n")}")
        /* an example of return value of dump()
          Handler (android.os.Handler) {19c03c27} @ 673356246
          Looper (renderThread, tid 31890) {16b62ee6}
          Message 0: { when=+55ms what=1001 arg1=13 arg2=3056 target=android.os.Handler }
          Message 1: { when=+115ms what=1001 arg1=11 arg2=3116 target=android.os.Handler }
          Message 2: { when=+149ms what=1001 arg1=9 arg2=3151 target=android.os.Handler }
          (Total messages: 3, idling=true, quitting=false)
        */
        return dump.filter { it.trim().startsWith("Message") }.mapNotNull {
            """what=(\d+) arg1=(\d+) arg2=(\d+)""".toRegex().find(it)?.let {
                val (a, b, c) = it.destructured.toList().map { it.toInt() }
                MessageTriple(a, b, c)
            }
        }
    }

    /**
     * Since [VideoPlayer] doesn't have pause state, returns `true` even if playback speed is 0.0.
     */
    fun isPlaying(): Boolean {
        return ::videoUri.isInitialized &&
                ::decoder.isInitialized &&
                ::extractor.isInitialized &&
                handlerThread.isAlive
    }

    /**
     * [VideoPlayer] doesn't have pause state. Instead, it just set [playbackSpeed] `0.0`.
     */
    val togglePause = run {
        var lastNonZeroPlaybackSpeed = 1.0
        {
            if (playbackSpeed != 0.0) {
                lastNonZeroPlaybackSpeed = playbackSpeed
                playbackSpeed = 0.0
            } else {
                playbackSpeed = lastNonZeroPlaybackSpeed
            }
        }
    }

    /**
     * @param seekMode Default is [MediaExtractor.SEEK_TO_CLOSEST_SYNC]
     * // TODO: exact seek
     */
    fun seekTo(positionMs: Long, seekMode: Int = MediaExtractor.SEEK_TO_CLOSEST_SYNC) {
        check(isPlaying()) { "Player is not playing." }
        handler.post {
            Log.i(TAG, "Seek to $positionMs ms.")
            decoder.flush()
            // It removes only messages posted by our handler.
            handler.removeCallbacksAndMessages(null)
            // Memorize buffer IDs that MediaCodec.Callback should check and avoid to use.
            parseMessageQueue().filter { it.what == WHAT_EVENT_CALLBACK }.forEach {
                when (it.arg1) {
                    ARG1_INPUT_BUFFER_AVAILABLE -> invalidInputBufferIds.add(it.arg2)
                    ARG1_OUTPUT_BUFFER_AVAILABLE -> invalidOutputBufferIds.add(it.arg2)
                }
            }
            val positionUs = positionMs * 1000
            extractor.seekTo(positionUs, seekMode)
            decoder.start()
            // Update starting ones.
            startingPositionUs = positionUs
            startingErtNs = ertNs()
            // only if it is 0, our handler's callback forces decoder to render the next available output buffer (for responsiveness).
            lastAcceptedRenderingTimeUs = 0L
        }
    }

    /**
     * Play the video. This can be called while playing another video.
     */
    fun play(videoUri: Uri) {
        Log.i(TAG, "Play video: $videoUri")
        this.videoUri = videoUri
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
        // Memorize elapsed real-time.
        startingErtNs = ertNs()
        handler.post(release)
        handler.post(initialize)
    }

    /**
     * Free up all resources. Player instance can't be used no more.
     */
    fun close() {
        handler.post(release)
        handler.post { handlerThread.quit() }
    }

    /**
     * Free up resources used by decoder and extractor.
     */
    private val release = Runnable {
        handler.removeMessages(WHAT_RELEASE)
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
                setCallback(decoderCallback)
                // TODO: setCallback(decoderCallback, handler) >= 23 (so that our handler remove all
                //  messages and therefore onInput/OutputBufferAvailable no longer need invalid buffer checking)
                configure(format, surface, null, 0)
                start()
            }
        }
    }

    /**
     * "renderingTime" is defined in [calculateTimeoutMs].
     * We should initialize it every time perform seek.
     */
    private var lastAcceptedRenderingTimeUs = 0L
    private var intermissionUs = 30_000L

    /**
     * Calculate the timeout to next rendering for the presentation time of output buffer.
     * When it returns 0, decoder should not render the buffer.
     */
    private fun calculateTimeoutMs(presentationTimeUs: Long): Long {
        val expectedPositionUs = expectedPositionUs
        val timeoutUs = ((presentationTimeUs - expectedPositionUs) / playbackSpeed).toLong()
            .coerceIn(0, 3600_000_000)
        // tricky definition: position + real time
        val renderingTimeUs = expectedPositionUs + timeoutUs
        val finalTimeoutUs = when {
            // we want to render just once when seek finished, for responsiveness.
            lastAcceptedRenderingTimeUs == 0L -> 1
            // or, when there is enough distance between renderingTime and lastAcceptedRenderingTimeUs.
            (renderingTimeUs - lastAcceptedRenderingTimeUs).absoluteValue >= intermissionUs -> timeoutUs / 1000
            else -> 0
        }
        if (finalTimeoutUs > 0) lastAcceptedRenderingTimeUs = renderingTimeUs
        return finalTimeoutUs
        /*
         .startingPosition  \\          .expectedPosition->      presentationTime
            x1.0 | x2.0     //                  |                      |
         -------------------\\--------------------------------------------> position
                 | <--2.0 * ertSinceSpeedSet--> | <---2.0 * timeout--> |
                 |          \\                  |                      |

         .expectedPosition :=  .startingPosition + 2.0 * ertSinceSpeedSet
                   timeout := (presentationTime - .expectedPosition) / 2.0

                  |        |       |             |<--intermission-->|
                --|--------|-------|-------------|---------+--------+-+---> time
                  <---------timeout0------------->         |          |
                  |        <-----------timeout1------------>          |
                  +        |       <--------------timeout2------------>
         expectedPosition0 +       |             +         |          |
                 expectedPosition1 +      renderingTime0   +          |
                          expectedPosition2          renderingTime1   +
                                                               renderingTime2

              intermission := 30 * log2(playbackSpeed + 1)  e.g.: 0.5x->18, 1x->30, 2x->48, 4x->70
           renderingTime_n := expectedPosition_n + timeout_n
         isGoingToRender_n := |renderingTime - last accepted renderingTime| > intermission
               return value = isGoingToRender_n && timeout_n > 0 ? timeout_n : 0

         Caller1 should not render the buffer it holds because renderingTime1 is too close to renderingTime0.
         Return values for these callers (onOutputBufferAvailable or playbackSpeed) will be:
           caller0: timeout0
           caller1: 0
           caller2: timeout2
         */
    }

    private fun sendReleaseMessage(outputBufferId: Int, presentationTimeUs: Long, tag: String) {
        val timeoutMs = calculateTimeoutMs(presentationTimeUs)
        val isGoingToRender = timeoutMs > 0L
        val bufIdAndRenderFlag = (outputBufferId shl 1) + if (isGoingToRender) 1 else 0
        val presentationTimeMs = (presentationTimeUs / 1000).toInt()
        val msg = handler.obtainMessage(WHAT_RELEASE, bufIdAndRenderFlag, presentationTimeMs)
        handler.sendMessageDelayed(msg, timeoutMs)
        Log.d(
            TAG,
            "ob$outputBufferId ($presentationTimeMs) will be ${if (isGoingToRender) "rendered" else "released"} in ${timeoutMs}ms ($tag)"
        )
    }

    private val decoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(decoder: MediaCodec, inputBufferId: Int) {
            if (invalidInputBufferIds.remove(inputBufferId)) {
                Log.i(TAG, "inputBuffer $inputBufferId is invalid, skip.")
                return
            }
            Log.v(TAG, "inputBuffer $inputBufferId is available.")
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
            } else {
                decoder.queueInputBuffer(inputBufferId, 0, sampleSize, extractor.sampleTime, 0)
                extractor.advance()
            }
        }

        override fun onOutputBufferAvailable(
            decoder: MediaCodec,
            outputBufferId: Int,
            bufferInfo: MediaCodec.BufferInfo
        ) {
            if (invalidOutputBufferIds.remove(outputBufferId)) {
                Log.i(TAG, "outputBuffer $outputBufferId is invalid, skip.")
            } else if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                Log.i(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM")
            } else {
                sendReleaseMessage(outputBufferId, bufferInfo.presentationTimeUs, "oOBA")
            }
        }

        override fun onOutputFormatChanged(decoder: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "MediaCodec.Callback.onOutputFormatChanged, format: $format")
        }

        override fun onError(decoder: MediaCodec, exception: MediaCodec.CodecException) {
            Log.e(TAG, "MediaCodec.Callback.onError", exception)
        }
    }

    companion object {
        private val TAG = VideoPlayer::class.java.simpleName

        const val WHAT_RELEASE = 1001

        // Parameter of Messages that MediaCodec posts.
        // They should reflects MediaCodec's private constants: https://android.googlesource.com/platform/frameworks/base/+/339567d/media/java/android/media/MediaCodec.java
        // At the time of writing:
        //   what: 1=EVENT_CALLBACK, 2=EVENT_SET_CALLBACK
        //   arg1: (1=input|2=output) buffer available
        //   arg2: buffer id
        const val WHAT_EVENT_CALLBACK = 1
        const val ARG1_INPUT_BUFFER_AVAILABLE = 1
        const val ARG1_OUTPUT_BUFFER_AVAILABLE = 2
    }
}

