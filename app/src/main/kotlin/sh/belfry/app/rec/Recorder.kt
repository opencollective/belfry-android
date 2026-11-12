package sh.belfry.app.rec

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import sh.belfry.core.audio.Adts
import sh.belfry.core.audio.M4a
import sh.belfry.core.audio.PauseBy
import sh.belfry.core.audio.RecCommand
import sh.belfry.core.audio.RecMeta
import sh.belfry.core.audio.RecNotice
import sh.belfry.core.audio.RecPhase
import sh.belfry.core.audio.RecState
import sh.belfry.core.audio.RecorderMachine
import sh.belfry.core.audio.Recordings
import sh.belfry.core.crypto.Random
import sh.belfry.core.engine.NewAttachment
import sh.belfry.core.engine.Session
import java.io.File
import java.time.ZoneId

/** Posting a recording did not work; [notice] is a fixed message. */
class RecException(val notice: RecNotice) : Exception(notice.text)

/**
 * The one audio recording of this app (SPEC 19.25). The rules are the core's [RecorderMachine];
 * this does what it says with the microphone and the disk:
 *
 * - `MediaRecorder` writes AAC in ADTS to `files/recordings/<id>.aac` as it records. ADTS has no
 *   header to finish: when the app is killed or the battery dies, the file is whole up to the
 *   last frame written. Its small `<id>.json` says which account and channel it belongs to.
 * - [RecordingService] (a foreground service of type `microphone`) keeps the process alive with
 *   the screen off and shows Pause / Resume / Stop; a wake lock keeps the processor on.
 * - A call or another app taking the audio pauses it (audio focus); storage is checked as it goes.
 * - Posting ([post]) makes an .m4a of each 30 minutes ([M4a], from the finished .aac, in memory:
 *   there is never a half-written .m4a) and hands them to the outbox, which keeps them and
 *   retries the upload; only then is the recording deleted here.
 *
 * Call everything from the main thread.
 */
object Recorder {
    private val machine = RecorderMachine()
    private val _state = MutableStateFlow(RecState())
    val state: StateFlow<RecState> = _state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var app: Context? = null
    private var engine: MediaRecorder? = null
    private var wake: PowerManager.WakeLock? = null
    private var focus: AudioFocusRequest? = null
    private var ticker: Job? = null

    private fun now(): Long = System.currentTimeMillis()

    fun dir(context: Context): File = File(context.filesDir, "recordings")
    private fun audio(context: Context, id: String) = File(dir(context), "$id.aac")
    private fun metaFile(context: Context, id: String) = File(dir(context), "$id.json")

    fun permitted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun free(context: Context): Long = try {
        dir(context).mkdirs()
        StatFs(dir(context).path).availableBytes
    } catch (e: Exception) {
        Long.MAX_VALUE // cannot tell: do not refuse for it
    }

    /** Recorded time now, ms. */
    fun elapsed(): Long = _state.value.elapsed(now())

    /** The input level now, 0..1 (for the meter). */
    fun level(): Float = try {
        if (_state.value.meta?.phase == RecPhase.RECORDING) ((engine?.maxAmplitude ?: 0) / 20000f).coerceIn(0f, 1f) else 0f
    } catch (e: Exception) { 0f }

    /** ＋ → Audio recording → Start, in this account and channel. The permission is asked by the screen first. */
    fun start(context: Context, account: String, groupId: String) {
        val c = context.applicationContext
        app = c
        if (!permitted(c)) { machine.startFailed(RecNotice.NO_PERMISSION); publish(); return }
        run(machine.start(Random.hex(16), account, groupId, now(), free(c)))
    }

    fun pause(by: PauseBy = PauseBy.USER) = run(machine.pause(by, now()))

    fun resume(by: PauseBy = PauseBy.USER) = run(machine.resume(by, now()))

    /** Stop: the audio is on the device; it waits to be posted (or thrown away). */
    fun stop() = run(machine.stop(now()))

    fun dismissNotice() { machine.dismissNotice(); publish() }

    private var serviceOn = false

    private fun publish() {
        _state.value = machine.state
        val c = app ?: return
        // the service runs exactly while a recording is live (it follows [state] by itself once started)
        val live = machine.state.live
        if (live == serviceOn) return
        try {
            if (live) c.startService(Intent(c, RecordingService::class.java)) else c.stopService(Intent(c, RecordingService::class.java))
            serviceOn = live
        } catch (e: Exception) {
            // not allowed to start it (the app is not in front): the recording goes on while the app is open
            machine.noBackground()
            _state.value = machine.state
        }
    }

    /** The service could not go to the foreground. */
    fun noBackground() { machine.noBackground(); _state.value = machine.state }

    private fun run(commands: List<RecCommand>) {
        val c = app
        if (c != null) for (cmd in commands) {
            when (cmd) {
                RecCommand.SAVE -> save(c)
                RecCommand.START_ENGINE -> if (!startEngine(c)) {
                    machine.state.meta?.let { m -> audio(c, m.id).delete(); metaFile(c, m.id).delete() }
                    machine.startFailed()
                }
                RecCommand.PAUSE_ENGINE -> pauseEngine()
                RecCommand.RESUME_ENGINE -> if (!resumeEngine(c)) { run(machine.broke(now())); return }
                RecCommand.STOP_ENGINE -> stopEngine(c)
            }
        }
        publish()
    }

    private fun save(c: Context) {
        val m = machine.state.meta ?: return
        try {
            dir(c).mkdirs()
            val tmp = File(dir(c), m.id + ".json.tmp")
            tmp.writeText(m.toJsonString())
            if (!tmp.renameTo(metaFile(c, m.id))) { metaFile(c, m.id).writeText(m.toJsonString()); tmp.delete() }
        } catch (e: Exception) {
            // the audio itself is what matters; a recording without its small file is not offered back
        }
    }

    private fun startEngine(c: Context): Boolean {
        val m = machine.state.meta ?: return false
        dir(c).mkdirs()
        // 32 kHz first (speech at 48 kbit/s); 44.1 kHz is the one rate every device must record
        for (rate in intArrayOf(Recordings.SAMPLE_RATE, 44_100)) {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(c) else @Suppress("DEPRECATION") MediaRecorder()
            try {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioChannels(1)
                r.setAudioSamplingRate(rate)
                r.setAudioEncodingBitRate(Recordings.BITRATE)
                r.setOutputFile(audio(c, m.id).absolutePath)
                r.setOnErrorListener { _, _, _ -> scope.launch { if (engine === r) run(machine.broke(now())) } }
                r.prepare()
                r.start()
                engine = r
                holdOn(c)
                return true
            } catch (e: Exception) {
                try { r.release() } catch (x: Exception) { }
                audio(c, m.id).delete()
            }
        }
        return false
    }

    private fun pauseEngine() {
        try { engine?.pause() } catch (e: Exception) { }
        letGo(keepFocus = true)
    }

    private fun resumeEngine(c: Context): Boolean = try {
        val r = engine ?: return false
        r.resume()
        holdOn(c)
        true
    } catch (e: Exception) {
        false
    }

    private fun stopEngine(c: Context) {
        val r = engine
        engine = null
        if (r != null) {
            // stop() throws when nothing was recorded yet; the file is whole either way (ADTS)
            try { r.stop() } catch (e: Exception) { }
            try { r.release() } catch (e: Exception) { }
        }
        letGo(keepFocus = false)
        // the exact length, from the file itself
        val m = machine.state.meta ?: return
        scope.launch(Dispatchers.IO) { remeasure(c, m) }
    }

    private fun remeasure(c: Context, m: RecMeta): RecMeta {
        val sum = try { audio(c, m.id).inputStream().buffered().use { Adts.read(it) } } catch (e: Exception) { return m }
        val exact = m.copy(ms = sum.durationMs, phase = RecPhase.STOPPED, pausedBy = null)
        try { metaFile(c, m.id).writeText(exact.toJsonString()) } catch (e: Exception) { }
        return exact
    }

    /** While recording: the processor stays on, the audio focus is ours, storage is watched. */
    private fun holdOn(c: Context) {
        try {
            if (wake == null) wake = c.getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "belfry:recording")?.apply { setReferenceCounted(false) }
            wake?.acquire(12 * 60 * 60 * 1000L)
        } catch (e: Exception) { }
        try {
            val am = c.getSystemService(AudioManager::class.java)
            if (am != null) {
                val req = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener { change ->
                        scope.launch {
                            when (change) {
                                // a call, an alarm, a voice assistant: for a while
                                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause(PauseBy.INTERRUPTION)
                                // another app plays or records now: for good
                                AudioManager.AUDIOFOCUS_LOSS -> pause(PauseBy.TAKEN)
                                AudioManager.AUDIOFOCUS_GAIN -> resume(PauseBy.INTERRUPTION)
                            }
                        }
                    }
                    .build().also { focus = it }
                am.requestAudioFocus(req)
            }
        } catch (e: Exception) { }
        if (ticker == null) ticker = scope.launch {
            while (isActive) {
                delay(5_000)
                if (!machine.state.live) break
                run(machine.tick(now(), free(c)))
            }
            ticker = null
        }
    }

    private fun letGo(keepFocus: Boolean) {
        try { wake?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        if (!keepFocus) {
            try { focus?.let { app?.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(it) } } catch (e: Exception) { }
            focus = null
        }
    }

    // ---- what is on the device -----------------------------------------------------------------------

    /**
     * This account's recordings that were not posted: stopped ones, and one cut short (the app was
     * killed while it recorded), which is measured from its file and counts as stopped. Reads the
     * disk: not on the main thread.
     */
    fun unfinished(context: Context, account: String): List<RecMeta> {
        val c = context.applicationContext
        val live = _state.value.meta?.takeIf { _state.value.live }?.id
        val all = (dir(c).listFiles() ?: emptyArray()).filter { it.name.endsWith(".json") }.mapNotNull { f ->
            val m = try { RecMeta.fromJsonString(f.readText()) } catch (e: Exception) { null }
            when {
                m == null || !audio(c, m.id).exists() -> null
                m.id == live -> m
                m.phase != RecPhase.STOPPED -> remeasure(c, m)
                else -> m
            }
        }
        return Recordings.unfinished(all, live, account).filter { it.ms > 0 }
    }

    /** Forget a recording on this device (posted, or thrown away). From any thread. */
    @Synchronized
    fun discard(context: Context, id: String) {
        if (!RecMeta.isId(id)) return
        val c = context.applicationContext
        audio(c, id).delete()
        metaFile(c, id).delete()
        if (machine.state.meta?.id == id && !machine.state.live) { machine.clear(); _state.value = machine.state }
    }

    /**
     * Posts a recording in [target] (a channel or an event's chat) the way the web does: one message
     * "🎙 Recording · 12:34" with the audio attached, a file per 30 minutes. The outbox keeps the
     * files and uploads them; the recording is deleted here once the outbox has it. A recording of
     * more than three parts goes a part a message, so no message holds more than the phone's memory takes.
     */
    suspend fun post(context: Context, session: Session, meta: RecMeta, target: String) = withContext(Dispatchers.IO) {
        val c = context.applicationContext
        val f = audio(c, meta.id)
        val sum = try { f.inputStream().buffered().use { Adts.read(it) } } catch (e: Exception) { throw RecException(RecNotice.EMPTY) }
        if (sum.frames == 0L) throw RecException(RecNotice.EMPTY)
        val parts = sum.parts(Recordings.PART_FRAMES)
        val ms = sum.durationMs
        val zone = ZoneId.systemDefault()
        fun attachment(adts: ByteArray, part: Int): NewAttachment {
            // an .m4a plays and seeks everywhere; should making it fail, the .aac itself is posted
            val m4a = try { M4a.mux(adts) } catch (e: Throwable) { null }
            return if (m4a != null) NewAttachment(Recordings.fileName(meta.startedAt, zone, part, parts, "m4a"), "audio/mp4", m4a)
            else NewAttachment(Recordings.fileName(meta.startedAt, zone, part, parts, "aac"), "audio/aac", adts)
        }
        if (parts <= Recordings.PARTS_PER_MESSAGE) {
            val files = ArrayList<NewAttachment>()
            f.inputStream().buffered().use { input -> Adts.read(input, Recordings.PART_FRAMES) { files.add(attachment(it, files.size + 1)) } }
            session.postMessage(target, Recordings.text(ms, parts), files)
        } else {
            var part = 0
            f.inputStream().buffered().use { input ->
                Adts.read(input, Recordings.PART_FRAMES) { adts ->
                    part++
                    val file = attachment(adts, part)
                    runBlocking { session.postMessage(target, Recordings.textOfPart(ms, part, parts), listOf(file)) }
                }
            }
        }
        discard(c, meta.id)
    }

    /** For the notification's clock: when a recording that has run for [elapsed] would have begun. */
    fun chronometerBase(): Long = now() - elapsed()
}
