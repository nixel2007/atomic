package dev.atomic.shared.net

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Shared Json configuration for client and server; polymorphic sealed types work out of the box. */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "t"
    prettyPrint = false
}

fun ClientMessage.encode(): String = ProtocolJson.encodeToString(ClientMessage.serializer(), this)
fun ServerMessage.encode(): String = ProtocolJson.encodeToString(ServerMessage.serializer(), this)

fun decodeClientMessage(raw: String): ClientMessage =
    ProtocolJson.decodeFromString(ClientMessage.serializer(), raw)

fun decodeServerMessage(raw: String): ServerMessage =
    ProtocolJson.decodeFromString(ServerMessage.serializer(), raw)
