package io.embodit.kingsrow.data

import android.content.Context
import android.util.Log
import io.embodit.kingsrow.domain.GameStore
import io.embodit.kingsrow.domain.Language
import io.embodit.kingsrow.domain.SaveCodec
import io.embodit.kingsrow.domain.SavedGame
import io.embodit.kingsrow.domain.Settings
import io.embodit.kingsrow.domain.SidePick
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * files/game.txt, written after every move (and mid-chain): a temp file, a read-back check, then a rename; the
 * previous good copy stays as game.bak (Chess Stores.kt pattern, design dataModel.storage).
 */
class FileGameStore(context: Context) : GameStore {
    private val dir = context.filesDir
    private val file = File(dir, "game.txt")
    private val backup = File(dir, "game.bak")

    override fun load(): SavedGame? =
        listOf(file, backup).firstNotNullOfOrNull { f -> runCatching { if (f.exists()) SaveCodec.decode(f.readText()) else null }.getOrNull() }

    override fun save(game: SavedGame) {
        val text = SaveCodec.encode(game)
        runCatching {
            val tmp = File(dir, "game.tmp")
            tmp.writeText(text)
            check(SaveCodec.decode(tmp.readText()) != null) { "read-back failed" }
            if (file.exists()) file.copyTo(backup, overwrite = true)
            if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) }
        }.onFailure { Log.e("KingsRow", "SAVE failed", it) }
    }

    override fun clear() {
        file.delete()
        backup.delete()
    }
}

/** App settings in SharedPreferences, exposed as a StateFlow so both windows recompose on change. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val value: Settings get() = _settings.value

    fun update(change: (Settings) -> Settings) = set(change(_settings.value))

    fun set(s: Settings) {
        _settings.value = s
        prefs.edit()
            .putString("language", s.language.name)
            .putBoolean("sound", s.sound)
            .putInt("volume", s.volume)
            .putBoolean("reducedMotion", s.reducedMotion)
            .putBoolean("firstRunDone", s.firstRunDone)
            .putBoolean("coachDone", s.coachDone)
            .putString("side", s.side.name)
            .putInt("level", s.level)
            .apply()
    }

    private fun read(): Settings = runCatching {
        Settings(
            language = Language.valueOf(prefs.getString("language", Language.SYSTEM.name)!!),
            sound = prefs.getBoolean("sound", true),
            volume = prefs.getInt("volume", 2).coerceIn(0, 2),
            reducedMotion = prefs.getBoolean("reducedMotion", false),
            firstRunDone = prefs.getBoolean("firstRunDone", false),
            coachDone = prefs.getBoolean("coachDone", false),
            side = SidePick.valueOf(prefs.getString("side", SidePick.BLACK.name)!!),
            level = prefs.getInt("level", 1).coerceIn(1, 5),
        )
    }.getOrElse { Settings() }
}
