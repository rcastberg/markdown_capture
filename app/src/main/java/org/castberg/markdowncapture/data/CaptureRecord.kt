package org.castberg.markdowncapture.data

import org.json.JSONObject

data class CaptureRecord(
    val id: String,
    val baseName: String,
    val markdown: String,
    val timestamp: Long,
    val folderUri: String,
    val hasError: Boolean = false
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("baseName", baseName)
        put("markdown", markdown)
        put("timestamp", timestamp)
        put("folderUri", folderUri)
        put("hasError", hasError)
    }.toString()

    companion object {
        fun fromJson(json: JSONObject) = CaptureRecord(
            id       = json.getString("id"),
            baseName = json.getString("baseName"),
            markdown = json.getString("markdown"),
            timestamp = json.getLong("timestamp"),
            folderUri = json.getString("folderUri"),
            hasError  = json.optBoolean("hasError", false)
        )
    }
}
