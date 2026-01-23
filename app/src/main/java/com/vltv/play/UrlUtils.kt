package com.vltv.play

fun montarUrlStream(
    server: String,
    streamType: String, // "live", "movie" ou "series"
    user: String,
    pass: String,
    id: Int,
    ext: String?
): String {
    val finalExt = if (!ext.isNullOrBlank()) ".$ext" else ""
    return "$server/$streamType/$user/$pass/$id$finalExt"
}
