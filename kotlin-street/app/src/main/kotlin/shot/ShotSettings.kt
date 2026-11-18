package com.timerpg.street.shot

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The "Screenshots to Claude" settings: whether the camera button is on the game screen, whether crash reports are sent
 * by themselves, and the GitHub token. The token is kept in EncryptedSharedPreferences (a key in the Android Keystore);
 * if that cannot be opened on this phone, in this app's private preferences instead ([tokenStore] says which). It is never
 * logged, never written to any other file, and the app has no backup (allowBackup is false).
 */
object ShotSettings {
    /** The camera button on the game screen (off by default). */
    var buttonOn by mutableStateOf(false)
        private set
    /** Crash reports sent by themselves on the next start (on by default once a token exists). */
    var autoReports by mutableStateOf(true)
        private set
    var hasToken by mutableStateOf(false)
        private set
    /** Where the token is kept: "encrypted" or "private preferences". */
    var tokenStore = "encrypted"
        private set
    /** A text field of ours has the focus: the game's keys (WASD, arrows) are typed, not walked. */
    @Volatile var typing = false
    /** The hidden test-crash buttons (a long press on the row's title). */
    var debug by mutableStateOf(false)

    @Volatile private var loaded = false
    private var secret: SharedPreferences? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("shot", Context.MODE_PRIVATE)

    @Synchronized fun load(ctx: Context) {
        if (loaded) return
        val p = prefs(ctx)
        buttonOn = p.getBoolean("button", false)
        autoReports = p.getBoolean("autoReports", true)
        secret = openSecret(ctx.applicationContext)
        hasToken = !secret?.getString(KEY, null).isNullOrEmpty()
        loaded = true
    }

    @Suppress("DEPRECATION")
    private fun openSecret(ctx: Context): SharedPreferences = try {
        val key = androidx.security.crypto.MasterKey.Builder(ctx).setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
        androidx.security.crypto.EncryptedSharedPreferences.create(
            ctx, "shot_secret", key,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).also { tokenStore = "encrypted" }
    } catch (t: Throwable) {
        // (a broken keystore on some phones): the app's own private preferences, which no other app can read
        tokenStore = "private preferences"
        ctx.getSharedPreferences("shot_token", Context.MODE_PRIVATE)
    }

    fun setButton(ctx: Context, on: Boolean) { buttonOn = on; prefs(ctx).edit().putBoolean("button", on).apply() }
    fun setAutoReports(ctx: Context, on: Boolean) { autoReports = on; prefs(ctx).edit().putBoolean("autoReports", on).apply() }

    fun saveToken(ctx: Context, token: String) {
        load(ctx)
        val t = token.trim()
        if (t.isEmpty()) return
        secret?.edit()?.putString(KEY, t)?.apply()
        hasToken = true
    }

    fun forgetToken(ctx: Context) {
        load(ctx)
        secret?.edit()?.remove(KEY)?.apply()
        hasToken = false
    }

    /** The token, for the uploader's headers only. */
    internal fun token(ctx: Context): String? { load(ctx); return secret?.getString(KEY, null)?.takeIf { it.isNotEmpty() } }

    private const val KEY = "github_token"
}
