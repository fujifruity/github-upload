package com.example.decodersample

import android.util.Log
import androidx.test.core.app.launchActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class PlayerTest {

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
                val delta = 100.0
                Thread.sleep(2000)
                // Seek forward.
                it.seekTo(4000)
                Thread.sleep(100)
                assertEquals(4100.0, it.currentPositionMs.toDouble(), delta)
                Thread.sleep(2000)
                assertEquals(6100.0, it.currentPositionMs.toDouble(), delta)
                // Seek backward.
                it.seekTo(0)
                Thread.sleep(100)
                assertEquals(100.0, it.currentPositionMs.toDouble(), delta)
                Thread.sleep(2000)
                assertEquals(2100.0, it.currentPositionMs.toDouble(), delta)
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
                assertTrue(it.isInitializedWithVideo())
                assertTrue(false)
                // Change video and check player state.
                it.play(videoUris[1])
                Thread.sleep(3000)
                assertTrue(it.isInitializedWithVideo())
                assertTrue(it.videoUri == videoUris[1])
            }
        }
    }

    @Test
    fun playToTheEndThenPause() {
        val scenario = launchActivity<MainActivity>()
        scenario.onActivity { activity ->
            activity.player!!.also {
                val delta = 100.0
                val durationMs = it.durationMs!! / 1000
                Log.d(TAG, "duration=$durationMs ms")
                Thread.sleep(2000)
                // Seek to the end and check player state.
                it.seekTo(durationMs - 2000)
                Thread.sleep(100)
                assertEquals(durationMs - 1900.0, it.currentPositionMs.toDouble(), delta)
                Thread.sleep(3000)
                // Player should be pausing at the end.
                assertEquals(durationMs.toDouble(), it.currentPositionMs.toDouble(), delta)
                // Player should be playable after pause and seek.
                it.seekTo(durationMs - 2000)
                Thread.sleep(100)
                assertEquals(durationMs - 1900.0, it.currentPositionMs.toDouble(), delta)
                Thread.sleep(3000)
                assertEquals(durationMs.toDouble(), it.currentPositionMs.toDouble(), delta)
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

