package com.tim.sleeptimer

/**
 * Which app shortcuts exist for the current presets (SPEC F15, DECISIONS D25). Pure Kotlin, no Android
 * imports; `Shortcuts.kt` hands the result to `ShortcutManagerCompat`.
 *
 * Ids: dynamic shortcuts are `preset_<presetId>`; shortcuts pinned from the preset editor are
 * `pin_<presetId>_<millis>` (unique per pin, so re-adding a preset never revives an old pin). Preset ids
 * are `pN`, so they contain no underscore. A launcher can also pin a dynamic shortcut itself (drag).
 */
data class ShortcutSpec(val id: String, val presetId: String, val shortLabel: String, val longLabel: String)

data class PinnedReconcile(val update: List<ShortcutSpec>, val disableIds: List<String>)

object ShortcutPlan {
    /** Most launchers show four shortcuts. */
    const val MAX_DYNAMIC = 4

    private const val DYNAMIC_PREFIX = "preset_"
    private const val PIN_PREFIX = "pin_"

    fun dynamicId(presetId: String) = "$DYNAMIC_PREFIX$presetId"

    fun pinnedId(presetId: String, millis: Long) = "$PIN_PREFIX${presetId}_$millis"

    /** The preset a shortcut id belongs to, or null when the id is not ours. */
    fun presetIdOf(shortcutId: String): String? = when {
        shortcutId.startsWith(DYNAMIC_PREFIX) -> shortcutId.removePrefix(DYNAMIC_PREFIX).takeIf { it.isNotEmpty() }
        shortcutId.startsWith(PIN_PREFIX) ->
            shortcutId.removePrefix(PIN_PREFIX).substringBefore('_').takeIf { it.isNotEmpty() }
        else -> null
    }

    /** Shortcut for [preset] under [id]: "Nap · 20 min" short, "Start Nap · 20 min" long. */
    fun spec(id: String, preset: Preset) = ShortcutSpec(
        id = id,
        presetId = preset.id,
        shortLabel = preset.label,
        longLabel = "Start ${preset.label}",
    )

    /** Dynamic shortcuts: the first presets in the user's order, at most [limit] (launcher cap) and [MAX_DYNAMIC]. */
    fun dynamic(presets: List<Preset>, limit: Int = MAX_DYNAMIC): List<ShortcutSpec> =
        presets.take(minOf(limit, MAX_DYNAMIC)).map { spec(dynamicId(it.id), it) }

    /**
     * Pinned shortcuts already on a home screen: refresh the label of those whose preset still exists,
     * disable ("Preset deleted") those whose preset is gone. Ids that are not ours are left alone.
     */
    fun reconcilePinned(pinnedIds: List<String>, presets: List<Preset>): PinnedReconcile {
        val byId = presets.associateBy { it.id }
        val update = mutableListOf<ShortcutSpec>()
        val disable = mutableListOf<String>()
        for (id in pinnedIds) {
            val presetId = presetIdOf(id) ?: continue
            val preset = byId[presetId]
            if (preset != null) update += spec(id, preset) else disable += id
        }
        return PinnedReconcile(update, disable)
    }
}
