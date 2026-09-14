package hive.adapter.`in`

/**
 * Inbound adapter layer (Hexagonal Architecture "driving" side).
 *
 * Contents: REST controllers and their request/response DTOs.
 *
 * NOTE ON THE PACKAGE NAME: `in` is a Kotlin hard keyword, so every package
 * declaration (and every import) for this package and its sub-packages must
 * escape that segment with backticks:
 *
 *     package hive.adapter.`in`.model
 *     import hive.adapter.`in`.PingController
 *
 * The directory on disk is plain `in`; only the Kotlin source token is escaped.
 * The package name is mandated by the project spec -- do not rename it.
 */
internal const val LAYER: String = "adapter.in"
