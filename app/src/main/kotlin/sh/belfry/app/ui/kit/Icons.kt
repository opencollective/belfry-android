package sh.belfry.app.ui.kit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line icons on a 24 px grid, 1.75 stroke, round caps: the web chat's `src/ui/icons.tsx` plus the
 * few it draws with emoji. Tint them with `Icon(BelfryIcons.Bell, …, tint = …)`.
 */
object BelfryIcons {
    private class P(val d: String, val filled: Boolean = false)

    private fun circle(cx: Float, cy: Float, r: Float) =
        "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0Z"

    private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float) =
        "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}" +
            "a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}Z"

    private fun icon(name: String, vararg paths: P): ImageVector {
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (p in paths) {
            b.addPath(
                pathData = addPathNodes(p.d),
                fill = if (p.filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.75f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    private fun icon(name: String, vararg d: String): ImageVector = icon(name, *d.map { P(it) }.toTypedArray())

    private const val BELL = "M6 9a6 6 0 1 1 12 0c0 4.5 1.5 6.5 2 7H4c.5-.5 2-2.5 2-7Z"
    private const val CLAPPER = "M10 19.5a2 2 0 0 0 4 0"

    val Menu by lazy { icon("menu", "M4 7h16M4 12h16M4 17h16") }
    val Search by lazy { icon("search", circle(11f, 11f, 6.5f), "m20 20-4.2-4.2") }
    val Bell by lazy { icon("bell", BELL, CLAPPER) }
    /** Following: e-mails come. */
    val BellFilled by lazy { icon("bell-filled", P(BELL, filled = true), P(CLAPPER)) }
    val Send by lazy { icon("send", "M21 3 10 14", "M21 3l-7 18-4-7-7-4z") }
    val Plus by lazy { icon("plus", "M12 5v14M5 12h14") }
    val Reply by lazy { icon("reply", "M9 17 4 12l5-5", "M20 18v-2a4 4 0 0 0-4-4H4") }
    val Thread by lazy { icon("thread", "M4 5h16v10H9l-5 4z", "M8 9h8M8 12h5") }
    val Edit by lazy { icon("edit", "M4 20h4L19 9l-4-4L4 16z", "M13.5 6.5l4 4") }
    val Trash by lazy { icon("trash", "M4 7h16M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3") }
    val Copy by lazy { icon("copy", rect(9f, 9f, 12f, 12f, 2f), "M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1") }
    val Link by lazy {
        icon("link", "M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7", "M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7")
    }
    val Qr by lazy {
        icon("qr", "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4z", "M14 14h2v2h-2zM18 18h2v2h-2zM19 14h1M14 19v1")
    }
    val Share by lazy {
        icon("share", circle(18f, 5f, 2.5f), circle(6f, 12f, 2.5f), circle(18f, 19f, 2.5f), "M8.2 10.8l7.6-4.4M8.2 13.2l7.6 4.4")
    }
    val Users by lazy {
        icon(
            "users", "M16 19v-1.5a3.5 3.5 0 0 0-3.5-3.5h-5A3.5 3.5 0 0 0 4 17.5V19", circle(10f, 7.5f, 3.5f),
            "M20 19v-1.5a3.5 3.5 0 0 0-2.5-3.3M15.5 4.2a3.5 3.5 0 0 1 0 6.6",
        )
    }
    val User by lazy { icon("user", "M19 20v-1.5a4 4 0 0 0-4-4H9a4 4 0 0 0-4 4V20", circle(12f, 8f, 3.5f)) }
    val Settings by lazy {
        icon(
            "settings", circle(12f, 12f, 3f),
            "M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z",
        )
    }
    val Mail by lazy { icon("mail", rect(3f, 5.5f, 18f, 13f, 2f), "m3.5 7 8.5 6 8.5-6") }
    val Hash by lazy { icon("hash", "M9.5 4 7.5 20M16.5 4l-2 16M4.5 9h16M3.5 15h16") }
    val Calendar by lazy { icon("calendar", rect(3.5f, 5f, 17f, 15f, 2f), "M3.5 10h17M8 3v4M16 3v4") }
    val Pod by lazy { icon("pod", "M12 3l7.8 4.5v9L12 21l-7.8-4.5v-9z") }
    val Proposal by lazy { icon("proposal", "M7 3h7l4 4v14H7zM14 3v4h4M10 12h5M10 16h5") }
    val Megaphone by lazy { icon("megaphone", "M4 10v4h3l8 4V6l-8 4H4zM18 9.5a3.5 3.5 0 0 1 0 5M8 14.5l1 4.5h2.5l-1-3.5") }
    /** A pinned topic (a push pin). The place marker is [MapPin]. */
    val Pin by lazy { icon("pin", "M9 4h6l-1 6 3 3H7l3-3z", "M12 13v7") }
    val MapPin by lazy { icon("map-pin", "M12 21s-7-6.1-7-11.5a7 7 0 0 1 14 0C19 14.9 12 21 12 21Z", circle(12f, 9.5f, 2.5f)) }
    val Image by lazy { icon("image", rect(3f, 4f, 18f, 16f, 2.5f), circle(8.5f, 9.5f, 1.5f), "m4 18 5-5 4 4 2.5-2.5L20 19") }
    val Close by lazy { icon("close", "M18 6 6 18M6 6l12 12") }
    val Check by lazy { icon("check", "M5 12.5l4.5 4.5L19 7.5") }
    /** Points right: "opens something". */
    val Chevron by lazy { icon("chevron", "M9 6l6 6-6 6") }
    val ChevronDown by lazy { icon("chevron-down", "M6 9l6 6 6-6") }
    val Back by lazy { icon("back", "M19 12H5M11 6l-6 6 6 6") }
    val More by lazy {
        icon("more", P(circle(5f, 12f, 1.2f), true), P(circle(12f, 12f, 1.2f), true), P(circle(19f, 12f, 1.2f), true))
    }
    val Inbox by lazy { icon("inbox", "M4 13l2.5-8h11L20 13v6H4z", "M4 13h5a3 3 0 0 0 6 0h5") }
    /** Drop box / upload. */
    val Upload by lazy { icon("upload", "M12 16V4M7 9l5-5 5 5", "M4 16v4h16v-4") }
    val Shield by lazy { icon("shield", "M12 3l7 3v5c0 4.5-3 8.2-7 10-4-1.8-7-5.5-7-10V6z") }
    val At by lazy { icon("at", circle(12f, 12f, 4f), "M16 8v5a3 3 0 0 0 6 0v-1a10 10 0 1 0-4 8") }
    val Globe by lazy { icon("globe", circle(12f, 12f, 9f), "M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18") }
    /** Add a reaction: a smile with a small plus. */
    val SmilePlus by lazy {
        icon("smile-plus", "M21 11.5V12a9 9 0 1 1-9-9h.5", "M8.5 14.5s1.3 1.5 3.5 1.5 3.5-1.5 3.5-1.5M9 9.5h.01M15 9.5h.01", "M19 2v6M16 5h6")
    }
    val Logout by lazy { icon("logout", "M10 4H5v16h5", "M15 8l4 4-4 4M19 12H9") }
    val Lock by lazy { icon("lock", rect(5f, 11f, 14f, 9f, 2f), "M8 11V8a4 4 0 0 1 8 0v3") }
    val Clock by lazy { icon("clock", circle(12f, 12f, 9f), "M12 7v5l3 2") }
    val Retry by lazy { icon("retry", "M20 12a8 8 0 1 1-2.6-5.9", "M20 4v5h-5") }

    /** Every icon with its name, for the kit gallery. */
    val all: List<Pair<String, ImageVector>> by lazy {
        listOf(
            "menu" to Menu, "search" to Search, "bell" to Bell, "bell on" to BellFilled, "send" to Send, "plus" to Plus,
            "reply" to Reply, "thread" to Thread, "edit" to Edit, "trash" to Trash, "copy" to Copy, "link" to Link,
            "qr" to Qr, "share" to Share, "users" to Users, "user" to User, "settings" to Settings, "mail" to Mail,
            "hash" to Hash, "calendar" to Calendar, "pod" to Pod, "proposal" to Proposal, "megaphone" to Megaphone,
            "pin" to Pin, "map pin" to MapPin, "image" to Image, "close" to Close, "check" to Check, "chevron" to Chevron,
            "down" to ChevronDown, "back" to Back, "more" to More, "inbox" to Inbox, "upload" to Upload, "shield" to Shield,
            "at" to At, "globe" to Globe, "react" to SmilePlus, "logout" to Logout, "lock" to Lock, "clock" to Clock,
            "retry" to Retry,
        )
    }
}
