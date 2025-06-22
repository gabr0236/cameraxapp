package com.example.cameraxapp.network

import android.content.ContentResolver
import android.net.Uri
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

fun Uri.asMultipart(
    resolver: ContentResolver,
    fieldName: String = "file",
    mime: String = "image/jpeg"
): MultipartBody.Part {
    val bytes = resolver.openInputStream(this)!!.readBytes()
    val req   = bytes.toRequestBody(mime.toMediaType())
    return MultipartBody.Part.createFormData(fieldName, "photo.jpg", req)
}
