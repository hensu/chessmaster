package com.hensu.chessmaster

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** One lenient JSON for content files and saved state (unknown keys ignored, like Codable). */
val AppJson = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true; explicitNulls = false }

/**
 * UserDefaults on Android: a thin wrapper over SharedPreferences with the same reads the Swift
 * code uses. `Defaults.standard` is UserDefaults.standard / @AppStorage.
 * Missing numbers read as 0 and missing bools as false, like UserDefaults.
 */
class Defaults(private val prefs: SharedPreferences) {
    fun has(key: String): Boolean = prefs.contains(key)
    fun string(key: String): String? = prefs.getString(key, null)
    fun integer(key: String): Int = intOrNull(key) ?: 0
    fun intOrNull(key: String): Int? = if (prefs.contains(key)) runCatching { prefs.getInt(key, 0) }.getOrNull() else null
    fun bool(key: String): Boolean = boolOrNull(key) ?: false
    fun boolOrNull(key: String): Boolean? = if (prefs.contains(key)) runCatching { prefs.getBoolean(key, false) }.getOrNull() else null
    fun double(key: String): Double = doubleOrNull(key) ?: 0.0
    fun doubleOrNull(key: String): Double? =
        if (prefs.contains(key)) runCatching { Double.fromBits(prefs.getLong(key, 0)) }.getOrNull() else null
    fun long(key: String): Long = if (prefs.contains(key)) runCatching { prefs.getLong(key, 0) }.getOrDefault(0) else 0
    fun stringArray(key: String): List<String>? = json(key, kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()))

    fun set(key: String, value: String?) = prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
    fun set(key: String, value: Int) = prefs.edit().putInt(key, value).apply()
    fun set(key: String, value: Boolean) = prefs.edit().putBoolean(key, value).apply()
    fun set(key: String, value: Double) = prefs.edit().putLong(key, value.toBits()).apply()
    fun set(key: String, value: Long) = prefs.edit().putLong(key, value).apply()
    fun set(key: String, value: List<String>) =
        setJson(key, value, kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.serializer<String>()))
    fun remove(key: String) = prefs.edit().remove(key).apply()

    /** Codable values, dictionaries and arrays: stored as a JSON string under the key. */
    fun <T> json(key: String, serializer: KSerializer<T>): T? =
        prefs.getString(key, null)?.let { runCatching { AppJson.decodeFromString(serializer, it) }.getOrNull() }
    fun <T> setJson(key: String, value: T, serializer: KSerializer<T>) =
        prefs.edit().putString(key, AppJson.encodeToString(serializer, value)).apply()

    /** The raw stored value (String, Int, Boolean, Long…), for copying keys between kids. */
    fun raw(key: String): Any? = prefs.all[key]
    fun setRaw(key: String, value: Any?) {
        val e = prefs.edit()
        when (value) {
            null -> e.remove(key)
            is String -> e.putString(key, value)
            is Int -> e.putInt(key, value)
            is Boolean -> e.putBoolean(key, value)
            is Long -> e.putLong(key, value)
            is Float -> e.putFloat(key, value)
            else -> error("Defaults.setRaw: unsupported ${value::class}")
        }
        e.apply()
    }
    fun clear() = prefs.edit().clear().apply()

    companion object {
        /** UserDefaults.standard / @AppStorage. */
        val standard: Defaults by lazy { suite("standard") }
        fun suite(name: String): Defaults = Defaults(App.context.getSharedPreferences(name, Context.MODE_PRIVATE))
    }
}

/** dictionary(forKey:) as? [String: String] / [String: Int] helpers. */
fun Defaults.stringMap(key: String): Map<String, String> =
    json(key, kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), kotlinx.serialization.serializer<String>())) ?: emptyMap()
fun Defaults.setStringMap(key: String, value: Map<String, String>) =
    setJson(key, value, kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), kotlinx.serialization.serializer<String>()))
fun Defaults.intMap(key: String): Map<String, Int> =
    json(key, kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), kotlinx.serialization.serializer<Int>())) ?: emptyMap()
fun Defaults.setIntMap(key: String, value: Map<String, Int>) =
    setJson(key, value, kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), kotlinx.serialization.serializer<Int>()))

/** Bundle.main resources: files under the iOS project's Chessmaster/Resources folder ("Learn/puzzles.json"). */
object Assets {
    fun text(path: String): String? =
        runCatching { App.context.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()
    fun exists(path: String): Boolean =
        runCatching { App.context.assets.open(path).close(); true }.getOrDefault(false)
    fun list(dir: String): List<String> = App.context.assets.list(dir)?.toList() ?: emptyList()
}
