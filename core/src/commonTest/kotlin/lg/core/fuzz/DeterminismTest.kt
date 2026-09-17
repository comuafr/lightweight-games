package lg.core.fuzz

import lg.core.GameMeta
import lg.core.GameRegistry
import lg.core.input.Gesture
import lg.core.input.GestureKind
import lg.core.input.PointerPhase
import lg.core.scene.Scene
import lg.core.util.Rng
import kotlin.test.Test
import kotlin.test.assertEquals

/** Same seed + same input script must always produce the exact same final scene. */
public class DeterminismTest {
    private companion object {
        const val SCRIPT_LENGTH = 1000
        const val WIDTH = 360f
        const val HEIGHT = 640f
    }

    @Test
    public fun sameSeedAndScriptProduceSameFinalHash(): Unit {
        for (meta in GameRegistry.all) {
            val playerCounts = if (meta.players.last >= 2) {
                listOf(meta.players.first, meta.players.last)
            } else {
                listOf(meta.players.first)
            }
            for (players in playerCounts) {
                val hashA = playScriptAndHash(meta, seed = 42L, players = players)
                val hashB = playScriptAndHash(meta, seed = 42L, players = players)
                assertEquals(hashA, hashB, "non-deterministic replay for ${meta.id} players=$players")
            }
        }
    }

    private fun playScriptAndHash(meta: GameMeta, seed: Long, players: Int): Int {
        val game = meta.factory()
        game.reset(seed, players)
        game.resize(WIDTH, HEIGHT)

        val rng = Rng(seed xor 0x5EEDL)
        val gesture = Gesture()
        val pointerPhases = PointerPhase.entries.toTypedArray()
        val gestureKinds = GestureKind.entries.toTypedArray()
        for (i in 0 until SCRIPT_LENGTH) {
            when (rng.nextInt(3)) {
                0 -> game.update(16)
                1 -> {
                    val id = rng.nextInt(3)
                    val phase = pointerPhases[rng.nextInt(pointerPhases.size)]
                    game.onPointer(id, phase, rng.nextFloat() * WIDTH, rng.nextFloat() * HEIGHT)
                }
                2 -> {
                    gesture.kind = gestureKinds[rng.nextInt(gestureKinds.size)]
                    gesture.x = rng.nextFloat() * WIDTH
                    gesture.y = rng.nextFloat() * HEIGHT
                    gesture.pointerId = 0
                    game.onGesture(gesture)
                }
            }
        }

        val scene = Scene()
        game.render(scene)
        return scene.hash()
    }
}
