package net.bjoernpetersen.musicbot.mpv;

import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.IntSerializer
import net.bjoernpetersen.musicbot.api.config.NonnullConfigChecker
import net.bjoernpetersen.musicbot.api.config.NumberBox
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
import net.bjoernpetersen.musicbot.spi.util.FileStorage
import net.bjoernpetersen.musicbot.youtube.playback.YouTubePlaybackFactory
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
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
    private lateinit var fullscreen: Config.BooleanEntry
    private lateinit var screen: Config.SerializedEntry<Int>
    private lateinit var ignoreSystemConfig: Config.BooleanEntry

    @Inject
    private lateinit var fileStorage: FileStorage
    private lateinit var cmdFileDir: File

    override fun createStateEntries(state: Config) {}
    override fun createConfigEntries(config: Config): List<Config.Entry<*>> {
        noVideo = config.BooleanEntry(
            "noVideo",
            "Don't show video for video files",
            true
        )
        fullscreen = config.BooleanEntry(
            "fullscreen",
            "Show videos in fullscreen mode",
            true
        )
        screen = config.SerializedEntry(
            key = "screen",
            description = "Screen to show videos on (0-32)",
            serializer = IntSerializer,
            configChecker = NonnullConfigChecker,
            uiNode = NumberBox(0, 32),
            default = 1
        )

        ignoreSystemConfig = config.BooleanEntry(
            "ignoreSystemConfig",
            "Ignore the default, system-wide mpv config",
            true
        )

        return listOf(noVideo, fullscreen, screen, ignoreSystemConfig)
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

        initStateWriter.state("Retrieving plugin dir...")
        cmdFileDir = fileStorage.forPlugin(this, true)
    }

    override fun createPlayback(inputFile: File): Playback {
        if (!inputFile.isFile) throw IOException("File not found: ${inputFile.path}")
        return MpvPlayback(
            cmdFileDir,
            inputFile.canonicalPath,
            noVideo = noVideo.get(),
            fullscreen = fullscreen.get(),
            screen = screen.get()!!,
            ignoreSystemConfig = ignoreSystemConfig.get()
        )
    }

    override fun load(videoId: String): Resource = NoResource
    override fun createPlayback(videoId: String, resource: Resource): Playback {
        return MpvPlayback(
            cmdFileDir,
            "ytdl://$videoId",
            noVideo = noVideo.get(),
            fullscreen = fullscreen.get(),
            screen = screen.get()!!,
            ignoreSystemConfig = ignoreSystemConfig.get()
        )
    }

    override fun close() {}
}

private class MpvPlayback(
    dir: File,
    path: String,
    noVideo: Boolean,
    fullscreen: Boolean,
    screen: Int,
    ignoreSystemConfig: Boolean
) : AbstractPlayback() {

    private val logger = KotlinLogging.logger { }

    // TODO for some reason, the file method doesn't work for linux
    private val isWin = System.getProperty("os.name").toLowerCase().startsWith("win")
    private val filePath =
        if (isWin) File.createTempFile("mpvCmd", null, dir).canonicalPath
        else "/dev/stdin"
    private val mpv = ProcessBuilder(
        EXECUTABLE,
        "--input-file=$filePath",
        "--no-input-terminal",
        "--no-input-default-bindings",
        "--no-osc",
        "--config=${if (ignoreSystemConfig) "no" else "yes"}",
        "--really-quiet",
        "--video=${if (noVideo) "no" else "auto"}",
        "--fullscreen=${if (fullscreen) "yes" else "no"}",
        "--screen=$screen",
        "--pause",
        path
    )
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
    private val writer =
        if (isWin) File(filePath).bufferedWriter()
        else mpv.outputStream.bufferedWriter()

    override fun play() {
        writer.apply {
            write("set pause no")
            newLine()
            flush()
        }
    }

    override fun pause() {
        writer.apply {
            write("set pause yes")
            newLine()
            flush()
        }
    }

    override fun close() {
        if (mpv.isAlive) {
            writer.use {
                try {
                    it.write("quit")
                    it.newLine()
                } catch (e: IOException) {
                    logger.warn(e) { "Could not send quit command to mpv" }
                }
            }
        }

        if (!mpv.waitFor(5, TimeUnit.SECONDS)) {
            logger.warn { "There is probably an unclosed mpv process." }
            mpv.destroyForcibly()
        }

        if (isWin && !File(filePath).delete()) {
            logger.warn { "Could not delete temporary file: $filePath" }
        }
    }
}
