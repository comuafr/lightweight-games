package lg.android

import android.app.Activity
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import lg.android.ui.GameView
import lg.android.ui.HelpView
import lg.android.ui.Strings
import lg.core.GameMeta
import lg.core.GameRegistry
import lg.core.Orientation
import lg.core.session.GameSession
import lg.core.session.Overlay

/**
 * Hosts a single [GameSession] full-screen. Owns the session end-to-end for
 * this activity's lifetime: creates it from `EXTRA_GAME_ID`, locks the
 * screen orientation the game asks for, pauses + persists on
 * backgrounding, and never auto-resumes real-time play — the session always
 * comes back showing its Paused overlay (PLAN.md §5, "Retour
 * d'arrière-plan").
 */
class GameActivity : Activity() {
    private lateinit var session: GameSession
    private lateinit var gameView: GameView

    // Typed as Any? (never exposed as android.window.OnBackInvokedCallback in
    // a field signature) so this class verifies cleanly on API < 33 devices;
    // only ever created/cast under an SDK_INT >= 33 guard.
    private var backCallback: Any? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val meta = intent.getStringExtra(EXTRA_GAME_ID)?.let { GameRegistry.byId[it] }
        if (meta == null) {
            finish()
            return
        }

        val app = App.from(this)
        session = GameSession(meta, app.scores, app.settings, app.clock)
        session.tryRestore(app.store)
        session.onQuit = { finish() }

        requestedOrientation = when (meta.orientation) {
            Orientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            Orientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            Orientation.ANY -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        gameView = GameView(this).also {
            it.session = session
            it.soundSink = app.soundSink
        }
        val root = FrameLayout(this)
        root.addView(gameView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        setContentView(root, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        session.onHelp = {
            gameView.stopFrameLoop()
            showHelp(root, meta, app)
        }
        showHelpFirstTime(root, meta, app)
        EdgeToEdge.enterImmersiveSticky(window)

        if (meta.players.last >= 2) {
            gameView.addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                if (Build.VERSION.SDK_INT >= 29 && right > left && bottom > top) {
                    v.systemGestureExclusionRects = listOf(Rect(0, 0, right - left, bottom - top))
                }
            }
        }

        setupBackHandling()
    }

    private var helpView: HelpView? = null

    /** First time this game is opened: rules and controls on top of the game, until dismissed. */
    private fun showHelpFirstTime(root: FrameLayout, meta: GameMeta, app: App) {
        if (app.store.getBoolean(HelpView.seenKey(meta.id), false)) return
        showHelp(root, meta, app)
    }

    /** Rules and controls over the whole screen; the game stays paused underneath. */
    private fun showHelp(root: FrameLayout, meta: GameMeta, app: App) {
        if (helpView != null || !HelpView.hasHelp(this, meta.id)) return
        val title = getString(Strings.ORDINAL_TO_RES[meta.titleKey.ordinal])
        val view = HelpView(this, meta.id, title) {
            app.store.putBoolean(HelpView.seenKey(meta.id), true)
            helpView?.let { root.removeView(it) }
            helpView = null
            gameView.invalidate()
        }
        helpView = view
        root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    }

    override fun onPause() {
        super.onPause()
        session.pause()
        session.persist(App.from(this).store)
        gameView.stopFrameLoop()
    }

    override fun onResume() {
        super.onResume()
        // The session is always Paused (or Start/Over) coming back from the
        // background; just repaint the current state and let the frame loop
        // restart on its own if the (still-shown) overlay allows it.
        gameView.invalidate()
        gameView.ensureFrameLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= 33) {
            (backCallback as? OnBackInvokedCallback)?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
        }
    }

    private fun setupBackHandling() {
        if (Build.VERSION.SDK_INT >= 33) {
            val callback = OnBackInvokedCallback { handleBack() }
            backCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
        }
    }

    // GestureBackNavigation: lint can't see the SDK_INT guard below — on API
    // >= 33 predictive-back is wired via setupBackHandling() above instead,
    // this override is the intentional pre-33 fallback path.
    @Suppress("DEPRECATION", "GestureBackNavigation", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (Build.VERSION.SDK_INT >= 33) {
            super.onBackPressed()
        } else {
            handleBack()
        }
    }

    private fun handleBack() {
        helpView?.let {
            it.dismiss()
            return
        }
        if (session.overlay == Overlay.None) {
            session.pause()
            gameView.stopFrameLoop()
            gameView.invalidate()
        } else {
            finish()
        }
    }

    companion object {
        const val EXTRA_GAME_ID = "game_id"
    }
}
