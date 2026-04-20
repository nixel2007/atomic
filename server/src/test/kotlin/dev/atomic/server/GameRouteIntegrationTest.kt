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

    @Test
    fun healthAndMetricsEndpointsServeOk() = testApplication {
        application { module() }
        val health = client.get("/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertEquals("ok", health.bodyAsText())
        val metrics = client.get("/metrics")
        assertEquals(HttpStatusCode.OK, metrics.status)
        assertTrue(metrics.bodyAsText().startsWith("atomic_rooms "))
    }

    @Test
    fun twoClientsCanCreateJoinAndStartAGame() = testApplication {
        application { module() }
        val wsClient = createClient { install(WebSockets) }
        wsClient.webSocketSession(urlString = "/game").run {
            val host = this
            sendMsg(ClientMessage.CreateRoom(
                level = Level.rectangular("r", "r", 3, 3),
                settings = GameSettings(),
                seats = 2,
                nickname = "alice"
            ))
            val created = host.expectOf<ServerMessage.RoomCreated>()
            assertEquals(0, created.seat)
            assertEquals(2, created.maxSeats)
            val code = created.code

            wsClient.webSocketSession(urlString = "/game").run {
                val guest = this
                sendMsg(ClientMessage.JoinRoom(code, "bob"))
                val joined = guest.expectOf<ServerMessage.RoomJoined>()
                assertEquals(1, joined.seat)

                // Host receives PlayerJoined announcement.
                val announce = host.expectOf<ServerMessage.PlayerJoined>()
                assertEquals(1, announce.player.index)

                // Both ready → GameStarted broadcast.
                host.sendMsg(ClientMessage.SetReady)
                host.expectOf<ServerMessage.PlayerReady>()
                guest.expectOf<ServerMessage.PlayerReady>()
                guest.sendMsg(ClientMessage.SetReady)
                host.expectOf<ServerMessage.PlayerReady>()
                guest.expectOf<ServerMessage.PlayerReady>()
                val started = host.expectOf<ServerMessage.GameStarted>()
                guest.expectOf<ServerMessage.GameStarted>()
                assertEquals(0, started.state.currentPlayerIndex)

                // Host makes first move, both see GameUpdated.
                host.sendMsg(ClientMessage.MakeMove(Pos(0, 0)))
                val update = host.expectOf<ServerMessage.GameUpdated>()
                guest.expectOf<ServerMessage.GameUpdated>()
                assertEquals(0, update.bySeat)
                assertEquals(Pos(0, 0), update.lastMove)

                // Guest tries to play out of turn (it's P1's turn now — wait, P0 just played).
                // Actually after host's move the currentPlayerIndex should be 1.
                assertEquals(1, update.state.currentPlayerIndex)

                // Host attempting a move now should get NotYourTurn.
                host.sendMsg(ClientMessage.MakeMove(Pos(2, 2)))
                val err = host.expectOf<ServerMessage.ErrorMessage>()
                assertEquals(ErrorCode.NotYourTurn, err.code)

                guest.close()
            }
            host.close()
        }
    }

    @Test
    fun rejoinWithSameNicknameResumesSameSeat() = testApplication {
        application { module() }
        val wsClient = createClient { install(WebSockets) }
        val hostSession = wsClient.webSocketSession(urlString = "/game")
        hostSession.sendMsg(ClientMessage.CreateRoom(
            level = Level.rectangular("r", "r", 3, 3),
            settings = GameSettings(),
            seats = 2,
            nickname = "alice"
        ))
        val created = hostSession.expectOf<ServerMessage.RoomCreated>()
        val code = created.code

        val guest1 = wsClient.webSocketSession(urlString = "/game")
        guest1.sendMsg(ClientMessage.JoinRoom(code, "bob"))
        val joined = guest1.expectOf<ServerMessage.RoomJoined>()
        val bobSeat = joined.seat
        hostSession.expectOf<ServerMessage.PlayerJoined>() // announcement on host side

        // Guest drops the connection without LeaveRoom — seat is held in grace.
        guest1.close()

        // Host sees PlayerDisconnected (not PlayerLeft).
        val dc = hostSession.expectOf<ServerMessage.PlayerDisconnected>()
        assertEquals(bobSeat, dc.seat)

        // A new session claims the same nickname → tryRejoin swaps it into the
        // held seat instead of allocating a fresh one.
        val guest2 = wsClient.webSocketSession(urlString = "/game")
        guest2.sendMsg(ClientMessage.JoinRoom(code, "bob"))
        val rejoined = guest2.expectOf<ServerMessage.RoomJoined>()
        assertEquals(bobSeat, rejoined.seat)
        val rejAnnounce = hostSession.expectOf<ServerMessage.PlayerRejoined>()
        assertEquals(bobSeat, rejAnnounce.seat)

        guest2.close()
        hostSession.close()
    }

    @Test
    fun roomNotFoundErrorOnBadCode() = testApplication {
        application { module() }
        val wsClient = createClient { install(WebSockets) }
        val s = wsClient.webSocketSession(urlString = "/game")
        s.sendMsg(ClientMessage.JoinRoom("000000", "nobody"))
        val err = s.expectOf<ServerMessage.ErrorMessage>()
        assertEquals(ErrorCode.RoomNotFound, err.code)
        s.close()
    }

    @Test
    fun chatRateLimitKicksInAfterFiveMessages() = testApplication {
        application { module() }
        val wsClient = createClient { install(WebSockets) }
        val host = wsClient.webSocketSession(urlString = "/game")
        host.sendMsg(ClientMessage.CreateRoom(
            level = Level.rectangular("r", "r", 3, 3),
            settings = GameSettings(),
            seats = 2,
            nickname = "alice"
        ))
        host.expectOf<ServerMessage.RoomCreated>()

        // 5 messages inside the 10s window are allowed, the 6th must be
        // rejected with a BadRequest ErrorMessage.
        repeat(5) { i ->
            host.sendMsg(ClientMessage.Chat("msg $i"))
            val chat = host.expectOf<ServerMessage.Chat>()
            assertEquals("msg $i", chat.text)
        }
        host.sendMsg(ClientMessage.Chat("rate limited"))
        val err = host.expectOf<ServerMessage.ErrorMessage>()
        assertEquals(ErrorCode.BadRequest, err.code)
        assertTrue("rate limit" in err.message)
        host.close()
    }

    @Test
    fun createRoomRateLimitAllowsThreePerWindow() = testApplication {
        application { module() }
        val wsClient = createClient { install(WebSockets) }
        val s = wsClient.webSocketSession(urlString = "/game")
        // Three CreateRoom calls succeed; the fourth gets BadRequest.
        repeat(3) {
            // Before the next CreateRoom, must leave the current room so the
            // session can host another one; disconnect briefly.
            s.sendMsg(ClientMessage.CreateRoom(
                level = Level.rectangular("r", "r", 3, 3),
                settings = GameSettings(),
                seats = 2,
                nickname = "alice"
            ))
            s.expectOf<ServerMessage.RoomCreated>()
            s.sendMsg(ClientMessage.LeaveRoom)
            // PlayerLeft may be broadcast but only to others; our session has
            // no listeners, so the queue stays empty. No expect() needed.
        }
        s.sendMsg(ClientMessage.CreateRoom(
            level = Level.rectangular("r", "r", 3, 3),
            settings = GameSettings(),
            seats = 2,
            nickname = "alice"
        ))
        val err = s.expectOf<ServerMessage.ErrorMessage>()
        assertEquals(ErrorCode.BadRequest, err.code)
        s.close()
    }

    @Test
    fun malformedClientMessageYieldsBadRequest() = testApplication {
        application { module() }
        val wsClient = createClient { install(WebSockets) }
        val s = wsClient.webSocketSession(urlString = "/game")
        s.send(Frame.Text("this is not json"))
        val err = s.expectOf<ServerMessage.ErrorMessage>()
        assertEquals(ErrorCode.BadRequest, err.code)
        s.close()
    }
}
