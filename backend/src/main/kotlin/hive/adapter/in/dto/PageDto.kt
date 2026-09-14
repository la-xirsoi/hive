package hive.adapter.`in`.dto

import hive.domain.model.PageRequest

/**
 * The pagination envelope of `docs/api-contract.md` section 1.3.
 *
 * `{ content, page, size, totalElements, totalPages }` -- the same five fields
 * the domain's [hive.domain.model.Page] already carries, restated here so that
 * a change to the wire format never reaches back into the domain.
 */
data class PageResponse<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

/**
 * Build the domain's page request from the contract's query parameters.
 *
 * The bounds (`page >= 0`, `1 <= size <= 200`) are [PageRequest]'s own and are
 * enforced by its constructor, so an out-of-range `?size=0` surfaces as the
 * contract's 400 with a `size` field error -- the same answer the domain would
 * give any other adapter.
 */
fun pageRequestOf(page: Int, size: Int): PageRequest = PageRequest(page = page, size = size)
