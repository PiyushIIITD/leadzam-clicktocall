package com.leadzam.clicktocall

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DialRequestParserTest {
    private fun payload(vararg extra: Pair<String, String>) = mapOf(
        "type" to "dial_request",
        "requestId" to "r-1",
        "phoneNumber" to "+919876543210",
        "contactName" to "Rahul Sharma",
        "source" to "LeadZam CRM",
    ) + extra

    @Test fun parsesValidPayload() {
        val r = DialRequestParser.parse(payload(), now = 5L)
        assertNotNull(r)
        assertEquals("+919876543210", r!!.phoneNumber)
        assertEquals("Rahul Sharma", r.contactName)
        assertEquals("LeadZam CRM", r.source)
        assertEquals(5L, r.receivedAt)
    }

    @Test fun ignoresOtherMessageTypes() {
        assertNull(DialRequestParser.parse(payload("type" to "something_else")))
    }

    @Test fun rejectsMissingOrInvalidPhone() {
        assertNull(DialRequestParser.parse(payload("phoneNumber" to "")))
        assertNull(DialRequestParser.parse(payload("phoneNumber" to "abc")))
        assertNull(DialRequestParser.parse(payload("phoneNumber" to "123")))
        assertNull(DialRequestParser.parse(payload("phoneNumber" to "1".repeat(16))))
    }

    @Test fun rejectsMissingRequestId() {
        assertNull(DialRequestParser.parse(payload("requestId" to " ")))
    }

    @Test fun contactNameIsOptionalAndFallsBackToNumber() {
        val r = DialRequestParser.parse(payload("contactName" to ""))!!
        assertEquals("", r.contactName)
        assertEquals("+919876543210", r.displayName)
    }

    @Test fun sanitizesFormattingFromNumber() {
        assertEquals("+919876543210", DialRequestParser.sanitizePhone("+91 98765-43210"))
    }

    @Test fun stripsControlCharsFromName() {
        val r = DialRequestParser.parse(payload("contactName" to "A\u0000B\n"))!!
        assertEquals("AB", r.contactName)
    }
}
