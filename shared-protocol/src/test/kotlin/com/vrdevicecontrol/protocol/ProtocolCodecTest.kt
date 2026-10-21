package com.vrdevicecontrol.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProtocolCodecTest {
    private val headsetCodec = ProtocolCodec.forHeadset()

    private fun startAt(messageId: String = "m1", startAt: Long = 123_456_789_000L) = Envelope(
        type = MessageType.START_AT,
        messageId = messageId,
        senderId = "controller",
        senderInstanceId = "controller-proc",
        connectionId = "conn",
        receiverInstanceId = "headset-proc",
        controllerEpoch = "epoch",
        runId = "run",
        generation = 1,
        payload = headsetCodec.toPayload(
            StartAt(
                assetSha256 = "a".repeat(64),
                presentationRevision = 1,
                prepareToken = "token",
                clockSampleId = "clock",
                startAtControllerUs = startAt,
                acceptBeforeControllerUs = startAt - 500_000,
            ),
        ),
    )

    @Test
    fun `round trips a start command and encodes microseconds as strings`() {
        val text = headsetCodec.encode(startAt())
        val tree = ProtocolCodec.DefaultJson.parseToJsonElement(text).jsonObject
        val start = tree["payload"]!!.jsonObject["startAtControllerUs"]!!.jsonPrimitive
        assertTrue(start.isString, "64-bit timestamps must be decimal strings")
        assertEquals("123456789000", start.content)

        val decoded = assertIs<DecodeResult.Ok>(headsetCodec.decode(text)).envelope
        val payload = headsetCodec.payloadOf<StartAt>(decoded)
        assertEquals(123_456_789_000L, payload.startAtControllerUs)
        assertEquals(0L, payload.positionUs)
    }

    @Test
    fun `preserves full 64-bit precision`() {
        val big = Long.MAX_VALUE - 7
        val decoded = assertIs<DecodeResult.Ok>(headsetCodec.decode(headsetCodec.encode(startAt(startAt = big))))
        assertEquals(big, headsetCodec.payloadOf<StartAt>(decoded.envelope).startAtControllerUs)
    }

    @Test
    fun `tolerates unknown optional fields`() {
        val text = headsetCodec.encode(startAt()).replaceFirst("{", """{"futureField":{"x":1},""")
        assertIs<DecodeResult.Ok>(headsetCodec.decode(text))
    }

    @Test
    fun `rejects incompatible major version with correlated error`() {
        val text = headsetCodec.encode(startAt().copy(protocolMajor = 2))
        val rejected = assertIs<DecodeResult.Rejected>(headsetCodec.decode(text))
        assertEquals(ErrorCode.PROTOCOL_MISMATCH, rejected.error.code)
        assertEquals("m1", rejected.messageId)
    }

    @Test
    fun `rejects unknown commands`() {
        val text = headsetCodec.encode(startAt().copy(type = "SELF_DESTRUCT"))
        val rejected = assertIs<DecodeResult.Rejected>(headsetCodec.decode(text))
        assertEquals(ErrorCode.UNKNOWN_COMMAND, rejected.error.code)
    }

    @Test
    fun `rejects headset-only message types arriving at the headset`() {
        val text = headsetCodec.encode(startAt().copy(type = MessageType.READY))
        assertEquals(ErrorCode.UNKNOWN_COMMAND, assertIs<DecodeResult.Rejected>(headsetCodec.decode(text)).error.code)
    }

    @Test
    fun `rejects oversize messages before parsing`() {
        val huge = "{\"pad\":\"" + "x".repeat(MAX_CONTROL_MESSAGE_BYTES) + "\"}"
        assertEquals(ErrorCode.MESSAGE_TOO_LARGE, assertIs<DecodeResult.Rejected>(headsetCodec.decode(huge)).error.code)
    }

    @Test
    fun `rejects malformed json and session commands without run context`() {
        assertEquals(ErrorCode.MALFORMED, assertIs<DecodeResult.Rejected>(headsetCodec.decode("not json")).error.code)
        val text = headsetCodec.encode(startAt().copy(runId = null))
        assertEquals(ErrorCode.MALFORMED, assertIs<DecodeResult.Rejected>(headsetCodec.decode(text)).error.code)
    }

    @Test
    fun `fingerprint ignores connection but detects payload change`() {
        val a = startAt()
        val retry = a.copy(connectionId = "other-conn", senderInstanceId = "x")
        val changed = a.copy(payload = buildJsonObject {
            a.payload.forEach { (k, v) -> put(k, v) }
            put("startAtControllerUs", JsonPrimitive("1"))
        })
        assertEquals(headsetCodec.fingerprint(a), headsetCodec.fingerprint(retry))
        assertNotEquals(headsetCodec.fingerprint(a), headsetCodec.fingerprint(changed))
    }

    @Test
    fun `parses device credentials and refuses malformed ones`() {
        assertEquals("dev" to "s3cret", DeviceCredential.parse(DeviceCredential.header("dev", "s3cret")))
        assertEquals(null, DeviceCredential.parse("Bearer nodot"))
        assertEquals(null, DeviceCredential.parse("Basic abc.def"))
        assertEquals(null, DeviceCredential.parse(null))
    }
}
