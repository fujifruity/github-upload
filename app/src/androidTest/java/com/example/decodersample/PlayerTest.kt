package com.example.decodersample

import android.util.Log
import androidx.test.core.app.launchActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.absoluteValue

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class PlayerTest {

    fun approxEqual(a: Long, b: Long) = (a - b).absoluteValue < 300

    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.decodersample", appContext.packageName)
    }

    @Test
    fun performSeek() {
        val scenario = launchActivity<MainActivity>()
        scenario.onActivity { activity ->
            activity.player!!.also {
                Thread.sleep(3000)
                it.seekTo(10_000)
                Log.i(TAG, "position=${it.currentPositionMs}")
                assert(approxEqual(it.currentPositionMs, 10_000))
                Thread.sleep(3000)
            }
        }
    }

    @Test
    fun changeVideoWhilePlaying() {
        val scenario = launchActivity<MainActivity>()
        scenario.onActivity { activity ->
            Thread.sleep(3000)
            val videoUris = activity.findVideos(activity.applicationContext)
            activity.player!!.also {
                assert(it.isPlaying())
                assert(it.videoUri == videoUris[0])
                // Change video and check player state.
                it.play(videoUris[1])
                Thread.sleep(3000)
                assert(it.isPlaying())
                assert(it.videoUri == videoUris[1])
            }
        }
    }

    companion object {
        private val TAG = PlayerTest::class.java.simpleName
    }
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

