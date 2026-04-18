package dev.atomic.server

import dev.atomic.shared.net.ServerMessage
import dev.atomic.shared.net.encode
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send

class Session(private val ws: WebSocketSession) {
    private var room: Room? = null
    private var seat: Int = -1

    val currentRoom: Room? get() = room
    val currentSeat: Int get() = seat

    fun attach(room: Room, seat: Int) {
        this.room = room
        this.seat = seat
    }

    fun detach() {
        room = null
        seat = -1
    }

    suspend fun send(message: ServerMessage) {
        ws.send(Frame.Text(message.encode()))
    }
}
