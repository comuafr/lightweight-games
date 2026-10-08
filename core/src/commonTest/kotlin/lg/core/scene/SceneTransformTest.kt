package lg.core.scene

import kotlin.test.Test
import kotlin.test.assertEquals

public class SceneTransformTest {
    private fun f(scene: Scene, prim: Int, k: Int): Float = scene.f[prim * 6 + k]

    @Test
    public fun transformScalesGeometryThenMovesIt(): Unit {
        val scene = Scene()
        scene.rect(1f, 2f, 10f, 20f, Palette.FG, radius = 4f)
        scene.circle(5f, 6f, 3f, Palette.FG)
        scene.line(0f, 0f, 10f, 20f, Palette.FG, width = 2f)
        scene.arc(7f, 8f, 5f, 30f, 90f, Palette.FG)
        scene.text(3f, 4f, StringKey.SCORE, Palette.FG, 16f)
        scene.transformFrom(0, 2f, 100f, 50f)

        // rect: x y w h radius
        assertEquals(102f, f(scene, 0, 0)); assertEquals(54f, f(scene, 0, 1))
        assertEquals(20f, f(scene, 0, 2)); assertEquals(40f, f(scene, 0, 3)); assertEquals(8f, f(scene, 0, 4))
        // circle: x y r
        assertEquals(110f, f(scene, 1, 0)); assertEquals(62f, f(scene, 1, 1)); assertEquals(6f, f(scene, 1, 2))
        // line: both end points, then stroke width
        assertEquals(100f, f(scene, 2, 0)); assertEquals(50f, f(scene, 2, 1))
        assertEquals(120f, f(scene, 2, 2)); assertEquals(90f, f(scene, 2, 3)); assertEquals(4f, f(scene, 2, 4))
        // arc: radius scaled, angles untouched
        assertEquals(114f, f(scene, 3, 0)); assertEquals(66f, f(scene, 3, 1)); assertEquals(10f, f(scene, 3, 2))
        assertEquals(30f, f(scene, 3, 3)); assertEquals(90f, f(scene, 3, 4))
        // text: position and size
        assertEquals(106f, f(scene, 4, 0)); assertEquals(58f, f(scene, 4, 1)); assertEquals(32f, f(scene, 4, 2))
    }

    @Test
    public fun transformOnlyTouchesPrimitivesFromTheGivenIndex(): Unit {
        val scene = Scene()
        scene.rect(1f, 1f, 1f, 1f, Palette.FG)
        scene.rect(1f, 1f, 1f, 1f, Palette.FG)
        scene.transformFrom(1, 3f, 0f, 0f)
        assertEquals(1f, f(scene, 0, 0))
        assertEquals(3f, f(scene, 1, 0))
    }
}
