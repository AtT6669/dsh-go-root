package io.github.ccch1mneyyy.dsh_app.root

import io.github.ccch1mneyyy.dsh_app.phone.background.Proto
import io.github.ccch1mneyyy.dsh_app.phone.background.RootExecOps
import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class RootExecServerRequestTest {
    private fun read(raw: String) = RootExecServer.Request.read(ByteArrayInputStream(raw.toByteArray()))

    @Test fun getParsesThePathAndQuery() {
        val request = read("GET /v1/status?pid=42&tail=64 HTTP/1.1\r\nAuthorization: Bearer abc\r\n\r\n")!!
        assertEquals("GET", request.method)
        assertEquals("/v1/status", request.path)
        assertEquals("42", request.query["pid"])
        assertEquals("64", request.query["tail"])
        assertEquals("Bearer abc", request.headers["authorization"])
        assertEquals("", request.body)
    }

    @Test fun postReadsExactlyTheDeclaredBody() {
        val body = "{\"command\":\"id -u\"}"
        val request = read("POST /v1/exec HTTP/1.1\r\nContent-Length: ${body.length}\r\n\r\n$body")!!
        assertEquals("POST", request.method)
        assertEquals(body, request.body)
    }

    @Test fun aTruncatedBodyKeepsWhatArrived() {
        val request = read("POST /v1/exec HTTP/1.1\r\nContent-Length: 99\r\n\r\n{}")!!
        assertEquals("{}", request.body)
    }

    @Test fun oversizedHeadersAndBodiesAreRejectedBeforeReading() {
        val header = "GET /v1/ping HTTP/1.1\r\nX: " + "a".repeat(20_000) + "\r\n\r\n"
        assertThrows(IllegalStateException::class.java) { read(header) }
        assertThrows(IllegalStateException::class.java) {
            read("POST /v1/exec HTTP/1.1\r\nContent-Length: 70000\r\n\r\n")
        }
    }

    @Test fun anEmptyConnectionIsNotARequest() {
        assertNull(read(""))
    }

    @Test fun everyExecutionOpHasItsOwnName() {
        assertEquals(4, RootExecOps.OPS.size)
        for (name in RootExecOps.OPS) assertTrue(name.isNotBlank())
        assertFalse(RootExecOps.OPS.contains(Proto.OP_PROBE))
        assertFalse(RootExecOps.OPS.contains(Proto.OP_DESTROY))
    }
}
