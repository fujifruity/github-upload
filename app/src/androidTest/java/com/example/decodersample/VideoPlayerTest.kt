package com.example.decodersample

import android.content.ContentUris
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
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

    fun withPlayerAndVideos(action: (player: VideoPlayer, videos: List<Uri>) -> Unit) {
        withMainActivity { activity ->
            activity.videoPlayer.also { player ->
                val videos = findVideos(activity.applicationContext)
                action(player, videos)
            }
        }
    }

    fun keyframeTimestampsUs(context: Context, videoUri: Uri): List<Long> {
        val extractor = MediaExtractor().also { extractor ->
            extractor.setDataSource(context, videoUri, null)
            val (videoTrackIdToVideoFormat, mime) = 0.until(extractor.trackCount).map { trackId ->
                val format = extractor.getTrackFormat(trackId)
                val mime = format.getString(MediaFormat.KEY_MIME)!!
                Pair(trackId, format) to mime
            }.find { (_, mime) ->
                mime.startsWith("video/")
            }!!
            val (trackId, format) = videoTrackIdToVideoFormat
            // Configure extractor with video track.
            extractor.selectTrack(trackId)
        }
        // It may take some time. e.g. 300ms for 1h video.
        return sequence {
            while (extractor.sampleTime != -1L) {
                yield(extractor.sampleTime)
                extractor.seekTo(extractor.sampleTime + 1, MediaExtractor.SEEK_TO_NEXT_SYNC)
            }
        }.toList()
    }

    @Test
    fun justPlay() = withPlayerAndVideos { player, videos ->
        val delta = 100.0
        player.play(videos[0])
        Thread.sleep(2000)
        assertEquals(2000.0, player.currentPositionMs.toDouble(), delta)
    }

    @Test
    fun seekExact() = withPlayerAndVideos { player, videos ->
        val delta = 100.0
        val video = videos[0]
        player.play(video)
        Thread.sleep(2000)
        player.playbackSpeed = 0.0
        player.seekTo(3300)
        Thread.sleep(2000)
        assertEquals(3300.0, player.currentPositionMs.toDouble(), delta)
    }

    @Test
    fun seekImmediately() = withPlayerAndVideos { player, videos ->
        val delta = 200.0
        player.play(videos[0])
        // seek immediately after start playing
        player.seekTo(8_000)
        Thread.sleep(2000)
        assertEquals(10_000.0, player.currentPositionMs.toDouble(), delta)
        // seek immediately after change speed
//            it.playbackSpeed = 2.0
        player.seekTo(16_000)
        player.playbackSpeed = 2.0
        Thread.sleep(2000)
        assertEquals(20_000.0, player.currentPositionMs.toDouble(), delta)
    }

    @Test
    fun seekToBeginning() = withPlayerAndVideos { player, videos ->
        player.play(videos[0])
        val delta = 100.0
        Thread.sleep(2000)
        player.playbackSpeed = 0.0
        player.seekTo(0)
        Thread.sleep(2000)
        assertEquals(0.0, player.currentPositionMs.toDouble(), delta)
    }

    @Test
    fun seekForwardBackward() = withPlayerAndVideos { player, videos ->
        player.play(videos[0])
        val delta = 100.0
        Thread.sleep(2000)
        // Seek forward.
        player.seekTo(7000)
        Thread.sleep(1000)
        assertEquals(8000.0, player.currentPositionMs.toDouble(), delta)
        // Seek backward.
        player.seekTo(3000)
        Thread.sleep(1000)
        assertEquals(4000.0, player.currentPositionMs.toDouble(), delta)
    }

    @Test
    fun changeVideoWhilePlaying() = withPlayerAndVideos { player, videos ->
        val delta = 100.0
        player.play(videos[1])
        assertEquals(2000.0, player.currentPositionMs.toDouble(), delta)
        assertEquals(player.videoUri, videos[1])
        Thread.sleep(2000)
        // Change video and check player state.
        player.play(videos[2])
        Thread.sleep(2000)
        assertEquals(2000.0, player.currentPositionMs.toDouble(), delta)
        assertEquals(player.videoUri, videos[2])
    }

    @Test
    fun playToTheEndThenPause() = withPlayerAndVideos { player, videos ->
        val delta = 1000.0
        player.play(videos[1])
        Thread.sleep(1000)
        repeat(2) {
            // Seek to the right before the end, then check player's state.
            player.seekTo(player.durationMs)
            Thread.sleep(4000)
            // TODO: render once extractor sought
            assertEquals(player.durationMs.toDouble(), player.currentPositionMs.toDouble(), delta)
        }
    }

    @Test
    fun changePlaybackSpeed() = withPlayerAndVideos { player, videos ->
        val delta = 200.0
        var playedMs = 0.0
        val timeout = 2000L
        player.play(videos[0])
        Thread.sleep(timeout)
        listOf(0.0, 4.0, 0.1).forEach { spd ->
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

