package hive.domain.model

import hive.domain.error.ValidationException

/**
 * The five task statuses.
 *
 * [wireName] is the exact string used in `spec.md`, in the REST payloads and in
 * the database; the enum constant is the Kotlin-idiomatic spelling. The two are
 * kept apart deliberately so that renaming a constant cannot silently change
 * the wire format.
 */
enum class TaskStatus(val wireName: String) {
    DRAFT("Draft"),
    TODO("Todo"),
    IN_PROGRESS("In Progress"),
    COMPLETED("Completed"),
    CANCELED("Canceled"),
    ;

    /** Completed and Canceled admit no further transitions and no edits. */
    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELED

    override fun toString(): String = wireName

    companion object {
        /** The status every task starts in (TK-2). */
        val DEFAULT: TaskStatus = DRAFT

        private val BY_WIRE_NAME: Map<String, TaskStatus> = entries.associateBy { it.wireName }

        /**
         * Parse the exact wire spelling.
         *
         * @throws ValidationException if [wireName] is not one of the five.
         */
        fun fromWireName(wireName: String): TaskStatus =
            BY_WIRE_NAME[wireName]
                ?: throw ValidationException(
                    "status",
                    "must be one of ${entries.joinToString(", ") { it.wireName }}.",
                )

        /** Parse leniently, returning `null` rather than throwing. */
        fun fromWireNameOrNull(wireName: String): TaskStatus? = BY_WIRE_NAME[wireName]
    }
}
