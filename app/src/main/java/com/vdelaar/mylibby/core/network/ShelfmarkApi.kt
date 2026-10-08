package com.vdelaar.mylibby.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/** Shelfmark's JSON API (the same one its web UI uses). Authenticated with a session cookie. */
interface ShelfmarkApi {

    @POST("api/auth/login")
    suspend fun login(@Body body: ShelfmarkLoginRequest): Response<ResponseBody>

    @GET("api/auth/check")
    suspend fun authCheck(): JsonObject

    @GET("api/metadata/search")
    suspend fun search(
        @Query("query") query: String,
        @Query("content_type") contentType: String = "ebook",
        @Query("limit") limit: Int = 30,
        @Query("page") page: Int = 1,
    ): ShelfmarkSearchResponse

    /** Releases are kept as raw JSON so the exact object can be posted back to /api/releases/download. */
    @GET("api/releases")
    suspend fun releases(
        @Query("provider") provider: String,
        @Query("book_id") bookId: String,
        @Query("content_type") contentType: String = "ebook",
        /** One release source (e.g. "prowlarr", "direct_download"); null = all enabled. */
        @Query("source") source: String? = null,
        /** Comma-separated ISO codes or "all"; null = the user's default languages. */
        @Query("languages") languages: String? = null,
        /** Override the search text sent to the sources. */
        @Query("manual_query") manualQuery: String? = null,
    ): ShelfmarkReleasesResponse

    @GET("api/release-sources")
    suspend fun releaseSources(): List<ShelfmarkSource>

    @POST("api/releases/download")
    suspend fun download(@Body release: JsonObject): Response<ResponseBody>

    @POST("api/requests")
    suspend fun createRequest(@Body body: JsonObject): Response<ResponseBody>

    @GET("api/status")
    suspend fun status(): Map<String, Map<String, ShelfmarkTask>>
}

@Serializable
data class ShelfmarkLoginRequest(
    val username: String,
    val password: String,
    val remember_me: Boolean = true,
)

@Serializable
data class ShelfmarkBook(
    val provider: String,
    val provider_id: String,
    val title: String,
    val provider_display_name: String? = null,
    val authors: List<String> = emptyList(),
    val cover_url: String? = null,
    val description: String? = null,
    val publisher: String? = null,
    val publish_year: Int? = null,
    val language: String? = null,
    val genres: List<String> = emptyList(),
    val source_url: String? = null,
    val subtitle: String? = null,
    val series_name: String? = null,
    val series_position: Float? = null,
)

@Serializable
data class ShelfmarkSearchResponse(
    val books: List<ShelfmarkBook> = emptyList(),
    val provider: String? = null,
    val page: Int = 1,
    val total_found: Int? = null,
    val has_more: Boolean = false,
)

@Serializable
data class ShelfmarkReleasesResponse(
    val releases: List<JsonObject> = emptyList(),
    val book: JsonElement? = null,
    val sources_searched: List<String>? = null,
)

@Serializable
data class ShelfmarkSource(
    val name: String,
    val display_name: String? = null,
    val enabled: Boolean = true,
    val supported_content_types: List<String>? = null,
)

@Serializable
data class ShelfmarkTask(
    val id: String? = null,
    val title: String? = null,
    val author: String? = null,
    val format: String? = null,
    val size: String? = null,
    val preview: String? = null,
    val progress: Float? = null,
    val status: String? = null,
    val status_message: String? = null,
    val source_display_name: String? = null,
)
