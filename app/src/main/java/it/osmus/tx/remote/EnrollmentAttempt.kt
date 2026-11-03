package it.osmus.tx.remote

/** A single pending RX approval; claim is held only by the foreground service. */
internal data class EnrollmentAttempt(
    val id: String,
    val claim: String,
    val code: String,
    val expiresAtMillis: Long
)

/** Temporary errors must not create duplicate requests on RX. */
internal object EnrollmentRetryPolicy {
    fun mustRestartRequest(message: String?): Boolean {
        val detail = message.orEmpty()
        return detail.startsWith("HTTP 404") || detail.startsWith("HTTP 410")
    }
}
