package com.example.cameraxapp.network

import okhttp3.MultipartBody
import retrofit2.http.*

interface ApiService {
    @Multipart
    @POST("predict-json/")        // keep the trailing slash
    suspend fun predict(
        @Part file: MultipartBody.Part
    ): List<Prediction>
}
