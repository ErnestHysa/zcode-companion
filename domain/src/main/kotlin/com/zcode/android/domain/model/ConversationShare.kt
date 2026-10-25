package com.zcode.android.domain.model

/**
 * A3.16 + L6.1 (gaps-and-polish Phase 5C): the conversation-share typed error taxonomy —
 * the desktop's share client maps HTTP codes to these kinds (host/index.js ≈1215865:
 * `3001:"invalid_contract" … 3215:"safety_check_pending"`) plus the two local kinds
 * (`feature_disabled` is what the host's rejectMobileShare stub throws for EVERY
 * import/publish call from a web-remote-replayable client — PROBE-L6). The phone's
 * share-in surface renders these verbatim; share-OUT is the Android system sheet
 * (the delivery mechanism L6.1 names), which needs no desktop share service.
 */
object ConversationShareErrors {
    const val INVALID_CONTRACT = "invalid_contract"
    const val RATE_LIMITED = "rate_limited"
    const val FEATURE_DISABLED = "feature_disabled"
    const val AUTHENTICATION_REQUIRED = "authentication_required"
    const val INVALID_CONVERSATION = "invalid_conversation"
    const val DISCLOSURE_REQUIRED = "disclosure_required"
    const val UNSAFE_STRUCTURE = "unsafe_structure"
    const val ARTIFACT_NOT_ALLOWED = "artifact_not_allowed"
    const val LIMIT_EXCEEDED = "limit_exceeded"
    const val UPLOAD_INCOMPLETE = "upload_incomplete"
    const val NOT_FOUND = "not_found"
    const val EXPIRED = "expired"
    const val IMPORT_NOT_ALLOWED = "import_not_allowed"
    const val SAFETY_CHECK_PENDING = "safety_check_pending"
    const val CONNECTION_UNAVAILABLE = "connection_unavailable"

    /** code → desktop copy key suffix (chat shareDialog.errors.*), surfaced verbatim. */
    val ALL: Set<String> = setOf(
        INVALID_CONTRACT, RATE_LIMITED, FEATURE_DISABLED, AUTHENTICATION_REQUIRED,
        INVALID_CONVERSATION, DISCLOSURE_REQUIRED, UNSAFE_STRUCTURE, ARTIFACT_NOT_ALLOWED,
        LIMIT_EXCEEDED, UPLOAD_INCOMPLETE, NOT_FOUND, EXPIRED, IMPORT_NOT_ALLOWED,
        SAFETY_CHECK_PENDING, CONNECTION_UNAVAILABLE,
    )
}
