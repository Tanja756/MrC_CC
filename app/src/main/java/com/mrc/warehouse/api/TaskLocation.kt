package com.mrc.warehouse.api

import com.google.gson.annotations.SerializedName

/**
 * Модель для хранения местоположения заявки
 */
data class TaskLocation(
    @SerializedName("task_guid")
    val taskGuid: String,
    
    @SerializedName("latitude")
    val latitude: Double,
    
    @SerializedName("longitude")
    val longitude: Double,
    
    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
)