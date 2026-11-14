package io.github.corixien.viddaw.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.corixien.viddaw.engine.AudioEngine
import io.github.corixien.viddaw.model.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class ThemeMode { System, Light, Dark }
enum class LangMode { System, English, German }

@Serializable
data class Settings(
    val theme: ThemeMode = ThemeMode.System,
    val language: LangMode = LangMode.System,
    val recordOffsetMs: Int = 0,
)

val AppJson = Json { ignoreUnknownKeys = true }

/** App-level state: settings, the project library and the open studio. */
class AppState(val platform: Platform, val engine: AudioEngine, private val scope: CoroutineScope) {
    private val root = platform.dataDir
    private val settingsPath = "$root/settings.json"

    var settings by mutableStateOf(load<Settings>(settingsPath) ?: Settings())
        private set
    var projects by mutableStateOf(listProjects())
        private set
    var studio by mutableStateOf<StudioState?>(null)
        private set
    var showSettings by mutableStateOf(false)

    private inline fun <reified T> load(path: String): T? =
        platform.readText(path)?.let { runCatching { AppJson.decodeFromString<T>(it) }.getOrNull() }

    private fun dir(id: String) = "$root/projects/$id"

    private fun listProjects(): List<Project> =
        platform.listDirs("$root/projects").mapNotNull { load<Project>("${dir(it)}/project.json") }.sortedByDescending { it.modifiedAt }

    fun updateSettings(f: (Settings) -> Settings) {
        settings = f(settings)
        platform.writeText(settingsPath, AppJson.encodeToString(settings))
    }

    private fun write(p: Project) {
        platform.mkdirs("${dir(p.id)}/audio")
        platform.writeText("${dir(p.id)}/project.json", AppJson.encodeToString(p))
    }

    fun createProject(name: String) {
        val p = Project(id = "p${platform.nowMillis()}", name = name, modifiedAt = platform.nowMillis())
        write(p)
        open(p)
    }

    fun open(p: Project) {
        studio = StudioState(p, dir(p.id), engine, platform, scope, { settings }, ::write)
    }

    fun closeStudio() {
        studio?.close()
        studio = null
        projects = listProjects()
    }

    fun rename(p: Project, name: String) {
        write(p.copy(name = name))
        projects = listProjects()
    }

    fun duplicate(p: Project, name: String) {
        val copy = p.copy(id = "p${platform.nowMillis()}", name = name, modifiedAt = platform.nowMillis())
        platform.copy(dir(p.id), dir(copy.id))
        write(copy)
        projects = listProjects()
    }

    fun delete(p: Project) {
        platform.delete(dir(p.id))
        projects = listProjects()
    }

    /** Handles the system back action. Returns false when the app should close. */
    fun back(): Boolean {
        if (showSettings) {
            showSettings = false
            return true
        }
        val s = studio ?: return false
        if (!s.back()) closeStudio()
        return true
    }
}
