package ir.dotin.AIPlugin.service

import com.google.gson.Gson
import ir.dotin.AIPlugin.data.GenerationRequest
import ir.dotin.AIPlugin.data.GenerationResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit



class RemoteModelService {

    // 1. Configure the OkHttpClient with longer timeouts
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val serverUrl = "http://192.168.185.239:8000/prompt"

    fun queryRemoteModel(prompt: String): String {
        return try {
            val requestPayload = gson.toJson(GenerationRequest(prompt = prompt))

            val request = Request.Builder()
                .url(serverUrl)
                .post(requestPayload.toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return "Server Error: ${response.code} - ${response.message}"
                }
                val responseBody = response.body?.string()

                // Access the .response property to match the data class
                gson.fromJson(responseBody, GenerationResponse::class.java).response
            }
        } catch (e: Exception) {
            e.printStackTrace()
            // This will now catch the timeout exception if it still occurs after 60 seconds
            "Error: ${e.message}"
        }
    }
}
