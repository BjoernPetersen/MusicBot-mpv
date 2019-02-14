package net.bjoernpetersen.musicbot.mpv;

import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.loader.NoResource
import net.bjoernpetersen.musicbot.api.plugin.IdBase
import net.bjoernpetersen.musicbot.spi.loader.Resource
import net.bjoernpetersen.musicbot.spi.plugin.AbstractPlayback
import net.bjoernpetersen.musicbot.spi.plugin.InitializationException
import net.bjoernpetersen.musicbot.spi.plugin.Playback
import net.bjoernpetersen.musicbot.spi.plugin.management.InitStateWriter
import net.bjoernpetersen.musicbot.spi.plugin.predefined.AacPlabackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.FlacPlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.Mp3PlaybackFactory
import net.bjoernpetersen.musicbot.spi.plugin.predefined.WavePlaybackFactory
import net.bjoernpetersen.musicbot.youtube.playback.YouTubePlaybackFactory
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val EXECUTABLE = "mpv"

@IdBase("mpv")
class MpvPlaybackFactory :
    AacPlabackFactory,
    FlacPlaybackFactory,
    Mp3PlaybackFactory,
    WavePlaybackFactory,
    YouTubePlaybackFactory {

    override val name: String = "mpv"
    override val description: String = "Plays various files using mpv"

    private lateinit var noVideo: Config.BooleanEntry
    private lateinit var noConfig: Config.BooleanEntry

    override fun createStateEntries(state: Config) {}
    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        noVideo = config.BooleanEntry(
            "noVideo",
            "Don't show video for video files",
            true)

        noConfig = config.BooleanEntry(
            "noConfig",
            "Ignore the default, system-wide mpv config",
            true)

        return listOf(noVideo, noConfig)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()

    override fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Testing executable...")
        try {
            ProcessBuilder(EXECUTABLE, "-h", "--no-config").start()
        } catch (e: IOException) {
            initStateWriter.warning("Failed to start mpv.")
            throw InitializationException(e)
        }
    }

    override fun createPlayback(inputFile: File): Playback {
        if (!inputFile.isFile) throw IOException("File not found: ${inputFile.path}")
        return MpvPlayback(inputFile.canonicalPath, noVideo.get(), noConfig.get())
    }

    override fun load(videoId: String): Resource = NoResource
    override fun createPlayback(videoId: String, resource: Resource): Playback {
        return MpvPlayback("ytdl://$videoId", noVideo.get(), noConfig.get())
    }

    override fun close() {}
}

private class MpvPlayback(path: String, noVideo: Boolean, noConfig: Boolean) : AbstractPlayback() {
    private val logger = KotlinLogging.logger { }

    private val cmdFile = File.createTempFile("mpvCmdFile", null)
    private val mpv = ProcessBuilder(
        EXECUTABLE,
        "--input-file=${cmdFile.canonicalPath}",
        "--no-input-terminal",
        "--no-input-default-bindings",
        "--no-osc",
        "--config=${if (noConfig) "no" else "yes"}",
        "--really-quiet",
        "--video=${if (noVideo) "no" else "auto"}",
        "--pause",
        path)
        .start()
        .also { process ->
            thread(isDaemon = true, name = "mpv-playback-$path-out") {
                val reader = process.inputStream.bufferedReader()
                try {
                    while (process.isAlive) {
                        val line = reader.readLine()
                        logger.debug { "mpv says: $line" }
                    }
                } catch (e: Throwable) {
                    logger.error(e) { "Error while reading mpv output" }
                } finally {
                    reader.close()
                }
                logger.debug { "mpv process ended" }
                markDone()
            }
            thread(isDaemon = true, name = "mpv-playback-$path-error") {
                val reader = process.errorStream.bufferedReader()
                try {
                    while (process.isAlive) {
                        val line = reader.readLine()
                        logger.debug { "mpv warns: $line" }
                    }
                } catch (e: Throwable) {
                    logger.error(e) { "Error while reading mpv error output" }
                } finally {
                    reader.close()
                }
            }
        }
    private val writer: BufferedWriter = cmdFile.bufferedWriter()

    override fun play() {
        writer.write("set pause no")
        writer.newLine()
        writer.flush()
    }

    override fun pause() {
        writer.write("set pause yes")
        writer.newLine()
        writer.flush()
    }

    override fun close() {
        writer.write("quit")
        writer.newLine()
        writer.close()
        if (!mpv.waitFor(5, TimeUnit.SECONDS)) {
            mpv.destroyForcibly()
        }
        if (!cmdFile.delete()) {
            logger.warn { "Could not delete temporary file: ${cmdFile.path}" }
        }
    }
}
