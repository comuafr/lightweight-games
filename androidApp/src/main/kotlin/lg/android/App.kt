package lg.android

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import lg.android.audio.Beeper
import lg.android.audio.SoundSink
import lg.android.billing.PlayBilling
import lg.android.storage.PrefsStore
import lg.android.storage.SystemClock
import lg.core.CoreConfig
import lg.core.store.Entitlement
import lg.core.store.KeyValueStore
import lg.core.store.Scores
import lg.core.store.Settings
import lg.core.util.Clock

/**
 * Application-wide singletons: a single [KeyValueStore], [Clock], [Settings],
 * [Scores], [Entitlement] and [SoundSink], created lazily on first use and
 * shared by [MainActivity], [GameActivity] and [PaywallActivity].
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        updateCoreLanguage()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateCoreLanguage()
    }

    // HangmanGame picks its word list off this (PLAN.md §9 M5, docs/ARCHITECTURE.md
    // "CoreConfig"); refreshed on every configuration change too, so a locale
    // switch made while the app is running (Android settings, or per-app
    // language) takes effect for the next Hangman round without a restart.
    private fun updateCoreLanguage() {
        CoreConfig.languageTag = resources.configuration.locales[0].toLanguageTag()
    }

    val store: KeyValueStore by lazy { PrefsStore(this) }
    val clock: Clock by lazy { SystemClock() }
    val settings: Settings by lazy { Settings(store) }
    val scores: Scores by lazy { Scores(store) }
    val entitlement: Entitlement by lazy { Entitlement(store, clock) }

    // App-wide and never released; see the lifetime note on [Beeper] for why.
    // `Application.onCreate` never touches this property, so building the
    // AudioTrack set doesn't delay app start -- it only happens the first
    // time a GameActivity actually needs a SoundSink.
    internal val soundSink: SoundSink by lazy { Beeper() }

    // Lazy on purpose (PLAN.md §6 "connexion paresseuse"): building this
    // does not touch Play Billing at all, and nothing here connects to the
    // billing service until the paywall, "Restore purchases", or the
    // catalogue's opportunistic silent revalidation actually calls into it.
    // `Application.onCreate` never touches this property either.
    internal val billing: PlayBilling by lazy {
        PlayBilling(this, entitlement, Handler(Looper.getMainLooper()))
    }

    /** Whether the full catalogue is unlocked, per the local [Entitlement] cache. */
    fun isUnlocked(): Boolean = entitlement.isUnlocked()

    companion object {
        fun from(context: Context): App = context.applicationContext as App
    }
}
