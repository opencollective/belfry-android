package sh.belfry.app.push

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import sh.belfry.app.BuildConfig
import sh.belfry.app.MainActivity
import sh.belfry.app.R
import sh.belfry.app.data.Secrets
import sh.belfry.core.engine.ActionException
import sh.belfry.core.engine.Onboarding
import sh.belfry.core.engine.SavedSession
import sh.belfry.core.engine.Session
import sh.belfry.core.push.Push
import sh.belfry.core.push.PushChannel
import sh.belfry.core.push.PushNote
import sh.belfry.core.push.PushOpen
import kotlin.coroutines.resume

/** This phone's push state, as Me → Notifications shows it. */
enum class PushState(val line: String) {
    /** This build has no Firebase config (google-services.json): no push at all. */
    NOT_IN_BUILD("This build of the app has no notifications."),
    DEMO("The demo sends no notifications."),
    NEEDS_PERMISSION("Notifications are off for this app."),
    REGISTERING("Turning on notifications…"),
    ON("This phone gets notifications."),
    /** The belfry did not take the registration (or no push on it, or offline). */
    FAILED("This phone could not be registered. It tries again next time the app opens."),
}

/**
 * Push on Android (collective-belfry docs/push.md, SPEC 20): Firebase Cloud Messaging data-only
 * messages, decrypted here with the device key K and posted by the app. Everything degrades to
 * "off" without Firebase (a build without google-services.json) or without the permission.
 * The device key and the registered token live in the Keystore-encrypted [Secrets].
 */
object AndroidPush {
    const val ACTION_OPEN = "sh.belfry.app.OPEN_NOTIFICATION"
    const val EXTRA_KIND = "kind"
    const val EXTRA_CHANNEL = "channel"
    const val EXTRA_MESSAGE = "message"
    private const val S_KEY = "push-key"
    private const val S_TOKEN = "push-token"

    private val _state = MutableStateFlow(PushState.NEEDS_PERMISSION)
    val state: StateFlow<PushState> = _state

    /** Is Firebase configured in this build and started? */
    fun available(context: Context): Boolean =
        BuildConfig.PUSH && try { FirebaseApp.getApps(context).isNotEmpty() } catch (e: Throwable) { false }

    fun permitted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Android 13+ asks; earlier versions have notifications on by default. */
    val needsRuntimePermission: Boolean get() = Build.VERSION.SDK_INT >= 33

    /** The state for this session without registering (the screen's first look). */
    fun refreshState(context: Context, session: Session?) {
        val now = _state.value
        _state.value = when {
            session?.localOnly == true -> PushState.DEMO
            !available(context) -> PushState.NOT_IN_BUILD
            !permitted(context) -> PushState.NEEDS_PERMISSION
            now == PushState.ON || now == PushState.FAILED -> now
            else -> PushState.REGISTERING
        }
    }

    /** The device key K, made on first use (base64 of 32 random bytes). */
    private fun deviceKey(secrets: Secrets): String =
        secrets.get(S_KEY) ?: Push.newDeviceKey().also { secrets.set(S_KEY, it) }

    /**
     * Registers this phone with the belfry (`POST /push/devices` add), on every start once the
     * permission is there, so the belfry keeps the device (it forgets devices silent for 60 days).
     */
    suspend fun register(context: Context, session: Session) {
        ensureChannels(context)
        refreshState(context, session)
        if (session.localOnly || !available(context) || !permitted(context)) return
        _state.value = PushState.REGISTERING
        val secrets = Secrets(context)
        val token = withTimeoutOrNull(20_000) { fcmToken() }
        if (token == null) { _state.value = PushState.FAILED; return }
        _state.value = try {
            session.registerPush(token, deviceKey(secrets), deviceName())
            secrets.set(S_TOKEN, token)
            PushState.ON
        } catch (e: ActionException) {
            PushState.FAILED
        } catch (e: Exception) {
            PushState.FAILED
        }
    }

    /** Sign-out, before the session is revoked: the belfry forgets this phone; a new key next time. */
    suspend fun unregister(context: Context, session: Session) {
        val secrets = Secrets(context)
        val token = try { secrets.get(S_TOKEN) } catch (e: Exception) { null }
        if (token != null && !session.localOnly) withTimeoutOrNull(5_000) { session.unregisterPush(token) }
        try { secrets.remove(S_TOKEN); secrets.remove(S_KEY) } catch (e: Exception) { }
        if (available(context)) try { FirebaseMessaging.getInstance().deleteToken() } catch (e: Throwable) { }
        try { NotificationManagerCompat.from(context).cancelAll() } catch (e: Exception) { }
        _state.value = PushState.NEEDS_PERMISSION
    }

    private suspend fun fcmToken(): String? = suspendCancellableCoroutine { cont ->
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { t ->
                cont.resume(if (t.isSuccessful) t.result?.takeIf { it.isNotEmpty() } else null)
            }
        } catch (e: Throwable) {
            cont.resume(null)
        }
    }

    private fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.distinct()
        .joinToString(" ").replaceFirstChar { it.uppercase() }.ifEmpty { "Android phone" }

    /** The five channels (the person can turn each off in Android's settings). */
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        for (c in PushChannel.entries) {
            val importance = if (c == PushChannel.MESSAGES) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_HIGH
            nm.createNotificationChannel(NotificationChannel(c.id, c.title, importance))
        }
    }

    /**
     * A data message arrived: decrypt data.b with this phone's key and post it. Nothing is shown
     * for a payload that isn't for this key, or when signed out.
     */
    fun show(context: Context, payload: String) {
        val secrets = Secrets(context)
        val key = secrets.get(S_KEY) ?: return
        val domain = secrets.get(Onboarding.V_SESSION)?.let(SavedSession::fromJsonString)?.community?.domain ?: return
        val note = Push.open(payload, key) ?: return
        post(context, note, Push.target(note, domain))
    }

    @SuppressLint("MissingPermission") // checked just below (permitted), and notify catches SecurityException
    private fun post(context: Context, note: PushNote, open: PushOpen) {
        if (!permitted(context)) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            when (open) {
                is PushOpen.Channel -> { putExtra(EXTRA_KIND, "channel"); putExtra(EXTRA_CHANNEL, open.channel); open.message?.let { putExtra(EXTRA_MESSAGE, it) } }
                PushOpen.Dms -> putExtra(EXTRA_KIND, "dms")
                PushOpen.Home -> putExtra(EXTRA_KIND, "home")
            }
        }
        val requestCode = (note.collapse + (note.event ?: "")).hashCode()
        val pi = PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, note.androidChannel.id)
            .setSmallIcon(R.drawable.ic_stat_belfry)
            .setContentTitle(note.title)
            .setContentText(note.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note.body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(if (note.type == "dm") NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL)
            .setGroup(note.collapse)
            .build()
        try {
            // one notification per conversation (tag = dm or the group id), the newest on top
            NotificationManagerCompat.from(context).notify(note.collapse, 1, n)
        } catch (e: SecurityException) {
            // the permission was taken back meanwhile
        }
    }

    /** Reads what a tap on our own notification asks to open (fixed names; ids checked). */
    fun openOf(intent: Intent?): PushOpen? {
        if (intent?.action != ACTION_OPEN) return null
        return when (intent.getStringExtra(EXTRA_KIND)) {
            "channel" -> {
                val ch = intent.getStringExtra(EXTRA_CHANNEL)?.takeIf { Regex("^[A-Za-z0-9_.-]{1,64}$").matches(it) } ?: return PushOpen.Home
                val m = intent.getStringExtra(EXTRA_MESSAGE)?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) }
                PushOpen.Channel(ch, m)
            }
            "dms" -> PushOpen.Dms
            else -> PushOpen.Home
        }
    }
}
