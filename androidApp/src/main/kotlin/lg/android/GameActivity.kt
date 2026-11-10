package lg.android

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
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
import lg.core.store.Scores

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
        session.onEnterInitials = { _, _ -> askInitials(app) }
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
    private var initialsDialog: AlertDialog? = null

    /**
     * A new high score: a dialog with a three-letter, upper-case field, prefilled with the initials used last.
     * The answer (or leaving the dialog, which keeps the prefilled/default initials) goes to [GameSession.submitInitials].
     */
    private fun askInitials(app: App) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(Scores.NAME_LENGTH))
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            gravity = Gravity.CENTER
            textSize = 32f
            setText(app.scores.lastInitials())
            setSelection(text.length)
        }
        val pad = (24 * resources.displayMetrics.density).toInt()
        val holder = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input, FrameLayout.LayoutParams(MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.enter_initials)
            .setView(holder)
            .setPositiveButton(android.R.string.ok) { _, _ -> finishInitials(input.text.toString()) }
            .setOnCancelListener { finishInitials(null) }
            .create()
        dialog.setCanceledOnTouchOutside(false)
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.dismiss()
                finishInitials(input.text.toString())
                true
            } else {
                false
            }
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        initialsDialog = dialog
        dialog.show()
    }

    private fun finishInitials(name: String?) {
        initialsDialog = null
        session.submitInitials(name)
        gameView.invalidate()
    }

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
        // Leaving while the initials are asked: keep the score under the default initials.
        initialsDialog?.dismiss()
        initialsDialog = null
        session.submitInitials(null)
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
        initialsDialog?.dismiss()
        initialsDialog = null
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
