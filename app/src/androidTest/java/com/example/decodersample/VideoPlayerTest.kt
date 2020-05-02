package com.example.decodersample

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.launchActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class VideoPlayerTest {

    /**
     * Returns video uris sorted by size in descending order.
     */
    fun findVideos(context: Context): List<Uri> {
        val externalContentUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val cursor = context.contentResolver.query(
            externalContentUri, null, null, null, null
        )
        return cursor?.use { cursor ->
            (1..cursor.count).mapNotNull {
                cursor.moveToNext()
                val id =
                    cursor.getColumnIndex(MediaStore.Video.Media._ID).let { cursor.getLong(it) }
                val size =
                    cursor.getColumnIndex(MediaStore.Video.Media.SIZE).let { cursor.getLong(it) }
                val uri = ContentUris.withAppendedId(externalContentUri, id)
                uri to size
            }.sortedBy { it.second /* size */ }.map { it.first /* uri */ }.reversed()
        }!!
    }

    fun launchMainActivityThen(onActivity: (activity: MainActivity) -> Unit) {
        val scenario = launchActivity<MainActivity>()
        scenario.onActivity(onActivity)
    }

    @Test
    fun justPlay() = launchMainActivityThen { activity ->
        activity.videoPlayer.also {
            val video = findVideos(activity.applicationContext)[0]
            it.play(video)
            Thread.sleep(1000)
        }
    }

    @Test
    fun seekExact() = launchMainActivityThen { activity ->
        activity.videoPlayer.also {
            val video = findVideos(activity.applicationContext)[0]
            val delta = 100.0
            it.play(video)
            Thread.sleep(2000)
            it.playbackSpeed = 0.0
            it.seekTo(3300)
            Thread.sleep(2000)
            assertEquals(3300.0, it.currentPositionMs.toDouble(), delta)
            // // TODO: do it speedy
            it.seekTo(3500)
            Thread.sleep(100)
            assertEquals(3500.0, it.currentPositionMs.toDouble(), delta)
        }
    }

    @Test
    fun seekImmediately() = launchMainActivityThen { activity ->
        activity.videoPlayer.also {
            val video = findVideos(activity.applicationContext)[0]
            val delta = 200.0
            it.play(video)
            // seek right after start playing
            it.seekTo(6_000)
            Thread.sleep(4000)
            assertEquals(10_000.0, it.currentPositionMs.toDouble(), delta)
            // seek right after change speed
            it.playbackSpeed = 2.0
            it.seekTo(16_000)
            Thread.sleep(2000)
            assertEquals(20_000.0, it.currentPositionMs.toDouble(), delta)
        }
    }

    @Test
    fun doPause() = launchMainActivityThen { activity ->
        activity.videoPlayer.also {
            val video = findVideos(activity.applicationContext)[0]
            val delta = 0.0
            it.play(video)
            it.playbackSpeed = 2.0
            Thread.sleep(2000)
            it.togglePause()
            Thread.sleep(2000)
            assertEquals(it.playbackSpeed, 0.0, delta)
            it.togglePause()
            Thread.sleep(2000)
            assertEquals(it.playbackSpeed, 2.0, delta)
        }
    }

    @Test
    fun seekToBeginning() = launchMainActivityThen { activity ->
        activity.videoPlayer.also {
            val video = findVideos(activity.applicationContext)[0]
            it.play(video)
            val delta = 100.0
            Thread.sleep(2000)
            it.playbackSpeed = 0.0
            it.seekTo(0)
            Thread.sleep(2000)
            assertEquals(0.0, it.currentPositionMs.toDouble(), delta)
        }
    }

    @Test
    fun seekForwardBackward() = launchMainActivityThen { activity ->
        activity.videoPlayer.also {
            val video = findVideos(activity.applicationContext)[0]
            it.play(video)
            val delta = 100.0
            Thread.sleep(2000)
            // Seek forward.
            it.seekTo(7000)
            Thread.sleep(1000)
            assertEquals(8000.0, it.currentPositionMs.toDouble(), delta)
            // Seek backward.
            it.seekTo(3000)
            Thread.sleep(1000)
            assertEquals(4000.0, it.currentPositionMs.toDouble(), delta)
        }
    }

    @Test
    fun changeVideoWhilePlaying() = launchMainActivityThen { activity ->
        activity.videoPlayer.also {
            val videoUris = findVideos(activity.applicationContext)
            it.play(videoUris[0])
            Thread.sleep(2000)
            assertTrue(it.isPlaying())
            assertEquals(it.videoUri, videoUris[0])
            // Change video and check player state.
            it.play(videoUris[1])
            Thread.sleep(2000)
            assertTrue(it.isPlaying())
            assertEquals(it.videoUri, videoUris[1])
        }
    }

    @Test
    fun playToTheEndThenPause() = launchMainActivityThen { activity ->
        activity.videoPlayer.also {
            val videoUris = findVideos(activity.applicationContext)
            val delta = 100.0
            it.play(videoUris[1])
            Thread.sleep(2000)
            val seekAndPauseAtTheEnd = {
                // Seek to the right before the end, then check player's state.
                it.seekTo(it.durationMs - 3000)
                Thread.sleep(2000)
                assertEquals(it.durationMs - 1000.0, it.currentPositionMs.toDouble(), delta)
                Thread.sleep(2000)
                // Player should be pausing at the end.
                assertTrue(it.durationMs - 1000.0 < it.currentPositionMs.toDouble())
            }
            seekAndPauseAtTheEnd()
            // Player should be playable after pausing at the end.
            seekAndPauseAtTheEnd()
        }
    }

    @Test
    fun changePlaybackSpeed() = launchMainActivityThen { activity ->
        activity.videoPlayer.also {
            val video = findVideos(activity.applicationContext)[0]
            val delta = 200.0
            var playedMs = 0.0
            val timeout = 2000L
            it.play(video)

            fun setSleepAssert(spd: Double) {
                it.playbackSpeed = spd
                Thread.sleep(timeout)
                playedMs += it.playbackSpeed * timeout
                assertEquals(playedMs, it.currentPositionMs.toDouble(), delta)
            }
            setSleepAssert(1.0)
            setSleepAssert(0.0)
            setSleepAssert(4.0)
            setSleepAssert(0.1)

        }
    }


    companion object {
        private val TAG = VideoPlayerTest::class.java.simpleName
    }
}

