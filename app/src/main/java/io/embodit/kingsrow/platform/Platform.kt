package io.embodit.kingsrow.platform

import android.app.Application
import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import android.util.Log
import com.pico.spatial.ui.foundation.dsl.launch
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity
import io.embodit.kingsrow.data.FileGameStore
import io.embodit.kingsrow.data.SettingsStore
import io.embodit.kingsrow.domain.GameFlow
import io.embodit.kingsrow.domain.Sound
import io.embodit.kingsrow.mainApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.random.Random

const val TAG = "KingsRow"

class LaunchActivity : SpatialLaunchActivity()

class SpatialApplication : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        io.embodit.kingsrow.ui.board.input.BoardInputLog.attach(this)
        graph = Graph(this)
        launch(::mainApp)
    }
}

/** The process-wide objects (native-window-move.md §3.4: state shared by the volume and the window lives here). */
class Graph(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(context)
    val sounds = SoundPlayer(context)
    val store = FileGameStore(context)
    val flow = GameFlow(
        scope = scope,
        engineDispatcher = Dispatchers.Default,
        clock = { SystemClock.uptimeMillis() },
        store = store,
        settings = { settings.value },
        onSettings = { settings.set(it) },
        onSound = { sounds.play(it) },
        log = { Log.i(TAG, it) },
    )
    val window = WindowCtl()

    init {
        scope.launch {
            settings.settings.collect { s ->
                sounds.enabled = s.sound
                sounds.volumeLevel = s.volume
            }
        }
        flow.start()
    }

    companion object {
        fun from(context: Context): Graph = (context.applicationContext as SpatialApplication).graph
    }
}

/**
 * Short effects from assets/sounds/<name>.wav (+ _2 / _3 variants) through a SoundPool (design sound.spatial allows
 * the SoundPool fallback). Physical sounds are CC0 recordings (tools/import_sounds.py); selecting a piece is silent
 * (no select.wav). Repeated sounds rotate variants with ±5 % pitch; one instance per event at a time (D51).
 */
class SoundPlayer(context: Context) {
    @Volatile var enabled = true
    @Volatile var volumeLevel = 2

    private val pool = SoundPool.Builder().setMaxStreams(8)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<Sound, List<Int>>()
    private val loaded = HashSet<Int>()
    private val random = Random(11)
    private var lastRate = 1f
    private val lastVariant = HashMap<Sound, Int>()
    private val varied = setOf(Sound.SLIDE, Sound.JUMP, Sound.TRAY)
    private val lastStream = HashMap<Sound, Int>()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        for (s in Sound.entries) {
            val base = s.name.lowercase()
            val found = ArrayList<Int>()
            for (name in listOf("$base.wav", "${base}_2.wav", "${base}_3.wav")) {
                runCatching { context.assets.openFd("sounds/$name").use { fd -> found += pool.load(fd, 1) } }
            }
            if (found.isNotEmpty()) ids[s] = found else Log.i(TAG, "SOUND $s silent (no file)")
        }
    }

    fun play(s: Sound) {
        if (!enabled) return
        val variants = ids[s] ?: return
        val vol = when (volumeLevel) { 0 -> 0.35f; 1 -> 0.65f; else -> 1f }
        var rate = 1f
        var v = 0
        if (s in varied) {
            rate = 0.95f + random.nextFloat() * 0.10f // ±5 % (D51)
            if (abs(rate - lastRate) < 0.015f) rate = if (rate < 1f) rate + 0.03f else rate - 0.03f
            lastRate = rate
            v = random.nextInt(variants.size)
            if (v == lastVariant[s] && variants.size > 1) v = (v + 1) % variants.size
            lastVariant[s] = v
        }
        val id = variants[v]
        if (synchronized(loaded) { id !in loaded }) return
        // D51: the same event never stacks — stop the one still ringing before the next starts (5 quick taps stay <= -1 dBFS)
        lastStream[s]?.let { pool.stop(it) }
        lastStream[s] = pool.play(id, vol, vol, 1, 0, rate)
    }

    fun release() = pool.release()
}
