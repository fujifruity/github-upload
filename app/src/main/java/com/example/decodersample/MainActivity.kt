package com.example.decodersample

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.*


class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private var player: PlayerThread? = null
    private lateinit var surfaceView: SurfaceView
    private lateinit var timer: Timer
    private lateinit var handler: Handler
    private lateinit var handlerThread: HandlerThread

    private val requestCodePermissions = 1
    private val permissions = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    private fun allPermissionsGranted() = permissions.all {
        ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (allPermissionsGranted()) {
            surfaceView.holder.addCallback(this)
        } else {
            Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        surfaceView = findViewById(R.id.surfaceView)
        handlerThread = HandlerThread("renderThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        if (allPermissionsGranted()) {
            surfaceView.holder.addCallback(this)
        } else {
            ActivityCompat.requestPermissions(this, permissions, requestCodePermissions)
        }

//        // Monitor playing position while flip-flopping playback speed.
//        timer = Timer()
//        timer.schedule(object : TimerTask() {
//            var lastPosMs = 0L
//            var counter = 0
//            override fun run() {
//                player?.run {
//                    val pos = currentPositionUs().div(1_000)
//                    Log.d(TAG, "pos=${pos}ms (+${pos - lastPosMs}ms, x%.2f)".format(playbackSpeed))
//                    lastPosMs = pos
//                    if(counter%2==1){
//                        playbackSpeed = playbackSpeed % 2 + 1
//                    }
//                    counter+=1
//                }
//            }
//        }, 0, 1000)

//        // seek to somewhere every second.
//        timer = Timer()
//        timer.schedule(object : TimerTask() {
//            override fun run() {
//                player?.run {
//                }
//            }
//        }, 0, 1000)

    }

    override fun onPause() {
        super.onPause()
        Log.i(TAG, "Stop timer task.")
        timer.cancel()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        player = PlayerThread(holder.surface)
        player?.start()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        player?.interrupt()
    }

    private inner class PlayerThread(val surface: Surface) : Thread() {

        val bufferInfo = BufferInfo()
        var playbackSpeed = 1.0
        val extractor = MediaExtractor().apply {
            val videoUri = findVideo(this@MainActivity)
            Log.d(TAG, "video found: $videoUri")
            setDataSource(this@MainActivity, videoUri, null)
        }

        fun currentPositionUs(): Long {
            return bufferInfo.presentationTimeUs
        }

        fun seekTo(pos: Long) {
        }

        override fun run() {

            val decoder = extractor.run {
                val (trackIdToformat, mime) = 0.until(trackCount).map { trackId ->
                    val format = getTrackFormat(trackId)
                    val mime = format.getString(MediaFormat.KEY_MIME)!!
                    trackId to format to mime
                }.find { (_, mime) ->
                    mime.startsWith("video/")
                }!!
                val (trackId, format) = trackIdToformat
                // Configure extractor with video track.
                selectTrack(trackId)
                MediaCodec.createDecoderByType(mime).apply {
                    configure(format, surface, null, 0)
                }
            }

            decoder.start()
            var isEOS = false
            val nowNs = { System.nanoTime() }
            val startNs = nowNs()

            fun render() {
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

                // Retrieve a decoded frame from decoder and render it on the surface.
                val outputBufferId = decoder.dequeueOutputBuffer(bufferInfo, 10000)
                when (outputBufferId) {
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        Log.d(TAG, "INFO_OUTPUT_BUFFERS_CHANGED")
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        Log.d(TAG, "New format " + decoder.outputFormat)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        Log.d(TAG, "dequeueOutputBuffer timed out")
                    }
                    else -> {
                        // Send the output buffer to the surface.
                        decoder.releaseOutputBuffer(outputBufferId, true)
                    }
                }

                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    // All decoded frames have been rendered, we can stop playing now
                    Log.d(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM")
                    decoder.stop()
                    decoder.release()
                    extractor.release()
                } else {
                    val elapsedNs = nowNs() - startNs
                    val timeoutMs = (bufferInfo.presentationTimeUs * 1000 - elapsedNs) / 1000_000
                    handler.postDelayed({ render() }, timeoutMs)
                }
            }

            handler.post { render() }

        }

    }

    /**
     * Returns Uri of the shortest video.
     */
    fun findVideo(context: Context): Uri {
        val externalContentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cursor = context.contentResolver.query(
            externalContentUri, null, null, null, null
        )
        val uri = cursor?.use { cursor ->
            (1..cursor.count).mapNotNull {
                cursor.moveToNext()
                val id =
                    cursor.getColumnIndex(MediaStore.Video.Media._ID).let { cursor.getLong(it) }
                val size =
                    cursor.getColumnIndex(MediaStore.Video.Media.SIZE).let { cursor.getLong(it) }
                val uri = ContentUris.withAppendedId(externalContentUri, id)
                uri to size
            }.minBy { it.second }!!.first
        }!!
        return uri
    }

    companion object {
        private val TAG = MainActivity::class.java.simpleName
    }

}
