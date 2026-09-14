package hive.adapter.out.persistence

import hive.domain.model.Page
import hive.domain.model.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.domain.PageRequest as SpringPageRequest
import org.springframework.data.domain.Page as SpringPage

/**
 * The paging half of the adapter boundary.
 *
 * `hive.domain` declares its own [PageRequest] and [Page] so that the ports can
 * be expressed without importing Spring Data -- see `docs/architecture.md`. That
 * decision is only worth anything if the conversion happens *here*, in the
 * adapter, and never leaks the other way. These two functions are the whole of
 * it; nothing outside this package sees a [Pageable] or a Spring [SpringPage].
 */
internal object Paging {

    /**
     * Every paged query orders by ascending id.
     *
     * An unordered `LIMIT`/`OFFSET` query has no defined page boundaries: rows
     * can appear twice or not at all across pages, which looks exactly like a
     * visibility bug and is far harder to diagnose than one. Id ascending is
     * also insertion order for every table in this schema, which is the order
     * CM-2 ("oldest first") asks for.
     */
    val BY_ID: Sort = Sort.by(Sort.Direction.ASC, "id")

    /** Domain paging request -> Spring Data [Pageable], ordered by [BY_ID]. */
    fun toPageable(request: PageRequest): Pageable =
        SpringPageRequest.of(request.page, request.size, BY_ID)

    /**
     * Spring Data page of entities -> domain page of domain objects.
     *
     * The metadata is taken from [request] rather than from the Spring page so
     * that an empty trailing page still reports the size that was asked for.
     */
    fun <E : Any, D> toDomainPage(
        springPage: SpringPage<E>,
        request: PageRequest,
        map: (E) -> D,
    ): Page<D> =
        Page.of(
            content = springPage.content.map(map),
            request = request,
            totalElements = springPage.totalElements,
        )
}
