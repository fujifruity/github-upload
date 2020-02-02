package com.example.decodersample

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaCodec.BufferInfo
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Surface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.nio.ByteBuffer


class MainActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private val TAG = this::class.java.simpleName
    private var player: PlayerThread? = null
    private lateinit var surfaceView: SurfaceView

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

        surfaceView = findViewById<SurfaceView>(R.id.surfaceView)

        if (allPermissionsGranted()) {
            surfaceView.holder.addCallback(this)
        } else {
            ActivityCompat.requestPermissions(this, permissions, requestCodePermissions)
        }

    }

    override fun surfaceCreated(holder: SurfaceHolder) {}

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int
    ) {
        if (player == null) {
            player = PlayerThread(holder.surface)
            player!!.start()
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (player != null) {
            player!!.interrupt()
        }
    }

    private inner class PlayerThread(surface: Surface) : Thread() {

        private var extractor: MediaExtractor? = null
        private var decoder: MediaCodec? = null
        private val surface = surface

        override fun run() {
            extractor = MediaExtractor().apply {
                val videoUri = findVideo(this@MainActivity)
                Log.d(TAG, "video found: $videoUri")
                setDataSource(this@MainActivity, videoUri, null)
            }

            for (i in 0 until extractor!!.trackCount) {
                val format = extractor!!.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime.startsWith("video/")) {
                    extractor!!.selectTrack(i)
                    decoder = MediaCodec.createDecoderByType(mime)
                    decoder!!.configure(format, surface, null, 0)
                    break
                }
            }

            if (decoder == null) {
                Log.e("DecodeActivity", "Can't find video info!")
                return
            }

            decoder!!.start()
            val inputBuffers: Array<ByteBuffer> = decoder!!.inputBuffers
            var outputBuffers: Array<ByteBuffer> = decoder!!.outputBuffers
            val info = BufferInfo()
            var isEOS = false
            val startMs = System.currentTimeMillis()

            while (!interrupted()) {
                if (!isEOS) {
                    val inIndex = decoder!!.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val buffer: ByteBuffer = inputBuffers[inIndex]
                        val sampleSize = extractor!!.readSampleData(buffer, 0)
                        if (sampleSize < 0) { // We shouldn't stop the playback at this point, just pass the EOS
// flag to decoder, we will get it again from the
// dequeueOutputBuffer
                            Log.d("DecodeActivity", "InputBuffer BUFFER_FLAG_END_OF_STREAM")
                            decoder!!.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            isEOS = true
                        } else {
                            decoder!!.queueInputBuffer(
                                inIndex,
                                0,
                                sampleSize,
                                extractor!!.sampleTime,
                                0
                            )
                            extractor!!.advance()
                        }
                    }
                }

                val outIndex = decoder!!.dequeueOutputBuffer(info, 10000)

                when (outIndex) {
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                        Log.d("DecodeActivity", "INFO_OUTPUT_BUFFERS_CHANGED")
                        outputBuffers = decoder!!.outputBuffers
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Log.d(
                        "DecodeActivity",
                        "New format " + decoder!!.outputFormat
                    )
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Log.d(
                        "DecodeActivity",
                        "dequeueOutputBuffer timed out!"
                    )
                    else -> {
                        val buffer: ByteBuffer = outputBuffers[outIndex]
                        Log.v(
                            "DecodeActivity",
                            "We can't use this buffer but render it due to the API limit, $buffer"
                        )

                        // We use a very simple clock to keep the video FPS, or the video
// playback will be too fast
                        while (info.presentationTimeUs / 1000 > System.currentTimeMillis() - startMs) {
                            try {
                                sleep(10)
                            } catch (e: InterruptedException) {
                                e.printStackTrace()
                                break
                            }
                        }

                        decoder!!.releaseOutputBuffer(outIndex, true)
                    }
                }

                // All decoded frames have been rendered, we can stop playing now
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    Log.d("DecodeActivity", "OutputBuffer BUFFER_FLAG_END_OF_STREAM")
                    break
                }
            }

            decoder!!.stop()
            decoder!!.release()
            extractor!!.release()
        }

    }

    /**
     * Returns Uri of the largest video.
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
            }.maxBy { it.second }!!.first
        }!!
        return uri
    }

}
