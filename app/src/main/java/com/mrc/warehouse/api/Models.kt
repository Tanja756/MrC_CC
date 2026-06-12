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

// ===================== PPR models =====================

/** PPR task item (from ppr_list response) */
data class PprTaskItem(
    val guid: String? = null,
    val number: String? = null,
    val name: String? = null,
    val status: String? = null,
    val date: String? = null,
    val priority: Int? = null,
    @SerializedName("name_department")
    val nameDepartment: String? = null,
    val description: String? = null,
    @SerializedName("close_comment")
    val closeComment: String? = null,
    val period: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

/** Response from ppr_add (single) */
data class PprAddResponse(
    val status: String? = null,
    val guid: String? = null,
    val count: Int? = null,
    val guids: List<String>? = null,
    val error: String? = null
)

/** Request body for ppr_close */
data class PprCloseRequest(
    val guid: String,
    val comment: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val attachments: List<AttachmentData> = emptyList()
)

/** Response from ppr_list */
data class PprListResponse(
    val tasks: List<PprTaskItem>? = null
)

/** Response from /hs/api/v1/ppr_departments */
data class PprDepartmentsResponse(
    val departments: List<String>? = null
)

// ===================== Documents API models =====================

/** Request body for POST /api/tasks/documents */
data class DocumentsRequest(
    val guid: String,
    val login: String,
    val password: String,
    @SerializedName("profile_name")
    val profileName: String = "",
    @SerializedName("include_act")
    val includeAct: Boolean = true,
    @SerializedName("include_fn")
    val includeFn: Boolean = true,
    @SerializedName("include_m15")
    val includeM15: Boolean = true
)