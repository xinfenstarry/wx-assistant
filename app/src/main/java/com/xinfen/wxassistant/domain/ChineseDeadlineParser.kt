package com.xinfen.wxassistant.domain

import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import kotlin.math.abs

data class TextRange(val start: Int, val endExclusive: Int) {
    init {
        require(start >= 0 && endExclusive >= start)
    }

    fun asIntRange(): IntRange = start until endExclusive
}

enum class DateExpressionKind {
    FULL_DATE,
    MONTH_DAY,
    RELATIVE_DAY,
    WEEKDAY,
    INFERRED_TODAY,
}

data class DeadlineParseResult(
    val dateTime: LocalDateTime,
    val dateExpression: String?,
    val timeExpression: String?,
    val dateRange: TextRange?,
    val timeRange: TextRange?,
    val dateExpressionKind: DateExpressionKind,
    val timeWasExplicit: Boolean,
) {
    val matchedExpression: String
        get() = listOfNotNull(dateExpression, timeExpression)
            .distinct()
            .joinToString(" ")
}

/**
 * Parses common Chinese deadline expressions without Android dependencies.
 *
 * Supported dates include 今天/明天/后天, 周几/星期几 (plus 本周/下周), 月日,
 * explicit Chinese year-month-day, and yyyy-MM-dd/yyyy/MM/dd. Times include HH:mm and
 * Chinese hour expressions with 凌晨/早上/上午/中午/下午/傍晚/晚上/今晚.
 */
class ChineseDeadlineParser(
    private val defaultDeadlineTime: LocalTime = LocalTime.of(18, 0),
) {
    fun parse(text: String, reference: LocalDateTime): DeadlineParseResult? {
        if (text.isBlank()) return null

        val dateCandidate = findDateCandidate(text, reference.toLocalDate())
        val timeCandidate = findTimeCandidate(text, dateCandidate?.range)
        if (dateCandidate == null && timeCandidate == null) return null

        val date = dateCandidate?.date ?: reference.toLocalDate()
        val time = timeCandidate?.time ?: defaultDeadlineTime
        var resolved = LocalDateTime.of(date, time)

        // A bare time normally means the next occurrence of that time.
        if (dateCandidate == null && timeCandidate != null && resolved.isBefore(reference)) {
            resolved = resolved.plusDays(1)
        }

        // "周二 9点" said on Tuesday after 9 means the next Tuesday. 本周二 remains explicit.
        if (dateCandidate?.rollIfElapsed == true && resolved.isBefore(reference)) {
            resolved = resolved.plusWeeks(1)
        }

        return DeadlineParseResult(
            dateTime = resolved,
            dateExpression = dateCandidate?.expression,
            timeExpression = timeCandidate?.expression,
            dateRange = dateCandidate?.range,
            timeRange = timeCandidate?.range,
            dateExpressionKind = dateCandidate?.kind ?: DateExpressionKind.INFERRED_TODAY,
            timeWasExplicit = timeCandidate != null,
        )
    }

    private fun findDateCandidate(text: String, referenceDate: LocalDate): DateCandidate? {
        val candidates = mutableListOf<DateCandidate>()

        EXPLICIT_CHINESE_DATE.findAll(text).forEach { match ->
            validDate(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )?.let { date ->
                candidates += DateCandidate(
                    date,
                    match.value,
                    match.toTextRange(),
                    DateExpressionKind.FULL_DATE,
                )
            }
        }

        NUMERIC_FULL_DATE.findAll(text).forEach { match ->
            validDate(
                match.groupValues[1].toInt(),
                match.groupValues[2].toInt(),
                match.groupValues[3].toInt(),
            )?.let { date ->
                candidates += DateCandidate(
                    date,
                    match.value,
                    match.toTextRange(),
                    DateExpressionKind.FULL_DATE,
                )
            }
        }

        MONTH_DAY.findAll(text).forEach { match ->
            val month = match.groupValues[1].toInt()
            val day = match.groupValues[2].toInt()
            var date = validDate(referenceDate.year, month, day)
            if (date != null && date.isBefore(referenceDate)) {
                date = validDate(referenceDate.year + 1, month, day)
            }
            date?.let {
                candidates += DateCandidate(
                    it,
                    match.value,
                    match.toTextRange(),
                    DateExpressionKind.MONTH_DAY,
                )
            }
        }

        RELATIVE_DAY.findAll(text).forEach { match ->
            val offset = when (match.value) {
                "今天" -> 0L
                "明天" -> 1L
                else -> 2L
            }
            candidates += DateCandidate(
                referenceDate.plusDays(offset),
                match.value,
                match.toTextRange(),
                DateExpressionKind.RELATIVE_DAY,
            )
        }

        WEEKDAY.findAll(text).forEach { match ->
            val prefix = match.groupValues[1]
            val targetDay = chineseWeekday(match.groupValues[2]) ?: return@forEach
            val monday = referenceDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            val date = when {
                prefix.startsWith("下") -> monday.plusWeeks(1).plusDays((targetDay.value - 1).toLong())
                prefix.startsWith("本") || prefix.startsWith("这") ->
                    monday.plusDays((targetDay.value - 1).toLong())
                else -> referenceDate.with(TemporalAdjusters.nextOrSame(targetDay))
            }
            candidates += DateCandidate(
                date,
                match.value,
                match.toTextRange(),
                DateExpressionKind.WEEKDAY,
                rollIfElapsed = prefix == "周" || prefix == "星期",
            )
        }

        return candidates
            .distinctBy { it.range }
            .maxWithOrNull(
                compareBy<DateCandidate> { deadlineCueScore(text, it.range) }
                    .thenBy { expressionSpecificity(it.kind) }
                    .thenByDescending { -it.range.start },
            )
    }

    private fun findTimeCandidate(text: String, dateRange: TextRange?): TimeCandidate? {
        val candidates = mutableListOf<TimeCandidate>()

        COLON_TIME.findAll(text).forEach { match ->
            val period = match.groupValues[1].ifBlank { null }
            val hour = match.groupValues[2].toInt()
            val minute = match.groupValues[3].toInt()
            adjustedTime(period, hour, minute)?.let { time ->
                candidates += TimeCandidate(time, match.value, match.toTextRange())
            }
        }

        CHINESE_TIME.findAll(text).forEach { match ->
            val period = match.groupValues[1].ifBlank { null }
            val hour = match.groupValues[2].toInt()
            val minute = match.groupValues[3].ifBlank { "0" }.toInt()
            adjustedTime(period, hour, minute)?.let { time ->
                candidates += TimeCandidate(time, match.value, match.toTextRange())
            }
        }

        if (candidates.isEmpty()) {
            PERIOD_ONLY.findAll(text).forEach { match ->
                val time = when (match.value) {
                    "凌晨" -> LocalTime.of(1, 0)
                    "早上", "上午" -> LocalTime.of(9, 0)
                    "中午" -> LocalTime.NOON
                    "下午", "傍晚" -> LocalTime.of(15, 0)
                    else -> LocalTime.of(20, 0)
                }
                candidates += TimeCandidate(time, match.value, match.toTextRange())
            }
        }

        return candidates.maxWithOrNull(
            compareBy<TimeCandidate> { deadlineCueScore(text, it.range) }
                .thenBy { dateRange?.let { range -> -rangeDistance(range, it.range) } ?: 0 }
                .thenByDescending { -it.range.start },
        )
    }

    private fun deadlineCueScore(text: String, range: TextRange): Int {
        var best = 0
        DEADLINE_CUE.findAll(text).forEach { cue ->
            val distance = rangeDistance(range, cue.toTextRange())
            best = maxOf(best, 100 - distance.coerceAtMost(100))
        }
        return best
    }

    private fun rangeDistance(left: TextRange, right: TextRange): Int = when {
        left.endExclusive < right.start -> right.start - left.endExclusive
        right.endExclusive < left.start -> left.start - right.endExclusive
        else -> 0
    }

    private fun adjustedTime(period: String?, rawHour: Int, minute: Int): LocalTime? {
        if (rawHour !in 0..23 || minute !in 0..59) return null
        var hour = rawHour
        when (period) {
            "凌晨", "上午", "早上" -> if (hour == 12) hour = 0
            "中午" -> if (hour in 1..10) hour += 12
            "下午", "傍晚", "晚上", "今晚" -> {
                if (hour in 1..11) hour += 12
                else if (hour == 12 && (period == "晚上" || period == "今晚")) hour = 0
            }
        }
        return try {
            LocalTime.of(hour, minute)
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun validDate(year: Int, month: Int, day: Int): LocalDate? = try {
        LocalDate.of(year, month, day)
    } catch (_: DateTimeException) {
        null
    }

    private fun chineseWeekday(value: String): DayOfWeek? = when (value) {
        "一" -> DayOfWeek.MONDAY
        "二" -> DayOfWeek.TUESDAY
        "三" -> DayOfWeek.WEDNESDAY
        "四" -> DayOfWeek.THURSDAY
        "五" -> DayOfWeek.FRIDAY
        "六" -> DayOfWeek.SATURDAY
        "日", "天" -> DayOfWeek.SUNDAY
        else -> null
    }

    private fun expressionSpecificity(kind: DateExpressionKind): Int = when (kind) {
        DateExpressionKind.FULL_DATE -> 4
        DateExpressionKind.MONTH_DAY -> 3
        DateExpressionKind.RELATIVE_DAY -> 2
        DateExpressionKind.WEEKDAY -> 1
        DateExpressionKind.INFERRED_TODAY -> 0
    }

    private fun MatchResult.toTextRange() = TextRange(range.first, range.last + 1)

    private data class DateCandidate(
        val date: LocalDate,
        val expression: String,
        val range: TextRange,
        val kind: DateExpressionKind,
        val rollIfElapsed: Boolean = false,
    )

    private data class TimeCandidate(
        val time: LocalTime,
        val expression: String,
        val range: TextRange,
    )

    companion object {
        private val EXPLICIT_CHINESE_DATE =
            Regex("(?<!\\d)(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]")
        private val NUMERIC_FULL_DATE =
            Regex("(?<!\\d)(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?!\\d)")
        private val MONTH_DAY = Regex("(?<![\\d年])(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]")
        private val RELATIVE_DAY = Regex("后天|明天|今天")
        private val WEEKDAY = Regex("(下周|下星期|本周|这周|本星期|这星期|周|星期)([一二三四五六日天])")
        private val COLON_TIME = Regex(
            "(?:(凌晨|早上|上午|中午|下午|傍晚|晚上|今晚)\\s*)?([01]?\\d|2[0-3])[:：]([0-5]\\d)",
        )
        private val CHINESE_TIME = Regex(
            "(?:(凌晨|早上|上午|中午|下午|傍晚|晚上|今晚)\\s*)?(\\d{1,2})\\s*(?:点|时)(?:(\\d{1,2})\\s*分?)?",
        )
        private val PERIOD_ONLY = Regex("凌晨|早上|上午|中午|下午|傍晚|晚上|今晚")
        private val DEADLINE_CUE = Regex("(?i)截止|最晚|之前|以前|前|ddl|deadline|提交|完成|交付")
    }
}
