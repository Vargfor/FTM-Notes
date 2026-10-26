package io.github.vargfor.ftmnotes.core.food.engine

import io.github.vargfor.ftmnotes.core.model.Quantity
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.encoding.*
import kotlinx.serialization.json.*
import kotlin.math.*

/** JSON numbers for exact amounts; {min,max} for ranges. Readers also accept human fractions. */
object QuantitySerializer : KSerializer<Quantity> {
    override val descriptor = JsonElement.serializer().descriptor
    override fun serialize(encoder: Encoder, value: Quantity) {
        val json = encoder as JsonEncoder
        json.encodeJsonElement(if (value.min == value.max) JsonPrimitive(value.min) else buildJsonObject { put("min", value.min); put("max", value.max) })
    }
    override fun deserialize(decoder: Decoder): Quantity {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return when (element) {
            is JsonPrimitive -> element.doubleOrNull?.let { Quantity(it) } ?: AmountParser.parse(element.content)
                ?: throw SerializationException("Expected a quantity, fraction or range")
            is JsonObject -> {
                fun amount(key: String): Double = element[key]?.jsonPrimitive?.let { it.doubleOrNull ?: AmountParser.parse(it.content)?.min }
                    ?: throw SerializationException("Missing quantity $key")
                Quantity(amount("min"), amount("max"))
            }
            else -> throw SerializationException("Expected a quantity")
        }
    }
}

object AmountParser {
    private val fractions = mapOf('¼' to "1/4", '½' to "1/2", '¾' to "3/4", '⅐' to "1/7",
        '⅑' to "1/9", '⅒' to "1/10", '⅓' to "1/3", '⅔' to "2/3", '⅕' to "1/5",
        '⅖' to "2/5", '⅗' to "3/5", '⅘' to "4/5", '⅙' to "1/6", '⅚' to "5/6",
        '⅛' to "1/8", '⅜' to "3/8", '⅝' to "5/8", '⅞' to "7/8")
    private val number = Regex("""^(?:\d+\s+\d+\s*/\s*\d+|\d+\s*/\s*\d+|\d+(?:[.,]\d+)?|[.,]\d+)""")
    private val range = Regex("""^\s*(?:[-–—]|to\b|til\b)\s*""", RegexOption.IGNORE_CASE)
    data class Parsed(val quantity: Quantity, val remainder: String)

    fun normalise(text: String): String = buildString {
        text.replace('⁄', '/').replace('\u00a0', ' ').forEach { c ->
            val fraction = fractions[c]
            if (fraction == null) append(c)
            else { if (lastOrNull()?.isDigit() == true) append(' '); append(fraction) }
        }
    }

    private fun exact(token: String): Double? {
        val parts = token.trim().split(Regex("\\s+"))
        val whole = if (parts.size > 1 && '/' !in parts.first()) parts.first().toDoubleOrNull() ?: return null else 0.0
        val fraction = if (whole != 0.0 || parts.size > 1 && '/' !in parts.first()) parts.drop(1).joinToString("") else parts.joinToString("")
        if ('/' !in fraction) return fraction.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        val pair = fraction.split('/')
        if (pair.size != 2) return null
        val a = pair[0].toDoubleOrNull() ?: return null
        val b = pair[1].toDoubleOrNull()?.takeIf { it > 0 } ?: return null
        return whole + a / b
    }

    fun prefix(input: String): Parsed? {
        val value = normalise(input).trimStart()
        val first = number.find(value) ?: return null
        val low = exact(first.value) ?: return null
        var rest = value.substring(first.range.last + 1)
        val separator = range.find(rest)
        if (separator != null) {
            val secondText = rest.substring(separator.range.last + 1)
            val second = number.find(secondText) ?: return null
            val high = exact(second.value) ?: return null
            if (high < low) return null
            rest = secondText.substring(second.range.last + 1)
            return Parsed(Quantity(low, high), rest.trimStart())
        }
        return Parsed(Quantity(low), rest.trimStart())
    }
    fun parse(input: String): Quantity? = runCatching { prefix(input)?.takeIf { it.remainder.isBlank() }?.quantity }.getOrNull()
}

object QuantityFormatter {
    fun decimal(value: Double, comma: Boolean = false, digits: Int = 6): String {
        if (value < 0) return "-" + decimal(-value, comma, digits)
        val factor = 10.0.pow(digits)
        val rounded = round(value * factor) / factor
        val whole = floor(rounded).toLong()
        val remainder = round((rounded - whole) * factor).toLong()
        val text = if (remainder == 0L) whole.toString() else "$whole." + remainder.toString().padStart(digits, '0').trimEnd('0')
        return if (comma) text.replace('.', ',') else text
    }
    fun fraction(value: Double, kitchen: Boolean = false): String {
        val whole = floor(value).toLong()
        val fractional = value - whole
        if (fractional < 0.00001) return whole.toString()
        val candidates = listOf(2, 3, 4, 8, 16).map { denominator -> round(fractional * denominator).toInt() to denominator }
        val best = candidates.minBy { abs(fractional - it.first.toDouble() / it.second) }
        val tolerance = if (kitchen) 1.0 / 32 else 0.0001
        if (abs(fractional - best.first.toDouble() / best.second) > tolerance || best.first == 0) return decimal(value)
        if (best.first == best.second) return (whole + 1).toString()
        val divisor = gcd(best.first, best.second)
        val part = "${best.first / divisor}/${best.second / divisor}"
        return if (whole == 0L) part else "$whole $part"
    }
    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
    fun format(quantity: Quantity, us: Boolean = false, comma: Boolean = false, kitchen: Boolean = false): String {
        fun one(value: Double) = if (us) fraction(value, kitchen) else decimal(value, comma)
        return if (quantity.min == quantity.max) one(quantity.min) else one(quantity.min) + "–" + one(quantity.max)
    }
}
