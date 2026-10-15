package com.tally

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class ServerTest {

    @Test
    fun `health is open`() = testApplication {
        configure()
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
    }

    @Test
    fun `api rejects calls without token`() = testApplication {
        configure()
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/status").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/squareoff").status)
    }

    @Test
    fun `dashboard page is served and its data needs the token`() = testApplication {
        configure()
        val page = client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue(page.bodyAsText().contains("AI trade ideas"))
        assertEquals(HttpStatusCode.OK, client.get("/app.js").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/api/dashboard").status)
        assertEquals(HttpStatusCode.Forbidden, client.post("/api/ai/autotrade").status)
    }

    @Test
    fun `kite callback rejects a login it did not start`() = testApplication {
        configure()
        val resp = client.get("/auth/callback?status=success&request_token=forged&state=whatever")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
