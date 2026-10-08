package com.vdelaar.mylibby.core.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface GrimmoryApi {

    // Auth
    @POST("api/v1/auth/login")
    suspend fun login(@Body body: LoginRequest): AccessTokenDto

    @POST("api/v1/auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): AccessTokenDto

    // Single sign-on (OIDC)
    @GET("api/v1/public-settings")
    suspend fun publicSettings(): PublicSettingsDto

    @GET("api/v1/auth/oidc/state")
    suspend fun oidcState(): OidcStateDto

    @FormUrlEncoded
    @POST("api/v1/auth/oidc/mobile/callback")
    suspend fun oidcMobileCallback(
        @Field("code") code: String,
        @Field("code_verifier") codeVerifier: String,
        @Field("redirect_uri") redirectUri: String,
        @Field("nonce") nonce: String,
        @Field("state") state: String,
    ): AccessTokenDto

    // Mobile app catalog
    @GET("api/v1/app/books")
    suspend fun books(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 30,
        @Query("sort") sort: String? = null,
        @Query("dir") dir: String? = null,
        @Query("search") search: String? = null,
        @Query("shelfId") shelfId: Long? = null,
        @Query("authors") authors: List<String>? = null,
        @Query("series") series: List<String>? = null,
        @Query("category") category: List<String>? = null,
        @Query("language") language: List<String>? = null,
        @Query("status") status: List<String>? = null,
        @Query("fileType") fileType: List<String>? = null,
    ): PageResponse<BookSummaryDto>

    @GET("api/v1/app/books/{id}")
    suspend fun bookDetail(@Path("id") id: Long): BookDetailDto

    // Similar books ("recommendations"); the server allows 1..25.
    @GET("api/v1/books/{id}/recommendations")
    suspend fun recommendations(@Path("id") id: Long, @Query("limit") limit: Int = 12): List<RecommendationDto>

    @GET("api/v1/app/books/{id}/progress")
    suspend fun bookProgress(@Path("id") id: Long): BookProgressResponseDto

    @PUT("api/v1/app/books/{id}/progress")
    suspend fun updateProgress(@Path("id") id: Long, @Body body: UpdateProgressRequest): Response<Unit>

    @PUT("api/v1/app/books/{id}/status")
    suspend fun updateStatus(@Path("id") id: Long, @Body body: UpdateStatusRequest): Response<Unit>

    @GET("api/v1/app/books/continue-reading")
    suspend fun continueReading(@Query("limit") limit: Int = 12): List<BookSummaryDto>

    @GET("api/v1/app/books/recently-added")
    suspend fun recentlyAdded(@Query("limit") limit: Int = 20): List<BookSummaryDto>

    @GET("api/v1/app/filter-options")
    suspend fun filterOptions(): FilterOptionsDto

    @GET("api/v1/app/series")
    suspend fun series(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 40,
        @Query("search") search: String? = null,
    ): PageResponse<SeriesSummaryDto>

    @GET("api/v1/app/series/{name}/books")
    suspend fun seriesBooks(
        @Path("name") name: String,
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 100,
    ): PageResponse<BookSummaryDto>

    @GET("api/v1/app/authors")
    suspend fun authors(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 60,
        @Query("search") search: String? = null,
    ): PageResponse<AuthorSummaryDto>

    @GET("api/v1/app/shelves")
    suspend fun appShelves(): List<ShelfSummaryDto>

    // Shelves (favourites live on a regular shelf)
    @GET("api/v1/shelves")
    suspend fun shelves(): List<ShelfDto>

    @POST("api/v1/shelves")
    suspend fun createShelf(@Body body: ShelfCreateRequest): ShelfDto

    @POST("api/v1/books/shelves")
    suspend fun assignShelves(@Body body: ShelvesAssignmentRequest): Response<ResponseBody>

    // Content
    @Streaming
    @GET("api/v1/books/{id}/content")
    suspend fun content(@Path("id") id: Long, @Query("bookType") bookType: String? = null): Response<ResponseBody>

    // Annotations & bookmarks
    @GET("api/v1/annotations/book/{bookId}")
    suspend fun annotations(@Path("bookId") bookId: Long): List<AnnotationDto>

    @POST("api/v1/annotations")
    suspend fun createAnnotation(@Body body: CreateAnnotationRequest): AnnotationDto

    @PUT("api/v1/annotations/{id}")
    suspend fun updateAnnotation(@Path("id") id: Long, @Body body: UpdateAnnotationRequest): AnnotationDto

    @DELETE("api/v1/annotations/{id}")
    suspend fun deleteAnnotation(@Path("id") id: Long): Response<Unit>

    @GET("api/v1/bookmarks/book/{bookId}")
    suspend fun bookmarks(@Path("bookId") bookId: Long): List<BookmarkDto>

    @POST("api/v1/bookmarks")
    suspend fun createBookmark(@Body body: CreateBookmarkRequest): BookmarkDto

    @DELETE("api/v1/bookmarks/{id}")
    suspend fun deleteBookmark(@Path("id") id: Long): Response<Unit>

    // Ratings & reviews (personal rating is 1..10 = half stars)
    @PUT("api/v1/books/personal-rating")
    suspend fun setPersonalRating(@Body body: PersonalRatingRequest): Response<ResponseBody>

    @POST("api/v1/books/reset-personal-rating")
    suspend fun resetPersonalRating(@Body ids: List<Long>): Response<ResponseBody>

    @GET("api/v1/reviews/book/{bookId}")
    suspend fun reviews(@Path("bookId") bookId: Long): List<BookReviewDto>

    // Reading sessions (feeds Grimmory's own statistics)
    @POST("api/v1/reading-sessions")
    suspend fun recordSession(@Body body: ReadingSessionRequest): Response<Unit>
}
