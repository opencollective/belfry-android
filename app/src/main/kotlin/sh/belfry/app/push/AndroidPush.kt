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
import sh.belfry.core.engine.AccountList
import sh.belfry.core.engine.AccountRecord
import sh.belfry.core.engine.ActionException
import sh.belfry.core.engine.Slots
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
 * Push on Android (collective-belfry docs/push.md, SPEC 19.19): Firebase Cloud Messaging data-only
 * messages, decrypted here with the device key K and posted by the app. Everything degrades to
 * "off" without Firebase (a build without google-services.json) or without the permission.
 * The device key and the registered token live in the Keystore-encrypted [Secrets].
 */
object AndroidPush {
    const val ACTION_OPEN = "sh.belfry.app.OPEN_NOTIFICATION"
    const val EXTRA_KIND = "kind"
    const val EXTRA_CHANNEL = "channel"
    const val EXTRA_MESSAGE = "message"
    /** Whose notification it is: an account id (`<pubkey>@<domain>`), SPEC 19.24. */
    const val EXTRA_ACCOUNT = "account"
    // Per account (Slots.key): the first account keeps the names from before accounts.
    private const val S_KEY = "push-key"
    private const val S_TOKEN = "push-token"
    private const val S_UNREAD = "push-unread"
    private val ACCOUNT_ID = Regex("^[0-9a-f]{64}@[a-z0-9.-]{4,253}$")

    /** The app is in the foreground (BelfryApp's lifecycle observer). */
    @Volatile var foreground = false
    /** Messages or a conversation is on screen (counted by those screens). */
    val dmScreens = java.util.concurrent.atomic.AtomicInteger(0)

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
    private fun deviceKey(secrets: Secrets, slot: String): String =
        secrets.get(Slots.key(S_KEY, slot)) ?: Push.newDeviceKey().also { secrets.set(Slots.key(S_KEY, slot), it) }

    /** The accounts on this device, read from the vault (also before the app's state exists). */
    private fun accounts(secrets: Secrets): AccountList =
        secrets.get(Onboarding.V_ACCOUNTS)?.let(AccountList::fromJsonString)
            ?: secrets.get(Onboarding.V_SESSION)?.let(SavedSession::fromJsonString)?.let(AccountList::migrated)
            ?: AccountList.EMPTY

    /**
     * The accounts that are not open register this device too, each with its own belfry and its
     * own key K (the same FCM token), so their notifications keep coming: once per launch, after
     * the open account. One account per belfry ([AccountList.pushOwners]: a belfry keeps one owner
     * per device token), so a second identity in the open account's belfry is skipped.
     */
    suspend fun registerOthers(context: Context, onboarding: Onboarding, current: String) {
        if (!available(context) || !permitted(context)) return
        val secrets = Secrets(context)
        val owners = try { onboarding.accounts.pushOwners().filter { it.id != current } } catch (e: Exception) { emptyList() }
        if (owners.isEmpty()) return
        val token = withTimeoutOrNull(20_000) { fcmToken() } ?: return
        for (a in owners) {
            try {
                val key = deviceKey(secrets, a.slot)
                withTimeoutOrNull(20_000) { onboarding.asAccount(a.id) { api, signer -> api.addPushDevice(signer, token, key, deviceName()) } }
                    ?.let { secrets.set(Slots.key(S_TOKEN, a.slot), token) }
            } catch (e: Exception) {
                // offline, or that belfry has no push: next launch
            }
        }
    }

    /** Accounts something came for while another one was open (the switcher's dot). */
    fun unread(context: Context): Set<String> = try {
        val secrets = Secrets(context)
        accounts(secrets).accounts.filter { secrets.has(Slots.key(S_UNREAD, it.slot)) }.map { it.id }.toSet()
    } catch (e: Exception) { emptySet() }

    /** That account was opened: its dot goes, and its notifications. */
    fun clearUnread(context: Context, id: String) {
        try {
            val secrets = Secrets(context)
            val a = accounts(secrets)[id] ?: return
            secrets.remove(Slots.key(S_UNREAD, a.slot))
        } catch (e: Exception) { }
    }

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
            session.registerPush(token, deviceKey(secrets, session.slot), deviceName())
            secrets.set(Slots.key(S_TOKEN, session.slot), token)
            PushState.ON
        } catch (e: ActionException) {
            PushState.FAILED
        } catch (e: Exception) {
            PushState.FAILED
        }
    }

    /**
     * Sign-out of one account, before its session is revoked: its belfry forgets this phone, its
     * key and its notifications go. The FCM token itself is deleted only with the last account
     * (the others use the same one).
     */
    suspend fun unregister(context: Context, session: Session) {
        val secrets = Secrets(context)
        val slot = session.slot
        val token = try { secrets.get(Slots.key(S_TOKEN, slot)) } catch (e: Exception) { null }
        if (token != null && !session.localOnly) withTimeoutOrNull(5_000) { session.unregisterPush(token) }
        try { for (n in listOf(S_TOKEN, S_KEY, S_UNREAD)) secrets.remove(Slots.key(n, slot)) } catch (e: Exception) { }
        val last = try { accounts(secrets).accounts.none { it.id != session.accountId } } catch (e: Exception) { true }
        if (last && available(context)) try { FirebaseMessaging.getInstance().deleteToken() } catch (e: Throwable) { }
        try {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (last) nm?.cancelAll() else nm?.activeNotifications?.forEach { if (it.tag?.startsWith("$slot|") == true) nm.cancel(it.tag, it.id) }
        } catch (e: Exception) { }
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
     * A data message arrived: decrypt data.b and post it. Each account registered its own key K
     * with its belfry, so the key that opens the payload says whose notification it is (the
     * tap rule of SPEC 19.19 is then applied with that account's belfry). Nothing is shown for a payload no account's
     * key opens, or when signed out. [openAccount]: the account that is open in the app, if any.
     * Returns the account it was for when that is not the open one (its dot in the switcher).
     */
    fun show(context: Context, payload: String, lastDmSentMs: Long = 0L, openAccount: String? = null): String? {
        val secrets = Secrets(context)
        val list = accounts(secrets)
        for (a in list.accounts) {
            if (a.demo) continue
            val key = secrets.get(Slots.key(S_KEY, a.slot)) ?: continue
            val note = Push.open(payload, key) ?: continue
            val open = (openAccount ?: list.current) == a.id
            // my own copy of a DM I just sent (NIP-17), or my messages are on screen: nothing to tell
            if (open && Push.quiet(note, System.currentTimeMillis(), lastDmSentMs, foreground && dmScreens.get() > 0)) return null
            if (!open) try { secrets.set(Slots.key(S_UNREAD, a.slot), "1") } catch (e: Exception) { }
            post(context, note, Push.target(note, a.domain), a, several = list.accounts.size > 1)
            return if (open) null else a.id
        }
        return null
    }

    @SuppressLint("MissingPermission") // checked just below (permitted), and notify catches SecurityException
    private fun post(context: Context, note: PushNote, open: PushOpen, account: AccountRecord, several: Boolean) {
        if (!permitted(context)) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ACCOUNT, account.id)
            when (open) {
                is PushOpen.Channel -> { putExtra(EXTRA_KIND, "channel"); putExtra(EXTRA_CHANNEL, open.channel); open.message?.let { putExtra(EXTRA_MESSAGE, it) } }
                is PushOpen.Thread -> { putExtra(EXTRA_KIND, "thread"); putExtra(EXTRA_CHANNEL, open.channel); putExtra(EXTRA_MESSAGE, open.root) }
                PushOpen.Dms -> putExtra(EXTRA_KIND, "dms")
                PushOpen.Home -> putExtra(EXTRA_KIND, "home")
            }
        }
        // one notification per conversation of an account (tag = its slot + dm or the group id), the newest on top
        val tag = account.slot + "|" + note.collapse
        val requestCode = (account.id + note.collapse + (note.event ?: "")).hashCode()
        val pi = PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = NotificationCompat.Builder(context, note.androidChannel.id)
            .setSmallIcon(R.drawable.ic_stat_belfry)
            .setContentTitle(note.title)
            .setContentText(note.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(note.body))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setCategory(if (note.type == "dm") NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_SOCIAL)
            .setGroup(tag)
        // several accounts: say which one (its address, or its community, as this device knows them)
        if (several) b.setSubText(account.address.ifEmpty { account.community.ifEmpty { account.domain } })
        try {
            NotificationManagerCompat.from(context).notify(tag, 1, b.build())
        } catch (e: SecurityException) {
            // the permission was taken back meanwhile
        }
    }

    /** The account a tapped notification belongs to (checked as an id), or null (an older notification). */
    fun accountOf(intent: Intent?): String? =
        if (intent?.action == ACTION_OPEN) intent.getStringExtra(EXTRA_ACCOUNT)?.takeIf { ACCOUNT_ID.matches(it) } else null

    /** Reads what a tap on our own notification asks to open (fixed names; ids checked). */
    fun openOf(intent: Intent?): PushOpen? {
        if (intent?.action != ACTION_OPEN) return null
        return when (intent.getStringExtra(EXTRA_KIND)) {
            "channel" -> {
                val ch = intent.getStringExtra(EXTRA_CHANNEL)?.takeIf { Regex("^[A-Za-z0-9_.-]{1,64}$").matches(it) } ?: return PushOpen.Home
                val m = intent.getStringExtra(EXTRA_MESSAGE)?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) }
                PushOpen.Channel(ch, m)
            }
            "thread" -> {
                val ch = intent.getStringExtra(EXTRA_CHANNEL)?.takeIf { Regex("^[A-Za-z0-9_.-]{1,64}$").matches(it) } ?: return PushOpen.Home
                val root = intent.getStringExtra(EXTRA_MESSAGE)?.takeIf { Regex("^[0-9a-f]{64}$").matches(it) } ?: return PushOpen.Channel(ch, null)
                PushOpen.Thread(ch, root)
            }
            "dms" -> PushOpen.Dms
            else -> PushOpen.Home
        }
    }
}
