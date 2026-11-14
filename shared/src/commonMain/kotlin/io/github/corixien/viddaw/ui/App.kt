package io.github.corixien.viddaw.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import io.github.corixien.viddaw.app.PickedMedia
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.*
import io.github.corixien.viddaw.app.AppState
import io.github.corixien.viddaw.app.LangMode
import io.github.corixien.viddaw.app.ThemeMode
import io.github.corixien.viddaw.model.Project

@Composable
fun App(app: AppState, systemDark: Boolean, font: FontFamily) {
    val dark = when (app.settings.theme) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val lang = when (app.settings.language) {
        LangMode.System -> if (app.platform.systemLanguage.startsWith("de")) Lang.De else Lang.En
        LangMode.English -> Lang.En
        LangMode.German -> Lang.De
    }
    ViddawTheme(dark, font, lang) {
        Box(Modifier.fillMaxSize().background(pal.window)) {
            // Opening a project pushes the studio in from the right; closing reverses it.
            AnimatedContent(
                app.studio,
                transitionSpec = {
                    val dir = if (targetState != null) 1 else -1
                    val spec = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)
                    slideInHorizontally(spec) { it * dir } togetherWith slideOutHorizontally(spec) { -it * dir / 3 }
                },
                contentKey = { it?.dir },
            ) { studio ->
                if (studio == null) ProjectsScreen(app) else StudioScreen(app, studio)
            }
            SettingsSheet(app)
        }
    }
}

@Composable
private fun ProjectsScreen(app: AppState) {
    var naming by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Project?>(null) }
    var renaming by remember { mutableStateOf<Project?>(null) }
    var deleting by remember { mutableStateOf<Project?>(null) }
    val lang = LocalLang.current

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(T.projects.t, Modifier.weight(1f), type.title)
                    IconBtn(Lucide.Settings, { app.showSettings = true })
                }
                if (app.projects.isEmpty()) {
                    Column(Modifier.weight(1f).fillMaxWidth(), Arrangement.Center, Alignment.CenterHorizontally) {
                        Txt(T.noProjects.t, style = type.heading)
                        Spacer(Modifier.height(4.dp))
                        Txt(T.noProjectsHint.t, color = pal.dim)
                    }
                } else {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        for (p in app.projects) ProjectCard(p, { app.open(p) }, { actionsFor = p })
                        Spacer(Modifier.height(8.dp))
                    }
                }
                Btn(T.newProject.t, { naming = true }, Modifier.fillMaxWidth().padding(vertical = 12.dp).height(48.dp), primary = true, icon = Lucide.Plus)
            }
        }

        NameSheet(naming, T.newProject.t, "", { naming = false }) {
            naming = false
            app.createProject(it.ifBlank { T.untitled.of(lang) })
        }
        NameSheet(renaming != null, T.rename.t, renaming?.name ?: "", { renaming = null }) { name ->
            renaming?.let { app.rename(it, name.ifBlank { it.name }) }
            renaming = null
        }
        Sheet(actionsFor != null, { actionsFor = null }, actionsFor?.name) {
            val p = actionsFor
            ListRow(T.rename.t, { renaming = p; actionsFor = null }, icon = Lucide.Pencil)
            ListRow(T.duplicate.t, { p?.let { app.duplicate(it, "${it.name} 2") }; actionsFor = null }, icon = Lucide.Copy)
            ListRow(T.delete.t, { deleting = p; actionsFor = null }, icon = Lucide.Trash2, danger = true)
        }
        Sheet(deleting != null, { deleting = null }, deleting?.name) {
            Txt(T.deleteProjectAsk.t, color = pal.dim, maxLines = 3)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Btn(T.cancel.t, { deleting = null }, Modifier.weight(1f))
                Btn(T.delete.t, { deleting?.let(app::delete); deleting = null }, Modifier.weight(1f), danger = true)
            }
        }
    }
}

@Composable
private fun ProjectCard(p: Project, onOpen: () -> Unit, onMore: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).pressable(onClick = onOpen)
            .background(pal.surface, RoundedCornerShape(Radius.panel))
            .border(1.dp, pal.line, RoundedCornerShape(Radius.panel)).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A miniature of the arrangement: one bar per track in its colour.
        Column(Modifier.size(40.dp), Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
            val shown = p.tracks.take(5)
            if (shown.isEmpty()) Box(Modifier.fillMaxWidth().height(5.dp).background(pal.sunken, RoundedCornerShape(3.dp)))
            shown.forEachIndexed { i, t ->
                Box(Modifier.fillMaxWidth(if (i % 2 == 0) 1f else 0.7f).height(5.dp).background(trackColor(t.color), RoundedCornerShape(3.dp)))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(p.name, style = type.heading)
            Txt("${p.tracks.size} ${if (p.tracks.size == 1) T.trackCountOne.t else T.tracksCount.t}, ${p.bpm.toInt()} BPM", style = type.small, color = pal.dim)
        }
        IconBtn(Lucide.Ellipsis, onMore, tint = pal.dim)
    }
}

/** Sheet with a single name field, used for creating and renaming. */
@Composable
fun androidx.compose.foundation.layout.BoxScope.NameSheet(visible: Boolean, title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    Sheet(visible, onDismiss, title) {
        var text by remember(visible, initial) { mutableStateOf(initial) }
        if (visible) TextInput(text, { text = it.take(40) }, Modifier.fillMaxWidth(), T.name.t, autoFocus = true, onDone = { onDone(text.trim()) })
        Spacer(Modifier.height(14.dp))
        Btn(T.done.t, { onDone(text.trim()) }, Modifier.fillMaxWidth(), primary = true)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.SettingsSheet(app: AppState) {
    Sheet(app.showSettings, { app.showSettings = false }, T.settings.t) {
        val s = app.settings
        Txt(T.appearance.t, style = type.label, color = pal.dim)
        Spacer(Modifier.height(6.dp))
        Segmented(listOf(T.system.t, T.light.t, T.dark.t), s.theme.ordinal, { i -> app.updateSettings { it.copy(theme = ThemeMode.entries[i]) } }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Txt(T.language.t, style = type.label, color = pal.dim)
        Spacer(Modifier.height(6.dp))
        Segmented(listOf(T.system.t, "English", "Deutsch"), s.language.ordinal, { i -> app.updateSettings { it.copy(language = LangMode.entries[i]) } }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        SliderRow(T.recordOffset.t, "${s.recordOffsetMs} ms", (s.recordOffsetMs + 100) / 400f, { v -> app.updateSettings { it.copy(recordOffsetMs = (v * 400).toInt() - 100) } })
        Txt(T.recordOffsetHint.t, style = type.small, color = pal.dim, maxLines = 3)
        Spacer(Modifier.height(16.dp))
        Hairline()
        Spacer(Modifier.height(12.dp))
        BluetoothMidi(app)
        Spacer(Modifier.height(12.dp))
        Hairline()
        Spacer(Modifier.height(12.dp))
        Txt("Viddaw 0.1.0", style = type.label)
        Txt(T.aboutText.t, style = type.small, color = pal.dim, maxLines = 2)
    }
}

@Composable
private fun BluetoothMidi(app: AppState) {
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<PickedMedia>?>(null) }
    var status by remember { mutableStateOf<Pair<String, Str>?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Txt(T.bluetoothMidi.t, style = type.label)
            Txt(T.midiHint.t, style = type.small, color = pal.dim, maxLines = 2)
        }
        Btn(if (scanning) T.scanning.t else T.scan.t, {
            scanning = true
            scope.launch {
                found = app.platform.scanBluetoothMidi()
                scanning = false
            }
        }, enabled = !scanning)
    }
    found?.let { list ->
        if (list.isEmpty()) Txt(T.noDevices.t, Modifier.padding(top = 8.dp), color = pal.dim)
        for (d in list) {
            ListRow(d.name, {
                scope.launch { status = d.uri to if (app.platform.connectBluetoothMidi(d.uri)) T.connected else T.connectFailed }
            }, subtitle = status?.takeIf { it.first == d.uri }?.second?.t, icon = Lucide.Keyboard)
        }
    }
}
