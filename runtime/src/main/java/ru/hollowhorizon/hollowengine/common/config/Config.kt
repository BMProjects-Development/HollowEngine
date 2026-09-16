@file:OptIn(kotlinx.serialization.InternalSerializationApi::class, ExperimentalAtomicApi::class)

package ru.hollowhorizon.hollowengine.common.config

import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.serializer
import net.peanuuutz.tomlkt.TomlTable
import org.apache.logging.log4j.LogManager
import ru.hollowhorizon.hollowengine.common.config.properties.ConfigProperty
import ru.hollowhorizon.hollowengine.common.config.properties.Properties
import ru.hollowhorizon.hollowengine.common.utils.ObservableList
import ru.hollowhorizon.hollowengine.common.utils.ObservableMap
import ru.hollowhorizon.hollowengine.common.utils.ObservableSet
import ru.hollowhorizon.hollowengine.common.utils.toml.toml
import java.nio.file.*
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.io.path.readText

open class Config {
    val properties = Properties { markDirty() }
    private lateinit var configFile: Path
    private val isDirty = AtomicBoolean(false)
    private val saveLock = Any()
    private var lastSaveTime = 0L

    fun initialize() {
        javaClass.getAnnotation(ConfigName::class.java)?.let { annotation ->
            val name = annotation.name
            configFile = CONFIG_DIR.resolve("${name.removeSuffix(".toml")}.toml")

            ensureConfigDirectoryExists()
            loadOrCreateConfig()
            registerFileWatcher()
        } ?: run {
            throw IllegalStateException("Config class ${javaClass.simpleName} must be annotated with @ConfigName")
        }
    }

    private fun loadOrCreateConfig() {
        try {
            if (Files.exists(configFile)) {
                if (!load()) {
                    LOGGER.warn("Config loaded with errors, regenerating: {}", configFile.fileName)
                } else {
                    LOGGER.info("Config loaded: {}", configFile.fileName)
                }
            } else {
                save(force = true)
                LOGGER.info("Config created: {}", configFile.fileName)
            }
        } catch (e: Exception) {
            LOGGER.error("Failed to load config {}", configFile.fileName, e)
            createBackupAndRegenerate("errored")
        }
    }

    private fun backupFile(suffix: String): Path {
        val timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP_FORMAT)
        return configFile.resolveSibling("${configFile.fileName}.${timestamp}_$suffix")
    }

    private fun createBackupAndRegenerate(suffix: String) {
        try {
            if (Files.exists(configFile)) {
                val backup = backupFile(suffix)
                Files.copy(configFile, backup, StandardCopyOption.REPLACE_EXISTING)
                LOGGER.warn("Config backed up as: $backup")
            }
            properties.clearOrphanedKeys()
            save(force = true)
            LOGGER.info("Config regenerated after error: {}", configFile.fileName)
        } catch (e: Exception) {
            LOGGER.error("Critical: Failed to regenerate config {}", configFile.fileName, e)
        }
    }

    private fun registerFileWatcher() {
        val parentDir = configFile.parent
        try {
            val key = parentDir.register(fileWatcher, ENTRY_MODIFY)
            configRegistrations[key] = FileWatcherEntry(this, configFile, configFile.fileName.toString())
        } catch (e: Exception) {
            LOGGER.warn("Failed to register file watcher for {}", configFile.fileName, e)
        }
    }

    private fun ensureConfigDirectoryExists() {
        val dir = configFile.parent
        if (!Files.exists(dir)) {
            Files.createDirectories(dir)
        }
    }

    @PublishedApi
    internal fun markDirty() {
        if (isDirty.compareAndSet(expectedValue = false, newValue = true)) {
            scheduleDelayedSave()
        }
    }

    private fun scheduleDelayedSave() {
        saveExecutor.schedule({
            if (isDirty.compareAndSet(expectedValue = true, newValue = false)) {
                save()
            }
        }, SAVE_DELAY_MS, TimeUnit.MILLISECONDS)
    }

    open fun save(force: Boolean = false) {
        synchronized(saveLock) {
            val now = System.currentTimeMillis()
            if (!force && now - lastSaveTime < MIN_SAVE_INTERVAL_MS) {
                return
            }

            try {
                val table = properties.serialize()
                val tempFile = configFile.resolveSibling("${configFile.fileName}.tmp")
                Files.write(tempFile, toml.encodeToString(table).toByteArray())
                Files.move(tempFile, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)

                lastSaveTime = now
                LOGGER.debug("Config saved: {}", configFile.fileName)
            } catch (e: Exception) {
                LOGGER.error("Failed to save config {}", configFile.fileName, e)
                isDirty.store(true)
            }
        }
    }

    open fun load(): Boolean {
        return try {
            val content = configFile.readText(Charsets.UTF_8)
            val table = toml.decodeFromString<TomlTable>(content)
            properties.deserialize(table)
            LOGGER.info("Config reloaded: {}", configFile.fileName)
            true
        } catch (e: Exception) {
            LOGGER.error("Error loading config {}: {}", configFile.fileName, e.message)
            createBackupAndRegenerate("errored")
            false
        }
    }

    fun <T> property(value: T) = properties.add(value)
    inline fun <reified T : Any> list(vararg value: T): ConfigProperty<List<T>> =
        properties.add<List<T>>(ObservableList(mutableListOf(*value), onChange = { markDirty() }, T::class.serializer()))

    inline fun <reified T : Any> set(vararg value: T): ConfigProperty<Set<T>> =
        properties.add<Set<T>>(ObservableSet(mutableSetOf(*value), onChange = { markDirty() }, T::class.serializer()))

    inline fun <reified K : Any, reified V : Any> map(vararg pair: Pair<K, V>): ConfigProperty<Map<K, V>> =
        properties.add<Map<K, V>>(
            ObservableMap(
                mutableMapOf(*pair),
                onChange = { markDirty() },
                K::class.serializer(),
                V::class.serializer()
            )
        )

    companion object {
        private val LOGGER = LogManager.getLogger()
        private const val SAVE_DELAY_MS = 2000L
        private const val MIN_SAVE_INTERVAL_MS = 1000L
        private const val FILE_STABLE_CHECK_INTERVAL_MS = 150L
        private const val FILE_STABLE_MAX_WAIT_MS = 3000L
        private val BACKUP_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")

        val CONFIG_DIR: Path = Paths.get("config").toAbsolutePath()

        private val fileWatcher = FileSystems.getDefault().newWatchService()
        private val configRegistrations = ConcurrentHashMap<WatchKey, FileWatcherEntry>()
        private val saveExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "Config-Save-Thread").apply {
                isDaemon = true
                priority = Thread.MIN_PRIORITY
            }
        }
        private val watchDispatcher = Executors.newFixedThreadPool(1) { runnable ->
            Thread(runnable, "Config-Watcher-Thread").apply {
                isDaemon = true
            }
        }.asCoroutineDispatcher()

        @JvmStatic
        @JvmOverloads
        fun startObserver(scope: CoroutineScope = CoroutineScope(watchDispatcher)) {
            scope.launch {
                while (isActive) {
                    try {
                        val key = fileWatcher.take() ?: continue
                        val entry = configRegistrations[key] ?: continue

                        key.pollEvents().forEach { event ->
                            if (event.kind() == ENTRY_MODIFY && event.context()?.toString() == entry.fileName) {
                                waitForFileStable(entry)
                            }
                        }

                        if (!key.reset()) {
                            configRegistrations.remove(key)
                        }
                    } catch (_: InterruptedException) {
                        break
                    } catch (e: CancellationException) {
                        break
                    } catch (e: Exception) {
                        LOGGER.error("Error in config file watcher", e)
                    }
                }
            }
        }

        private suspend fun waitForFileStable(entry: FileWatcherEntry) {
            val configPath = entry.configPath
            val startTime = System.currentTimeMillis()

            delay(FILE_STABLE_CHECK_INTERVAL_MS)

            var lastModified = try {
                Files.getLastModifiedTime(configPath).toMillis()
            } catch (_: Exception) {
                return
            }

            while (System.currentTimeMillis() - startTime < FILE_STABLE_MAX_WAIT_MS) {
                delay(FILE_STABLE_CHECK_INTERVAL_MS)
                val currentModified = try {
                    Files.getLastModifiedTime(configPath).toMillis()
                } catch (_: Exception) {
                    return
                }

                if (currentModified == lastModified) {
                    entry.config.load()
                    return
                }
                lastModified = currentModified
            }
        }

        @JvmStatic
        fun shutdown() {
            saveExecutor.shutdown()
            fileWatcher.close()
        }
    }

    private data class FileWatcherEntry(val config: Config, val configPath: Path, val fileName: String)
}


