package tv.own.owntv.features.multiview

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.util.UnstableApi
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.live.OpenStreamRegistry
import tv.own.owntv.core.live.StreamGrant
import tv.own.owntv.core.live.StreamPurpose
import tv.own.owntv.core.live.connectionBudget
import tv.own.owntv.features.live.LiveViewModel
import tv.own.owntv.player.LiveEnginePool

private fun openingTileCount(maxTiles: Int): Int = minOf(2, maxTiles).coerceAtLeast(1)

data class MultiviewTile(
    val channel: ChannelEntity? = null,
    val refusal: StreamGrant.Refused? = null,
    val deviceLimit: Boolean = false,
) {
    val isEmpty: Boolean get() = channel == null && refusal == null && !deviceLimit
}

@UnstableApi
class MultiviewState(
    val pool: LiveEnginePool,
    private val registry: OpenStreamRegistry,
    private val live: LiveViewModel,
    private val maxTiles: Int,
) {
    val tiles = mutableStateListOf<MultiviewTile>().apply {
        repeat(openingTileCount(maxTiles)) { add(MultiviewTile()) }
    }

    val canAddTile: Boolean get() = tiles.size < maxTiles

    fun addTile() {
        if (canAddTile) tiles.add(MultiviewTile())
    }

    var focused by mutableIntStateOf(0)
        private set

    var audible by mutableIntStateOf(0)
        private set

    private val claims = HashMap<Int, OpenStreamRegistry.Claim>()

    /** Tile indexes currently used as audio-only sources. Normally this contains at most one item. */
    val soundOnly = mutableStateListOf<Int>()

    var videoSourceTile by mutableStateOf<Int?>(null)
        private set

    var audioSourceTile by mutableStateOf<Int?>(null)
        private set

    /** Make [tile] the full-resolution video source and keep a different audio source. */
    fun setVideoSource(tile: Int) {
        if (tiles.getOrNull(tile)?.channel == null) return
        val audioTile = audioSourceTile?.takeIf { it != tile && tiles.getOrNull(it)?.channel != null }
            ?: tiles.indices.firstOrNull { it != tile && tiles[it].channel != null }
        if (audioTile == null) {
            giveSoundTo(tile)
            return
        }
        videoSourceTile = tile
        audioSourceTile = audioTile
        soundOnly.clear()
        soundOnly.add(audioTile)
        audible = audioTile
        pool.setAudioSource(audioTile = audioTile, videoTile = tile)
    }

    /** Make [tile] the audio source for the current video source. */
    fun setAudioSource(tile: Int) {
        if (tiles.getOrNull(tile)?.channel == null) return
        val videoTile = videoSourceTile?.takeIf { it != tile && tiles.getOrNull(it)?.channel != null }
            ?: tiles.indices.firstOrNull { it != tile && tiles[it].channel != null }
        if (videoTile == null) {
            setSoundOnly(tile, true)
            return
        }
        videoSourceTile = videoTile
        audioSourceTile = tile
        soundOnly.clear()
        soundOnly.add(tile)
        audible = tile
        pool.setAudioSource(audioTile = tile, videoTile = videoTile)
    }

    /** Leave split source mode and return audio to the video source. */
    fun removeAudioSource() {
        val videoTile = videoSourceTile?.takeIf { tiles.getOrNull(it)?.channel != null }
        val audioTile = audioSourceTile?.takeIf { tiles.getOrNull(it)?.channel != null }
        audioTile?.let { pool.peek(it)?.exitAudioOnly() }
        soundOnly.clear()
        audioSourceTile = null
        if (videoTile != null) {
            videoSourceTile = null
            giveSoundTo(videoTile)
        } else {
            videoSourceTile = null
            audioTile?.let { giveSoundTo(it) }
        }
    }

    /**
     * Explicitly separate Video Source and Audio Source.
     *
     * The selected [tile] supplies audio. The currently audible normal tile is retained as the
     * Video Source whenever possible, so choosing an Arabic commentary tile does not downgrade the
     * 4K video tile to the normal 720p background limit.
     */
    fun setSoundOnly(tile: Int, value: Boolean) {
        if (tiles.getOrNull(tile)?.channel == null) return

        if (value) {
            val videoTile = when {
                audible != tile && tiles.getOrNull(audible)?.channel != null -> audible
                else -> tiles.indices.firstOrNull { it != tile && tiles[it].channel != null }
            }

            // Restore any previous audio-only tile before assigning the new role.
            soundOnly.toList().filter { it != tile }.forEach { other ->
                pool.peek(other)?.exitAudioOnly()
                soundOnly.remove(other)
            }

            if (videoTile != null) {
                videoSourceTile = videoTile
                audioSourceTile = tile
                pool.setAudioSource(audioTile = tile, videoTile = videoTile)
            } else {
                videoSourceTile = null
                audioSourceTile = tile
                pool.setSoundOnly(tile, true)
            }

            if (tile !in soundOnly) soundOnly.add(tile)
            audible = tile
        } else {
            if (audioSourceTile == tile) {
                removeAudioSource()
                return
            }
            pool.setSoundOnly(tile, false)
            soundOnly.remove(tile)
            if (videoSourceTile == tile) videoSourceTile = null
            if (audible == tile) giveSoundTo(tile)
        }
    }

    fun focus(tile: Int) {
        if (tile in tiles.indices) focused = tile
    }

    fun silenceFailed(tile: Int) {
        pool.peek(tile)?.setMuted(true)
        if (audible != tile) return
        val next = tiles.indices.firstOrNull { it != tile && tiles[it].channel != null && !failed(it) }
        if (next != null) giveSoundTo(next) else pool.giveSoundTo(null)
    }

    private fun failed(tile: Int): Boolean =
        pool.peek(tile)?.state?.value == tv.own.owntv.player.LivePreviewEngine.State.ERROR

    /** Give sound to a normal visible tile and cancel source separation if it is active. */
    fun giveSoundTo(tile: Int) {
        if (tiles.getOrNull(tile)?.channel == null) return
        if (failed(tile)) return

        // Selecting a normal audible tile means the user is leaving Video Source + Audio Source mode.
        soundOnly.toList().forEach { audioTile ->
            pool.peek(audioTile)?.exitAudioOnly()
        }
        soundOnly.clear()
        videoSourceTile = null
        audioSourceTile = null

        audible = tile
        pool.giveSoundTo(tile)
    }

    fun fill(tile: Int, channel: ChannelEntity) {
        if (tile !in tiles.indices) return
        releaseClaim(tile)
        val source = live.sourceOf(channel)
        when (val grant = connectionBudget(source, registry.openOn(channel.sourceId), StreamPurpose.WATCHING)) {
            is StreamGrant.Refused -> {
                tiles[tile] = MultiviewTile(refusal = grant)
                return
            }
            StreamGrant.Allowed -> Unit
        }
        claims[tile] = registry.claim(channel.sourceId, StreamPurpose.WATCHING)
        tiles[tile] = MultiviewTile(channel = channel)
        val splitMode = videoSourceTile != null && audioSourceTile != null
        live.tuneTile(pool.engineFor(tile), channel, muted = splitMode || audible != tile)
        if (splitMode) {
            pool.peek(tile)?.setMuted(true)
            pool.peek(tile)?.setMaxVideoHeight(tv.own.owntv.player.LiveEnginePool.BACKGROUND_TILE_HEIGHT)
            pool.setAudioSource(audioSourceTile!!, videoSourceTile!!)
        } else if (tiles.count { it.channel != null } == 1) {
            giveSoundTo(tile)
        } else if (audible == tile) {
            pool.giveSoundTo(tile)
        }
    }

    fun refuseDeviceLimit(tile: Int) {
        if (tiles.getOrNull(tile)?.channel == null) return
        releaseClaim(tile)
        pool.release(tile)
        soundOnly.remove(tile)
        tiles[tile] = MultiviewTile(deviceLimit = true)
        if (audible == tile) {
            val next = tiles.indexOfFirst { it.channel != null }
            if (next >= 0) giveSoundTo(next) else pool.giveSoundTo(null)
        }
    }

    fun clear(tile: Int) {
        if (tile !in tiles.indices) return
        releaseClaim(tile)
        val removedAudioSource = audioSourceTile == tile
        val removedVideoSource = videoSourceTile == tile
        pool.release(tile)
        soundOnly.remove(tile)
        if (removedAudioSource) audioSourceTile = null
        if (removedVideoSource) videoSourceTile = null
        tiles[tile] = MultiviewTile()
        while (tiles.size > openingTileCount(maxTiles) && tiles.last().isEmpty) {
            pool.release(tiles.lastIndex)
            tiles.removeAt(tiles.lastIndex)
        }
        if (focused > tiles.lastIndex) focused = tiles.lastIndex
        if (audible == tile) audible = tiles.indexOfFirst { it.channel != null }.coerceAtLeast(0)
        if (removedVideoSource && audioSourceTile != null) {
            val replacement = tiles.indices.firstOrNull { it != audioSourceTile && tiles[it].channel != null }
            if (replacement != null) {
                videoSourceTile = replacement
                pool.setAudioSource(audioSourceTile!!, replacement)
            } else {
                pool.peek(audioSourceTile!!)?.exitAudioOnly()
                pool.giveSoundTo(audioSourceTile)
                soundOnly.clear()
                audioSourceTile = null
                videoSourceTile = null
            }
        }
    }

    fun releaseAll() {
        claims.values.forEach { registry.release(it) }
        claims.clear()
        pool.releaseAll()
    }

    private fun releaseClaim(tile: Int) {
        claims.remove(tile)?.let { registry.release(it) }
    }
}
