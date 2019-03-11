package net.bjoernpetersen.musicbot.mpv;

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import net.bjoernpetersen.musicbot.api.config.Config
import net.bjoernpetersen.musicbot.api.config.FileChooser
import net.bjoernpetersen.musicbot.api.config.FileSerializer
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
import kotlin.coroutines.CoroutineContext

private const val EXECUTABLE = "mpv"

@IdBase("mpv")
class MpvPlaybackFactory :
    AacPlabackFactory,
    FlacPlaybackFactory,
    Mp3PlaybackFactory,
    WavePlaybackFactory,
    YouTubePlaybackFactory,
    CoroutineScope {

    override val name: String = "mpv"
    override val description: String = "Plays various files using mpv"

    private val logger = KotlinLogging.logger { }

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.IO + job

    private lateinit var noVideo: Config.BooleanEntry
    private lateinit var fullscreen: Config.BooleanEntry
    private lateinit var screen: Config.SerializedEntry<Int>
    private lateinit var configFile: Config.SerializedEntry<File>
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

        configFile = config.SerializedEntry(
            key = "configFile",
            description = "A config file in a custom location to include",
            serializer = FileSerializer,
            configChecker = { if (it != null && !it.isFile) "Not a file" else null },
            uiNode = FileChooser(false),
            default = null
        )
        ignoreSystemConfig = config.BooleanEntry(
            "ignoreSystemConfig",
            "Ignore the default, system-wide mpv config",
            true
        )

        return listOf(noVideo, fullscreen, screen, configFile, ignoreSystemConfig)
    }

    override fun createSecretEntries(secrets: Config): List<Config.Entry<*>> = emptyList()

    override suspend fun initialize(initStateWriter: InitStateWriter) {
        initStateWriter.state("Testing executable...")
        withContext(coroutineContext) {
            try {
                @Suppress("BlockingMethodInNonBlockingContext")
                ProcessBuilder(EXECUTABLE, "-h", "--no-config").start()
            } catch (e: IOException) {
                initStateWriter.warning("Failed to start mpv.")
                throw InitializationException(e)
            }
        }

        initStateWriter.state("Retrieving plugin dir...")
        cmdFileDir = fileStorage.forPlugin(this, true)
    }

    override suspend fun createPlayback(inputFile: File): Playback {
        return withContext(coroutineContext) {
            if (!inputFile.isFile) throw IOException("File not found: ${inputFile.path}")
            MpvPlayback(
                cmdFileDir,
                inputFile.canonicalPath,
                noVideo = noVideo.get(),
                fullscreen = fullscreen.get(),
                screen = screen.get()!!,
                configFile = configFile.get(),
                ignoreSystemConfig = ignoreSystemConfig.get()
            )
        }
    }

    override suspend fun load(videoId: String): Resource = NoResource
    override suspend fun createPlayback(videoId: String, resource: Resource): Playback {
        logger.debug { "Creating playback for $videoId" }
        return withContext(coroutineContext) {
            MpvPlayback(
                cmdFileDir,
                "ytdl://$videoId",
                noVideo = noVideo.get(),
                fullscreen = fullscreen.get(),
                screen = screen.get()!!,
                configFile = configFile.get(),
                ignoreSystemConfig = ignoreSystemConfig.get()
            )
        }
    }

    override suspend fun close() {
        job.cancel()
    }
}

private class MpvPlayback(
    dir: File,
    path: String,
    noVideo: Boolean,
    fullscreen: Boolean,
    screen: Int,
    configFile: File?,
    ignoreSystemConfig: Boolean
) : AbstractPlayback() {

    private val logger = KotlinLogging.logger { }

    // TODO for some reason, the file method doesn't work for linux
    private val isWin = System.getProperty("os.name").toLowerCase().startsWith("win")
    private val filePath =
        if (isWin) File.createTempFile("mpvCmd", null, dir).canonicalPath
        else "/dev/stdin"

    private val mpv = mutableListOf(
        EXECUTABLE,
        "--input-file=$filePath",
        "--no-input-terminal",
        "--no-input-default-bindings",
        "--no-osc",
        "--config=${if (ignoreSystemConfig) "no" else "yes"}",
        "--really-quiet",
        "--video=${if (noVideo) "no" else "auto"}",
        "--fullscreen=${if (fullscreen) "yes" else "no"}",
        "--fs-screen=$screen",
        "--screen=$screen",
        "--pause"
    ).let { command ->
        if (configFile != null) command.add("--include=${configFile.absolutePath}")

        command.add(path)

        ProcessBuilder(command)
            .start()
            .also { process ->
                launch(coroutineContext) {
                    logger.debug { "Listening to output" }
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
                launch(coroutineContext) {
                    logger.debug { "Listening to warnings" }
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
                logger.debug { "Started mpv for path: $path" }
            }
    }

    private val writer =
        if (isWin) File(filePath).bufferedWriter()
        else mpv.outputStream.bufferedWriter()

    override suspend fun play() {
        withContext(coroutineContext) {
            writer.apply {
                write("set pause no")
                newLine()
                flush()
            }
        }
    }

    override suspend fun pause() {
        withContext(coroutineContext) {
            writer.apply {
                write("set pause yes")
                newLine()
                flush()
            }
        }
    }

    override suspend fun close() {
        withContext(coroutineContext) {
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
        super.close()
    }
}
