package com.banoku.oplus.autobrightness.util

import android.content.SharedPreferences
import com.banoku.oplus.autobrightness.BrightnessMath
import java.util.Locale
import java.util.UUID

data class CurveProfile(
    val id: String,
    val name: String,
    val nits: List<Float>,
    val updatedAt: Long = System.currentTimeMillis()
)

data class CurveProfileStoreState(
    val profiles: List<CurveProfile>,
    val activeProfileId: String
)

object CurveProfileRepository {

    private const val PREF_PROFILES_JSON = "pref_curve_profiles_json"
    private const val PREF_ACTIVE_PROFILE_ID = "pref_active_curve_profile_id"
    private const val PREF_LEGACY_CURVE_CONFIG = "pref_curve_config"

    fun loadStore(prefs: SharedPreferences, defaultProfileName: String = "Мой профиль"): CurveProfileStoreState {
        val rawJson = prefs.getString(PREF_PROFILES_JSON, "").orEmpty()
        val activeId = prefs.getString(PREF_ACTIVE_PROFILE_ID, "").orEmpty()

        val parsed = if (rawJson.isNotBlank()) parseJsonStore(rawJson) else emptyList()
        if (parsed.isNotEmpty()) {
            val resolvedActive = if (parsed.any { it.id == activeId }) activeId else parsed.first().id
            return CurveProfileStoreState(parsed, resolvedActive)
        }

        // Seamless migration from v1.4.1 / v1.4.2 single curve configuration
        val legacyRaw = prefs.getString(PREF_LEGACY_CURVE_CONFIG, "").orEmpty()
        val legacyConfig = BrightnessMath.parseCurveConfig(legacyRaw)
        val initialProfile = CurveProfile(
            id = "profile_default",
            name = defaultProfileName,
            nits = legacyConfig.nits.toList()
        )
        val initialList = listOf(initialProfile)
        saveStore(prefs, initialList, initialProfile.id)
        return CurveProfileStoreState(initialList, initialProfile.id)
    }

    fun saveStore(prefs: SharedPreferences, profiles: List<CurveProfile>, activeId: String) {
        val safeList = profiles.ifEmpty {
            listOf(CurveProfile("profile_default", "Мой профиль", BrightnessMath.copyDefaults().toList()))
        }
        val safeActive = if (safeList.any { it.id == activeId }) activeId else safeList.first().id
        val json = serializeStoreJson(safeList, safeActive)
        prefs.edit()
            .putString(PREF_PROFILES_JSON, json)
            .putString(PREF_ACTIVE_PROFILE_ID, safeActive)
            .apply()
    }

    fun createProfile(name: String, nits: List<Float>): CurveProfile {
        val cleanName = name.trim().ifEmpty { "Профиль" }.take(32)
        val monotonic = BrightnessMath.enforceMonotonic(nits.toFloatArray()).toList()
        return CurveProfile(
            id = "p_${UUID.randomUUID().toString().take(8)}",
            name = cleanName,
            nits = monotonic,
            updatedAt = System.currentTimeMillis()
        )
    }

    fun exportProfilesToJson(profiles: List<CurveProfile>, activeId: String? = null): String {
        return serializeStoreJson(profiles, activeId ?: profiles.firstOrNull()?.id.orEmpty())
    }

    fun importProfilesFromJson(rawJson: String): List<CurveProfile> {
        val imported = parseJsonStore(rawJson)
        if (imported.isNotEmpty()) return imported

        // Also allow single-line property format like "1:10,20,30,70,155,320,600" or "10,20,..."
        val trimmed = rawJson.trim()
        val cfg = if (trimmed.contains(':')) {
            BrightnessMath.parseCurveConfig(trimmed)
        } else {
            val arr = BrightnessMath.parseNitArray(trimmed)
            if (arr != null) BrightnessMath.CurveConfig(true, arr) else null
        }
        if (cfg != null && (trimmed.contains(',') && BrightnessMath.parseNitArray(trimmed.substringAfter(':')) != null)) {
            return listOf(createProfile("Импорт", cfg.nits.toList()))
        }
        return emptyList()
    }

    internal fun serializeStoreJson(profiles: List<CurveProfile>, activeId: String): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"schema\": 1,\n")
        sb.append("  \"app\": \"AutoBrightnessGuard\",\n")
        sb.append("  \"anchorsLux\": [0, 5, 25, 100, 500, 2000, 10000],\n")
        sb.append("  \"activeProfileId\": \"").append(escapeJson(activeId)).append("\",\n")
        sb.append("  \"profiles\": [\n")
        profiles.forEachIndexed { idx, p ->
            val monotonic = BrightnessMath.enforceMonotonic(p.nits.toFloatArray())
            sb.append("    {\n")
            sb.append("      \"id\": \"").append(escapeJson(p.id)).append("\",\n")
            sb.append("      \"name\": \"").append(escapeJson(p.name)).append("\",\n")
            sb.append("      \"updatedAt\": ").append(p.updatedAt).append(",\n")
            sb.append("      \"nits\": [")
            for (i in monotonic.indices) {
                if (i > 0) sb.append(", ")
                sb.append(String.format(Locale.US, "%.1f", monotonic[i]))
            }
            sb.append("]\n")
            sb.append("    }")
            if (idx < profiles.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ]\n")
        sb.append("}")
        return sb.toString()
    }

    internal fun parseJsonStore(raw: String): List<CurveProfile> {
        if (raw.isBlank()) return emptyList()
        val profilesSectionIdx = raw.indexOf("\"profiles\"")
        val searchBody = if (profilesSectionIdx >= 0) raw.substring(profilesSectionIdx) else raw

        val objRegex = Regex("\\{[^{}]*\"nits\"\\s*:\\s*\\[([^\\]]+)\\][^{}]*\\}")
        val idRegex = Regex("\"id\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val nameRegex = Regex("\"name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val updatedRegex = Regex("\"updatedAt\"\\s*:\\s*(\\d+)")

        val result = mutableListOf<CurveProfile>()
        objRegex.findAll(searchBody).forEachIndexed { idx, match ->
            val block = match.value
            val nitsRaw = match.groupValues[1]
            val parsedNits = BrightnessMath.parseNitArray(nitsRaw) ?: return@forEachIndexed
            val monotonic = BrightnessMath.enforceMonotonic(parsedNits).toList()

            val rawId = idRegex.find(block)?.groupValues?.get(1)?.let(::unescapeJson).orEmpty()
            val rawName = nameRegex.find(block)?.groupValues?.get(1)?.let(::unescapeJson).orEmpty()
            val updatedAt = updatedRegex.find(block)?.groupValues?.get(1)?.toLongOrNull()
                ?: System.currentTimeMillis()

            result.add(
                CurveProfile(
                    id = rawId.ifBlank { "p_import_${idx + 1}_${UUID.randomUUID().toString().take(4)}" },
                    name = rawName.ifBlank { "Профиль ${idx + 1}" },
                    nits = monotonic,
                    updatedAt = updatedAt
                )
            )
        }
        return result
    }

    private fun escapeJson(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun unescapeJson(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '\\' -> sb.append('\\')
                    '"' -> sb.append('"')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    else -> sb.append(value[i + 1])
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }
}
