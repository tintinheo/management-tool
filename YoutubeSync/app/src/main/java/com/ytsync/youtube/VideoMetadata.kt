package com.ytsync.youtube

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds the `videos.insert` resource body. Fixed choices the user asked for:
 *  - status.privacyStatus = private
 *  - status.selfDeclaredMadeForKids = false (an 18+ video can't be "made for kids")
 *  - contentDetails.contentRating.ytRating = ytAgeRestricted (when [ageRestrict]; YouTube documents this
 *    field as read-only, so the uploader verifies it afterwards and falls back to Studio if ignored)
 */
object VideoMetadata {
    const val CATEGORY_PEOPLE_BLOGS = "22"
    private const val MAX_TITLE = 100

    /** Default: date first so videos sort by day in Studio, e.g. "20261109 Video 17:51". */
    const val DEFAULT_TEMPLATE = "{YYYYMMDD} Video {HH}:{mm}"

    /** Placeholders a title template may use (case-sensitive: {MM} = month, {mm} = minute). */
    val TOKENS = listOf("{YYYYMMDD}", "{YYYY}", "{MM}", "{DD}", "{HH}", "{mm}", "{ss}", "{name}")

    /** Extra token for group renames: 1, 2, 3… in recording order (zero-padded to the group size). */
    const val SEQ = "{n}"

    /**
     * Fills [template] with the RECORDING time [takenAtMillis] (in [zone]) and file name.
     * Blank template → [DEFAULT_TEMPLATE]. Example: "{YYYYMMDD} Du lịch Đà Lạt" → "20261109 Du lịch Đà Lạt".
     * [seq]/[seqOf] fill {n} ("03" for item 3 of 12); without them {n} is dropped.
     */
    fun title(
        template: String,
        takenAtMillis: Long,
        fileName: String = "",
        zone: ZoneId = ZoneId.systemDefault(),
        seq: Int? = null,
        seqOf: Int = 1,
    ): String {
        val t = Instant.ofEpochMilli(takenAtMillis).atZone(zone)
        fun f(p: String) = DateTimeFormatter.ofPattern(p).format(t)
        val raw = template.trim().ifEmpty { DEFAULT_TEMPLATE }
            .replace("{YYYYMMDD}", f("yyyyMMdd"))
            .replace("{YYYY}", f("yyyy"))
            .replace("{MM}", f("MM"))
            .replace("{DD}", f("dd"))
            .replace("{HH}", f("HH"))
            .replace("{mm}", f("mm"))
            .replace("{ss}", f("ss"))
            .replace("{name}", fileName.substringBeforeLast('.'))
            .replace(SEQ, seq?.toString()?.padStart(seqOf.toString().length, '0') ?: "")
        // YouTube rejects '<' and '>' in titles and caps them at 100 characters.
        return raw.replace("<", "").replace(">", "").replace(Regex("\\s+"), " ").trim().take(MAX_TITLE).trim()
    }

    fun parts(ageRestrict: Boolean): String =
        if (ageRestrict) "snippet,status,recordingDetails,contentDetails" else "snippet,status,recordingDetails"

    fun build(
        title: String,
        fileName: String,
        takenAtMillis: Long,
        ageRestrict: Boolean,
    ): JsonObject = buildJsonObject {
        putJsonObject("snippet") {
            put("title", title)
            put("description", "Tự động tải lên từ camera điện thoại · $fileName")
            put("categoryId", CATEGORY_PEOPLE_BLOGS)
        }
        putJsonObject("status") {
            put("privacyStatus", "private")
            put("selfDeclaredMadeForKids", false)
            put("embeddable", false)
        }
        putJsonObject("recordingDetails") {
            put("recordingDate", Instant.ofEpochMilli(takenAtMillis).toString())
        }
        if (ageRestrict) {
            putJsonObject("contentDetails") {
                putJsonObject("contentRating") { put("ytRating", "ytAgeRestricted") }
            }
        }
    }
}
