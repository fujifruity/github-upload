package com.example.videoplayer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.util.concurrent.SynchronousQueue
import kotlin.math.absoluteValue

/**
 * Simple video player powered by MediaCodec's async mode.
 * When an object is no longer being used, call [close] as soon as possible to release the resources being used.
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
    val durationMs: Long
        get() = session!!.durationUs / 1000
    val videoUri: Uri
        get() = session!!.videoUri
    val currentPositionMs: Long
        get() = session!!.currentPositionMs
    var playbackSpeed: Double
        get() = session!!.playbackSpeed
        set(value) {
            session!!.playbackSpeed = value
        }
    private var session: PlaybackSession? = null
    private val playbackThread = HandlerThread("playbackThread").also { it.start() }
    private val playbackThreadHandler = Handler(playbackThread.looper)

    fun playWhenReady(videoUri: Uri) {
        session?.also { it.close() }
        playbackThreadHandler.post {
            session = PlaybackSession(videoUri, context, surface, playbackThread.looper)
        }
    }

    /** Blocks until playback starts. */
    fun play(videoUri: Uri) {
        playWhenReady(videoUri)
        val queue = SynchronousQueue<Unit>()
        playbackThreadHandler.post { queue.put(Unit) }
        queue.take()
    }

    fun close() {
        session!!.close()
        playbackThread.quit()
    }

    fun seekTo(positionMs: Long, mode: Int = MediaExtractor.SEEK_TO_PREVIOUS_SYNC) {
        session!!.seekTo(positionMs, mode)
    }
}

/**
 * Represents playback of video specified by [videoUri]. Immediately starts playback on instantiation.
 */
class PlaybackSession(
    val videoUri: Uri,
    private val context: Context,
    private val surface: Surface,
    looper: Looper
) {
    /** The position of the latest rendered frame. */
    var currentPositionMs: Long = 0L
        private set

    /** "renderingTime" is defined in [calculateTimeoutMs]. */
    private var lastAcceptedRenderingTimeUs = 0L
    private var intermissionUs = 30_000L
    private var hasOutdatedOutputBuffer = false
    private var hasFormatChanged = false
    private var isCaughtUp = false
    private var pendingSeekRequest: Runnable? = null

    /** Elapsed real-time since system boot. */
    private fun ertNs() = System.nanoTime()

    /** The last elapsed real-time at which [playbackSpeed] is changed or start playback. */
    private var startingErtNs = 0L

    /** The last position at which [playbackSpeed] is changed or start playback. */
    private var startingPositionUs = 0L

    /** Must be updated [extractor].seekTo() is called. */
    private var startingKeyframeTimestampUs = 0L

    private val expectedPositionUs: Long
        get() {
            val ertSinceStartUs = (ertNs() - startingErtNs) / 1000
            val predictedPosition = startingPositionUs + (ertSinceStartUs * playbackSpeed).toLong()
            return predictedPosition.coerceAtMost(durationUs)
        }

    data class BufferReleaseProperty(
        val outputBufferId: Int,
        val presentationTimeUs: Long,
        val hasToRender: Boolean
    )

    /**
     * Buffer release messages that are pending will go invalid when [playbackSpeed] is changed or
     * [seekTo] is called. While [seekTo] only requires to remove all messages, [playbackSpeed]'s
     * setter has to recreate messages and send them again so as not to skip any frame.
     * Since there is probably no way to peek messages ([Looper.dump] does, but its format is not
     * reliable), the recreation needs all pending messages' properties.
     * - who adds an element: [sendReleaseMessage]
     * - who removes an element: [handler]'s onMessage callback
     * - who removes all elements: [seekTo] and [playbackSpeed]'s setter
     */
    private val pendingMessageProperties = mutableListOf<BufferReleaseProperty>()

    private val handler = Handler(looper, Handler.Callback {
        if (it.what != WHAT_RELEASE_BUFFER) return@Callback false
        val property = it.obj as BufferReleaseProperty
        pendingMessageProperties.remove(property)
        val (bufId, presentationTimeUs, hasToRender) = property
        // simultaneously renders the buffer onto the surface.
        decoder.releaseOutputBuffer(bufId, hasToRender)
        val presentationTimeMs = presentationTimeUs / 1000
        if (hasToRender) currentPositionMs = presentationTimeMs
        Log.v(
            TAG,
            "outputBuffer$bufId ($presentationTimeMs) released, exp=${expectedPositionUs / 1000}, cur=${currentPositionMs}${if (hasToRender) ", rendered" else ""}"
        )
        true
    })

    private val decoderCallback: MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(decoder: MediaCodec, bufferId: Int) {
            val bufIdWithTimestamp = "inputBuffer$bufferId (${extractor.sampleTime / 1000})"
            Log.v(TAG, "$bufIdWithTimestamp is available")
            val inputBuffer = decoder.getInputBuffer(bufferId)!!
            val sampleSize = extractor.readSampleData(inputBuffer, 0)
            if (sampleSize == -1) {
                Log.d(TAG, "$bufIdWithTimestamp BUFFER_FLAG_END_OF_STREAM")
                decoder.queueInputBuffer(bufferId, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            } else {
                decoder.queueInputBuffer(bufferId, 0, sampleSize, extractor.sampleTime, 0)
                extractor.advance()
            }
        }

        override fun onOutputBufferAvailable(
            decoder: MediaCodec,
            bufferId: Int,
            bufferInfo: MediaCodec.BufferInfo
        ) {
            val bufIdWithTimestamp =
                "outputBuffer$bufferId (${bufferInfo.presentationTimeUs / 1000})"
            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                Log.i(TAG, "$bufIdWithTimestamp BUFFER_FLAG_END_OF_STREAM")
            } else if (hasOutdatedOutputBuffer) {
                if (bufferInfo.presentationTimeUs == startingKeyframeTimestampUs) {
                    Log.i(TAG, "$bufIdWithTimestamp has the target key frame of seek")
                    hasOutdatedOutputBuffer = false
                    val prop = BufferReleaseProperty(bufferId, bufferInfo.presentationTimeUs, true)
                    sendReleaseMessage(prop, 0L, "oOBA")
                } else {
                    Log.i(TAG, "$bufIdWithTimestamp is outdated, skipped")
                }
            } else if (!isCaughtUp && bufferInfo.presentationTimeUs >= expectedPositionUs) {
                // when perform seek with very small playbackSpeed, we want to render once no matter
                // how long the rendering timeout is.
                Log.i(TAG, "$bufIdWithTimestamp playback is caught up")
                isCaughtUp = true
                val prop = BufferReleaseProperty(bufferId, bufferInfo.presentationTimeUs, true)
                sendReleaseMessage(prop, 0L, "oOBA")
            } else {
                sendReleaseMessageDelayed(bufferId, bufferInfo.presentationTimeUs, "oOBA")
            }
        }

        override fun onOutputFormatChanged(decoder: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "onOutputFormatChanged, format: $format")
            hasFormatChanged = true
            pendingSeekRequest?.also {
                Log.i(TAG, "posting pending seek request")
                handler.post(it)
                pendingSeekRequest = null
            }
        }

        override fun onError(decoder: MediaCodec, exception: MediaCodec.CodecException) {
            Log.e(TAG, "onError", exception)
        }
    }

    private val extractor = MediaExtractor().apply {
        Log.i(TAG, "creating extractor")
        setDataSource(context, videoUri, null)
    }

    private val decoder = extractor.let {
        Log.i(TAG, "creating decoder")
        val (trackId, format, mime) =
            0.until(extractor.trackCount).map { trackId ->
                val format = extractor.getTrackFormat(trackId)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                Triple(trackId, format, mime)
            }.find { (_, _, mime) ->
                mime.startsWith("video/")
            }!!
        // Configure extractor with video track.
        extractor.selectTrack(trackId)
        MediaCodec.createDecoderByType(mime).apply {
            setCallback(decoderCallback)
            configure(format, surface, null, 0)
        }
    }

    /**
     * Free up all resources. [PlaybackSession] instance cannot be used no more.
     */
    fun close() {
        handler.post {
            handler.removeMessages(WHAT_RELEASE_BUFFER)
            Log.i(TAG, "releasing extractor and decoder")
            decoder.release()
            extractor.release()
        }
    }

    /**
     * - Note: actual playback speed depends on low-level implementations. Maybe 0x to 6x is manageable.
     * - Known issue: setting value right after seekTo() will cause crash.
     */
    var playbackSpeed: Double = 1.0
        set(value) {
            if (value == playbackSpeed) {
                Log.i(TAG, "Same playbackSpeed, nothing to do")
                return
            }
            handler.post {
                Log.i(TAG, "Setting playback speed x$value")
                // expectedPositionUs needs old playbackSpeed.
                startingPositionUs = expectedPositionUs
                startingErtNs = ertNs()
                field = value
                intermissionUs = (30_000 * playbackSpeed).toLong()
                Log.v(TAG, "intermissionMs=${intermissionUs / 1000}")
                // recreate and send release buffer messages that are pending and invalid now.
                handler.removeMessages(WHAT_RELEASE_BUFFER)
                val copy = pendingMessageProperties.toList()
                pendingMessageProperties.clear()
                copy.forEach { (bufId, presentationTimeUs, _) ->
                    sendReleaseMessageDelayed(bufId, presentationTimeUs, "spd setter")
                }
            }
        }

    /**
     * @param seekMode:
     *  - [MediaExtractor.SEEK_TO_CLOSEST_SYNC]
     *  - [MediaExtractor.SEEK_TO_NEXT_SYNC]
     *  - [MediaExtractor.SEEK_TO_PREVIOUS_SYNC] (default)
     */
    fun seekTo(positionMs: Long, seekMode: Int = MediaExtractor.SEEK_TO_PREVIOUS_SYNC) {
        val seekRequest = Runnable {
            val positionUs = (positionMs * 1000).coerceIn(0, durationUs)
            Log.i(TAG, "Seek to ${positionMs}ms (actual: ${positionUs / 1000}ms)")
            // TODO: create `ContinuousPlayback` to remove these re-initialization code
            startingPositionUs = positionUs
            startingErtNs = ertNs()
            lastAcceptedRenderingTimeUs = 0L
            isCaughtUp = false
            extractor.seekTo(positionUs, seekMode)
            decoder.flush()
            decoder.start()
            handler.removeMessages(WHAT_RELEASE_BUFFER)
            pendingMessageProperties.clear()
            // prevents outlived onOutputBufferAvailable from sending invalid buffer release messages.
            hasOutdatedOutputBuffer = true
            startingKeyframeTimestampUs = extractor.sampleTime
        }
        // According to reference: if you flush the codec too soon after start() – generally, before the first output buffer or output format change is received – you will need to resubmit the codec-specific-data to the codec.
        if (!hasFormatChanged) {
            pendingSeekRequest = seekRequest
        } else {
            handler.post(seekRequest)
        }
    }

    /**
     * Schedules releasing output buffer after calculating timeout.
     */
    private fun sendReleaseMessageDelayed(
        outputBufferId: Int,
        presentationTimeUs: Long,
        tag: String
    ) {
        val (timeoutMs, hasToRender) = run {
            val expectedPositionUs = expectedPositionUs
            val timeoutCandidateUs =
                ((presentationTimeUs - expectedPositionUs) / playbackSpeed).toLong()
                    .coerceAtMost(3600_000_000)
            if (timeoutCandidateUs < 0) return@run 0L to false
            // tricky definition: position + real time
            val renderingTimeUs = expectedPositionUs + timeoutCandidateUs
            val isWellSeparated =
                (lastAcceptedRenderingTimeUs - renderingTimeUs).absoluteValue >= intermissionUs
            if (isWellSeparated) {
                lastAcceptedRenderingTimeUs = renderingTimeUs
                timeoutCandidateUs / 1000 to true
            } else 0L to false
        }
        sendReleaseMessage(
            BufferReleaseProperty(outputBufferId, presentationTimeUs, hasToRender),
            timeoutMs,
            tag
        )
        /*
    .startingPosition  \\          .expectedPosition->      presentationTime
       x1.0 | x2.0     //                  |                      |
    -------------------\\--------------------------------------------> position
            | <--2.0 * ertSinceSpeedSet--> | <---2.0 * timeout--> |
            |          \\                  |                      |

    .expectedPosition :=  .startingPosition + 2.0 * ertSinceSpeedSet
              timeout := (presentationTime - .expectedPosition) / 2.0

            |        |       |             |<-intermission->|
          --|--------|-------|-------------|---------+------+---+---> time
            |<----------timeout0---------->|         |          |
            |        |<----------timeout1----------->|          |
            +        |       |<-------------timeout2----------->|
    expectedPosition0 +      |             +         |          |
           expectedPosition1 +      renderingTime0   +          |
                    expectedPosition2          renderingTime1   +
                                                         renderingTime2
    renderingTime_n := expectedPosition_n + timeout_n
      hasToRender_n := timeout_n > 0 && |renderingTime_n - lastAcceptedRenderingTime| > intermission
        return value = hasToRender_n ? timeout_n : 0

    Caller1 should not render the buffer it holds because renderingTime1 is too close to renderingTime0.
    Return values for these callers (onOutputBufferAvailable or playbackSpeed) will be:
    caller0: timeout0
    caller1: 0
    caller2: timeout2
         */
    }

    private fun sendReleaseMessage(property: BufferReleaseProperty, timeoutMs: Long, tag: String) {
        val msg = handler.obtainMessage(WHAT_RELEASE_BUFFER, property)
        handler.sendMessageDelayed(msg, timeoutMs)
        pendingMessageProperties.add(property)
        val (outputBufferId, presentationTimeUs, hasToRender) = property
        Log.v(
            TAG,
            "outputBuffer$outputBufferId (${presentationTimeUs / 1000}) will be ${if (hasToRender) "rendered" else "released"} in ${timeoutMs}ms ($tag)"
        )
    }

    /**
     * Accurate video duration extracted via MediaExtractor.
     * Note: media metadata duration ([MediaMetadataRetriever.METADATA_KEY_DURATION])
     * may not be equal to the timestamp of the last sample.
     */
    var durationUs = run {
        Log.i(TAG, "extracting duration of the video")
        var sampleTimeUs = 0L
        extractor.seekTo(Long.MAX_VALUE, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        while (extractor.sampleTime != -1L) {
            sampleTimeUs = extractor.sampleTime
            extractor.advance()
        }
        // TODO: always seek to around 1s, not 0s
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        sampleTimeUs
    }

    init {
        startingErtNs = ertNs()
        decoder.start()
    }

    companion object {
        private val TAG = PlaybackSession::class.java.simpleName

        const val WHAT_RELEASE_BUFFER = 1001

    }
}

