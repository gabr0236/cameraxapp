package com.example.cameraxapp.network

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class Prediction(
    @Json(name = "class") val clazz: String,
    val prob: Double
)