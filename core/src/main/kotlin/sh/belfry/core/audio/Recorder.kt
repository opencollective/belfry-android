package sh.belfry.core.audio

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import sh.belfry.core.model.CalEvent
import sh.belfry.core.model.EventStatus
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.s
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Audio recording (the web's core/recorder.ts, 2026-11-12; SPEC 19.25): ＋ → Audio recording,
// pause and resume, stop, then it is posted as a message with the audio attached. The rules are
// here, free of Android, so they can be tested: the app's recorder does what [RecorderMachine]
// tells it ([RecCommand]) and tells it what happened.

enum class RecPhase(val key: String) { RECORDING("recording"), PAUSED("paused"), STOPPED("stopped") }

/** Why a recording is paused. */
enum class PauseBy {
    /** The person tapped Pause. */
    USER,
    /** A call or another app has the audio for a while: it goes on by itself when that ends. */
    INTERRUPTION,
    /** Another app took the audio for good: only the person resumes. */
    TAKEN,
}

/** Fixed messages of the recorder. Never text from the system or from elsewhere. */
enum class RecNotice(val text: String) {
    NO_PERMISSION("The microphone is off for Belfry. Allow it to record; nothing is recorded before you start."),
    NO_ROOM("There is not enough room on this device to record. Free some space first."),
    FULL("This device is almost full: the recording was stopped. What was recorded is kept."),
    INTERRUPTED("Paused: a call or another app is using the microphone. It goes on when that ends."),
    TAKEN("Paused: another app took the microphone. Tap Resume to go on."),
    NO_MICROPHONE("The microphone could not be started. Another app may be using it."),
    BROKE("The recording stopped: the microphone gave an error. What was recorded is kept."),
    BACKGROUND("Keep Belfry open while recording: this device does not let it record in the background."),
    BUSY("Another recording is going on. Stop it first."),
    EMPTY("Nothing was recorded."),
}

/** A recording on this device, as its small file next to the audio says (`<id>.json`). */
data class RecMeta(
    val id: String,
    /** The account it was made in (SPEC 19.24) and the channel it was started from. */
    val account: String,
    val groupId: String,
    /** When it began, ms. */
    val startedAt: Long,
    /** Recorded time so far, ms (pauses not counted). */
    val ms: Long = 0,
    val phase: RecPhase = RecPhase.RECORDING,
    val pausedBy: PauseBy? = null,
) {
    fun toJsonString(): String = buildJsonObject {
        put("id", JsonPrimitive(id)); put("account", JsonPrimitive(account)); put("groupId", JsonPrimitive(groupId))
        put("startedAt", JsonPrimitive(startedAt)); put("ms", JsonPrimitive(ms)); put("state", JsonPrimitive(phase.key))
    }.toString()

    companion object {
        private val ID = Regex("^[0-9a-f]{16,64}$")
        private val GROUP = Regex("^[A-Za-z0-9_.-]{1,64}$")
        fun isId(s: String): Boolean = ID.matches(s)

        fun fromJsonString(text: String): RecMeta? {
            val o = parseJsonOrNull(text) as? JsonObject ?: return null
            fun long(k: String) = (o[k] as? JsonPrimitive)?.content?.toLongOrNull()
            return RecMeta(
                id = o.s("id")?.takeIf(::isId) ?: return null,
                account = o.s("account") ?: return null,
                groupId = o.s("groupId")?.takeIf { GROUP.matches(it) } ?: return null,
                startedAt = long("startedAt") ?: return null,
                ms = (long("ms") ?: 0).coerceAtLeast(0),
                phase = RecPhase.entries.firstOrNull { it.key == o.s("state") } ?: RecPhase.STOPPED,
            )
        }
    }
}

/** What the app's recorder must do next. */
enum class RecCommand { START_ENGINE, PAUSE_ENGINE, RESUME_ENGINE, STOP_ENGINE, SAVE }

/** The recorder at one moment. [since]: when the current stretch of recording began (ms), 0 while not recording. */
data class RecState(val meta: RecMeta? = null, val since: Long = 0, val notice: RecNotice? = null) {
    val live: Boolean get() = meta != null && meta.phase != RecPhase.STOPPED
    fun elapsed(now: Long): Long = (meta?.ms ?: 0) + if (since > 0) (now - since).coerceAtLeast(0) else 0
}

/**
 * One recording at a time. Every function takes what happened and gives the commands for the
 * engine; [state] is what the screens show. Time is in ms and given by the caller.
 */
class RecorderMachine {
    var state: RecState = RecState()
        private set

    /** ＋ → Audio recording → Start. [free]: bytes free on the device. */
    fun start(id: String, account: String, groupId: String, now: Long, free: Long): List<RecCommand> {
        if (state.live) { state = state.copy(notice = RecNotice.BUSY); return emptyList() }
        if (free < Recordings.MIN_FREE_TO_START) { state = RecState(notice = RecNotice.NO_ROOM); return emptyList() }
        state = RecState(RecMeta(id, account, groupId, now), since = now)
        return listOf(RecCommand.SAVE, RecCommand.START_ENGINE)
    }

    /** The microphone did not start: there is no recording. */
    fun startFailed(notice: RecNotice = RecNotice.NO_MICROPHONE) { state = RecState(notice = notice) }

    /** The foreground service could not be started (the recording goes on while the app is open). */
    fun noBackground() { if (state.live) state = state.copy(notice = RecNotice.BACKGROUND) }

    fun pause(by: PauseBy, now: Long): List<RecCommand> {
        val m = state.meta ?: return emptyList()
        if (m.phase == RecPhase.PAUSED && by == PauseBy.USER && m.pausedBy != PauseBy.USER) {
            // paused by a call, and now by the person too: it no longer goes on by itself
            state = RecState(m.copy(pausedBy = PauseBy.USER), since = 0)
            return listOf(RecCommand.SAVE)
        }
        // (a person's pause is never replaced by an interruption's, which would resume by itself)
        if (m.phase != RecPhase.RECORDING) return emptyList()
        state = RecState(
            m.copy(ms = state.elapsed(now), phase = RecPhase.PAUSED, pausedBy = by), since = 0,
            notice = when (by) { PauseBy.INTERRUPTION -> RecNotice.INTERRUPTED; PauseBy.TAKEN -> RecNotice.TAKEN; PauseBy.USER -> null },
        )
        return listOf(RecCommand.PAUSE_ENGINE, RecCommand.SAVE)
    }

    /**
     * Resume. [by] USER: the person tapped Resume (from any pause). INTERRUPTION: the call ended;
     * it goes on only when it was the call that paused it.
     */
    fun resume(by: PauseBy, now: Long): List<RecCommand> {
        val m = state.meta ?: return emptyList()
        if (m.phase != RecPhase.PAUSED) return emptyList()
        if (by != PauseBy.USER && m.pausedBy != PauseBy.INTERRUPTION) return emptyList()
        state = RecState(m.copy(phase = RecPhase.RECORDING, pausedBy = null), since = now)
        return listOf(RecCommand.RESUME_ENGINE, RecCommand.SAVE)
    }

    /** Stop (the person, or [notice] says why): the audio is kept, the recording waits to be posted. */
    fun stop(now: Long, notice: RecNotice? = null): List<RecCommand> {
        val m = state.meta ?: return emptyList()
        if (m.phase == RecPhase.STOPPED) return emptyList()
        state = RecState(m.copy(ms = state.elapsed(now), phase = RecPhase.STOPPED, pausedBy = null), since = 0, notice = notice)
        return listOf(RecCommand.STOP_ENGINE, RecCommand.SAVE)
    }

    /** Every few seconds while live: keeps the time on disk, and stops when the device is almost full. */
    fun tick(now: Long, free: Long): List<RecCommand> {
        val m = state.meta ?: return emptyList()
        if (m.phase == RecPhase.STOPPED) return emptyList()
        if (free < Recordings.MIN_FREE_TO_GO_ON) return stop(now, RecNotice.FULL)
        if (m.phase == RecPhase.RECORDING) {
            state = state.copy(meta = m.copy(ms = state.elapsed(now)), since = now)
            return listOf(RecCommand.SAVE)
        }
        return emptyList()
    }

    /** The microphone gave an error while recording. */
    fun broke(now: Long): List<RecCommand> = stop(now, RecNotice.BROKE)

    /** The stopped recording was posted or thrown away (or its notice was read). */
    fun clear() { if (!state.live) state = RecState() }
    fun dismissNotice() { state = state.copy(notice = null) }
}

object Recordings {
    /** Not started with less than this free; stopped (and kept) when less than that is left. */
    const val MIN_FREE_TO_START = 50L * 1024 * 1024
    const val MIN_FREE_TO_GO_ON = 15L * 1024 * 1024

    /** AAC-LC, one channel, 32 kHz, 48 kbit/s: speech, about 22 MB an hour. */
    const val SAMPLE_RATE = 32_000
    const val BITRATE = 48_000
    /** A new part every 30 minutes (about 11 MB: a Blossom upload takes 25 MB), each a file that plays on its own. */
    const val PART_FRAMES = 30 * 60 * SAMPLE_RATE / Adts.SAMPLES_PER_FRAME
    /** One message carries up to this many parts; a longer recording is posted a part a message. */
    const val PARTS_PER_MESSAGE = 3

    /** `1:02:03`, `12:34`, `0:07`: the web's clock. */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val x = s % 60
        fun p(n: Long) = n.toString().padStart(2, '0')
        return if (h > 0) "$h:${p(m)}:${p(x)}" else "$m:${p(x)}"
    }

    /** The message a recording is posted with (the web's text). */
    fun text(ms: Long, parts: Int): String = "🎙 Recording · ${clock(ms)}" + if (parts > 1) " · $parts parts" else ""

    /** A long recording, one message a part. */
    fun textOfPart(ms: Long, part: Int, parts: Int): String = "🎙 Recording · ${clock(ms)} · part $part of $parts"

    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")

    /** `recording-2026-11-12-0727.m4a`, `recording-2026-11-12-0727-part2.m4a` (the web's names). */
    fun fileName(startedAt: Long, zone: ZoneId, part: Int, parts: Int, ext: String): String =
        "recording-" + STAMP.format(Instant.ofEpochMilli(startedAt).atZone(zone)) + (if (parts > 1) "-part$part" else "") + "." + ext

    /**
     * Recordings left on the device that can be offered ("Post it" / "Discard"): everything but the
     * live one. One that still says "recording" or "paused" was cut short (the app was killed):
     * it counts as stopped. Newest first.
     */
    fun unfinished(all: List<RecMeta>, live: String?, account: String): List<RecMeta> =
        all.filter { it.id != live && it.account == account }.map { if (it.phase == RecPhase.STOPPED) it else it.copy(phase = RecPhase.STOPPED, pausedBy = null) }
            .sortedByDescending { it.startedAt }

    /**
     * The events and meetings a recording made at [at] (ms) probably belongs to (the web's
     * `candidates`): Belfry events not cancelled that start within 12 hours and ended less than
     * 36 hours ago, the one going on first, then the nearest. Six at most.
     */
    fun candidates(calendar: List<CalEvent>, at: Long): List<CalEvent> {
        val t = at / 1000
        fun end(e: CalEvent) = e.end ?: e.start
        fun on(e: CalEvent) = if (e.start <= t && t <= end(e) + 3600) 0 else 1
        return calendar
            .filter { it.isNew && it.status != EventStatus.CANCELLED && it.start - t < 12 * 3600 && end(it) > t - 36 * 3600 }
            .sortedWith(compareBy<CalEvent> { on(it) }.thenBy { kotlin.math.abs(it.start - t) })
            .take(6)
    }
}
