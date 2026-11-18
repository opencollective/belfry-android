package sh.belfry.app

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.belfry.app.ui.chat.PickMarks

/** channels/composer-mention: which parts of the composer's text are marked as picks. */
class PickMarksTest {
    @Test fun marksWholeLabelsOnly() {
        val labels = listOf("@Maya Okafor", "#garden")
        val t = "Hi @Maya Okafor, see #garden and #gardening"
        assertEquals(listOf(3..14, 21..27), PickMarks.marks(t, labels))
        // one letter deleted: the label no longer matches, no mark (sent as plain text)
        assertEquals(listOf(20..26), PickMarks.marks("Hi @Maya Okafo, see #garden", labels))
        // typed back: marked again
        assertEquals(listOf(3..14), PickMarks.marks("Hi @Maya Okafor", labels))
        // a longer name is someone else
        assertEquals(emptyList<IntRange>(), PickMarks.marks("@Maya Okaforsen", labels))
    }
}
