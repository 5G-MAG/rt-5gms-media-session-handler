package com.fivegmag.a5gmsmediasessionhandler.service

import com.fivegmag.a5gmscommonlibrary.consumptionReporting.ConsumptionRequest
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class PlaybackRequestTest {

    @Test
    fun `QoE session identifier uses hexBinary format`() {
        val mediaDeliverySessionId = UUID.randomUUID().toString()
        val playbackRequest = createPlaybackRequest(
            entryPoints = arrayListOf(),
            consumptionRequest = ConsumptionRequest(),
            qoeMetricsRequests = arrayListOf(),
            mediaDeliverySessionId = mediaDeliverySessionId
        )

        assertTrue(
            "QoE session identifier must contain 32 hex characters",
            playbackRequest.mediaStreamingSessionIdentifier
                .matches(Regex("[0-9a-fA-F]{32}"))
        )
        assertEquals(mediaDeliverySessionId, playbackRequest.mediaDeliverySessionId)
    }
}
