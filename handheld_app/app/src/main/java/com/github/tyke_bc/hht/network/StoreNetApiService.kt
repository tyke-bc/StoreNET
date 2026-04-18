package com.github.tyke_bc.hht.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
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
    @SerializedName("qty_picked") val qtyPicked: Int,
    @SerializedName("short_reason") val shortReason: String? = null
)

data class FinalizeOrderRequest(
    @SerializedName("short_reasons") val shortReasons: Map<String, String>? = null
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
        @Path("id") orderId: Int,
        @Body request: FinalizeOrderRequest = FinalizeOrderRequest()
    ): GenericResponse

    // Truck Receiving APIs
    @GET("api/bopis/manifests")
    suspend fun getManifests(
        @Header("X-Store-ID") storeId: String
    ): ManifestListResponse

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

    @GET("api/cyclecount/section/{pogId}/{section}")
    suspend fun getCycleCountSection(
        @Header("X-Store-ID") storeId: String,
        @Path("pogId") pogId: String,
        @Path("section") section: String
    ): CycleCountSectionResponse

    @GET("api/cyclecount/resolve/{barcode}")
    suspend fun resolveCycleBarcode(
        @Header("X-Store-ID") storeId: String,
        @Path("barcode", encoded = true) barcode: String
    ): CycleCountSectionResponse

    @POST("api/cyclecount/submit")
    suspend fun submitCycleCount(
        @Header("X-Store-ID") storeId: String,
        @Body request: CycleCountSubmitRequest
    ): GenericResponse

    @GET("api/inventory/event_check/{sku}")
    suspend fun checkPricingEvents(
        @Header("X-Store-ID") storeId: String,
        @Path("sku") sku: String
    ): EventCheckResponse

    @GET("api/tasks")
    suspend fun getTasks(@Header("X-Store-ID") storeId: String): List<Task>

    @PUT("api/tasks/{id}")
    suspend fun updateTask(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Body request: UpdateTaskRequest
    ): GenericResponse

    @GET("api/pogs/reset/tasks")
    suspend fun getResetTasks(
        @Header("X-Store-ID") storeId: String,
        @retrofit2.http.Query("status") status: String = "OPEN",
        @retrofit2.http.Query("limit") limit: Int = 25
    ): ResetTasksResponse

    @POST("api/pogs/reset/scan")
    suspend fun scanResetTag(
        @Header("X-Store-ID") storeId: String,
        @Body request: ResetScanRequest
    ): ResetScanResponse

    @POST("api/pogs/reset/reprint/{taskId}")
    suspend fun reprintResetSignoff(
        @Header("X-Store-ID") storeId: String,
        @Path("taskId") taskId: Int
    ): GenericResponse

    @GET("api/inventory/sales/{upcOrSku}")
    suspend fun getSalesHistory(
        @Header("X-Store-ID") storeId: String,
        @Path(value = "upcOrSku", encoded = true) upcOrSku: String
    ): SalesHistoryResponse

    @POST("api/inventory/adjust")
    suspend fun submitAdjustment(
        @Header("X-Store-ID") storeId: String,
        @Body request: AdjustmentRequest
    ): AdjustmentResponse

    @POST("api/compliance/submit")
    suspend fun submitCompliance(
        @Header("X-Store-ID") storeId: String,
        @Body request: ComplianceRequest
    ): GenericResponse

    @POST("api/inventory/transfer/request")
    suspend fun requestTransfer(
        @Header("X-Store-ID") storeId: String,
        @Body request: TransferRequest
    ): GenericResponse

    @GET("api/reports/movers")
    suspend fun getMovers(
        @Header("X-Store-ID") storeId: String,
        @retrofit2.http.Query("days") days: Int
    ): MoversResponse

    @GET("api/review/pending")
    suspend fun getReviewPending(
        @Header("X-Store-ID") storeId: String,
        @retrofit2.http.Query("days") days: Int
    ): ReviewResponse

    // PRP Returns
    @GET("api/prp/batches")
    suspend fun getPrpBatches(@Header("X-Store-ID") storeId: String): List<PrpBatch>

    @GET("api/prp/batches/{id}")
    suspend fun getPrpBatch(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int
    ): PrpBatchDetailResponse

    @POST("api/prp/batches")
    suspend fun createPrpBatch(
        @Header("X-Store-ID") storeId: String,
        @Body request: CreatePrpBatchRequest
    ): CreatePrpBatchResponse

    @POST("api/prp/batches/{id}/item")
    suspend fun addPrpItem(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Body request: AddPrpItemRequest
    ): AddPrpItemResponse

    @DELETE("api/prp/batches/{id}/item/{itemId}")
    suspend fun removePrpItem(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Path("itemId") itemId: Int
    ): GenericResponse

    @POST("api/prp/batches/{id}/close")
    suspend fun closePrpBatch(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Body request: ClosePrpBatchRequest
    ): GenericResponse

    @POST("api/prp/batches/{id}/ship")
    suspend fun shipPrpBatch(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Body request: ShipPrpBatchRequest
    ): GenericResponse

    @POST("api/prp/batches/{id}/print")
    suspend fun printPrpManifest(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Body request: EmptyRequest = EmptyRequest()
    ): GenericResponse

    // Vendors (enterprise — no store header needed but harmless)
    @GET("api/vendors")
    suspend fun getVendors(): List<Vendor>

    // Vendor Deliveries (per-store — HHT receives what vendor drops off)
    @GET("api/vendor-deliveries")
    suspend fun getVendorDeliveries(@Header("X-Store-ID") storeId: String): List<VendorDelivery>

    @GET("api/vendor-deliveries/{id}")
    suspend fun getVendorDelivery(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int
    ): VendorDeliveryDetailResponse

    @POST("api/vendor-deliveries")
    suspend fun createVendorDelivery(
        @Header("X-Store-ID") storeId: String,
        @Body request: CreateVendorDeliveryRequest
    ): CreateVendorDeliveryResponse

    @POST("api/vendor-deliveries/{id}/scan")
    suspend fun scanVendorDelivery(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Body request: VendorDeliveryScanRequest
    ): VendorDeliveryScanResponse

    @POST("api/vendor-deliveries/{id}/complete")
    suspend fun completeVendorDelivery(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Body request: EmptyRequest = EmptyRequest()
    ): GenericResponse

    // Vendor inventory (per-store list of SKUs tagged to a vendor, for DG Respond low-stock browsing)
    @GET("api/vendors/{id}/inventory")
    suspend fun getVendorInventory(
        @Header("X-Store-ID") storeId: String,
        @Path("id") vendorId: Int
    ): List<VendorInventoryRow>

    // Refrigeration units (Refrigeration Maintenance screen)
    @GET("api/refrigeration/units")
    suspend fun getRefrigerationUnits(
        @Header("X-Store-ID") storeId: String,
        @retrofit2.http.Query("category") category: String? = null
    ): RefrigerationUnitsResponse

    @POST("api/refrigeration/units")
    suspend fun createRefrigerationUnit(
        @Header("X-Store-ID") storeId: String,
        @Body request: CreateRefrigerationUnitRequest
    ): CreateIdResponse

    @PUT("api/refrigeration/units/{id}")
    suspend fun updateRefrigerationUnit(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Body request: UpdateRefrigerationUnitRequest
    ): GenericResponse

    @DELETE("api/refrigeration/units/{id}")
    suspend fun deleteRefrigerationUnit(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int
    ): GenericResponse

    // Vendor visits (check-in log)
    @GET("api/vendor-visits/active")
    suspend fun getActiveVendorVisits(@Header("X-Store-ID") storeId: String): VendorVisitsResponse

    @GET("api/vendor-visits")
    suspend fun getVendorVisits(
        @Header("X-Store-ID") storeId: String,
        @retrofit2.http.Query("vendor_id") vendorId: Int? = null,
        @retrofit2.http.Query("limit") limit: Int = 50
    ): VendorVisitsResponse

    @POST("api/vendor-visits")
    suspend fun checkInVendor(
        @Header("X-Store-ID") storeId: String,
        @Body request: VendorCheckInRequest
    ): CreateIdResponse

    @POST("api/vendor-visits/{id}/checkout")
    suspend fun checkOutVendor(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Body request: VendorCheckOutRequest = VendorCheckOutRequest()
    ): GenericResponse

    // Resolve "VO-123" or "123" scanned on a DSD order sheet → SUBMITTED order + items
    @GET("api/vendor-orders/resolve/{code}")
    suspend fun resolveVendorOrder(
        @Header("X-Store-ID") storeId: String,
        @Path("code") code: String
    ): ResolveVendorOrderResponse

    // Vendor Returns (DG Respond)
    @GET("api/vendor-returns")
    suspend fun getVendorReturns(@Header("X-Store-ID") storeId: String): List<VendorReturn>

    @GET("api/vendor-returns/{id}")
    suspend fun getVendorReturn(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int
    ): VendorReturnDetailResponse

    @POST("api/vendor-returns")
    suspend fun createVendorReturn(
        @Header("X-Store-ID") storeId: String,
        @Body request: CreateVendorReturnRequest
    ): CreateIdResponse

    @POST("api/vendor-returns/{id}/item")
    suspend fun addVendorReturnItem(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Body request: AddVendorReturnItemRequest
    ): AddVendorReturnItemResponse

    @DELETE("api/vendor-returns/{id}/item/{itemId}")
    suspend fun removeVendorReturnItem(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Path("itemId") itemId: Int
    ): GenericResponse

    @POST("api/vendor-returns/{id}/close")
    suspend fun closeVendorReturn(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Body request: CloseVendorReturnRequest
    ): GenericResponse

    @POST("api/vendor-returns/{id}/print")
    suspend fun printVendorReturn(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Body request: EmptyRequest = EmptyRequest()
    ): GenericResponse

    // Vendor Orders (DG Respond)
    @GET("api/vendor-orders")
    suspend fun getVendorOrders(@Header("X-Store-ID") storeId: String): List<VendorOrder>

    @GET("api/vendor-orders/{id}")
    suspend fun getVendorOrder(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int
    ): VendorOrderDetailResponse

    @POST("api/vendor-orders")
    suspend fun createVendorOrder(
        @Header("X-Store-ID") storeId: String,
        @Body request: CreateVendorOrderRequest
    ): CreateIdResponse

    @POST("api/vendor-orders/{id}/item")
    suspend fun addVendorOrderItem(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Body request: AddVendorOrderItemRequest
    ): AddVendorOrderItemResponse

    @DELETE("api/vendor-orders/{id}/item/{itemId}")
    suspend fun removeVendorOrderItem(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Path("itemId") itemId: Int
    ): GenericResponse

    @POST("api/vendor-orders/{id}/submit")
    suspend fun submitVendorOrder(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Body request: SubmitVendorOrderRequest
    ): GenericResponse

    @POST("api/vendor-orders/{id}/print")
    suspend fun printVendorOrder(
        @Header("X-Store-ID") storeId: String,
        @Path("id") id: Int,
        @Body request: EmptyRequest = EmptyRequest()
    ): GenericResponse
}

data class ResolveVendorOrderResponse(
    val success: Boolean,
    val order: VendorOrder?,
    val vendor: Vendor?,
    val items: List<VendorOrderItem>?,
    @SerializedName("existing_delivery_id") val existingDeliveryId: Int?,
    val message: String? = null
)

// --- Vendor-inventory row ---
data class VendorInventoryRow(
    val sku: String,
    val name: String?,
    val quantity: Int,
    @SerializedName("reorder_min") val reorderMin: Int?,
    @SerializedName("reorder_max") val reorderMax: Int?,
    val price: Double?
)

// --- Vendor Return DTOs ---
data class VendorReturn(
    val id: Int,
    @SerializedName("vendor_id") val vendorId: Int,
    @SerializedName("vendor_code") val vendorCode: String?,
    @SerializedName("vendor_name") val vendorName: String?,
    val status: String,
    @SerializedName("rep_name") val repName: String?,
    @SerializedName("credit_memo_number") val creditMemoNumber: String?,
    val notes: String?,
    @SerializedName("opened_by_eid") val openedByEid: String?,
    @SerializedName("closed_by_eid") val closedByEid: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("closed_at") val closedAt: String?,
    @SerializedName("item_count") val itemCount: Int? = 0,
    @SerializedName("unit_count") val unitCount: Int? = 0
)

data class VendorReturnItem(
    val id: Int,
    @SerializedName("return_id") val returnId: Int,
    val sku: String,
    val name: String?,
    val quantity: Int,
    @SerializedName("reason_code") val reasonCode: String,
    val notes: String?,
    @SerializedName("scanned_by_eid") val scannedByEid: String?,
    @SerializedName("scanned_at") val scannedAt: String?
)

data class VendorReturnDetailResponse(
    val success: Boolean,
    @SerializedName("return") val returnRecord: VendorReturn?,
    val vendor: Vendor?,
    val items: List<VendorReturnItem>?,
    val message: String? = null
)

data class CreateVendorReturnRequest(
    @SerializedName("vendor_id") val vendorId: Int,
    @SerializedName("rep_name") val repName: String?,
    val notes: String?,
    @SerializedName("credit_memo_number") val creditMemoNumber: String?,
    val eid: String?
)

data class AddVendorReturnItemRequest(
    val sku: String,
    val quantity: Int,
    @SerializedName("reason_code") val reasonCode: String,
    val notes: String?,
    val eid: String?
)

data class AddVendorReturnItemResponse(
    val success: Boolean,
    val id: Int?,
    val sku: String?,
    val name: String?,
    @SerializedName("new_quantity") val newQuantity: Int?,
    val message: String?
)

data class CloseVendorReturnRequest(
    val eid: String?,
    @SerializedName("credit_memo_number") val creditMemoNumber: String?
)

// --- Vendor Order DTOs ---
data class VendorOrder(
    val id: Int,
    @SerializedName("vendor_id") val vendorId: Int,
    @SerializedName("vendor_code") val vendorCode: String?,
    @SerializedName("vendor_name") val vendorName: String?,
    val status: String,
    @SerializedName("rep_name") val repName: String?,
    val notes: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("submitted_at") val submittedAt: String?,
    @SerializedName("line_count") val lineCount: Int? = 0,
    @SerializedName("unit_count") val unitCount: Int? = 0
)

data class VendorOrderItem(
    val id: Int,
    @SerializedName("order_id") val orderId: Int,
    val sku: String,
    val name: String?,
    @SerializedName("quantity_requested") val quantityRequested: Int,
    val notes: String?,
    @SerializedName("on_hand") val onHand: Int?,
    @SerializedName("reorder_min") val reorderMin: Int?,
    @SerializedName("reorder_max") val reorderMax: Int?,
    @SerializedName("created_at") val createdAt: String?
)

data class VendorOrderDetailResponse(
    val success: Boolean,
    val order: VendorOrder?,
    val vendor: Vendor?,
    val items: List<VendorOrderItem>?,
    val message: String? = null
)

data class CreateVendorOrderRequest(
    @SerializedName("vendor_id") val vendorId: Int,
    @SerializedName("rep_name") val repName: String?,
    val notes: String?,
    val eid: String?
)

data class AddVendorOrderItemRequest(
    val sku: String,
    @SerializedName("quantity_requested") val quantityRequested: Int,
    val notes: String?
)

data class AddVendorOrderItemResponse(
    val success: Boolean,
    val id: Int?,
    val sku: String?,
    val name: String?,
    val message: String?
)

data class SubmitVendorOrderRequest(val eid: String?)

data class CreateIdResponse(
    val success: Boolean,
    val id: Int?,
    val message: String?
)

// --- Vendor DTOs ---
data class Vendor(
    val id: Int,
    val code: String,
    val name: String,
    @SerializedName("contact_name") val contactName: String?,
    @SerializedName("contact_phone") val contactPhone: String?,
    @SerializedName("contact_email") val contactEmail: String?,
    @SerializedName("delivery_schedule") val deliverySchedule: String?,
    val notes: String?,
    val active: Int,
    @SerializedName("sku_count") val skuCount: Int? = 0
)

data class RefrigerationUnit(
    val id: Int,
    @SerializedName("unit_number") val unitNumber: String,
    val description: String?,
    val category: String?,
    val oos: Int = 0,
    @SerializedName("created_at") val createdAt: String?
)

data class RefrigerationUnitsResponse(
    val success: Boolean,
    val units: List<RefrigerationUnit>?
)

data class CreateRefrigerationUnitRequest(
    @SerializedName("unit_number") val unitNumber: String,
    val description: String? = null,
    val category: String? = null
)

data class UpdateRefrigerationUnitRequest(
    @SerializedName("unit_number") val unitNumber: String? = null,
    val description: String? = null,
    val category: String? = null,
    val oos: Boolean? = null
)

data class VendorVisit(
    val id: Int,
    @SerializedName("vendor_id") val vendorId: Int,
    @SerializedName("vendor_code") val vendorCode: String?,
    @SerializedName("vendor_name") val vendorName: String?,
    @SerializedName("rep_name") val repName: String?,
    @SerializedName("checked_in_at") val checkedInAt: String?,
    @SerializedName("checked_out_at") val checkedOutAt: String?,
    val notes: String?
)

data class VendorVisitsResponse(
    val success: Boolean,
    val visits: List<VendorVisit>?
)

data class VendorCheckInRequest(
    @SerializedName("vendor_id") val vendorId: Int,
    @SerializedName("rep_name") val repName: String?,
    val eid: String?,
    val notes: String? = null
)

data class VendorCheckOutRequest(
    val notes: String? = null
)

data class VendorDelivery(
    val id: Int,
    @SerializedName("vendor_id") val vendorId: Int,
    @SerializedName("vendor_code") val vendorCode: String?,
    @SerializedName("vendor_name") val vendorName: String?,
    @SerializedName("order_id") val orderId: Int?,
    val status: String,
    @SerializedName("invoice_number") val invoiceNumber: String?,
    @SerializedName("rep_name") val repName: String?,
    val notes: String?,
    @SerializedName("received_by_eid") val receivedByEid: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("completed_at") val completedAt: String?,
    @SerializedName("line_count") val lineCount: Int? = 0,
    @SerializedName("unit_count") val unitCount: Int? = 0
)

data class VendorDeliveryItem(
    val id: Int,
    @SerializedName("delivery_id") val deliveryId: Int,
    val sku: String,
    val name: String?,
    @SerializedName("quantity_received") val quantityReceived: Int,
    @SerializedName("scanned_by_eid") val scannedByEid: String?,
    @SerializedName("scanned_at") val scannedAt: String?
)

data class VendorDeliveryDetailResponse(
    val success: Boolean,
    val delivery: VendorDelivery?,
    val vendor: Vendor?,
    val items: List<VendorDeliveryItem>?,
    val message: String? = null
)

data class CreateVendorDeliveryRequest(
    @SerializedName("vendor_id") val vendorId: Int,
    @SerializedName("order_id") val orderId: Int? = null,
    @SerializedName("rep_name") val repName: String?,
    @SerializedName("invoice_number") val invoiceNumber: String?,
    val notes: String?,
    val eid: String?
)

data class CreateVendorDeliveryResponse(
    val success: Boolean,
    val id: Int?,
    val message: String?
)

data class VendorDeliveryScanRequest(
    val sku: String,
    val quantity: Int,
    val eid: String?
)

data class VendorDeliveryScanResponse(
    val success: Boolean,
    val id: Int?,
    val sku: String?,
    val name: String?,
    @SerializedName("new_quantity") val newQuantity: Int?,
    val message: String?
)

// --- PRP DTOs ---
data class PrpBatch(
    val id: Int,
    val status: String,
    val vendor: String?,
    val carrier: String?,
    @SerializedName("tracking_number") val trackingNumber: String?,
    @SerializedName("opened_by_eid") val openedByEid: String?,
    @SerializedName("opened_by_name") val openedByName: String?,
    @SerializedName("closed_by_eid") val closedByEid: String?,
    @SerializedName("closed_by_name") val closedByName: String?,
    @SerializedName("shipped_at") val shippedAt: String?,
    val notes: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("closed_at") val closedAt: String?,
    @SerializedName("item_count") val itemCount: Int? = 0,
    @SerializedName("unit_count") val unitCount: Int? = 0
)

data class PrpBatchItem(
    val id: Int,
    @SerializedName("batch_id") val batchId: Int,
    val sku: String,
    val name: String?,
    val quantity: Int,
    @SerializedName("reason_code") val reasonCode: String,
    val notes: String?,
    @SerializedName("scanned_by_eid") val scannedByEid: String?,
    @SerializedName("scanned_by_name") val scannedByName: String?,
    @SerializedName("scanned_at") val scannedAt: String?
)

data class PrpBatchDetailResponse(
    val success: Boolean,
    val batch: PrpBatch?,
    val items: List<PrpBatchItem>?,
    val message: String? = null
)

data class CreatePrpBatchRequest(
    val vendor: String?,
    val notes: String?,
    val eid: String?
)

data class CreatePrpBatchResponse(
    val success: Boolean,
    val id: Int?,
    val message: String?,
    @SerializedName("batch_id") val existingBatchId: Int? = null
)

data class AddPrpItemRequest(
    val sku: String,
    val quantity: Int,
    @SerializedName("reason_code") val reasonCode: String,
    val notes: String?,
    val eid: String?
)

data class AddPrpItemResponse(
    val success: Boolean,
    val id: Int?,
    val sku: String?,
    val name: String?,
    @SerializedName("new_quantity") val newQuantity: Int?,
    val message: String?
)

data class ClosePrpBatchRequest(val eid: String?)

data class ShipPrpBatchRequest(
    val carrier: String?,
    @SerializedName("tracking_number") val trackingNumber: String?
)

class EmptyRequest

data class PricingEvent(
    val id: Int,
    val name: String,
    val type: String,
    val price: Double
)

data class EventCheckResponse(
    val success: Boolean,
    val events: List<PricingEvent>?,
    val message: String? = null
)

data class Task(
    val id: Int,
    val title: String,
    val description: String?,
    @SerializedName("assigned_eid") val assignedEid: String?,
    @SerializedName("assigned_name") val assignedName: String?,
    @SerializedName("due_date") val dueDate: String?,
    val priority: String,
    val status: String,
    @SerializedName("task_type") val taskType: String? = "GENERAL",
    @SerializedName("pog_items") val pogItems: List<TaskPogItem>? = null
)

data class TaskPogItem(
    @SerializedName("pog_id") val pogId: String,
    @SerializedName("pog_name") val pogName: String?,
    @SerializedName("pog_dimensions") val pogDimensions: String?,
    @SerializedName("pog_suffix") val pogSuffix: String?,
    @SerializedName("scanned_at") val scannedAt: String?,
    @SerializedName("scanned_by_eid") val scannedByEid: String?,
    @SerializedName("scanned_by_name") val scannedByName: String?
)

data class UpdateTaskRequest(val status: String)

data class ResetScanRequest(
    @SerializedName("pog_id") val pogId: String,
    val eid: String?
)

data class SaleRow(
    val timestamp: String?,
    @SerializedName("tender_type") val tenderType: String?,
    @SerializedName("receipt_total") val receiptTotal: Double?,
    val barcode: String?,
    val quantity: Int?,
    val price: Double?,
    @SerializedName("original_price") val originalPrice: Double?,
    val name: String?,
    val sku: String?,
    val upc: String?
)

data class SalesHistoryResponse(
    val success: Boolean,
    val sales: List<SaleRow>?,
    val message: String? = null
)

data class AdjustmentRequest(
    val sku: String,
    @SerializedName("adjustment_type") val adjustmentType: String,
    val quantity: Int,
    @SerializedName("reason_code") val reasonCode: String?,
    val notes: String?,
    val eid: String?
)

data class AdjustmentResponse(
    val success: Boolean,
    val message: String?,
    val sku: String? = null,
    @SerializedName("new_quantity") val newQuantity: Int? = null
)

data class ComplianceRequest(
    @SerializedName("check_type") val checkType: String,
    @SerializedName("fixture_id") val fixtureId: String?,
    val details: String?, // JSON-encoded
    val passed: Boolean,
    val notes: String?,
    val eid: String?
)

data class TransferRequest(
    val sku: String,
    val quantity: Int,
    @SerializedName("other_store_id") val otherStoreId: Int?,
    val notes: String?,
    val eid: String?
)

data class MoverRow(
    val sku: String,
    val name: String?,
    @SerializedName("units_sold") val unitsSold: Int? = null,
    val revenue: Double? = null,
    @SerializedName("on_hand") val onHand: Int? = null,
    val department: String? = null,
    val price: Double? = null
)

data class MoversResponse(
    val success: Boolean,
    @SerializedName("window_days") val windowDays: Int?,
    val tons: List<MoverRow>?,
    val nones: List<MoverRow>?
)

data class ReviewAdjustment(
    val id: Int,
    val sku: String,
    @SerializedName("adjustment_type") val adjustmentType: String,
    val quantity: Int,
    @SerializedName("reason_code") val reasonCode: String?,
    val notes: String?,
    val eid: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("item_name") val itemName: String?,
    @SerializedName("eid_name") val eidName: String?
)

data class ReviewTransfer(
    val id: Int,
    val direction: String,
    @SerializedName("other_store_id") val otherStoreId: Int?,
    val sku: String,
    val quantity: Int,
    val status: String,
    val notes: String?,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("item_name") val itemName: String?
)

data class ReviewComplianceFail(
    val id: Int,
    @SerializedName("check_type") val checkType: String,
    @SerializedName("fixture_id") val fixtureId: String?,
    val details: String?,
    val notes: String?,
    val eid: String?,
    @SerializedName("created_at") val createdAt: String?
)

data class ReviewResponse(
    val success: Boolean,
    val adjustments: List<ReviewAdjustment>?,
    val transfers: List<ReviewTransfer>?,
    @SerializedName("failed_compliance") val failedCompliance: List<ReviewComplianceFail>?
)

data class ResetTaskSummary(
    val id: Int,
    val title: String,
    val status: String,
    @SerializedName("created_at") val createdAt: String?,
    @SerializedName("completed_at") val completedAt: String?,
    @SerializedName("due_date") val dueDate: String?,
    val priority: String?,
    @SerializedName("pog_total") val pogTotal: Int,
    @SerializedName("pog_done") val pogDone: Int,
    @SerializedName("last_scan_at") val lastScanAt: String?,
    @SerializedName("last_scan_by") val lastScanBy: String?
)

data class ResetTasksResponse(
    val success: Boolean,
    val tasks: List<ResetTaskSummary>?
)

data class ResetScanResponse(
    val success: Boolean,
    val status: String?, // "applied" | "completed" | "already_done" | "not_found"
    @SerializedName("task_id") val taskId: Int?,
    @SerializedName("pog_id") val pogId: String?,
    @SerializedName("pog_name") val pogName: String?,
    val completed: Int?,
    val total: Int?,
    @SerializedName("scanned_at") val scannedAt: String?,
    @SerializedName("scanned_by_eid") val scannedByEid: String?,
    @SerializedName("scanned_by_name") val scannedByName: String?,
    val message: String? = null
)

data class CycleCountItem(
    val sku: String,
    val name: String,
    val upc: String?,
    val section: String?,
    val shelf: String?,
    val faces: String?,
    val quantity: Int
)

data class CycleCountSectionResponse(
    val success: Boolean,
    @SerializedName("pog_id") val pogId: String?,
    @SerializedName("pog_name") val pogName: String?,
    @SerializedName("pog_type") val pogType: String?,
    val section: String?,
    val items: List<CycleCountItem>?,
    val message: String? = null
)

data class CycleCountEntry(
    val sku: String,
    @SerializedName("counted_qty") val countedQty: Int
)

data class CycleCountSubmitRequest(
    val counts: List<CycleCountEntry>
)

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

// Server returns a paginated wrapper { rows, page, limit, total, totalPages },
// not a bare array. Matches the /api/bopis/manifests endpoint in server.js.
data class ManifestListResponse(
    val rows: List<TruckManifest> = emptyList(),
    val page: Int? = null,
    val limit: Int? = null,
    val total: Int? = null,
    val totalPages: Int? = null
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
