package net.bjoernpetersen.musicbot.mpv;

import com.zaxxer.nuprocess.NuAbstractProcessHandler
import com.zaxxer.nuprocess.NuProcess
import com.zaxxer.nuprocess.NuProcessBuilder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.time.Duration
import java.util.LinkedList
import java.util.regex.Matcher
import java.util.regex.Pattern
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

private class MpvHandler : NuAbstractProcessHandler() {
    private lateinit var mpv: NuProcess

    private val patternMutex = Mutex()
    private val patterns: MutableMap<Pattern, CompletableDeferred<Matcher>> = HashMap(32)

    private val currentLine = StringBuilder(128)

    private val exitValue = CompletableDeferred<Int>()

    override fun onPreStart(nuProcess: NuProcess) {
        this.mpv = nuProcess
    }

    private fun matchLine(line: String) {
        runBlocking {
            patternMutex.withLock {
                val remove = LinkedList<Pattern>()
                for ((pattern, deferred) in patterns.entries) {
                    val matcher = pattern.matcher(line)
                    if (matcher.matches()) {
                        deferred.complete(matcher)
                        remove.add(pattern)
                    }
                }
                remove.forEach { patterns.remove(it) }
            }
        }
    }

    override fun onStdout(buffer: ByteBuffer, closed: Boolean) {
        if (closed) {
            if (!currentLine.isBlank()) {
                matchLine(currentLine.toString())
                currentLine.clear()
            }
            return
        }

        while (buffer.hasRemaining()) {
            val char = buffer.char
            if (CharCategory.LINE_SEPARATOR.contains(char)) {
                matchLine(currentLine.toString())
                currentLine.clear()
            } else {
                currentLine.append(char)
            }
        }
    }

    /**
     * Expects a message on StdOut matching the given [pattern] within the given [timeout].
     */
    suspend fun expectMessageAsync(pattern: Pattern, timeout: Duration): Deferred<Matcher> {
        val matcher = CompletableDeferred<Matcher>()
        patternMutex.withLock {
            patterns[pattern] = matcher
        }
        return coroutineScope {
            async {
                val result = withTimeout(timeout.toMillis()) {
                    matcher.await()
                }
                patternMutex.withLock {
                    patterns.remove(pattern)
                }
                result
            }
        }
    }

    fun getExitValueAsync(): Deferred<Int> = exitValue

    override fun onExit(statusCode: Int) {
        exitValue.complete(statusCode)
    }
}

private class MpvPlayback(
    dir: File,
    private val path: String,
    private val noVideo: Boolean,
    private val fullscreen: Boolean,
    private val screen: Int,
    private val configFile: File?,
    private val ignoreSystemConfig: Boolean
) : AbstractPlayback() {

    private val logger = KotlinLogging.logger { }

    // TODO for some reason, the file method doesn't work for linux
    private val isWin = System.getProperty("os.name").toLowerCase().startsWith("win")
    private val filePath =
        if (isWin) File.createTempFile("mpvCmd", null, dir).canonicalPath
        else "/dev/stdin"

    private val handler = MpvHandler()
    private val mpv: NuProcess = NuProcessBuilder(createCommand()).run {
        setProcessListener(handler)
        start()
    }

    private val cmdWriter: BufferedWriter? = if (isWin) File(filePath).bufferedWriter()
    else null

    init {
        launch {
            val exitValue = handler.getExitValueAsync().await()
            if (exitValue > 0) logger.warn { "mpv exited with non-zero exit value: $exitValue" }
            else logger.debug { "mpv process ended" }
            markDone()
        }
    }

    private fun createCommand(): List<String> {
        val command = mutableListOf(
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
        )

        if (configFile != null) command.add("--include=${configFile.absolutePath}")
        command.add(path)
        return command
    }

    private fun writeCommand(command: String) {
        if (isWin) {
            val cmdWriter = cmdWriter!!
            cmdWriter.write(command)
            cmdWriter.newLine()
            cmdWriter.flush()
        } else {
            val encoder = Charsets.UTF_8.newEncoder()
            val commandBuffer = encoder.encode(CharBuffer.wrap(command + System.lineSeparator()))
            mpv.writeStdin(commandBuffer)
        }
    }

    override suspend fun play() {
        withContext(coroutineContext) {
            writeCommand("set pause no")
        }
    }

    override suspend fun pause() {
        withContext(coroutineContext) {
            writeCommand("set pause yes")
        }
    }

    override suspend fun close() {
        withContext(coroutineContext) {
            if (mpv.isRunning) {
                try {
                    writeCommand("quit")
                } catch (e: IOException) {
                    logger.warn(e) { "Could not send quit command to mpv" }
                }
            }

            if (isWin) {
                cmdWriter!!.close()
            }

            val exitValue = withTimeoutOrNull(5000) {
                handler.getExitValueAsync().await()
            }
            if (exitValue == null) {
                logger.warn { "There is probably an unclosed mpv process." }
                mpv.destroy(true)
            }

            if (isWin && !File(filePath).delete()) {
                logger.warn { "Could not delete temporary file: $filePath" }
            }
        }
        super.close()
    }
}
