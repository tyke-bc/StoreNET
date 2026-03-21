package com.github.tyke_bc.hht.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

// DTOs
data class InventoryItem(
    val id: Int,
    val sku: String,
    val name: String,
    val quantity: Int,
    val price: Double,
    val department: String,
    val status: String
)

data class DamageRequest(
    val sku: String,
    val quantity: Int,
    val reason: String,
    val type: String
)

data class GenericResponse(
    val success: Boolean,
    val message: String? = null
)

interface StoreNetApiService {

    @GET("api/inventory/{sku}")
    suspend fun getInventoryItem(@Path("sku") sku: String): InventoryItem

    @POST("api/damages")
    suspend fun submitDamage(@Body request: DamageRequest): GenericResponse
}
