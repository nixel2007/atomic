package dev.atomic.shared.net

import dev.atomic.shared.engine.GameSettings
import dev.atomic.shared.model.Level
import dev.atomic.shared.model.Pos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProtocolTest {

    @Test
    fun clientCreateRoomRoundTrip() {
        val msg: ClientMessage = ClientMessage.CreateRoom(
            level = Level.rectangular("room-1", "Standard 6x9", 6, 9),
            settings = GameSettings(),
            seats = 3,
            nickname = "alice"
        )
        val decoded = decodeClientMessage(msg.encode())
        assertEquals(msg, decoded)
    }

    @Test
    fun serverErrorRoundTrip() {
        val msg: ServerMessage = ServerMessage.ErrorMessage(ErrorCode.RoomFull, "no free seat")
        val decoded = decodeServerMessage(msg.encode())
        assertEquals(msg, decoded)
    }

    @Test
    fun clientMakeMoveRoundTrip() {
        val msg: ClientMessage = ClientMessage.MakeMove(Pos(3, 4))
        val json = msg.encode()
        assertTrue("\"t\"" in json, "expected class discriminator 't' in $json")
        assertEquals(msg, decodeClientMessage(json))
    }
}
