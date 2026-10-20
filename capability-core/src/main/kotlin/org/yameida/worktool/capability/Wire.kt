package org.yameida.worktool.capability

import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

data class Target(val deviceId: String, val sessionId: String)
data class Command(val id: String, val target: Target, val operation: String,
                   val params: JsonObject, val expiresAt: Long)
data class Result(val commandId: String, val target: Target, val sequence: Int,
                  val state: String, val code: String, val data: JsonObject = JsonObject()) {
    val terminal get() = state !in setOf("accepted", "running")
}
data class ObservedMessage(val conversationId: String, val text: String, val sender: String)
class ProtocolException(val code: String) : IllegalArgumentException(code)

/** Explicit codecs avoid reflection/R8 contracts and never trust data-class nullability on input. */
object Wire {
    const val MAX_FRAME = 65_536
    private val identifier = Regex("[A-Za-z0-9._:-]{1,128}")
    private val codePattern = Regex("[a-z0-9_]{1,64}")
    fun validId(value: String) = identifier.matches(value)
    // A fixed v1 predicate, independent of Java/Go Unicode whitespace tables.
    private fun protocolSpace(c: Char) = c in '\u0009'..'\u000d' || c in '\u001c'..'\u0020' ||
        c == '\u0085' || c == '\u00a0' || c == '\u1680' || c in '\u2000'..'\u200a' ||
        c in '\u2028'..'\u2029' || c == '\u202f' || c == '\u205f' || c == '\u3000'
    fun validText(value: String) = value.any { !protocolSpace(it) } && value.length <= 4000 && unicodeValid(value)
    private fun unicodeValid(value: String) =
        value.indices.all { i ->
            when {
                value[i].isHighSurrogate() -> i + 1 < value.length && value[i + 1].isLowSurrogate()
                value[i].isLowSurrogate() -> i > 0 && value[i - 1].isHighSurrogate()
                else -> true
            }
        }
    fun obj(vararg fields: Pair<String, Any?>): JsonObject = JsonObject().apply {
        fields.forEach { (k, v) -> when (v) {
            null -> Unit
            is JsonElement -> add(k, v)
            is Boolean -> addProperty(k, v)
            is Number -> addProperty(k, v)
            is String -> addProperty(k, v)
            else -> error("Unsupported JSON value")
        } }
    }
    fun strings(values: Collection<String>) = JsonArray().apply { values.forEach { add(it) } }
    private fun bad(): Nothing = throw ProtocolException("malformed")
    fun objectAt(o: JsonObject, key: String): JsonObject = o.get(key)?.takeIf { it.isJsonObject }?.asJsonObject ?: bad()
    fun stringAt(o: JsonObject, key: String): String = o.get(key)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isString
    }?.asString ?: bad()
    fun idAt(o: JsonObject, key: String): String = stringAt(o, key).also { if (!validId(it)) bad() }
    fun numberAt(o: JsonObject, key: String): Long {
        val value = o.get(key) ?: bad()
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber ||
            !value.asString.matches(Regex("[0-9]+"))) bad()
        return value.asString.toLongOrNull()?.takeIf { it in 1..9_007_199_254_740_991 } ?: bad()
    }
    fun parse(text: String): JsonObject {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_FRAME) throw ProtocolException("frame_too_large")
        try {
            val reader = JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }
            val value = read(reader, 0)
            if (reader.peek() != JsonToken.END_DOCUMENT || !value.isJsonObject) bad()
            val root = value.asJsonObject
            if (numberAt(root, "version") != 1L) throw ProtocolException("unsupported_version")
            return root
        } catch (e: ProtocolException) { throw e }
        catch (_: Exception) { bad() }
    }
    private fun read(r: JsonReader, depth: Int): JsonElement {
        if (depth > 16) bad()
        return when (r.peek()) {
            JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                r.beginObject()
                while (r.hasNext()) {
                    val name = r.nextName()
                    if (!unicodeValid(name)) bad()
                    if (has(name)) bad()
                    add(name, read(r, depth + 1))
                }
                r.endObject()
            }
            JsonToken.BEGIN_ARRAY -> JsonArray().apply {
                r.beginArray()
                while (r.hasNext()) add(read(r, depth + 1))
                r.endArray()
            }
            JsonToken.STRING -> JsonPrimitive(r.nextString().also { if (!unicodeValid(it)) bad() })
            JsonToken.NUMBER -> JsonParser.parseString(r.nextString())
            JsonToken.BOOLEAN -> JsonPrimitive(r.nextBoolean())
            JsonToken.NULL -> { r.nextNull(); JsonNull.INSTANCE }
            else -> bad()
        }
    }
    fun commands(text: String): List<Command> {
        val root = parse(text)
        return when (stringAt(root, "kind")) {
            "command" -> listOf(command(root))
            "batch" -> {
                val list = root.get("commands")?.takeIf { it.isJsonArray }?.asJsonArray ?: bad()
                if (list.size() !in 1..32) bad()
                list.map { if (!it.isJsonObject) bad() else command(it.asJsonObject) }
            }
            else -> bad()
        }
    }
    private fun command(o: JsonObject): Command {
        if (numberAt(o, "version") != 1L) throw ProtocolException("unsupported_version")
        if (stringAt(o, "kind") != "command") bad()
        val t = objectAt(o, "target")
        val operation = stringAt(o, "operation").also { if (it.isEmpty() || it.length > 64) bad() }
        val p = objectAt(o, "params")
        // Normalize only supported semantic arguments. Unknown extension fields never change identity.
        val params = when (operation) {
            "text.send" -> obj("conversationId" to idAt(p, "conversationId"),
                "text" to stringAt(p, "text").also { if (!validText(it)) bad() })
            "state.get", "capabilities.get" -> JsonObject()
            else -> p.deepCopy()
        }
        return Command(idAt(o, "id"), Target(idAt(t, "deviceId"), idAt(t, "sessionId")),
            operation, params, numberAt(o, "expiresAt"))
    }
    fun encode(c: Command): String = obj("version" to 1, "kind" to "command", "id" to c.id,
        "target" to obj("deviceId" to c.target.deviceId, "sessionId" to c.target.sessionId),
        "operation" to c.operation, "params" to c.params, "expiresAt" to c.expiresAt).toString()
    fun encode(r: Result): String = obj("version" to 1, "kind" to "result", "commandId" to r.commandId,
        "deviceId" to r.target.deviceId, "sessionId" to r.target.sessionId, "sequence" to r.sequence,
        "state" to r.state, "code" to r.code, "data" to r.data).toString()
    fun decodeResult(text: String): Result {
        val o = parse(text)
        if (stringAt(o, "kind") != "result") bad()
        val state = stringAt(o, "state")
        val rawSeq = numberAt(o, "sequence")
        if (rawSeq !in 1..3) bad()
        val seq = rawSeq.toInt()
        val allowed = when (state) {
            "accepted" -> setOf(1)
            "running" -> setOf(2)
            "rejected" -> setOf(1, 3)
            "succeeded", "failed", "partial_failure", "unknown" -> setOf(3)
            else -> bad()
        }
        if (seq !in allowed) bad()
        val code = stringAt(o, "code").also { if (!codePattern.matches(it)) bad() }
        return Result(idAt(o, "commandId"), Target(idAt(o, "deviceId"), idAt(o, "sessionId")),
            seq, state, code, objectAt(o, "data"))
    }
    fun error(code: String, commandId: String? = null): String =
        obj("version" to 1, "kind" to "error", "code" to code, "commandId" to commandId).toString()
    fun event(id: String, target: Target, at: Long, message: ObservedMessage): String {
        require(validId(id) && validId(message.conversationId) && validText(message.text))
        require(message.sender in setOf("self", "remote"))
        return obj("version" to 1, "kind" to "event", "eventId" to id,
            "deviceId" to target.deviceId, "sessionId" to target.sessionId,
            "name" to "message.observed", "occurredAt" to at,
            "data" to obj("conversationId" to message.conversationId, "text" to message.text,
                "sender" to message.sender, "source" to "visible_ui")).toString()
    }
}
