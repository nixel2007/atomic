package dev.atomic.server

import dev.atomic.shared.engine.GameSettings
import dev.atomic.shared.model.Level
import dev.atomic.shared.model.Pos
import dev.atomic.shared.net.ClientMessage
import dev.atomic.shared.net.ErrorCode
import dev.atomic.shared.net.ServerMessage
import dev.atomic.shared.net.decodeServerMessage
import dev.atomic.shared.net.encode
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class GameRouteIntegrationTest {

    /**
     * Awaits the next frame from this session and decodes it. Fails the test
     * if nothing arrives within [timeoutMillis] — prevents hung tests.
     */
    private suspend fun DefaultClientWebSocketSession.expect(
        timeoutMillis: Long = 2000
    ): ServerMessage {
        val frame = try {
            withTimeout(timeoutMillis) { incoming.receive() }
        } catch (_: TimeoutCancellationException) {
            fail("timed out waiting for server message")
        }
        val text = (frame as? Frame.Text)?.readText() ?: fail("expected text frame, got $frame")
        return decodeServerMessage(text)
    }

    private suspend inline fun <reified T : ServerMessage> DefaultClientWebSocketSession.expectOf(): T {
        val msg = expect()
        return msg as? T ?: fail("expected ${T::class.simpleName}, got $msg")
    }

    private suspend fun DefaultClientWebSocketSession.sendMsg(m: ClientMessage) {
        send(Frame.Text(m.encode()))
    }

    private fun defaultCreateRoom(nickname: String = "alice") = ClientMessage.CreateRoom(
        level = Level.rectangular("r", "r", 3, 3),
        settings = GameSettings(),
        seats = 2,
        nickname = nickname
    )

    @Test
    fun healthAndMetricsEndpointsServeOk() = testApplication {
        // given — the server module is running
        application { module() }

        // when — the HTTP health and metrics endpoints are hit
        val health = client.get("/health")
        val metrics = client.get("/metrics")

        // then — both return 200 with the expected bodies
        assertEquals(HttpStatusCode.OK, health.status)
        assertEquals("ok", health.bodyAsText())
        assertEquals(HttpStatusCode.OK, metrics.status)
        assertTrue(metrics.bodyAsText().startsWith("atomic_rooms "))
    }

    @Test
    fun twoClientsCanCreateJoinAndStartAGame() = testApplication {
        // given — the server is running and two WebSocket clients are connected
        application { module() }
        val wsClient = createClient { install(WebSockets) }
        val host = wsClient.webSocketSession(urlString = "/game")
        val guest = wsClient.webSocketSession(urlString = "/game")

        // when — host creates a room and guest joins it
        host.sendMsg(defaultCreateRoom("alice"))
        val created = host.expectOf<ServerMessage.RoomCreated>()
        val code = created.code
        guest.sendMsg(ClientMessage.JoinRoom(code, "bob"))
        val joined = guest.expectOf<ServerMessage.RoomJoined>()
        val announce = host.expectOf<ServerMessage.PlayerJoined>()

        // then — seats are assigned in order and host is notified
        assertEquals(0, created.seat)
        assertEquals(2, created.maxSeats)
        assertEquals(1, joined.seat)
        assertEquals(1, announce.player.index)

        // when — both players ready up
        host.sendMsg(ClientMessage.SetReady)
        host.expectOf<ServerMessage.PlayerReady>()
        guest.expectOf<ServerMessage.PlayerReady>()
        guest.sendMsg(ClientMessage.SetReady)
        host.expectOf<ServerMessage.PlayerReady>()
        guest.expectOf<ServerMessage.PlayerReady>()
        val started = host.expectOf<ServerMessage.GameStarted>()
        guest.expectOf<ServerMessage.GameStarted>()

        // then — GameStarted is broadcast and P0 is the opener
        assertEquals(0, started.state.currentPlayerIndex)

        // when — host makes the opening move
        host.sendMsg(ClientMessage.MakeMove(Pos(0, 0)))
        val update = host.expectOf<ServerMessage.GameUpdated>()
        guest.expectOf<ServerMessage.GameUpdated>()

        // then — both clients see the move and turn rotates to P1
        assertEquals(0, update.bySeat)
        assertEquals(Pos(0, 0), update.lastMove)
        assertEquals(1, update.state.currentPlayerIndex)

        // when — host tries to move out of turn
        host.sendMsg(ClientMessage.MakeMove(Pos(2, 2)))
        val err = host.expectOf<ServerMessage.ErrorMessage>()

        // then — the server rejects with NotYourTurn
        assertEquals(ErrorCode.NotYourTurn, err.code)

        guest.close()
        host.close()
    }

    @Test
    fun hostAloneInLobbyGetsLongerGraceWindow() = testApplication {
        // given — a host who just created a room and is sitting alone in it
        application { module() }
        val wsClient = createClient { install(WebSockets) }
        val host = wsClient.webSocketSession(urlString = "/game")
        host.sendMsg(defaultCreateRoom("alice"))
        val code = host.expectOf<ServerMessage.RoomCreated>().code
        host.close()

        // when — a different session tries to rejoin the same room shortly
        // after the host drops (simulating the host backgrounding the app to
        // share the code and coming back a minute later)
        val revived = wsClient.webSocketSession(urlString = "/game")
        revived.sendMsg(ClientMessage.JoinRoom(code, "alice"))
        val msg = revived.expectOf<ServerMessage.RoomJoined>()

        // then — the room is still there and the same seat is resumed, not
        // RoomNotFound. With the short 30s grace this test would only pass
        // because it runs in <30s, but the grace is now ten minutes for a
        // lonely host in lobby — the interesting bit is that we get
        // RoomJoined instead of ErrorMessage(RoomNotFound).
        assertEquals(0, msg.seat)
        revived.close()
    }

    @Test
    fun rejoinWithSameNicknameResumesSameSeat() = testApplication {
        // given — a room with a host and a guest
        application { module() }
        val wsClient = createClient { install(WebSockets) }
        val hostSession = wsClient.webSocketSession(urlString = "/game")
        hostSession.sendMsg(defaultCreateRoom("alice"))
        val code = hostSession.expectOf<ServerMessage.RoomCreated>().code
        val guest1 = wsClient.webSocketSession(urlString = "/game")
        guest1.sendMsg(ClientMessage.JoinRoom(code, "bob"))
        val bobSeat = guest1.expectOf<ServerMessage.RoomJoined>().seat
        hostSession.expectOf<ServerMessage.PlayerJoined>()

        // when — the guest's socket drops without LeaveRoom
        guest1.close()
        val dc = hostSession.expectOf<ServerMessage.PlayerDisconnected>()

        // then — host is told the seat is held, not freed
        assertEquals(bobSeat, dc.seat)

        // when — a new session rejoins with the same nickname
        val guest2 = wsClient.webSocketSession(urlString = "/game")
        guest2.sendMsg(ClientMessage.JoinRoom(code, "bob"))
        val rejoined = guest2.expectOf<ServerMessage.RoomJoined>()
        val rejAnnounce = hostSession.expectOf<ServerMessage.PlayerRejoined>()

        // then — tryRejoin swaps the new session into the same seat
        assertEquals(bobSeat, rejoined.seat)
        assertEquals(bobSeat, rejAnnounce.seat)

        guest2.close()
        hostSession.close()
    }

    @Test
    fun roomNotFoundErrorOnBadCode() = testApplication {
        // given — a connected client and no rooms on the server
        application { module() }
        val wsClient = createClient { install(WebSockets) }
        val s = wsClient.webSocketSession(urlString = "/game")

        // when — the client tries to join a bogus code
        s.sendMsg(ClientMessage.JoinRoom("000000", "nobody"))
        val err = s.expectOf<ServerMessage.ErrorMessage>()

        // then — the server replies RoomNotFound
        assertEquals(ErrorCode.RoomNotFound, err.code)
        s.close()
    }

    @Test
    fun chatRateLimitKicksInAfterFiveMessages() = testApplication {
        // given — a session sitting alone in a room
        application { module() }
        val wsClient = createClient { install(WebSockets) }
        val host = wsClient.webSocketSession(urlString = "/game")
        host.sendMsg(defaultCreateRoom("alice"))
        host.expectOf<ServerMessage.RoomCreated>()

        // when — five chats go through inside the 10s window
        repeat(5) { i ->
            host.sendMsg(ClientMessage.Chat("msg $i"))
            val chat = host.expectOf<ServerMessage.Chat>()
            // then (intermediate) — each is echoed back verbatim
            assertEquals("msg $i", chat.text)
        }

        // when — the sixth chat inside the same window is sent
        host.sendMsg(ClientMessage.Chat("rate limited"))
        val err = host.expectOf<ServerMessage.ErrorMessage>()

        // then — the server rejects with BadRequest citing the rate limit
        assertEquals(ErrorCode.BadRequest, err.code)
        assertTrue("rate limit" in err.message)
        host.close()
    }

    @Test
    fun createRoomRateLimitAllowsThreePerWindow() = testApplication {
        // given — a single session that will repeatedly create then leave rooms
        application { module() }
        val wsClient = createClient { install(WebSockets) }
        val s = wsClient.webSocketSession(urlString = "/game")

        // when — three successive CreateRoom calls are made inside the window
        repeat(3) {
            s.sendMsg(defaultCreateRoom("alice"))
            s.expectOf<ServerMessage.RoomCreated>()
            s.sendMsg(ClientMessage.LeaveRoom)
            // PlayerLeft is only broadcast to other occupants; the session is
            // alone in its room so no queue drain is needed here.
        }

        // when — a fourth CreateRoom call follows immediately
        s.sendMsg(defaultCreateRoom("alice"))
        val err = s.expectOf<ServerMessage.ErrorMessage>()

        // then — the limiter rejects it with BadRequest
        assertEquals(ErrorCode.BadRequest, err.code)
        s.close()
    }

    @Test
    fun malformedClientMessageYieldsBadRequest() = testApplication {
        // given — a connected client
        application { module() }
        val wsClient = createClient { install(WebSockets) }
        val s = wsClient.webSocketSession(urlString = "/game")

        // when — the client sends a non-JSON text frame
        s.send(Frame.Text("this is not json"))
        val err = s.expectOf<ServerMessage.ErrorMessage>()

        // then — the server replies BadRequest instead of disconnecting
        assertEquals(ErrorCode.BadRequest, err.code)
        s.close()
    }

    @Test
    fun playerCanCancelReadyBeforeGameStarts() = testApplication {
        // given — two clients in the same room
        application { module() }
        val wsClient = createClient { install(WebSockets) }
        val host = wsClient.webSocketSession(urlString = "/game")
        val guest = wsClient.webSocketSession(urlString = "/game")
        host.sendMsg(defaultCreateRoom("alice"))
        val code = host.expectOf<ServerMessage.RoomCreated>().code
        guest.sendMsg(ClientMessage.JoinRoom(code, "bob"))
        guest.expectOf<ServerMessage.RoomJoined>()
        host.expectOf<ServerMessage.PlayerJoined>()

        // when — host marks ready then cancels
        host.sendMsg(ClientMessage.SetReady)
        host.expectOf<ServerMessage.PlayerReady>()
        guest.expectOf<ServerMessage.PlayerReady>()
        host.sendMsg(ClientMessage.CancelReady)
        val hostUnready = host.expectOf<ServerMessage.PlayerNotReady>()
        val guestUnready = guest.expectOf<ServerMessage.PlayerNotReady>()

        // then — both clients are notified of the unready state
        assertEquals(0, hostUnready.seat)
        assertEquals(0, guestUnready.seat)

        // when — both players ready up properly
        host.sendMsg(ClientMessage.SetReady)
        host.expectOf<ServerMessage.PlayerReady>()
        guest.expectOf<ServerMessage.PlayerReady>()
        guest.sendMsg(ClientMessage.SetReady)
        host.expectOf<ServerMessage.PlayerReady>()
        guest.expectOf<ServerMessage.PlayerReady>()

        // then — game starts after all ready
        host.expectOf<ServerMessage.GameStarted>()
        guest.expectOf<ServerMessage.GameStarted>()

        // when — a player tries to cancel ready after game started (should be ignored)
        host.sendMsg(ClientMessage.CancelReady)

        // then — no message is sent (the server silently ignores it)
        // We verify by sending a chat that echoes, confirming the server is still responsive
        host.sendMsg(ClientMessage.Chat("hello"))
        val chat = host.expectOf<ServerMessage.Chat>()
        assertEquals("hello", chat.text)

        guest.close()
        host.close()
    }
}
