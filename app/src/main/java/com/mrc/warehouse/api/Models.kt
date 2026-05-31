package com.mrc.warehouse.api

import com.google.gson.annotations.SerializedName

/** Response from /hs/api/v1/login */
data class LoginResponse(
    val priorities: List<PriorityItem>? = null,
    val divisions: List<DivisionItem>? = null
)

data class PriorityItem(
    val value: Int? = null,
    val name: String? = null
)

data class DivisionItem(
    val guid: String? = null,
    val name: String? = null
)

/** Storage (склад) */
data class StorageItem(
    val guid: String? = null,
    val name: String? = null
)

/** Balance (остаток) */
data class BalanceItem(
    @SerializedName("product_name")
    val productName: String? = null,
    @SerializedName("series_name")
    val seriesName: String? = null,
    @SerializedName("inventory_number")
    val inventoryNumber: String? = null,
    val balance: Int? = null
)

/** Client (клиент) */
data class ClientItem(
    val guid: String? = null,
    @SerializedName("for_selection")
    val forSelection: Boolean? = null,
    val name: String? = null
)

/** Product (товар) */
data class ProductItem(
    val guid: String? = null,
    val name: String? = null
)

/** Task (заявка) */
data class TaskItem(
    val guid: String? = null,
    val number: String? = null,
    val name: String? = null,
    val description: String? = null,
    val status: String? = null,
    val date: String? = null,
    val period: String? = null,
    val priority: Int? = null,
    @SerializedName("name_department")
    val nameDepartment: String? = null,
    val user: String? = null,
    @SerializedName("guid_client")
    val guidClient: String? = null,
    @SerializedName("hasAttachments")
    val hasAttachments: Boolean? = null,
    @SerializedName("closeComment")
    val closeComment: String? = null,
    
    // Поля для локального использования - не отправляются на сервер
    @Transient
    var hasLocation: Boolean = false
)

/** Response for task list endpoints */
data class TasksResponse(
    val docs: List<ClientItem>? = null,
    val tasks: List<TaskItem>? = null
)

/** Salary data item */
data class SalaryItem(
    val title: String? = null,
    val value: Double? = null
)

/** Salary response */
data class SalaryResponse(
    @SerializedName("Data")
    val data: List<SalaryItem>? = null,
    @SerializedName("total_amount")
    val totalAmount: Double? = null
)

/** Response for task-take endpoint */
data class TaskTakeResponse(
    val status: String? = null,
    val error: String? = null
)

/** Attachment data for task-close request */
data class AttachmentData(
    val data: String,
    val extension: String
)

/** Request body for /hs/api/v1/task-close */
data class TaskCloseRequest(
    val attachments: List<AttachmentData>,
    val comment: String,
    val guid: String,
    @SerializedName("guid_doc")
    val guidDoc: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val services: List<String> = emptyList()
)

/** Movement direction */
enum class MovementType {
    INCOME,     // приход
    WRITE_OFF   // списание
}

/** Storage movement (движение по складу) */
data class StorageMovement(
    @SerializedName("product_name")
    val productName: String? = null,
    @SerializedName("series_name")
    val seriesName: String? = null,
    @SerializedName("inventory_number")
    val inventoryNumber: String? = null,
    val quantity: Int? = null,
    val date: String? = null,
    @SerializedName("movement_type")
    val movementType: String? = null // "INCOME" or "WRITE_OFF"
)

/** Generic error response */
data class ErrorResponse(
    val error: String? = null
)
