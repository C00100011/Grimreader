package com.vdelaar.mylibby.core.network

import kotlinx.serialization.Serializable

// DTOs mirror Grimmory's backend (org.booklore.app.dto / model.dto). Timestamps are kept as ISO strings.

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class OidcProviderDto(
    val providerName: String? = null,
    val clientId: String? = null,
    val issuerUri: String? = null,
    val scopes: String? = null,
)

@Serializable
data class PublicSettingsDto(
    val oidcEnabled: Boolean = false,
    val oidcProviderDetails: OidcProviderDto? = null,
    val oidcForceOnlyMode: Boolean = false,
)

@Serializable
data class OidcStateDto(val state: String)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class AccessTokenDto(
    val accessToken: String,
    val refreshToken: String,
    val expires: Long? = null,
    val isDefaultPassword: Boolean? = null,
)

@Serializable
data class PageResponse<T>(
    val content: List<T> = emptyList(),
    val page: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
)

@Serializable
data class BookSummaryDto(
    val id: Long,
    val title: String? = null,
    val authors: List<String>? = null,
    val thumbnailUrl: String? = null,
    val readStatus: String? = null,
    val personalRating: Int? = null,
    val seriesName: String? = null,
    val seriesNumber: Float? = null,
    val libraryId: Long? = null,
    val addedOn: String? = null,
    val lastReadTime: String? = null,
    val readProgress: Float? = null,
    val primaryFileId: Long? = null,
    val primaryFileType: String? = null,
    val primaryFileName: String? = null,
    val coverUpdatedOn: String? = null,
    val isPhysical: Boolean? = null,
    val publisher: String? = null,
    val categories: List<String>? = null,
    val tags: List<String>? = null,
    val language: String? = null,
    val publishedDate: String? = null,
    val pageCount: Int? = null,
    val fileSizeKb: Long? = null,
    val goodreadsRating: Double? = null,
)

@Serializable
data class BookReviewDto(
    val id: Long? = null,
    val metadataProvider: String? = null,
    val reviewerName: String? = null,
    val title: String? = null,
    /** Provider scale, normally 0..5. */
    val rating: Float? = null,
    val date: String? = null,
    val body: String? = null,
    val country: String? = null,
    val spoiler: Boolean? = null,
)

@Serializable
data class PersonalRatingRequest(val ids: List<Long>, val rating: Int)

@Serializable
data class ShelfSummaryDto(
    val id: Long,
    val name: String,
    val icon: String? = null,
    val bookCount: Int = 0,
    val publicShelf: Boolean = false,
)

@Serializable
data class BookFileDto(
    val id: Long,
    val bookId: Long? = null,
    val fileName: String? = null,
    val isBook: Boolean = true,
    val bookType: String? = null,
    val fileSizeKb: Long? = null,
    val extension: String? = null,
    val isPrimary: Boolean = false,
)

@Serializable
data class EpubProgressDto(
    val cfi: String? = null,
    val href: String? = null,
    val percentage: Float? = null,
    val updatedAt: String? = null,
)

@Serializable
data class KoreaderProgressDto(
    val percentage: Float? = null,
    val device: String? = null,
    val lastSyncTime: String? = null,
)

@Serializable
data class BookDetailDto(
    val id: Long,
    val title: String? = null,
    val authors: List<String>? = null,
    val thumbnailUrl: String? = null,
    val readStatus: String? = null,
    val personalRating: Int? = null,
    val seriesName: String? = null,
    val seriesNumber: Float? = null,
    val libraryId: Long? = null,
    val addedOn: String? = null,
    val lastReadTime: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
    val categories: List<String>? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val pageCount: Int? = null,
    val isbn13: String? = null,
    val language: String? = null,
    val goodreadsRating: Double? = null,
    val goodreadsReviewCount: Int? = null,
    val libraryName: String? = null,
    val shelves: List<ShelfSummaryDto>? = null,
    val readProgress: Float? = null,
    val primaryFileType: String? = null,
    val fileTypes: List<String>? = null,
    val files: List<BookFileDto>? = null,
    val coverUpdatedOn: String? = null,
    val isPhysical: Boolean? = null,
    val epubProgress: EpubProgressDto? = null,
    val koreaderProgress: KoreaderProgressDto? = null,
)

@Serializable
data class BookProgressResponseDto(
    val readProgress: Float? = null,
    val readStatus: String? = null,
    val lastReadTime: String? = null,
    val epubProgress: EpubProgressDto? = null,
)

@Serializable
data class FileProgressDto(
    val bookFileId: Long,
    val positionData: String? = null,
    val positionHref: String? = null,
    val progressPercent: Float,
    val ttsPositionCfi: String? = null,
)

@Serializable
data class UpdateProgressRequest(
    val fileProgress: FileProgressDto? = null,
    val dateFinished: String? = null,
)

@Serializable
data class UpdateStatusRequest(val status: String)

@Serializable
data class CountedOption(val name: String, val count: Long = 0)

@Serializable
data class LanguageOption(val code: String, val label: String? = null, val count: Long = 0)

@Serializable
data class FilterOptionsDto(
    val authors: List<CountedOption> = emptyList(),
    val languages: List<LanguageOption> = emptyList(),
    val readStatuses: List<CountedOption> = emptyList(),
    val fileTypes: List<CountedOption> = emptyList(),
    val categories: List<CountedOption> = emptyList(),
    val series: List<CountedOption> = emptyList(),
    val tags: List<CountedOption> = emptyList(),
    val shelves: List<CountedOption> = emptyList(),
)

@Serializable
data class SeriesCoverBookDto(val bookId: Long? = null, val coverUpdatedOn: String? = null)

@Serializable
data class SeriesSummaryDto(
    val seriesName: String,
    val bookCount: Int = 0,
    val seriesTotal: Int? = null,
    val authors: List<String>? = null,
    val booksRead: Int = 0,
    val latestAddedOn: String? = null,
    val coverBooks: List<SeriesCoverBookDto>? = null,
)

@Serializable
data class AuthorSummaryDto(
    val id: Long,
    val name: String,
    val bookCount: Int = 0,
    val hasPhoto: Boolean = false,
)

@Serializable
data class ShelfDto(
    val id: Long,
    val name: String,
    val icon: String? = null,
    val iconType: String? = null,
    val userId: Long? = null,
    val publicShelf: Boolean = false,
    val bookCount: Int = 0,
)

@Serializable
data class ShelfCreateRequest(
    val name: String,
    val icon: String? = null,
    val iconType: String? = null,
    val publicShelf: Boolean = false,
)

@Serializable
data class ShelvesAssignmentRequest(
    val bookIds: Set<Long>,
    val shelvesToAssign: Set<Long> = emptySet(),
    val shelvesToUnassign: Set<Long> = emptySet(),
)

@Serializable
data class AnnotationDto(
    val id: Long,
    val bookId: Long? = null,
    val cfi: String,
    val text: String? = null,
    val color: String? = null,
    val style: String? = null,
    val note: String? = null,
    val chapterTitle: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
)

@Serializable
data class CreateAnnotationRequest(
    val bookId: Long,
    val cfi: String,
    val text: String,
    val color: String? = null,
    val style: String? = null,
    val note: String? = null,
    val chapterTitle: String? = null,
)

@Serializable
data class UpdateAnnotationRequest(
    val color: String? = null,
    val style: String? = null,
    val note: String? = null,
)

@Serializable
data class BookmarkDto(
    val id: Long,
    val bookId: Long? = null,
    val cfi: String? = null,
    val title: String? = null,
    val color: String? = null,
    val notes: String? = null,
    val createdAt: String? = null,
)

@Serializable
data class CreateBookmarkRequest(
    val bookId: Long,
    val cfi: String,
    val title: String? = null,
)

@Serializable
data class ReadingSessionRequest(
    val bookId: Long,
    val bookType: String? = null,
    val startTime: String,
    val endTime: String,
    val durationSeconds: Int,
    val durationFormatted: String? = null,
    val startProgress: Float? = null,
    val endProgress: Float? = null,
    val progressDelta: Float? = null,
    val startLocation: String? = null,
    val endLocation: String? = null,
)

@Serializable
data class StreakDto(
    val currentStreak: Int? = null,
    val longestStreak: Int? = null,
)

// Similar books: GET /api/v1/books/{id}/recommendations -> [{ book: Book, similarityScore }].
// `book` is Grimmory's full Book DTO, so title/authors/series live under `metadata`.
@Serializable
data class RecommendationDto(
    val book: RecommendedBookDto? = null,
    val similarityScore: Double = 0.0,
)

@Serializable
data class RecommendedBookDto(
    val id: Long,
    val title: String? = null,
    val metadata: RecommendedMetadataDto? = null,
    val primaryFile: RecommendedFileDto? = null,
    val readStatus: String? = null,
    val personalRating: Int? = null,
    val addedOn: String? = null,
    val lastReadTime: String? = null,
)

@Serializable
data class RecommendedMetadataDto(
    val title: String? = null,
    val authors: List<String>? = null,
    val seriesName: String? = null,
    val seriesNumber: Float? = null,
    val categories: List<String>? = null,
    val language: String? = null,
    val pageCount: Int? = null,
    val coverUpdatedOn: String? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val description: String? = null,
)

@Serializable
data class RecommendedFileDto(
    val id: Long? = null,
    val bookType: String? = null,
)
