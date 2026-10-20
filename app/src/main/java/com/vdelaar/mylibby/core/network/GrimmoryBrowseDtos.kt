package com.vdelaar.mylibby.core.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The regular Grimmory API ("browse" endpoints under /api/v1/books, /api/v1/authors): this is what replaces the
// /api/v1/app routes that Grimmory 4.0 removes. Shapes mirror org.booklore.browse / org.booklore.model.dto.

@Serializable
data class BrowsePageDto<T>(
    val content: List<T> = emptyList(),
    val page: BrowsePageMetaDto = BrowsePageMetaDto(),
    val links: List<BrowseLinkDto> = emptyList(),
)

@Serializable
data class BrowsePageMetaDto(
    val number: Int = 0,
    val size: Int = 0,
    val totalElements: Long = 0,
    val totalPages: Int = 0,
    val cursor: String? = null,
)

/** `rel` is one string or an array of strings, depending on the link. */
@Serializable
data class BrowseLinkDto(
    val rel: kotlinx.serialization.json.JsonElement? = null,
    val href: String? = null,
    val title: String? = null,
    val value: String? = null,
    val properties: BrowseLinkPropertiesDto? = null,
) {
    fun hasRel(name: String): Boolean = when (val r = rel) {
        is kotlinx.serialization.json.JsonPrimitive -> r.content == name
        is kotlinx.serialization.json.JsonArray -> r.any { (it as? kotlinx.serialization.json.JsonPrimitive)?.content == name }
        else -> false
    }
}

@Serializable
data class BrowseLinkPropertiesDto(val numberOfItems: Long? = null)

@Serializable
data class FacetGroupsDto(
    val links: List<BrowseLinkDto> = emptyList(),
    val facets: List<FacetGroupDto> = emptyList(),
)

@Serializable
data class FacetGroupDto(
    val metadata: FacetMetadataDto = FacetMetadataDto(),
    val links: List<BrowseLinkDto> = emptyList(),
)

@Serializable
data class FacetMetadataDto(val key: String? = null, val title: String? = null)

/** Grimmory's full Book DTO (`GET /books/page`, `GET /books/{id}`). */
@Serializable
data class BookDto(
    val id: Long,
    val libraryId: Long? = null,
    val libraryName: String? = null,
    val primaryFile: ServerFileDto? = null,
    val title: String? = null,
    val lastReadTime: String? = null,
    val addedOn: String? = null,
    val metadata: BookMetadataDto? = null,
    val epubProgress: ServerProgressDto? = null,
    val pdfProgress: ServerProgressDto? = null,
    val cbxProgress: ServerProgressDto? = null,
    val audiobookProgress: ServerProgressDto? = null,
    val koreaderProgress: ServerProgressDto? = null,
    val koboProgress: ServerProgressDto? = null,
    val personalRating: Int? = null,
    val shelves: List<ShelfSummaryDto>? = null,
    val readStatus: String? = null,
    val isPhysical: Boolean? = null,
    val alternativeFormats: List<ServerFileDto>? = null,
)

@Serializable
data class BookMetadataDto(
    val title: String? = null,
    val subtitle: String? = null,
    val publisher: String? = null,
    val publishedDate: String? = null,
    val description: String? = null,
    val seriesName: String? = null,
    val seriesNumber: Float? = null,
    val seriesTotal: Int? = null,
    val isbn13: String? = null,
    val pageCount: Int? = null,
    val language: String? = null,
    val goodreadsRating: Double? = null,
    val goodreadsReviewCount: Int? = null,
    val coverUpdatedOn: String? = null,
    val authors: List<String>? = null,
    val categories: List<String>? = null,
    val tags: List<String>? = null,
)

@Serializable
data class ServerFileDto(
    val id: Long,
    val bookId: Long? = null,
    val fileName: String? = null,
    /** Lombok serialises `isBook` as `book`. */
    @SerialName("book") val isBook: Boolean = true,
    val bookType: String? = null,
    val fileSizeKb: Long? = null,
    val extension: String? = null,
)

/** One progress object of the Book DTO (epub, pdf, cbx, audiobook, KOReader, Kobo): only some fields exist on each. */
@Serializable
data class ServerProgressDto(
    val cfi: String? = null,
    val href: String? = null,
    val percentage: Float? = null,
)

@Serializable
data class ReadProgressRequest(
    val bookId: Long,
    val fileProgress: FileProgressDto? = null,
    val dateFinished: String? = null,
)

@Serializable
data class ReadStatusRequest(val bookIds: List<Long>, val status: String)
