package com.leadzam.clicktocall

/** Lifecycle of a click-to-call request as seen by the phone. */
enum class RequestStatus { PENDING, OPENED, DISMISSED }

data class CallRequest(
    val requestId: String,
    val phoneNumber: String,
    val contactName: String,
    val source: String,
    val receivedAt: Long,
    val status: RequestStatus = RequestStatus.PENDING,
) {
    /** Name to show; falls back to the number when the CRM sent no name. */
    val displayName: String get() = contactName.ifBlank { phoneNumber }
}

/**
 * Turns an FCM `data` map into a [CallRequest], or null if it is not a valid
 * dial request. Pure Kotlin (no Android types) so it can be unit tested.
 *
 * The phone re-validates everything the backend already validated: FCM data is
 * just a string map and this app should never trust it blindly before building
 * a `tel:` URI from it.
 */
object DialRequestParser {
    const val TYPE_DIAL_REQUEST = "dial_request"
    private val PHONE_REGEX = Regex("^\\+?[0-9]{7,15}$")

    fun parse(data: Map<String, String>, now: Long = System.currentTimeMillis()): CallRequest? {
        if (data["type"] != TYPE_DIAL_REQUEST) return null
        val requestId = data["requestId"]?.trim().orEmpty()
        if (requestId.isEmpty() || requestId.length > 64) return null
        val phone = sanitizePhone(data["phoneNumber"]) ?: return null
        val name = data["contactName"].orEmpty().filter { !it.isISOControl() }.trim().take(100)
        val source = data["source"].orEmpty().trim().ifBlank { "LeadZam CRM" }.take(40)
        return CallRequest(requestId, phone, name, source, now)
    }

    /** Keeps digits and a leading '+', and enforces 7-15 digits. */
    fun sanitizePhone(raw: String?): String? {
        if (raw == null) return null
        val compact = raw.filter { it.isDigit() || it == '+' }
        return if (PHONE_REGEX.matches(compact)) compact else null
    }
}
