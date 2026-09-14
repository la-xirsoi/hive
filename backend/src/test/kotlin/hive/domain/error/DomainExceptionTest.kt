package hive.domain.error

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The exception hierarchy the REST layer translates into status codes.
 *
 * The important properties are that every domain failure is a [DomainException]
 * (so the error handler can catch one type), that the four subtypes are
 * distinguishable (so it can pick the right status), and that messages carry no
 * implementation detail.
 */
@DisplayName("DomainException hierarchy")
class DomainExceptionTest {

    @Test
    fun `every domain failure is a DomainException and therefore a RuntimeException`() {
        val all: List<DomainException> =
            listOf(
                ValidationException("name", "must not be blank."),
                NotFoundException("Task", 42L),
                ConflictException("The task is already Completed."),
                AuthorizationException("Only the project owner may do that."),
            )

        all.forEach { assertInstanceOf(RuntimeException::class.java, it) }
        assertEquals(4, all.size)
    }

    @Test
    fun `400 ValidationException carries per-field detail`() {
        val thrown =
            ValidationException(
                listOf(
                    FieldError("name", "must not be blank."),
                    FieldError("email", "must be a valid email address."),
                ),
            )

        assertEquals(listOf("name", "email"), thrown.fieldErrors.map { it.field })
        assertTrue(thrown.message!!.contains("name: must not be blank."))
        assertTrue(thrown.message!!.contains("email: must be a valid email address."))
    }

    @Test
    fun `400 ValidationException has a single-field convenience constructor`() {
        val thrown = ValidationException("size", "must be between 1 and 200.")

        assertEquals(FieldError("size", "must be between 1 and 200."), thrown.fieldErrors.single())
    }

    @Test
    fun `400 ValidationException with no field errors still has a message`() {
        assertEquals("Validation failed.", ValidationException(emptyList()).message)
    }

    @Test
    fun `404 NotFoundException names the resource and the id it was asked for`() {
        val thrown = NotFoundException("Task", 42L)

        assertEquals("Task", thrown.resource)
        assertEquals(42L, thrown.id)
        assertEquals("Task 42 was not found.", thrown.message)
    }

    @Test
    fun `404 NotFoundException copes with a null id`() {
        assertEquals("Team was not found.", NotFoundException("Team", null).message)
    }

    @Test
    fun `409 ConflictException and 403 AuthorizationException carry their message verbatim`() {
        assertEquals("The task is already Completed.", ConflictException("The task is already Completed.").message)
        assertEquals("Not allowed.", AuthorizationException("Not allowed.").message)
    }

    @Test
    fun `messages are end-user readable and leak no implementation detail`() {
        val messages =
            listOf(
                ValidationException("name", "must not be blank.").message!!,
                NotFoundException("Task", 42L).message!!,
                ConflictException("A Completed task cannot be edited.").message!!,
                AuthorizationException("Only the project owner may rename this project.").message!!,
            )

        val forbidden = listOf("select ", "sql", "hibernate", "org.", "jakarta", "exception", "kotlin.")
        messages.forEach { message ->
            forbidden.forEach { needle ->
                assertFalse(
                    message.contains(needle, ignoreCase = true),
                    "\"$message\" should not mention \"$needle\"",
                )
            }
        }
    }

    @Test
    fun `there is deliberately no domain exception for 401`() {
        // Authentication is a security-adapter concern: the domain never sees an
        // unauthenticated actor, so it has nothing to say about one. This test
        // documents the omission so it is not "fixed" by accident.
        val subclassNames =
            DomainException::class.sealedSubclasses.mapNotNull { it.simpleName }.toSet()

        assertEquals(
            setOf("ValidationException", "NotFoundException", "ConflictException", "AuthorizationException"),
            subclassNames,
        )
    }
}
