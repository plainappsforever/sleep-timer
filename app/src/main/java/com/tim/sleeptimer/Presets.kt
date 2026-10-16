package com.tim.sleeptimer

/**
 * Presets with their own settings (SPEC F10, DECISIONS D26). Pure Kotlin, no Android imports.
 * A null field means "use the global setting". Later phases add only the fields they own; the
 * codec ignores unknown keys and tolerates missing ones.
 */
data class Preset(
    val id: String,
    val name: String? = null,
    val minutes: Int,
    val fadeSeconds: Int? = null,
    /** "Lock screen at end" for this preset (P4, SPEC F16); null = the global switch. */
    val lockAtEnd: Boolean? = null,
    /** "Do Not Disturb at start" for this preset (P5, SPEC F18); null = the global switch. */
    val dnd: Boolean? = null,
    /** Bedtime volume for this preset (P5): null = the global setting, 0 = off, 5-100 = % of max. */
    val bedtimeVolumePercent: Int? = null,
    /** Goodnight sequence for this preset (P6, SPEC F20); null = the global switch. */
    val goodnight: Boolean? = null,
) {
    /** Chip / list label: "Name · 30 min", or "30 min" when unnamed. */
    val label: String get() = if (name.isNullOrBlank()) "$minutes min" else "$name · $minutes min"

    /** The fade this preset runs with, falling back to the global setting. */
    fun effectiveFadeSeconds(globalFadeSeconds: Int): Int = fadeSeconds ?: globalFadeSeconds

    /** DND at start for a run of this preset (P5): its own choice, else the global switch. */
    fun effectiveDnd(globalDnd: Boolean): Boolean = dnd ?: globalDnd

    /** Bedtime volume % for a run of this preset, or null for "leave the volume alone". */
    fun effectiveBedtimePercent(globalEnabled: Boolean, globalPercent: Int): Int? =
        Presets.effectiveBedtimePercent(bedtimeVolumePercent, globalEnabled, globalPercent)

    /** Goodnight for a run of this preset (P6): its own choice, else the global switch. */
    fun effectiveGoodnight(globalGoodnight: Boolean): Boolean = goodnight ?: globalGoodnight
}

object Presets {
    const val MAX_PRESETS = 8
    const val MIN_PRESETS = 1
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = TimerPlan.MAX_DURATION_MINUTES
    const val MAX_FADE_SECONDS = 600
    const val MAX_NAME_LENGTH = 24

    /** Bedtime volume choices the editor offers besides "Default" (null) and "Off" (0). */
    val BEDTIME_OPTIONS = listOf(20, 30, 50)
    const val MIN_BEDTIME_PERCENT = 5
    const val MAX_BEDTIME_PERCENT = 100

    /** Fade choices the editor offers besides "Default" (null). */
    val FADE_OPTIONS = listOf(0, 10, 30, 60, 120)

    private val DEFAULT_MINUTES = listOf(15, 30, 45, 60, 90)

    /** Fresh install: the v1 chips, unnamed, global fade (D26). */
    fun defaults(): List<Preset> = DEFAULT_MINUTES.mapIndexed { i, m -> Preset(id = "p${i + 1}", minutes = m) }

    /** v1 stored the chip minutes as "15,30,45,60,90"; they become unnamed presets with the global fade. */
    fun migrateLegacyMinutes(csv: String?): List<Preset> {
        val minutes = csv?.split(",")?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
            .take(MAX_PRESETS)
            .map { it.coerceIn(MIN_MINUTES, MAX_MINUTES) }
        if (minutes.isEmpty()) return defaults()
        return minutes.mapIndexed { i, m -> Preset(id = "p${i + 1}", minutes = m) }
    }

    /** Smallest unused "pN" id, so ids stay short and deterministic. */
    fun nextId(existing: List<Preset>): String {
        val used = existing.map { it.id }.toSet()
        var n = 1
        while ("p$n" in used) n++
        return "p$n"
    }

    fun sanitize(preset: Preset): Preset = preset.copy(
        name = preset.name?.trim()?.take(MAX_NAME_LENGTH)?.ifBlank { null },
        minutes = preset.minutes.coerceIn(MIN_MINUTES, MAX_MINUTES),
        fadeSeconds = preset.fadeSeconds?.coerceIn(0, MAX_FADE_SECONDS),
        bedtimeVolumePercent = preset.bedtimeVolumePercent?.let { if (it <= 0) 0 else it.coerceIn(MIN_BEDTIME_PERCENT, MAX_BEDTIME_PERCENT) },
    )

    /**
     * Bedtime volume for a run (D19): a preset's own % wins, its 0 means off for that preset; null falls
     * back to the global switch and %. Null result = the volume is not touched at start.
     */
    fun effectiveBedtimePercent(presetPercent: Int?, globalEnabled: Boolean, globalPercent: Int): Int? = when {
        presetPercent == null -> if (globalEnabled) globalPercent.coerceIn(MIN_BEDTIME_PERCENT, MAX_BEDTIME_PERCENT) else null
        presetPercent <= 0 -> null
        else -> presetPercent.coerceIn(MIN_BEDTIME_PERCENT, MAX_BEDTIME_PERCENT)
    }

    /** Adds a new 30-minute preset; unchanged when already at [MAX_PRESETS]. */
    fun add(list: List<Preset>): List<Preset> =
        if (list.size >= MAX_PRESETS) list else list + Preset(id = nextId(list), minutes = 30)

    /** Removes [id]; unchanged when it would leave fewer than [MIN_PRESETS]. */
    fun remove(list: List<Preset>, id: String): List<Preset> =
        if (list.size <= MIN_PRESETS) list else list.filterNot { it.id == id }

    /** Moves [id] by [delta] positions (-1 = up, +1 = down), clamped to the list ends. */
    fun move(list: List<Preset>, id: String, delta: Int): List<Preset> {
        val from = list.indexOfFirst { it.id == id }
        if (from < 0) return list
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (to == from) return list
        val mutable = list.toMutableList()
        mutable.add(to, mutable.removeAt(from))
        return mutable
    }

    fun update(list: List<Preset>, updated: Preset): List<Preset> =
        list.map { if (it.id == updated.id) sanitize(updated) else it }
}

/**
 * One-string codec, e.g. `v1|id=p1,min=15;id=p2,name=Podcast,min=30,fade=10,lock=1,dnd=1,vol=30,gn=1`. Values are
 * percent-escaped for `% , ; = |`. No JSON library on purpose (org.json is stubbed in JVM tests).
 */
object PresetCodec {
    private const val PREFIX = "v1|"

    fun encode(presets: List<Preset>): String = PREFIX + presets.joinToString(";") { p ->
        buildList {
            add("id=" + escape(p.id))
            p.name?.let { add("name=" + escape(it)) }
            add("min=${p.minutes}")
            p.fadeSeconds?.let { add("fade=$it") }
            p.lockAtEnd?.let { add("lock=" + if (it) "1" else "0") }
            p.dnd?.let { add("dnd=" + if (it) "1" else "0") }
            p.bedtimeVolumePercent?.let { add("vol=$it") }
            p.goodnight?.let { add("gn=" + if (it) "1" else "0") }
        }.joinToString(",")
    }

    /** Null when [raw] is not a v1 string or holds no valid preset; the caller then falls back. */
    fun decode(raw: String?): List<Preset>? {
        if (raw == null || !raw.startsWith(PREFIX)) return null
        val seen = HashSet<String>()
        val result = raw.removePrefix(PREFIX).split(";").mapNotNull { entry ->
            if (entry.isBlank()) return@mapNotNull null
            var id: String? = null
            var name: String? = null
            var minutes: Int? = null
            var fade: Int? = null
            var lock: Boolean? = null
            var dnd: Boolean? = null
            var vol: Int? = null
            var gn: Boolean? = null
            for (field in entry.split(",")) {
                val eq = field.indexOf('=')
                if (eq <= 0) continue
                val value = unescape(field.substring(eq + 1)) ?: continue
                when (field.substring(0, eq)) {
                    "id" -> id = value
                    "name" -> name = value
                    "min" -> minutes = value.toIntOrNull()
                    "fade" -> fade = value.toIntOrNull()
                    "lock" -> lock = when (value) { "1" -> true; "0" -> false; else -> null }
                    "dnd" -> dnd = when (value) { "1" -> true; "0" -> false; else -> null }
                    "vol" -> vol = value.toIntOrNull()
                    "gn" -> gn = when (value) { "1" -> true; "0" -> false; else -> null }
                    else -> Unit // unknown field from a later phase: ignore
                }
            }
            val presetId = id?.takeIf { it.isNotEmpty() }
            val presetMinutes = minutes
            if (presetId == null || presetMinutes == null || !seen.add(presetId)) return@mapNotNull null
            Presets.sanitize(Preset(presetId, name, presetMinutes, fade, lock, dnd, vol, gn))
        }.take(Presets.MAX_PRESETS)
        return result.takeIf { it.isNotEmpty() }
    }

    private const val ESCAPED = "%,;=|"

    private fun escape(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c in ESCAPED || c == '\n' || c == '\r') sb.append('%').append("%02X".format(c.code))
            else sb.append(c)
        }
        return sb.toString()
    }

    private fun unescape(s: String): String? {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%') {
                val code = s.substring(i + 1, minOf(i + 3, s.length)).takeIf { it.length == 2 }?.toIntOrNull(16)
                    ?: return null
                sb.append(code.toChar())
                i += 3
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }
}
