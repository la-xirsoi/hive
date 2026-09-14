package hive.domain.model

import hive.domain.error.ValidationException

/**
 * A request for one page of results.
 *
 * Domain-owned on purpose: the ports are expressed in domain types, so the
 * domain must not depend on Spring Data's `Pageable`. The persistence adapter
 * converts at its own boundary.
 */
data class PageRequest(
    val page: Int = DEFAULT_PAGE,
    val size: Int = DEFAULT_SIZE,
) {
    init {
        if (page < 0) {
            throw ValidationException("page", "must be zero or greater.")
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw ValidationException("size", "must be between $MIN_SIZE and $MAX_SIZE.")
        }
    }

    /** Index of the first element of this page across the whole result set. */
    val offset: Long get() = page.toLong() * size

    companion object {
        const val DEFAULT_PAGE: Int = 0
        const val DEFAULT_SIZE: Int = 50
        const val MIN_SIZE: Int = 1
        const val MAX_SIZE: Int = 200

        /** `page=0&size=50`, the documented default of every paged endpoint. */
        val DEFAULT: PageRequest = PageRequest(DEFAULT_PAGE, DEFAULT_SIZE)
    }
}

/**
 * One page of results plus the counts the API envelope needs.
 *
 * `{ content, page, size, totalElements, totalPages }` is exactly the shape the
 * frozen API contract publishes.
 */
data class Page<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
) {
    /** Total number of pages at this page size; zero when there are no results. */
    val totalPages: Int
        get() = if (size <= 0 || totalElements <= 0L) 0 else ((totalElements + size - 1) / size).toInt()

    val isEmpty: Boolean get() = content.isEmpty()

    /** Convert the elements, keeping the paging metadata. Used by adapters to map to DTOs. */
    fun <R> map(transform: (T) -> R): Page<R> =
        Page(content.map(transform), page, size, totalElements)

    companion object {
        /** An empty page answering [request]. */
        fun <T> empty(request: PageRequest): Page<T> =
            Page(emptyList(), request.page, request.size, 0L)

        /** A page holding [content] as the answer to [request], out of [totalElements] in total. */
        fun <T> of(content: List<T>, request: PageRequest, totalElements: Long): Page<T> =
            Page(content, request.page, request.size, totalElements)
    }
}
