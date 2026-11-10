package sh.belfry.core.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import sh.belfry.core.nostr.obj
import sh.belfry.core.nostr.parseJsonOrNull
import sh.belfry.core.nostr.str
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What members say about themselves beyond their Nostr profile: tags and where they are. One
 * replaceable event per member on the community relay (members only): kind 30078, d =
 * "belfry:profile", content JSON `{"tags":[…],"share":"city"|"country"|"none","place":{…}}`.
 * A place is always a city's point from the belfry's list, never the phone's GPS.
 */
object People {
    private val NOT_TAG = Regex("[^\\p{L}\\p{N}_-]")
    private val SPACES = Regex("\\s+")
    private val COUNTRY = Regex("^[A-Z]{2}$")

    /** A tag as stored: lowercase, no `#`, spaces → `-`, letters/digits/`_`/`-` only, at most 32. */
    fun cleanTag(t: String): String =
        t.jsTrim().lowercase(Locale.ROOT).removePrefix("#").replace(SPACES, "-").replace(NOT_TAG, "").sliceUnits(32)

    fun cleanTags(tags: List<String>): List<String> = tags.map(::cleanTag).filter { it.isNotEmpty() }.distinct().take(30)

    private fun num(o: JsonObject, k: String, max: Double): Double? {
        val p = o[k] as? JsonPrimitive ?: return null
        if (p.isString) return null
        val v = p.doubleOrNull ?: return null
        return if (abs(v) <= max) (v * 100).roundToLong() / 100.0 else null
    }

    fun parsePlace(p: JsonObject, countryOnly: Boolean = false): Place? {
        val country = p["country"].str() ?: return null
        if (!COUNTRY.matches(country)) return null
        if (countryOnly) return Place(country)
        return Place(
            country = country,
            id = p["id"].str()?.sliceUnits(40),
            name = p["name"].str()?.sliceUnits(80),
            region = p["region"].str()?.sliceUnits(80),
            lat = num(p, "lat", 90.0),
            lon = num(p, "lon", 180.0),
            near = p["near"].str(),
        )
    }

    /** Null when the content is not a JSON object. */
    fun parseExtras(content: String, at: Long): Extras? {
        val c = parseJsonOrNull(content).obj() ?: return null
        val tags = (c["tags"] as? JsonArray)?.mapNotNull { it.str() }?.let(::cleanTags) ?: emptyList()
        val share = when (c["share"].str()) { "country" -> Share.COUNTRY; "none" -> Share.NONE; else -> Share.CITY }
        val place = if (share == Share.NONE) null else c["place"].obj()?.let { parsePlace(it, share == Share.COUNTRY)?.copy(near = null) }
        return Extras(tags, share, place, at)
    }

    fun placeJson(p: Place): JsonObject = buildJsonObject {
        if (p.id != null) put("id", JsonPrimitive(p.id))
        if (p.name != null) put("name", JsonPrimitive(p.name))
        if (p.region != null) put("region", JsonPrimitive(p.region))
        put("country", JsonPrimitive(p.country))
        if (p.lat != null) put("lat", JsonPrimitive(p.lat))
        if (p.lon != null) put("lon", JsonPrimitive(p.lon))
        if (p.near != null) put("near", JsonPrimitive(p.near))
    }

    /** The content of the event that saves my tags and place. */
    fun extrasContent(tags: List<String>, share: Share, place: Place?): String {
        val kept = if (share == Share.NONE || place == null) null else if (share == Share.COUNTRY) Place(place.country) else place
        return buildJsonObject {
            put("tags", buildJsonArray { cleanTags(tags).forEach { add(JsonPrimitive(it)) } })
            put("share", JsonPrimitive(share.key))
            if (kept != null) put("place", placeJson(kept))
        }.toString()
    }

    // ---- countries (ISO 3166-1 alpha-2), named in the reader's language ----

    val COUNTRY_CODES: List<String> = ("AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ " +
        "CA CC CD CF CG CH CI CK CL CM CN CO CR CU CV CW CX CY CZ DE DJ DK DM DO DZ EC EE EG EH ER ES ET FI FJ FK FM FO FR " +
        "GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU ID IE IL IM IN IO IQ IR IS IT JE JM JO JP " +
        "KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ " +
        "NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW " +
        "SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ " +
        "UA UG UM US UY UZ VA VC VE VG VI VN VU WF WS XK YE YT ZA ZM ZW").split(" ")

    fun countryName(code: String, locale: Locale = Locale.getDefault()): String {
        val n = try { Locale("", code).getDisplayCountry(locale) } catch (e: Exception) { "" }
        return n.ifEmpty { code }
    }

    /** (code, name) of every country, by name. */
    fun countries(locale: Locale = Locale.getDefault()): List<Pair<String, String>> =
        COUNTRY_CODES.map { it to countryName(it, locale) }.sortedBy { it.second }

    /** "Brussels, Belgium" or "Belgium"; "" without a place. */
    fun placeLabel(p: Place?, locale: Locale = Locale.getDefault()): String {
        if (p == null) return ""
        return if (!p.name.isNullOrEmpty()) "${p.name}, ${countryName(p.country, locale)}" else countryName(p.country, locale)
    }

    /** The demo's places (the web chat's DEMO_PLACES), for the local-only mode. */
    val DEMO_PLACES = listOf(
        Place("BE", "gn:2800866", "Brussels", "Brussels Capital", 50.85, 4.35),
        Place("GB", "gn:2643743", "London", "England", 51.51, -0.13),
        Place("NL", "gn:2759794", "Amsterdam", "North Holland", 52.37, 4.89),
        Place("NZ", "gn:2179537", "Wellington", "Wellington", -41.29, 174.78),
        Place("ES", "gn:3117735", "Madrid", "Madrid", 40.42, -3.7),
        Place("DE", "gn:2950159", "Berlin", "Berlin", 52.52, 13.41),
    )

    fun demoPlaces(q: String): List<Place> {
        val f = fold(q.trim())
        return if (f.length < 2) emptyList() else DEMO_PLACES.filter { fold(it.name ?: "").startsWith(f) }
    }
}
