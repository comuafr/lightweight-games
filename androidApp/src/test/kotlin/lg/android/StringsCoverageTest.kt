package lg.android

import lg.android.ui.Strings
import lg.core.scene.StringKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plain JVM unit test (no Android framework calls, no Context needed): the
 * `R.string.*` ids referenced by [Strings.ORDINAL_TO_RES] are just compiled
 * integer constants, so this runs under `testDebugUnitTest` without
 * Robolectric.
 */
class StringsCoverageTest {
    @Test
    fun `covers every StringKey exactly once`() {
        val map = Strings.ORDINAL_TO_RES
        assertEquals(StringKey.entries.size, map.size)
    }

    @Test
    fun `every entry is a resolved resource id`() {
        for (resId in Strings.ORDINAL_TO_RES) {
            assertTrue("resource id must not be 0", resId != 0)
        }
    }

    @Test
    fun `no two StringKeys share a resource id`() {
        val map = Strings.ORDINAL_TO_RES
        assertEquals(map.size, map.toSet().size)
    }

    @Test
    fun `every game has rules and controls texts in every language`() {
        for (dir in listOf("values", "values-fr")) {
            val xml = java.io.File("src/main/res/$dir/strings.xml").readText()
            for (game in lg.core.GameRegistry.all) {
                for (prefix in listOf("rules_", "controls_")) {
                    assertTrue("$dir is missing $prefix${game.id}", xml.contains("name=\"$prefix${game.id}\""))
                }
            }
        }
    }
}
