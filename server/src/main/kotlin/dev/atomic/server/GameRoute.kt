package dev.atomic.server

import dev.atomic.shared.net.ClientMessage
import dev.atomic.shared.net.ErrorCode
import dev.atomic.shared.net.ServerMessage
import dev.atomic.shared.net.decodeClientMessage
import io.ktor.server.routing.Routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("game-route")

fun Routing.gameWebSocket(rooms: RoomManager) {
    webSocket("/game") {
        val session = Session(this)
        try {
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val raw = frame.readText()
                val message = try {
                    decodeClientMessage(raw)
                } catch (t: Throwable) {
                    session.send(ServerMessage.ErrorMessage(ErrorCode.BadRequest, "bad message: ${t.message}"))
                    continue
                }
                handle(session, message, rooms)
            }
        } catch (t: Throwable) {
            log.warn("connection error: {}", t.message)
        } finally {
            val room = session.currentRoom
            val seat = session.currentSeat
            if (room != null && seat >= 0) {
                // Socket drop: hold the seat open for a grace window so the
                // same nickname can rejoin without losing their place.
                room.disconnect(seat) { rooms.evict(room) }
                session.detach()
            }
        }
    }
}

private suspend fun handle(session: Session, message: ClientMessage, rooms: RoomManager) {
    when (message) {
        is ClientMessage.CreateRoom -> {
            if (!session.createLimiter.tryAcquire()) {
                session.send(ServerMessage.ErrorMessage(ErrorCode.BadRequest, "too many rooms created, slow down"))
                return
            }
            rooms.create(message.level, message.settings, message.seats, session, message.nickname)
        }

        is ClientMessage.JoinRoom -> {
            val ok = rooms.join(message.code, session, message.nickname)
            if (!ok) {
                session.send(ServerMessage.ErrorMessage(ErrorCode.RoomNotFound, "room ${message.code} not found or full"))
            }
        }

        ClientMessage.SetReady -> session.currentRoom?.markReady(session.currentSeat)

        ClientMessage.CancelReady -> session.currentRoom?.setNotReady(session.currentSeat)

        is ClientMessage.MakeMove -> session.currentRoom?.applyMove(session.currentSeat, message.pos)

        ClientMessage.LeaveRoom -> {
            val room = session.currentRoom ?: return
            val empty = room.leave(session.currentSeat)
            session.detach()
            if (empty) rooms.evict(room)
        }

        is ClientMessage.Chat -> {
            if (!session.chatLimiter.tryAcquire()) {
                session.send(ServerMessage.ErrorMessage(ErrorCode.BadRequest, "chat rate limit: 5 messages / 10s"))
                return
            }
            session.currentRoom?.chat(session.currentSeat, message.text)
        }
    }
}
