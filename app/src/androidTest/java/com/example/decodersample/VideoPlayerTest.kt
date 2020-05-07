package com.example.decodersample

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.launchActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.videoplayer.VideoPlayer
import org.junit.Assert.assertEquals
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

    fun withMainActivity(action: (activity: MainActivity) -> Unit) {
        val scenario = launchActivity<MainActivity>()
        scenario.onActivity(action)
    }

    fun withPlayerAndVideos(action: (player: VideoPlayer, videos: List<Uri>, mainActivity: MainActivity) -> Unit) {
        withMainActivity { activity ->
            activity.videoPlayer.also { player ->
                val videos = findVideos(activity.applicationContext)
                action(player, videos, activity)
            }
        }
    }

    fun keyframeTimestampsUs(context: Context, videoUri: Uri): List<Long> {
        val extractor = MediaExtractor().also { extractor ->
            extractor.setDataSource(context, videoUri, null)
            // Configure extractor with video track.
            extractor.selectTrack(0)
        }
        // It may take some time. e.g. 300ms for 1h video.
        return sequence {
            while (extractor.sampleTime != -1L) {
                yield(extractor.sampleTime)
                extractor.seekTo(extractor.sampleTime + 1, MediaExtractor.SEEK_TO_NEXT_SYNC)
            }
            extractor.release()
        }.toList()
    }

    fun VideoPlayer.seekToThenSleep(positionMs: Long, sleepLengthMs: Long) {
        this.seekTo(positionMs)
        Thread.sleep(sleepLengthMs)
    }

    @Test
    fun justPlay() = withPlayerAndVideos { player, videos, _ ->
        val delta = 100.0
        val timeout = 3000L
        player.play(videos[0])
        Thread.sleep(timeout)
        assertEquals(timeout.toDouble(), player.currentPositionMs.toDouble(), delta)
    }

    @Test
    fun seekExact() = withPlayerAndVideos { player, videos, context ->
        val delta = 100.0
        val video = videos[0]
        player.play(video)
        player.playbackSpeed = 0.0
        val midPosition = keyframeTimestampsUs(context, video).drop(7).take(2).sum() / 2 / 1000
        player.seekToThenSleep(midPosition, 1000)
        assertEquals(
            "seek to the position which is far from surrounding keyframes'",
            midPosition.toDouble(), player.currentPositionMs.toDouble(), delta
        )
    }

    @Test
    fun seekAsSoonAsStart() = withPlayerAndVideos { player, videos, _ ->
        // decoder.flush random crash
        repeat(7) {
            player.play(videos[0])
            player.seekToThenSleep(3000 + 700 * it.toLong(), 1000)
        }
    }

    @Test
    fun seekOnSpeedChange() = withPlayerAndVideos { player, videos, _ ->
        val delta = 200.0
        player.play(videos[0])
        Thread.sleep(1000)
        repeat(7) { lap ->
            val seedMs = 700L
            var offsetMs = 3000
            fun destPosition() = offsetMs + seedMs * lap
            fun expected() = destPosition() + 1000 * player.playbackSpeed
            player.seekTo(destPosition())
            player.playbackSpeed = 0.5
            Thread.sleep(1000)
            assertEquals(
                "seek then change speed",
                expected(), player.currentPositionMs.toDouble(), delta
            )
            offsetMs = 6000
            player.playbackSpeed = 1.5
            player.seekToThenSleep(destPosition(), 1000)
            assertEquals(
                "seek immediately after change speed",
                expected(), player.currentPositionMs.toDouble(), delta
            )
        }
    }

    @Test
    fun seekToBeginning() = withPlayerAndVideos { player, videos, _ ->
        val delta = 100.0
        player.play(videos[0])
        Thread.sleep(1000)
        player.seekToThenSleep(0, 1000)
        assertEquals("seek with normal speed", 1000.0, player.currentPositionMs.toDouble(), delta)
        player.playbackSpeed = 0.0
        player.seekTo(0, 1000)
        // TODO: fails because extractor.seekTo() cannot seek to 0ms
        assertEquals(
            "seek with pause (known issue: extractor.seekTo() cannot seek to 0ms)",
            0.0, player.currentPositionMs.toDouble(), delta
        )
    }

    @Test
    fun changeVideoWhilePlaying() = withPlayerAndVideos { player, videos, _ ->
        val delta = 100.0
        (0..3).forEach {
            player.play(videos[it])
            Thread.sleep(2000)
            assertEquals(2000.0, player.currentPositionMs.toDouble(), delta)
            assertEquals(player.videoUri, videos[it])
        }
    }

    @Test
    fun playToTheEndThenPause() = withPlayerAndVideos { player, videos, _ ->
        val delta = 100.0
        player.play(videos[0])
        Thread.sleep(1000)
        repeat(3) {
            // Seek to the right before the end, then check player's state.
            player.seekToThenSleep(player.durationMs, 2000)
            assertEquals(player.durationMs.toDouble(), player.currentPositionMs.toDouble(), delta)
        }
    }

    @Test
    fun changePlaybackSpeed() = withPlayerAndVideos { player, videos, _ ->
        val delta = 200.0
        var playedMs = 0.0
        val timeout = 2000L
        player.play(videos[0])
        listOf(4.0, 0.1, 0.0).forEach { spd ->
            player.playbackSpeed = spd
            Thread.sleep(timeout)
            playedMs += player.playbackSpeed * timeout
            assertEquals(playedMs, player.currentPositionMs.toDouble(), delta)
        }
    }


    companion object {
        private val TAG = VideoPlayerTest::class.java.simpleName
    }
}

