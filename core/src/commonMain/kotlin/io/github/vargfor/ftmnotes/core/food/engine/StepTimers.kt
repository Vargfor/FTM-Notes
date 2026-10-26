package io.github.vargfor.ftmnotes.core.food.engine

import kotlin.math.roundToLong

data class TimerHint(val text: String, val seconds: Long)
object StepTimers {
    private val duration = Regex("""(\d+(?:[.,]\d+)?)\s*(hours?|hrs?|timer?|h|minutes?|mins?|minutter?|min|seconds?|secs?|sekunder?|sek|s)\b""", RegexOption.IGNORE_CASE)
    fun find(text: String): List<TimerHint> {
        val matches = duration.findAll(text).toList()
        val result = mutableListOf<TimerHint>()
        var index = 0
        while (index < matches.size) {
            var current = matches[index]
            var seconds = seconds(current)
            val first = current.range.first
            var last = current.range.last
            while (index + 1 < matches.size && text.substring(last + 1, matches[index + 1].range.first).trim().lowercase() in listOf("", "and", "og")) {
                index++; current = matches[index]; seconds += seconds(current); last = current.range.last
            }
            if (seconds in 1..604800) result += TimerHint(text.substring(first, last + 1), seconds)
            index++
        }
        return result
    }
    fun seconds(text: String): Long? = find(text).singleOrNull()?.seconds
    private fun seconds(match: MatchResult): Long {
        val unit = match.groupValues[2].lowercase()
        val factor = if (unit.startsWith("h") || unit.startsWith("tim")) 3600 else if (unit.startsWith("m")) 60 else 1
        return (match.groupValues[1].replace(',', '.').toDouble() * factor).roundToLong()
    }
}
