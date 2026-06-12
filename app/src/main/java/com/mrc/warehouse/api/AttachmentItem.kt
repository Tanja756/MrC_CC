package com.mrc.warehouse.api

import com.google.gson.annotations.SerializedName

/**
 * Модель вложения (файла), прикреплённого к заявке.
 * Возвращается эндпоинтом /hs/api/v1/tasks-attachment
 */
data class AttachmentItem(
    @SerializedName("filename")
    val filename: String? = null,

    @SerializedName("filetype")
    val filetype: String? = null,

    @SerializedName("content")
    val content: String? = null
)

/**
 * Ответ от эндпоинта /hs/api/v1/tasks-attachment
 */
data class AttachmentsResponse(
    val attachments: List<AttachmentItem>? = null
)