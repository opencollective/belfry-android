package sh.belfry.core.net

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import sh.belfry.core.model.WayIn
import sh.belfry.core.nostr.LocalSigner
import sh.belfry.core.nostr.Nip98
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.unixNow
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The web's way in, on the wire (collective-belfry docs/membership.md "Applying from the chat
 * (verified)", "A name and a username"). Every call is NIP-98 signed by [secretHex], this phone's
 * applicant key for that belfry (it becomes the session's client key once signed in). Each answer
 * goes through [WayIn.next]; no connection = a STAY with fixed words.
 */
class WayInApi(private val community: Community, private val http: OkHttpClient, private val secretHex: String, private val clock: () -> Long = ::unixNow) {
    private val base = community.apiBase

    suspend fun loginStart(email: String) = call("login/start", buildJsonObject { put("email", JsonPrimitive(email.trim())) })
    suspend fun loginVerify(email: String, code: String) = call("login/verify", buildJsonObject {
        put("email", JsonPrimitive(email.trim())); put("code", JsonPrimitive(code))
    })
    suspend fun applyStart(email: String, motivation: String, agreed: List<String>, name: String, username: String?, proof: String?) = call("apply/start", buildJsonObject {
        put("email", JsonPrimitive(email.trim()))
        put("motivation", JsonPrimitive(motivation.trim().take(4000)))
        put("agreed", JsonArray(agreed.map { JsonPrimitive(it) }))
        put("name", JsonPrimitive(name.trim().take(100)))
        if (!username.isNullOrBlank()) put("username", JsonPrimitive(username.trim().lowercase()))
        if (proof != null) put("proof", JsonPrimitive(proof))
    })
    suspend fun applyVerify(email: String, code: String) = call("apply/verify", buildJsonObject {
        put("email", JsonPrimitive(email.trim())); put("code", JsonPrimitive(code))
    })
    suspend fun applyStatus() = call("apply/status", JsonObject(emptyMap()))

    /** The member's own key signs /welcome (a member link, after its code). */
    suspend fun welcome(name: String, username: String, memberSecretHex: String) =
        call("welcome", buildJsonObject { put("name", JsonPrimitive(name.trim().take(100))); put("username", JsonPrimitive(username.trim().lowercase())) }, memberSecretHex)

    /** POST /username/check: the line under "Pick a username:" and the name it read (lower-cased, trimmed). Null: no answer. */
    suspend fun usernameCheck(username: String?, name: String? = null): Pair<String, String>? {
        val body = buildJsonObject {
            if (!username.isNullOrBlank()) put("username", JsonPrimitive(username.trim()))
            else if (!name.isNullOrBlank()) put("name", JsonPrimitive(name.trim()))
        }
        val (status, obj) = try { post("username/check", body, secretHex) } catch (e: IOException) { return null }
        if (status != 200 || obj == null) return null
        val read = (obj["username"] as? JsonPrimitive)?.content ?: return null
        return read to WayIn.usernameLine(obj, community.domain)
    }

    private suspend fun call(path: String, body: JsonObject, key: String = secretHex): WayIn.Outcome {
        val (status, obj) = try { post(path, body, key) } catch (e: IOException) {
            return WayIn.Outcome(WayIn.Screen.STAY, "No connection. Try again.")
        }
        return WayIn.next(path, status, obj, community.domain)
    }

    private suspend fun post(path: String, body: JsonObject, key: String): Pair<Int, JsonObject?> {
        val url = "$base/$path"
        val text = body.toString()
        val signer = LocalSigner(key)
        val auth = Nip98.authorization(signer.signNow(Nip98.template(url, "POST", text, NativeSignIn.stamp(clock()))))
        val req = Request.Builder().url(url).header("Authorization", auth).header("Accept", "application/json").post(text.toRequestBody(JSON)).build()
        val quick = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
        val (status, answer) = quick.fetch(req)
        return status to (parseJsonOrNull(answer) as? JsonObject)
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
