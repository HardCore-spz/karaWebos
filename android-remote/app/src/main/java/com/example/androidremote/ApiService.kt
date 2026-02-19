package com.example.androidremote

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

interface YouTubeApi {
    @GET("youtube/v3/search")
    suspend fun searchVideos(
        @Query("part") part: String = "snippet",
        @Query("type") type: String = "video",
        @Query("maxResults") maxResults: Int = 10,
        @Query("order") order: String = "relevance",
        @Query("videoEmbeddable") videoEmbeddable: String = "true",
        @Query("q") query: String,
        @Query("key") apiKey: String = ApiService.YOUTUBE_API_KEY
    ): YouTubeSearchResponse
}

object ApiService {
    const val YOUTUBE_API_KEY = "AIzaSyAsw4gtFHg54rkSXEPbEevl4sXfdEzWDIY"

    fun hasValidApiKey(): Boolean {
        val key = YOUTUBE_API_KEY.trim()
        if (key.isBlank()) return false
        if (key.equals("PUT_YOUR_YOUTUBE_DATA_API_KEY_HERE", ignoreCase = true)) return false
        return key.length >= 30
    }

    private val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl("https://www.googleapis.com/")
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    val youtubeApi: YouTubeApi = retrofit.create(YouTubeApi::class.java)
}

data class YouTubeSearchResponse(
    val items: List<YouTubeVideoItem> = emptyList()
)

data class YouTubeVideoItem(
    val id: VideoIdData,
    val snippet: VideoSnippet
)

data class VideoIdData(
    val videoId: String?
)

data class VideoSnippet(
    val title: String,
    val channelTitle: String = "",
    val thumbnails: ThumbnailCollection
)

data class ThumbnailCollection(
    val medium: ThumbnailData? = null,
    val default: ThumbnailData? = null
)

data class ThumbnailData(
    val url: String
)
