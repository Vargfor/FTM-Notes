package app.ftmnotes.core.format

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.time.Instant

/**
 * Writes ISO-8601 instants; reads anything an AI or another app is likely to produce:
 * `2026-10-22T10:15:00Z`, `2026-10-22T10:15`, `2026-10-22`, or epoch milliseconds/seconds.
 * Zone-less values are interpreted as UTC.
 */
object LenientInstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.ftmnotes.LenientInstant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant {
        if (decoder is JsonDecoder) {
            val element = decoder.decodeJsonElement()
            if (element is JsonPrimitive && !element.isString) {
                element.longOrNull?.let { return fromEpoch(it) }
            }
            if (element is JsonPrimitive) return parse(element.content)
            throw SerializationException("Expected a date/time string, got $element")
        }
        return parse(decoder.decodeString())
    }

    fun parse(raw: String): Instant {
        val text = raw.trim()
        text.toLongOrNull()?.let { return fromEpoch(it) }
        runCatching { return Instant.parse(text) }
        runCatching { return LocalDateTime.parse(text.replace(' ', 'T')).toInstant(TimeZone.UTC) }
        runCatching { return LocalDate.parse(text).atStartOfDayIn(TimeZone.UTC) }
        throw SerializationException("Unrecognised date/time '$raw'. Use ISO-8601, e.g. 2026-10-22T18:30:00Z")
    }

    // Values below ~1973 in milliseconds are almost certainly seconds.
    private fun fromEpoch(value: Long): Instant =
        if (value < 100_000_000_000L) Instant.fromEpochSeconds(value) else Instant.fromEpochMilliseconds(value)
}
