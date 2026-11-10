package sh.belfry.app.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import sh.belfry.app.ui.kit.ActionGroup
import sh.belfry.app.ui.kit.ActionRow
import sh.belfry.app.ui.kit.BelfryIcons
import sh.belfry.app.ui.kit.BelfryTextField
import sh.belfry.app.ui.kit.BusyButton
import sh.belfry.app.ui.kit.BusyRow
import sh.belfry.app.ui.kit.ButtonKind
import sh.belfry.app.ui.kit.SectionTitle
import sh.belfry.app.ui.kit.Segmented
import sh.belfry.app.ui.nav.LocalSession
import sh.belfry.app.ui.theme.Belfry
import sh.belfry.core.engine.ActionException
import sh.belfry.core.model.People
import sh.belfry.core.model.Place
import sh.belfry.core.model.Share
import sh.belfry.core.model.fold

private val SHARES = listOf(Share.CITY, Share.COUNTRY, Share.NONE)

/** Where you are: a city of the belfry's list (or one you add), the country only, or nothing. */
@Composable
internal fun PlaceField(share: Share, setShare: (Share) -> Unit, place: Place?, setPlace: (Place?) -> Unit) {
    val c = Belfry.colors
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        SectionTitle("Where you are")
        Segmented(listOf("City", "Country only", "Nothing"), SHARES.indexOf(share), { setShare(SHARES[it]); picking = false })
        when (share) {
            Share.CITY -> {
                Spacer(Modifier.height(10.dp))
                if (picking) {
                    CityPicker(onPick = { setPlace(it); picking = false }, onCancel = { picking = false })
                } else if (!place?.name.isNullOrEmpty()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(BelfryIcons.MapPin, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(People.placeLabel(place), fontSize = 15.sp, color = c.text)
                            val near = place?.near
                            if (!near.isNullOrEmpty()) Hint("on the map at $near")
                        }
                        BusyButton("Change", onClick = { picking = true }, kind = ButtonKind.Ghost, small = true)
                    }
                } else {
                    BusyButton("Choose your city", onClick = { picking = true }, kind = ButtonKind.Ghost, modifier = Modifier.fillMaxWidth())
                }
            }
            Share.COUNTRY -> {
                Spacer(Modifier.height(10.dp))
                CountryField(place?.country, onPick = { setPlace(Place(it)) })
            }
            Share.NONE -> {}
        }
        Spacer(Modifier.height(8.dp))
        Hint(
            when (share) {
                Share.CITY -> "Members see your city, and you on the map of a channel's members (at the city, never your exact spot). Guests and the public don't."
                Share.COUNTRY -> "Members see your country only. You are not on the map."
                Share.NONE -> "Nobody sees where you are."
            },
        )
    }
}

/** A country: shown by name, changed by typing a few letters and picking from the matches. */
@Composable
internal fun CountryField(code: String?, onPick: (String) -> Unit, label: String = "Country") {
    val all = remember { People.countries() }
    var changing by remember { mutableStateOf(code.isNullOrEmpty()) }
    var q by remember { mutableStateOf("") }
    if (!changing && !code.isNullOrEmpty()) {
        ActionGroup {
            ActionRow(People.countryName(code), onClick = { changing = true; q = "" }, icon = BelfryIcons.Globe, value = "Change")
        }
        return
    }
    val f = fold(q.trim())
    val matches = if (f.isEmpty()) emptyList() else (all.filter { fold(it.second).startsWith(f) } + all.filter { !fold(it.second).startsWith(f) && fold(it.second).contains(f) }).take(6)
    Column(Modifier.fillMaxWidth()) {
        BelfryTextField(
            q, { q = it }, label = label, placeholder = "Start typing a country",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        if (matches.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            ActionGroup {
                matches.forEach { (c2, name) -> ActionRow(name, onClick = { onPick(c2); changing = false; q = "" }) }
            }
        }
    }
}

/** Search the belfry's list; not there? Add it, then pick the nearest listed city for the map. */
@Composable
private fun CityPicker(onPick: (Place) -> Unit, onCancel: () -> Unit) {
    val session = LocalSession.current
    var adding by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newCountry by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    if (!adding) {
        PlaceSearch(
            "City", onPick = { onPick(it) }, onCancel = onCancel,
            extra = { q -> if (q.trim().length >= 2) "Add “${q.trim()}”: it's not in the list" to { newName = q.trim(); adding = true } else null },
        )
        return
    }
    Column(Modifier.fillMaxWidth()) {
        BelfryTextField(newName, { newName = it.take(80) }, label = "Name of the place")
        Spacer(Modifier.height(12.dp))
        CountryField(newCountry.ifEmpty { null }, onPick = { newCountry = it })
        Spacer(Modifier.height(12.dp))
        PlaceSearch(
            "Nearest city in the list · used for the map",
            onPick = { near ->
                if (newName.isBlank() || newCountry.isEmpty()) {
                    error = "Give the place a name and a country first."
                } else {
                    error = null
                    try {
                        onPick(session.addPlace(newName.trim(), newCountry, near))
                    } catch (e: ActionException) {
                        error = e.shown()
                    }
                }
            },
            onCancel = { adding = false },
        )
        ErrorLine(error)
    }
}

@Composable
private fun PlaceSearch(
    label: String,
    onPick: suspend (Place) -> Unit,
    onCancel: () -> Unit,
    extra: ((String) -> Pair<String, () -> Unit>?)? = null,
) {
    val c = Belfry.colors
    val session = LocalSession.current
    var q by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<Place>>(emptyList()) }
    var attribution by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("idle") }
    // A new query cancels the one before it (LaunchedEffect restarts), after a short pause in typing.
    LaunchedEffect(q) {
        if (q.trim().length < 2) {
            list = emptyList()
            state = "idle"
            return@LaunchedEffect
        }
        delay(200)
        state = "busy"
        try {
            val r = session.searchPlaces(q.trim())
            list = r.places
            attribution = r.attribution ?: ""
            state = "idle"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state = "error"
        }
    }
    Column(Modifier.fillMaxWidth()) {
        BelfryTextField(
            q, { q = it }, label = label, placeholder = "Start typing a city",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )
        val more = extra?.invoke(q)
        if (list.isNotEmpty() || (more != null && state != "busy")) {
            Spacer(Modifier.height(8.dp))
            ActionGroup {
                list.forEach { p ->
                    val where = listOfNotNull(p.region?.takeIf { it.isNotEmpty() }, People.countryName(p.country)).joinToString(", ")
                    val near = p.near
                    BusyRow(
                        p.name ?: People.countryName(p.country), onClick = { onPick(p) }, icon = BelfryIcons.MapPin,
                        sub = if (!near.isNullOrEmpty()) "$where · near $near" else where,
                    )
                }
                if (more != null && state != "busy") ActionRow(more.first, onClick = more.second, icon = BelfryIcons.Plus)
            }
        }
        if (state == "busy" && list.isEmpty()) Hint("Looking…", Modifier.padding(top = 8.dp))
        if (state == "error") ErrorLine("The list of places is not available right now.")
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(attribution, fontSize = 12.sp, lineHeight = 16.sp, color = c.muted, modifier = Modifier.weight(1f))
            Text(
                "Cancel", fontSize = 14.sp, color = c.text,
                modifier = Modifier.clickable(role = Role.Button, onClick = onCancel).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
