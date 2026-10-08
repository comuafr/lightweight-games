package lg.android

import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import lg.android.ui.TileView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumentation smoke test (PLAN.md §8/§9 M5): exercises the three
 * activities end to end on a real device/emulator. `androidTestImplementation`
 * only (see androidApp/build.gradle.kts) -- none of `androidx.test:*` ships in
 * the release APK.
 *
 * This module only compiles here (`./gradlew :androidApp:compileDebugAndroidTestKotlin`);
 * running it (`./gradlew :androidApp:connectedDebugAndroidTest`) requires a
 * connected device or emulator, which this dev box does not have.
 */
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @get:Rule
    val mainActivityRule: ActivityTestRule<MainActivity> =
        ActivityTestRule(MainActivity::class.java, false, false)

    @Test
    fun catalogShowsFifteenTiles() {
        val activity = mainActivityRule.launchActivity(null)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()

        val tileCount = countTileViews(activity.window.decorView)
        assertEquals(15, tileCount)
    }

    @Test
    fun snakeGameRespondsToInputAndSurvivesLifecycleAndRotation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // The first-open help page would cover the game; pretend it was already read.
        App.from(instrumentation.targetContext).store.putBoolean("help.seen.snake", true)
        val intent = Intent(instrumentation.targetContext, GameActivity::class.java).apply {
            putExtra(GameActivity.EXTRA_GAME_ID, "snake")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val activity = instrumentation.startActivitySync(intent) as GameActivity
        try {
            instrumentation.waitForIdleSync()

            val decorView = activity.window.decorView
            val centerX = decorView.width / 2f
            val centerY = decorView.height / 2f

            // Snake is a 1-player game: GameSession's Start overlay lays out a
            // single "Tap to start" button centered on screen (see
            // GameSession.layoutButtons), so a tap at the decor view's center
            // hits it and starts the game.
            tap(instrumentation, centerX, centerY)
            instrumentation.waitForIdleSync()

            // A swipe (direction change gesture) once playing; Snake ignores
            // swipes that don't line up with a legal turn, so this only needs
            // to exercise the injected-pointer-stream path without asserting
            // on the resulting game state.
            swipe(instrumentation, centerX, decorView.height * 0.7f, centerX, decorView.height * 0.3f)
            instrumentation.waitForIdleSync()

            instrumentation.callActivityOnPause(activity)
            instrumentation.callActivityOnResume(activity)
            instrumentation.waitForIdleSync()

            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            instrumentation.waitForIdleSync()
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            instrumentation.waitForIdleSync()

            assertFalse("activity should not be finishing after pause/resume/rotation", activity.isFinishing)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    @Test
    fun paywallLaunchesForSudokuAndClosesWithBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, PaywallActivity::class.java).apply {
            putExtra(PaywallActivity.EXTRA_GAME_ID, "sudoku")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val activity = instrumentation.startActivitySync(intent) as PaywallActivity
        instrumentation.waitForIdleSync()
        assertTrue(!activity.isFinishing && !activity.isDestroyed)

        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        instrumentation.waitForIdleSync()

        assertTrue("back should close the paywall", activity.isFinishing || activity.isDestroyed)
    }

    private fun countTileViews(view: View): Int {
        if (view is TileView) return 1
        if (view !is ViewGroup) return 0
        var count = 0
        for (i in 0 until view.childCount) count += countTileViews(view.getChildAt(i))
        return count
    }

    private fun tap(instrumentation: Instrumentation, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        send(instrumentation, downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        send(instrumentation, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
    }

    private fun swipe(instrumentation: Instrumentation, startX: Float, startY: Float, endX: Float, endY: Float) {
        val downTime = SystemClock.uptimeMillis()
        val steps = 8
        send(instrumentation, downTime, downTime, MotionEvent.ACTION_DOWN, startX, startY)
        for (i in 1..steps) {
            val fraction = i.toFloat() / steps
            val x = startX + (endX - startX) * fraction
            val y = startY + (endY - startY) * fraction
            send(instrumentation, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y)
        }
        send(instrumentation, downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, endX, endY)
    }

    private fun send(instrumentation: Instrumentation, downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        instrumentation.sendPointerSync(event)
        event.recycle()
    }
}
