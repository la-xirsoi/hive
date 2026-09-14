package hive.domain.model

import hive.domain.error.ValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

/**
 * The domain-owned pagination types. They exist so that the ports can be
 * expressed without importing Spring Data, and their envelope matches the frozen
 * API contract: `{ content, page, size, totalElements, totalPages }`.
 */
@DisplayName("PageRequest and Page")
class PageTest {

    @Nested
    @DisplayName("PageRequest")
    inner class Requests {

        @Test
        fun `the documented default is page 0 size 50`() {
            assertEquals(0, PageRequest.DEFAULT.page)
            assertEquals(50, PageRequest.DEFAULT.size)
            assertEquals(PageRequest.DEFAULT, PageRequest())
        }

        @Test
        fun `accepts the size boundaries of 1 and 200`() {
            assertDoesNotThrow { PageRequest(0, 1) }
            assertDoesNotThrow { PageRequest(0, 200) }
        }

        @Test
        fun `rejects a size of zero and a size over 200`() {
            assertEquals("size", assertThrows<ValidationException> { PageRequest(0, 0) }.fieldErrors.single().field)
            assertThrows<ValidationException> { PageRequest(0, 201) }
            assertThrows<ValidationException> { PageRequest(0, -1) }
        }

        @Test
        fun `rejects a negative page number`() {
            assertEquals("page", assertThrows<ValidationException> { PageRequest(-1, 50) }.fieldErrors.single().field)
        }

        @Test
        fun `computes the offset for the adapter`() {
            assertEquals(0L, PageRequest(0, 50).offset)
            assertEquals(50L, PageRequest(1, 50).offset)
            assertEquals(600L, PageRequest(3, 200).offset)
        }

        @Test
        fun `the offset does not overflow at a large page number`() {
            assertEquals(2_000_000_000L * 200, PageRequest(2_000_000_000, 200).offset)
        }
    }

    @Nested
    @DisplayName("Page")
    inner class Pages {

        @Test
        fun `totalPages rounds up`() {
            assertEquals(3, Page(listOf(1), 0, 50, 123L).totalPages)
            assertEquals(2, Page(listOf(1), 0, 50, 100L).totalPages)
            assertEquals(1, Page(listOf(1), 0, 50, 1L).totalPages)
        }

        @Test
        fun `an empty result set has zero pages`() {
            assertEquals(0, Page<Int>(emptyList(), 0, 50, 0L).totalPages)
            assertTrue(Page.empty<Int>(PageRequest.DEFAULT).isEmpty)
            assertEquals(0L, Page.empty<Int>(PageRequest.DEFAULT).totalElements)
        }

        @Test
        fun `of carries the request's paging metadata`() {
            val page = Page.of(listOf("a", "b"), PageRequest(2, 10), totalElements = 25L)

            assertEquals(2, page.page)
            assertEquals(10, page.size)
            assertEquals(25L, page.totalElements)
            assertEquals(3, page.totalPages)
            assertFalse(page.isEmpty)
        }

        @Test
        fun `map converts the content and keeps the metadata, which is how adapters build DTOs`() {
            val page = Page.of(listOf(1, 2, 3), PageRequest(1, 3), totalElements = 9L)

            val mapped = page.map { it * 2 }

            assertEquals(listOf(2, 4, 6), mapped.content)
            assertEquals(1, mapped.page)
            assertEquals(3, mapped.size)
            assertEquals(9L, mapped.totalElements)
            assertEquals(3, mapped.totalPages)
        }

        @Test
        fun `pages compare structurally`() {
            assertEquals(Page(listOf(1), 0, 50, 1L), Page(listOf(1), 0, 50, 1L))
            assertEquals(Page(listOf(1), 0, 50, 1L).hashCode(), Page(listOf(1), 0, 50, 1L).hashCode())
        }
    }
}
