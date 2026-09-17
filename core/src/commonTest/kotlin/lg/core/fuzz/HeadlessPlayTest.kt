package lg.core.fuzz

import lg.core.GameRegistry
import lg.core.input.Gesture
import lg.core.input.GestureKind
import lg.core.input.PointerPhase
import lg.core.scene.Scene
import lg.core.util.Rng
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Headless fuzz test: for every registered game, replays a large number of
 * pseudo-random pointer/gesture/update sequences across both a portrait and
 * a landscape layout and asserts basic invariants hold no matter what.
 */
public class HeadlessPlayTest {
    private companion object {
        const val SEED_COUNT = 20
        const val ITERATIONS = 5000
        const val PORTRAIT_W = 360f
        const val PORTRAIT_H = 640f
        const val LANDSCAPE_W = 640f
        const val LANDSCAPE_H = 360f
    }

    @Test
    public fun headlessPlayNeverCrashesOrDropsPrimitives(): Unit {
        val scene = Scene()
        val pointerPhases = PointerPhase.entries.toTypedArray()
        val gestureKinds = GestureKind.entries.toTypedArray()
        val gesture = Gesture()

        for (meta in GameRegistry.all) {
            // Exercise every player count the game supports (in particular,
            // 2-player mode for games that allow it), not just the default.
            val playerCounts = if (meta.players.last >= 2) {
                listOf(meta.players.first, meta.players.last)
            } else {
                listOf(meta.players.first)
            }
            for (players in playerCounts) {
            for (seedIndex in 0 until SEED_COUNT) {
                val seed = seedIndex.toLong() * 7919L + 1L
                val game = meta.factory()
                game.reset(seed, players)
                game.resize(PORTRAIT_W, PORTRAIT_H)
                game.resize(LANDSCAPE_W, LANDSCAPE_H)

                val rng = Rng(seed xor 0x5CA1AB1EL)
                for (i in 0 until ITERATIONS) {
                    when (rng.nextInt(4)) {
                        0 -> game.update(16)
                        1 -> {
                            val id = rng.nextInt(3)
                            val phase = pointerPhases[rng.nextInt(pointerPhases.size)]
                            val x = rng.nextFloat() * LANDSCAPE_W
                            val y = rng.nextFloat() * LANDSCAPE_H
                            game.onPointer(id, phase, x, y)
                        }
                        2 -> {
                            gesture.kind = gestureKinds[rng.nextInt(gestureKinds.size)]
                            gesture.x = rng.nextFloat() * LANDSCAPE_W
                            gesture.y = rng.nextFloat() * LANDSCAPE_H
                            gesture.pointerId = rng.nextInt(3)
                            game.onGesture(gesture)
                        }
                        3 -> {
                            if (meta.resumable) {
                                val snap = game.snapshot()
                                if (snap != null) game.restore(snap)
                            }
                        }
                    }
                    assertTrue(game.score >= 0, "score must never go negative (${meta.id}, seed=$seed, i=$i)")
                }

                scene.clear()
                game.render(scene)
                assertEquals(0, scene.dropped, "dropped primitives for ${meta.id} seed=$seed")
                assertTrue(game.score >= 0)

                val hash1 = scene.hash()
                scene.clear()
                game.render(scene)
                val hash2 = scene.hash()
                assertEquals(hash1, hash2, "render must be idempotent without update() for ${meta.id} seed=$seed")
            }
            }
        }
    }
}
