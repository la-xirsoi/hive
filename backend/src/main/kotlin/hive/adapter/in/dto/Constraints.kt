package hive.adapter.`in`.dto

/**
 * The edge constraints of `docs/api-contract.md` section 8.
 *
 * These are **mirrors** of the domain value objects' own rules, not a second
 * source of truth: [hive.domain.model.PersonName] and friends still validate
 * everything that reaches them, and a request that slipped past these
 * annotations would be rejected one layer down with the same message. They
 * exist so that a malformed body fails at the edge with per-field detail the
 * client can attach to a form control, instead of as a single flat message.
 *
 * The numbers and the wording are deliberately copied from the value objects so
 * that a client sees one vocabulary regardless of which layer answered.
 */

internal const val NAME_MIN: Int = 1
internal const val NAME_MAX: Int = 200
internal const val EMAIL_MIN: Int = 5
internal const val EMAIL_MAX: Int = 254
internal const val DESCRIPTION_MAX: Int = 4000
internal const val CONTENT_MIN: Int = 1
internal const val CONTENT_MAX: Int = 4000

internal const val MSG_REQUIRED: String = "is required."
internal const val MSG_NOT_BLANK: String = "must not be blank."
internal const val MSG_NAME_SIZE: String = "must be between 1 and 200 characters."
internal const val MSG_EMAIL_SIZE: String = "must be between 5 and 254 characters."
internal const val MSG_EMAIL_FORMAT: String = "must be a valid email address."
internal const val MSG_DESCRIPTION_SIZE: String = "must be at most 4000 characters."
internal const val MSG_CONTENT_SIZE: String = "must be between 1 and 4000 characters."
