package io.github.vargfor.ftmnotes.core.training

import io.github.vargfor.ftmnotes.core.format.FtmFormat
import io.github.vargfor.ftmnotes.core.model.FtmDocument
import kotlinx.serialization.json.*

/** Apply only changed fields; stable item IDs preserve concurrent additions and untouched edits. */
fun mergeTrainingEdits(current: FtmDocument, base: FtmDocument, edited: FtmDocument): FtmDocument {
    fun merge(now: JsonElement?, old: JsonElement?, value: JsonElement?): JsonElement? {
        if (old == value) return now
        if (now is JsonObject && old is JsonObject && value is JsonObject) return JsonObject(
            (now.keys + old.keys + value.keys).mapNotNull { key -> merge(now[key], old[key], value[key])?.let { key to it } }.toMap())
        if (now is JsonArray && old is JsonArray && value is JsonArray) {
            fun identified(array: JsonArray) = array.all { it is JsonObject && it["id"] is JsonPrimitive }
            if (identified(now) && identified(old) && identified(value)) {
                fun indexed(array: JsonArray) = array.associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
                val a = indexed(old); val b = indexed(value); val c = indexed(now)
                val order = b.keys + c.keys.filter { it !in a }
                val result = order.mapNotNull { id -> merge(c[id], a[id], b[id]) }
                val changedRemoved = c.keys.filter { it in a && it !in b && c[it] != a[it] }.mapNotNull { c[it] }
                return JsonArray(result + changedRemoved)
            }
        }
        return value
    }
    val now = Json.parseToJsonElement(FtmFormat.encodeDocument(current)).jsonObject
    val old = Json.parseToJsonElement(FtmFormat.encodeDocument(base)).jsonObject
    val value = Json.parseToJsonElement(FtmFormat.encodeDocument(edited)).jsonObject
    val merged = merge(now, old, value)!!.jsonObject.toMutableMap()
    listOf("id", "createdAt", "updatedAt", "type").forEach { key -> if (now[key] != null) merged[key] = now.getValue(key) else merged.remove(key) }
    return FtmFormat.decodeDocument(JsonObject(merged).toString())
}
