package lg.core.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

public class KvCodecTest {
    @Test
    public fun encodesAndDecodesSimpleMap(): Unit {
        val map = linkedMapOf("a" to "1", "b" to "2")
        val encoded = KvCodec.encode(map)
        assertEquals(map, KvCodec.decode(encoded))
    }

    @Test
    public fun emptyMapRoundTrips(): Unit {
        val encoded = KvCodec.encode(emptyMap())
        assertEquals("", encoded)
        assertTrue(KvCodec.decode(encoded).isEmpty())
    }

    @Test
    public fun escapesSpecialCharacters(): Unit {
        val map = linkedMapOf("k=1;2" to "va\\lue;with=specials")
        val encoded = KvCodec.encode(map)
        assertEquals(map, KvCodec.decode(encoded))
    }

    @Test
    public fun emptyValueRoundTrips(): Unit {
        val map = linkedMapOf("key" to "")
        val encoded = KvCodec.encode(map)
        assertEquals(map, KvCodec.decode(encoded))
    }

    @Test
    public fun multipleEntriesPreserveOrder(): Unit {
        val map = linkedMapOf("x" to "10", "y" to "20", "z" to "30")
        val encoded = KvCodec.encode(map)
        assertEquals(listOf("x", "y", "z"), KvCodec.decode(encoded).keys.toList())
    }
}
