package hive.domain.model

import hive.domain.error.ValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Boundary tests for the validating value objects: minimum, maximum, one over
 * the maximum, blank, and malformed. A value object that is wrong at its edges
 * is wrong everywhere, because nothing downstream re-checks it.
 */
@DisplayName("value objects")
class ValueObjectsTest {

    @Nested
    @DisplayName("PersonName (US-1: 1..200)")
    inner class PersonNames {

        @Test
        fun `accepts the minimum length of one character`() {
            assertEquals("a", PersonName("a").value)
        }

        @Test
        fun `accepts the maximum length of 200 characters`() {
            val max = "n".repeat(200)
            assertEquals(max, PersonName(max).value)
        }

        @Test
        fun `rejects 201 characters`() {
            val thrown = assertThrows<ValidationException> { PersonName("n".repeat(201)) }
            assertEquals(listOf("name"), thrown.fieldErrors.map { it.field })
        }

        @Test
        fun `rejects an empty name`() {
            assertThrows<ValidationException> { PersonName("") }
        }

        @ParameterizedTest
        @ValueSource(strings = ["   ", "\t", "\n", " \t\n "])
        fun `rejects a blank name`(blank: String) {
            val thrown = assertThrows<ValidationException> { PersonName(blank) }
            assertTrue(thrown.message!!.contains("blank"))
        }

        @Test
        fun `trims surrounding whitespace, and length is measured after trimming`() {
            assertEquals("Ada Lovelace", PersonName("  Ada Lovelace  ").value)
            assertDoesNotThrow { PersonName("  ${"n".repeat(200)}  ") }
        }

        @Test
        fun `compares by value`() {
            assertEquals(PersonName("Ada"), PersonName(" Ada "))
            assertEquals(PersonName("Ada").hashCode(), PersonName("Ada").hashCode())
            assertNotEquals(PersonName("Ada"), PersonName("Grace"))
            assertEquals("Ada", PersonName("Ada").toString())
        }
    }

    @Nested
    @DisplayName("EmailAddress (US-2: 5..254, valid format, case-insensitive)")
    inner class Emails {

        @Test
        fun `accepts the minimum length of five characters`() {
            assertEquals("a@b.c", EmailAddress("a@b.c").value)
        }

        @Test
        fun `accepts the maximum length of 254 characters`() {
            val max = "a".repeat(242) + "@example.com" // 242 + 1 + 11 = 254
            assertEquals(254, max.length)
            assertEquals(max, EmailAddress(max).value)
        }

        @Test
        fun `rejects 255 characters`() {
            val tooLong = "a".repeat(243) + "@example.com"
            assertEquals(255, tooLong.length)
            val thrown = assertThrows<ValidationException> { EmailAddress(tooLong) }
            assertEquals(listOf("email"), thrown.fieldErrors.map { it.field })
        }

        @Test
        fun `rejects an address shorter than five characters`() {
            assertThrows<ValidationException> { EmailAddress("a@b") }
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "not-an-email",
                "@example.com",
                "user@",
                "user@@example.com",
                "user@example",
                "user name@example.com",
                "user@exam ple.com",
                ".user@example.com",
                "user.@example.com",
                "us..er@example.com",
                "user@-example.com",
                "user@example-.com",
                "user@.example.com",
                "user@example..com",
                "user@example.com.",
            ],
        )
        fun `rejects malformed addresses`(malformed: String) {
            val thrown = assertThrows<ValidationException> { EmailAddress(malformed) }
            assertEquals("email", thrown.fieldErrors.single().field)
        }

        @ParameterizedTest
        @ValueSource(
            strings = [
                "user@example.com",
                "first.last@example.co.uk",
                "user+tag@example.com",
                "user_name@example.com",
                "user-name@sub.example.com",
                "u5er%odd@example.io",
            ],
        )
        fun `accepts a pragmatic subset of RFC 5322`(valid: String) {
            assertDoesNotThrow { EmailAddress(valid) }
        }

        @Test
        fun `rejects a blank address`() {
            assertThrows<ValidationException> { EmailAddress("     ") }
        }

        @Test
        fun `trims surrounding whitespace`() {
            assertEquals("user@example.com", EmailAddress("  user@example.com  ").value)
        }

        @Test
        fun `US-2 equality and therefore uniqueness is case-insensitive`() {
            assertEquals(EmailAddress("A@X.COM"), EmailAddress("a@x.com"))
            assertEquals(EmailAddress("A@X.COM").hashCode(), EmailAddress("a@x.com").hashCode())
        }

        @Test
        fun `preserves the original casing for display but normalises for comparison`() {
            val email = EmailAddress("Ada.Lovelace@Example.COM")

            assertEquals("Ada.Lovelace@Example.COM", email.value)
            assertEquals("ada.lovelace@example.com", email.normalized)
            assertEquals("Ada.Lovelace@Example.COM", email.toString())
        }

        @Test
        fun `different addresses are not equal`() {
            assertNotEquals(EmailAddress("a@x.com"), EmailAddress("b@x.com"))
        }
    }

    @Nested
    @DisplayName("TeamName, ProjectName and TaskName (1..200)")
    inner class SimpleNames {

        @Test
        fun `accept one character and reject zero`() {
            assertDoesNotThrow { TeamName("a") }
            assertDoesNotThrow { ProjectName("a") }
            assertDoesNotThrow { TaskName("a") }
            assertThrows<ValidationException> { TeamName("") }
            assertThrows<ValidationException> { ProjectName("") }
            assertThrows<ValidationException> { TaskName("") }
        }

        @Test
        fun `accept 200 characters and reject 201`() {
            val max = "x".repeat(200)
            val over = "x".repeat(201)

            assertDoesNotThrow { TeamName(max) }
            assertDoesNotThrow { ProjectName(max) }
            assertDoesNotThrow { TaskName(max) }
            assertThrows<ValidationException> { TeamName(over) }
            assertThrows<ValidationException> { ProjectName(over) }
            assertThrows<ValidationException> { TaskName(over) }
        }

        @Test
        fun `reject blank input`() {
            assertThrows<ValidationException> { TeamName("  ") }
            assertThrows<ValidationException> { ProjectName("\t") }
            assertThrows<ValidationException> { TaskName("\n") }
        }

        @Test
        fun `trim and compare by value`() {
            assertEquals(TeamName("Hive"), TeamName(" Hive "))
            assertEquals(ProjectName("Apiary"), ProjectName("Apiary "))
            assertEquals(TaskName("Requeen"), TaskName(" Requeen"))
            assertNotEquals(TaskName("Requeen"), TaskName("Rehive"))
            assertEquals("Hive", TeamName("Hive").toString())
            assertEquals("Apiary", ProjectName("Apiary").toString())
            assertEquals("Requeen", TaskName("Requeen").toString())
            assertEquals(TeamName("Hive").hashCode(), TeamName("Hive").hashCode())
            assertEquals(ProjectName("A").hashCode(), ProjectName("A").hashCode())
            assertEquals(TaskName("A").hashCode(), TaskName("A").hashCode())
        }
    }

    @Nested
    @DisplayName("TaskDescription (0..4000, may be empty)")
    inner class Descriptions {

        @Test
        fun `accepts an empty description`() {
            assertEquals("", TaskDescription("").value)
            assertTrue(TaskDescription("").isEmpty)
            assertTrue(TaskDescription.EMPTY.isEmpty)
        }

        @Test
        fun `accepts 4000 characters`() {
            val max = "d".repeat(4000)
            assertEquals(max, TaskDescription(max).value)
        }

        @Test
        fun `rejects 4001 characters`() {
            val thrown = assertThrows<ValidationException> { TaskDescription("d".repeat(4001)) }
            assertEquals("description", thrown.fieldErrors.single().field)
        }

        @Test
        fun `preserves whitespace, because indentation in a description is content`() {
            val text = "  line one\n    line two  "
            assertEquals(text, TaskDescription(text).value)
            assertFalse(TaskDescription("   ").isEmpty)
        }

        @Test
        fun `compares by value`() {
            assertEquals(TaskDescription("x"), TaskDescription("x"))
            assertEquals(TaskDescription("x").hashCode(), TaskDescription("x").hashCode())
            assertNotEquals(TaskDescription("x"), TaskDescription("y"))
            assertEquals("x", TaskDescription("x").toString())
        }
    }

    @Nested
    @DisplayName("CommentContent (1..4000, not blank)")
    inner class CommentContents {

        @Test
        fun `accepts one character`() {
            assertEquals("!", CommentContent("!").value)
        }

        @Test
        fun `accepts 4000 characters`() {
            val max = "c".repeat(4000)
            assertEquals(max, CommentContent(max).value)
        }

        @Test
        fun `rejects 4001 characters`() {
            assertThrows<ValidationException> { CommentContent("c".repeat(4001)) }
        }

        @Test
        fun `rejects empty and blank content`() {
            assertThrows<ValidationException> { CommentContent("") }
            assertThrows<ValidationException> { CommentContent("   \t\n ") }
        }

        @Test
        fun `trims and compares by value`() {
            assertEquals(CommentContent("Nice work"), CommentContent("  Nice work  "))
            assertEquals(CommentContent("a").hashCode(), CommentContent("a").hashCode())
            assertNotEquals(CommentContent("a"), CommentContent("b"))
            assertEquals("a", CommentContent("a").toString())
        }
    }

    @Nested
    @DisplayName("typed identifiers")
    inner class Identifiers {

        @Test
        fun `carry their value and print it plainly`() {
            assertEquals(7L, UserId(7).value)
            assertEquals("7", UserId(7).toString())
            assertEquals("8", TeamId(8).toString())
            assertEquals("9", ProjectId(9).toString())
            assertEquals("10", TaskId(10).toString())
            assertEquals("11", CommentId(11).toString())
        }

        @Test
        fun `compare by value`() {
            assertEquals(UserId(1), UserId(1))
            assertNotEquals(UserId(1), UserId(2))
        }
    }
}
