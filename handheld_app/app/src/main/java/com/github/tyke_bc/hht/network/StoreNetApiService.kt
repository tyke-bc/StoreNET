package com.github.tyke_bc.hht.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

// DTOs
data class InventoryItem(
    val sku: String,
    val upc: String?,
    val name: String,
    val quantity: Int,
    val price: Double,
    val department: String,
    val brand: String? = null,
    val variant: String? = null,
    val size: String? = null,
    @SerializedName("unit_price_unit") val unitPriceUnit: String? = null,
    val taxable: Boolean? = true,
    @SerializedName("pog_date") val pogDate: String? = null,
    val location: String? = null,
    val faces: String? = null,
    @SerializedName("pog_info") val pogInfo: String? = null,
    val position: Int? = 1
)

data class InventoryResponse(
    val success: Boolean,
    val item: InventoryItem?,
    val message: String? = null
)

data class PrintRequest(
    val name: String,
    val sku: String,
    val upc: String
)

data class PrintShelfLabelRequest(
    val brand: String?,
    val name: String,
    val variant: String?,
    val size: String?,
    val upc: String,
    val price: Double,
    @SerializedName("unit_price_unit") val unitPriceUnit: String?,
    val taxable: Boolean?,
    @SerializedName("pog_date") val pogDate: String?,
    val location: String?,
    val faces: String?
)

data class UpdateInventoryRequest(
    val oldSku: String,
    val newSku: String,
    val name: String,
    val department: String,
    val price: Double,
    val quantity: Int
)

data class AuthRequest(
    val eid: String,
    val pin: String
)

data class UserDto(
    val name: String,
    val role: String
)

data class AuthResponse(
    val success: Boolean,
    val user: UserDto?,
    val message: String? = null
)

data class GenericResponse(
    val success: Boolean,
    val message: String? = null
)

interface StoreNetApiService {

    @GET("api/inventory/local/{upcOrSku}")
    suspend fun getInventoryItem(
        @Header("X-Store-ID") storeId: String,
        @Path("upcOrSku") sku: String
    ): InventoryResponse

    @POST("api/print_sticker")
    suspend fun printSticker(
        @Header("X-Store-ID") storeId: String,
        @Body request: PrintRequest
    ): GenericResponse

    @POST("api/print_shelf_label")
    suspend fun printShelfLabel(
        @Header("X-Store-ID") storeId: String,
        @Body request: PrintShelfLabelRequest
    ): GenericResponse

    @POST("api/inventory/local/update")
    suspend fun updateInventory(
        @Header("X-Store-ID") storeId: String,
        @Body request: UpdateInventoryRequest
    ): GenericResponse

    @POST("api/employees/local/auth")
    suspend fun authLocal(
        @Header("X-Store-ID") storeId: String,
        @Body request: AuthRequest
    ): AuthResponse
}
