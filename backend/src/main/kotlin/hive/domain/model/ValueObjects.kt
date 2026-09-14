package hive.domain.model

import hive.domain.error.ValidationException

/**
 * Validating value objects.
 *
 * Every one of these validates in its `init` block and throws a
 * [ValidationException] naming the offending field. Once constructed, an
 * instance is known-good everywhere else in the system, which is why nothing
 * downstream re-validates strings.
 */

/** Shared length/blankness check used by the simple trimmed-text value objects. */
private fun requireTrimmedText(field: String, raw: String, min: Int, max: Int): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) {
        throw ValidationException(field, "must not be blank.")
    }
    if (trimmed.length < min || trimmed.length > max) {
        throw ValidationException(field, "must be between $min and $max characters.")
    }
    return trimmed
}

/** A person's display name. Trimmed, 1..200 characters, not blank. */
class PersonName(raw: String) {
    val value: String = requireTrimmedText(FIELD, raw, MIN_LENGTH, MAX_LENGTH)

    override fun equals(other: Any?): Boolean = this === other || (other is PersonName && other.value == value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        const val FIELD: String = "name"
        const val MIN_LENGTH: Int = 1
        const val MAX_LENGTH: Int = 200
    }
}

/**
 * An email address.
 *
 * Trimmed, 5..254 characters, and matching a pragmatic subset of RFC 5322.
 * Equality -- and therefore uniqueness -- is **case-insensitive**: uniqueness
 * that treats `A@x.com` and `a@x.com` as different addresses is a defect, not a
 * feature. The original casing is preserved in [value] for display; [normalized]
 * is the lowercased form used for comparison and for the database's unique key.
 */
class EmailAddress(raw: String) {
    val value: String
    val normalized: String

    init {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            throw ValidationException(FIELD, "must not be blank.")
        }
        if (trimmed.length < MIN_LENGTH || trimmed.length > MAX_LENGTH) {
            throw ValidationException(FIELD, "must be between $MIN_LENGTH and $MAX_LENGTH characters.")
        }
        if (!PATTERN.matches(trimmed)) {
            throw ValidationException(FIELD, "must be a valid email address.")
        }
        value = trimmed
        normalized = trimmed.lowercase()
    }

    override fun equals(other: Any?): Boolean =
        this === other || (other is EmailAddress && other.normalized == normalized)

    override fun hashCode(): Int = normalized.hashCode()

    override fun toString(): String = value

    companion object {
        const val FIELD: String = "email"
        const val MIN_LENGTH: Int = 5
        const val MAX_LENGTH: Int = 254

        /**
         * Pragmatic RFC 5322 subset: a dot-separated local part, an `@`, and a
         * dotted domain whose labels neither start nor end with a hyphen.
         * Deliberately not the full grammar -- quoted local parts and address
         * literals are rejected, which is the right trade for an app directory.
         */
        private val PATTERN =
            Regex(
                "^[A-Za-z0-9_%+-]+(\\.[A-Za-z0-9_%+-]+)*" +
                    "@[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?" +
                    "(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)+$",
            )
    }
}

/** A team's name. Trimmed, 1..200 characters, not blank. */
class TeamName(raw: String) {
    val value: String = requireTrimmedText(FIELD, raw, MIN_LENGTH, MAX_LENGTH)

    override fun equals(other: Any?): Boolean = this === other || (other is TeamName && other.value == value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        const val FIELD: String = "name"
        const val MIN_LENGTH: Int = 1
        const val MAX_LENGTH: Int = 200
    }
}

/** A project's name. Trimmed, 1..200 characters, not blank. */
class ProjectName(raw: String) {
    val value: String = requireTrimmedText(FIELD, raw, MIN_LENGTH, MAX_LENGTH)

    override fun equals(other: Any?): Boolean = this === other || (other is ProjectName && other.value == value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        const val FIELD: String = "name"
        const val MIN_LENGTH: Int = 1
        const val MAX_LENGTH: Int = 200
    }
}

/** A task's name. Trimmed, 1..200 characters, not blank. */
class TaskName(raw: String) {
    val value: String = requireTrimmedText(FIELD, raw, MIN_LENGTH, MAX_LENGTH)

    override fun equals(other: Any?): Boolean = this === other || (other is TaskName && other.value == value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        const val FIELD: String = "name"
        const val MIN_LENGTH: Int = 1
        const val MAX_LENGTH: Int = 200
    }
}

/**
 * A task's description. 0..4000 characters -- it may be empty, but never null.
 *
 * Unlike the name types this does not trim: indentation and line breaks in a
 * description are content, not noise.
 */
class TaskDescription(raw: String) {
    val value: String

    init {
        if (raw.length > MAX_LENGTH) {
            throw ValidationException(FIELD, "must be at most $MAX_LENGTH characters.")
        }
        value = raw
    }

    val isEmpty: Boolean get() = value.isEmpty()

    override fun equals(other: Any?): Boolean = this === other || (other is TaskDescription && other.value == value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        const val FIELD: String = "description"
        const val MIN_LENGTH: Int = 0
        const val MAX_LENGTH: Int = 4000

        /** The description of a task created without one. */
        val EMPTY: TaskDescription = TaskDescription("")
    }
}

/** The body of a comment. Trimmed, 1..4000 characters, not blank. */
class CommentContent(raw: String) {
    val value: String = requireTrimmedText(FIELD, raw, MIN_LENGTH, MAX_LENGTH)

    override fun equals(other: Any?): Boolean = this === other || (other is CommentContent && other.value == value)

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        const val FIELD: String = "content"
        const val MIN_LENGTH: Int = 1
        const val MAX_LENGTH: Int = 4000
    }
}
