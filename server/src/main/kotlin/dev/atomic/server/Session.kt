package dev.atomic.server

import dev.atomic.shared.net.ServerMessage
import dev.atomic.shared.net.encode
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send

class Session(private val ws: WebSocketSession) {
    private var room: Room? = null
    private var seat: Int = -1
    var isSpectator: Boolean = false
        private set

    val currentRoom: Room? get() = room
    val currentSeat: Int get() = seat

    /** 3 room creations per minute per connection. */
    val createLimiter: RateLimiter = RateLimiter(maxPerWindow = 3, windowMillis = 60_000)

    /** 5 chat messages per 10 seconds per connection. */
    val chatLimiter: RateLimiter = RateLimiter(maxPerWindow = 5, windowMillis = 10_000)

    fun attach(room: Room, seat: Int) {
        this.room = room
        this.seat = seat
        this.isSpectator = false
    }

    fun attachAsSpectator(room: Room) {
        this.room = room
        this.seat = -1
        this.isSpectator = true
    }

    fun detach() {
        room = null
        seat = -1
        isSpectator = false
    }

    suspend fun send(message: ServerMessage) {
        ws.send(Frame.Text(message.encode()))
    }
}
