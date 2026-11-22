package sh.belfry.core.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import sh.belfry.core.net.AliasCheck
import sh.belfry.core.net.AliasState
import sh.belfry.core.net.MailAlias
import sh.belfry.core.net.MailAliasApi
import sh.belfry.core.net.MailAliasInfo
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull

/**
 * A channel's e-mail address through the belfry (SPEC 19.60; docs/mail.md "A channel's address").
 * [info] asks once per channel per session (any member) and keeps the answer in the store; its
 * `address` feeds [sh.belfry.core.model.World.mailAddress] (shown, and what answers go out from).
 * An older belfry (404 / 409 `unsupported`): nothing kept, the app works the address out itself.
 * The demo answers here, from the world.
 */
class MailAliasService internal constructor(private val s: Session) {
    private val demo: Boolean get() = s.localOnly
    private val asked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val demoCustom = java.util.concurrent.ConcurrentHashMap<String, String>()

    private val _infos = MutableStateFlow(stored())
    /** What the belfry last said per channel (custom or automatic, may I set it). */
    val infos: StateFlow<Map<String, MailAliasInfo>> = _infos

    private fun stored(): Map<String, MailAliasInfo> =
        parseJsonOrNull(s.store.get(K_INFOS) ?: "").obj()?.mapNotNull { (k, v) -> MailAlias.parseInfo(v.obj())?.let { k to it } }?.toMap() ?: emptyMap()

    private fun keep(info: MailAliasInfo) {
        _infos.value = _infos.value + (info.channel to info)
        s.store.set(K_INFOS, kotlinx.serialization.json.JsonObject(_infos.value.mapValues { it.value.toJson() }).toString())
        s.setMailAddress(info.channel, info.address)
    }

    private suspend fun <T> api(write: Boolean, block: suspend (MailAliasApi) -> T): T = s.apiCall(write) { block(MailAliasApi(it, s.signer)) }

    /** CHANNEL's address as the belfry says (asked at most once a session unless FORCE); null on an older belfry or offline. */
    suspend fun info(channel: String, force: Boolean = false): MailAliasInfo? {
        if (!force && !asked.add(channel)) return _infos.value[channel]
        val got = try {
            if (demo) demoInfo(channel) else api(write = false) { it.info(channel) }
        } catch (e: ActionException) {
            return _infos.value[channel]
        }
        if (got != null) keep(got)
        return got
    }

    /** The live check of a left part (the channel's admins). */
    suspend fun check(channel: String, alias: String): AliasCheck =
        if (demo) demoCheck(channel, alias) else api(write = false) { it.check(channel, alias) }

    /** Set (or with null clear) the left part: the belfry's admins. Fixed words on a refusal ([MailAlias.ERRORS]). */
    suspend fun set(channel: String, alias: String?): MailAliasInfo {
        val info = if (demo) {
            s.checkWrite()
            if (alias != null) {
                val c = demoCheck(channel, alias)
                if (c.state != AliasState.AVAILABLE) throw ActionException(c.state.key, MailAlias.ERRORS[c.state.key] ?: MailAlias.FALLBACK)
                demoCustom[channel] = c.alias
            } else demoCustom.remove(channel)
            demoInfo(channel)
        } else api(write = true) { it.set(channel, alias) }
        keep(info)
        return info
    }

    // ---- the demo ----

    private fun automatic(channel: String): String = s.world.value.let { w -> w.group(channel)?.let { w.autoMailLocal(it) } ?: channel }

    private fun demoInfo(channel: String): MailAliasInfo {
        val w = s.world.value
        val custom = demoCustom[channel]
        val alias = custom ?: automatic(channel)
        return MailAliasInfo(channel, alias, "$alias@${w.ctx.domain}", custom != null, w.isCommunityAdmin)
    }

    private fun demoCheck(channel: String, raw: String): AliasCheck {
        val a = raw.trim().lowercase()
        val w = s.world.value
        val state = when {
            !VALID.matches(a) -> AliasState.INVALID
            a in RESERVED -> AliasState.RESERVED
            w.allGroups.any { it.id != channel && (demoCustom[it.id] ?: w.autoMailLocal(it)) == a } -> AliasState.TAKEN
            else -> AliasState.AVAILABLE
        }
        return AliasCheck(state, a)
    }

    companion object {
        const val K_INFOS = "mail-alias-infos"
        private val VALID = Regex("^[a-z0-9]([a-z0-9._-]{0,62}[a-z0-9])?$")
        private val RESERVED = setOf("postmaster", "abuse", "admin", "root", "hostmaster", "webmaster", "noreply", "no-reply", "mailer-daemon")
    }
}
