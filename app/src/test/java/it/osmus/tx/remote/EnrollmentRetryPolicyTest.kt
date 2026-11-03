package it.osmus.tx.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrollmentRetryPolicyTest {
    @Test fun transientErrorsKeepTheSameApprovalRequest() {
        for (message in listOf("HTTP 429", "HTTP 500", "timeout", "connection reset", "HTTP 503")) {
            assertFalse("Should preserve original request: $message",
                EnrollmentRetryPolicy.mustRestartRequest(message))
        }
    }

    @Test fun expiredOrGoneApprovalRequiresANewRequest() {
        assertTrue(EnrollmentRetryPolicy.mustRestartRequest("HTTP 410"))
        assertTrue(EnrollmentRetryPolicy.mustRestartRequest("HTTP 404"))
    }
}
