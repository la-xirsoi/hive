package hive.domain.model

/**
 * Typed identifiers, one per aggregate.
 *
 * These are inline value classes over [Long]: no allocation at runtime, but the
 * compiler will not let a [TaskId] be passed where a [UserId] is expected --
 * which is the single most common class of bug in an app whose every entity is
 * keyed by a number.
 *
 * Entities that have not been persisted yet carry `null` for their id rather
 * than a sentinel such as `0`.
 */
@JvmInline
value class UserId(val value: Long) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class TeamId(val value: Long) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class ProjectId(val value: Long) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class TaskId(val value: Long) {
    override fun toString(): String = value.toString()
}

@JvmInline
value class CommentId(val value: Long) {
    override fun toString(): String = value.toString()
}
