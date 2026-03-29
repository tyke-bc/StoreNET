package com.github.tyke_bc.hht.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {
    // The Backoffice is the District Controller. The POS is the local store. 
    // The HHT needs to talk to the local store DB.
    // However, Android cannot securely make direct JDBC connections to MySQL over a network.
    // It requires a middleman API.
    
    private const val BASE_URL = "http://192.168.0.192:3000/" // Pointing to the main StoreNET backoffice server

    val instance: StoreNetApiService by lazy {
        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        retrofit.create(StoreNetApiService::class.java)
    }
}
