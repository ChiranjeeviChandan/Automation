package com.verity.camera

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * User settings. The Anthropic API key is stored with Android Keystore-backed encryption and never
 * leaves the device except as the auth header to api.anthropic.com.
 *
 * For a distributed (multi-user) app, do NOT ship a key: put the director behind your own backend.
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "verity_settings",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var apiKey: String
        get() = prefs.getString("api_key", "").orEmpty()
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    var aiEnabled: Boolean
        get() = prefs.getBoolean("ai_enabled", true)
        set(v) = prefs.edit().putBoolean("ai_enabled", v).apply()

    /** Where the optional on-device LaMa ONNX model is downloaded from. */
    var modelUrl: String
        get() = prefs.getString("model_url", DEFAULT_MODEL_URL).orEmpty().ifBlank { DEFAULT_MODEL_URL }
        set(v) = prefs.edit().putString("model_url", v.trim()).apply()

    /** Also save the unedited capture. Off: only the edited photo is kept. */
    var keepOriginal: Boolean
        get() = prefs.getBoolean("keep_original", false)
        set(v) = prefs.edit().putBoolean("keep_original", v).apply()

    /** Capture agent mode: false = Assist (camera AE + agent trims), true = Pro (agent sets ISO/shutter). */
    var proAgent: Boolean
        get() = prefs.getBoolean("pro_agent", false)
        set(v) = prefs.edit().putBoolean("pro_agent", v).apply()

    var peaking: Boolean
        get() = prefs.getBoolean("peaking", true)
        set(v) = prefs.edit().putBoolean("peaking", v).apply()

    var zebras: Boolean
        get() = prefs.getBoolean("zebras", true)
        set(v) = prefs.edit().putBoolean("zebras", v).apply()

    var upscaleTo4k: Boolean
        get() = prefs.getBoolean("upscale_4k", true)
        set(v) = prefs.edit().putBoolean("upscale_4k", v).apply()

    companion object {
        /** Public LaMa export with a fixed 512x512 [image, mask] -> image signature. */
        const val DEFAULT_MODEL_URL = "https://huggingface.co/Carve/LaMa-ONNX/resolve/main/lama_fp32.onnx"
    }
}
