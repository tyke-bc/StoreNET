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
    @SerializedName("quantity_backstock") val quantityBackstock: Int? = 0,
    @SerializedName("pack_size") val packSize: Int? = 1,
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
    val position: Int? = 1,
    @SerializedName("reg_price") val regPrice: Double? = null
)

data class UpdatePackSizeRequest(
    val sku: String,
    @SerializedName("pack_size") val packSize: Int
)

data class InventoryResponse(
    val success: Boolean,
    val item: InventoryItem?,
    val message: String? = null
)

data class PrintRequest(
    val name: String,
    val sku: String,
    val upc: String,
    val location: String? = null,
    val faces: String? = null,
    val department: String? = null,
    @SerializedName("pog_info") val pogInfo: String? = null
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
    val faces: String?,
    @SerializedName("pog_info") val pogInfo: String?,
    @SerializedName("reg_price") val regPrice: Double?
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

// Order DTOs
data class OnlineOrder(
    val id: Int,
    @SerializedName("customer_name") val customerName: String,
    val status: String,
    @SerializedName("is_mock") val isMock: Int? = 0,
    val subtotal: Double? = 0.0,
    val tax: Double? = 0.0,
    val total: Double,
    @SerializedName("created_at") val createdAt: String
)

data class OnlineOrderItem(
    val id: Int,
    @SerializedName("order_id") val orderId: Int,
    val sku: String,
    val name: String,
    val price: Double? = 0.0,
    @SerializedName("qty_ordered") val qtyOrdered: Int,
    @SerializedName("qty_picked") val qtyPicked: Int
)

data class OrderDetailResponse(
    val success: Boolean,
    val order: OnlineOrder?,
    val items: List<OnlineOrderItem>?,
    val message: String? = null
)

data class PickRequest(
    val sku: String
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

    // Online Order (BOPIS) APIs
    @GET("api/bopis/pending")
    suspend fun getPendingOrders(
        @Header("X-Store-ID") storeId: String
    ): List<OnlineOrder>

    @GET("api/bopis/order/{id}")
    suspend fun getOrderDetails(
        @Header("X-Store-ID") storeId: String,
        @Path("id") orderId: Int
    ): OrderDetailResponse

    @POST("api/bopis/pick/{id}")
    suspend fun pickItem(
        @Header("X-Store-ID") storeId: String,
        @Path("id") orderId: Int,
        @Body request: PickRequest
    ): GenericResponse

    @POST("api/bopis/finalize/{id}")
    suspend fun finalizeOrder(
        @Header("X-Store-ID") storeId: String,
        @Path("id") orderId: Int
    ): GenericResponse

    // Truck Receiving APIs
    @GET("api/bopis/manifests")
    suspend fun getManifests(
        @Header("X-Store-ID") storeId: String
    ): List<TruckManifest>

    @GET("api/bopis/manifests/{id}")
    suspend fun getManifestDetails(
        @Header("X-Store-ID") storeId: String,
        @Path("id") manifestId: Int
    ): ManifestDetailResponse

    @POST("api/bopis/receive/{id}")
    suspend fun receiveItem(
        @Header("X-Store-ID") storeId: String,
        @Path("id") manifestId: Int,
        @Body request: PickRequest 
    ): GenericResponse

    @POST("api/bopis/manifest/master-receive")
    suspend fun masterReceive(
        @Header("X-Store-ID") storeId: String,
        @Body request: MasterReceiveRequest
    ): GenericResponse

    // Stocking APIs (Stage 2)
    @POST("api/inventory/stock/box")
    suspend fun stockBox(
        @Header("X-Store-ID") storeId: String,
        @Body request: PickRequest
    ): GenericResponse

    @POST("api/inventory/stock/rolltainer")
    suspend fun stockRolltainer(
        @Header("X-Store-ID") storeId: String,
        @Body request: RolltainerRequest
    ): GenericResponse

    @GET("api/bopis/rolltainers")
    suspend fun getRolltainers(
        @Header("X-Store-ID") storeId: String
    ): List<Rolltainer>

    @POST("api/inventory/local/update_pack_size")
    suspend fun updatePackSize(
        @Header("X-Store-ID") storeId: String,
        @Body request: UpdatePackSizeRequest
    ): GenericResponse
}

data class Rolltainer(
    val id: Int,
    val barcode: String,
    val status: String,
    @SerializedName("created_at") val createdAt: String
)

data class MasterReceiveRequest(
    @SerializedName("bol_number") val bolNumber: String?,
    @SerializedName("manifest_id") val manifestId: Int? = null
)

data class RolltainerRequest(
    val barcode: String
)

data class TruckManifest(
    val id: Int,
    @SerializedName("manifest_number") val manifestNumber: String,
    @SerializedName("bol_number") val bolNumber: String?,
    val status: String,
    @SerializedName("created_at") val createdAt: String
)

data class ManifestItem(
    val id: Int,
    @SerializedName("manifest_id") val manifestId: Int,
    val sku: String,
    val name: String,
    @SerializedName("pack_size") val packSize: Int,
    @SerializedName("expected_packs") val expectedPacks: Int,
    @SerializedName("received_packs") val receivedPacks: Int
)

data class ManifestDetailResponse(
    val success: Boolean,
    val manifest: TruckManifest?,
    val items: List<ManifestItem>?,
    val message: String? = null
)
