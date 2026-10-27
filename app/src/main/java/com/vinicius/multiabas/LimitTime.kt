package com.vinicius.multiabas

import java.util.Calendar
import java.util.Locale

/**
 * Descobre QUANDO o limite volta a partir do texto do aviso do Claude.
 *
 * Resultado:
 *  - UNKNOWN  → não achei horário no texto
 *  - PASSED   → o horário do aviso já passou (aviso velho; a conta já está liberada)
 *  - > 0      → momento (ms) em que o limite volta
 *
 * Regra importante: um horário que já passou NÃO vira "amanhã" automaticamente.
 * O limite do Claude volta em poucas horas; se "amanhã" ficaria a mais de 12h daqui,
 * é um aviso que ficou na tela depois do limite já ter voltado.
 */
object LimitTime {
    const val UNKNOWN = -1L
    const val PASSED = 0L

    /** Maior espera plausível para um aviso que só traz hora do dia (sem dia da semana). */
    private const val MAX_WAIT_MS = 12L * 3600_000L
    /** Espera máxima aceita quando o aviso traz dia da semana (limite semanal). */
    private const val MAX_WEEK_MS = 8L * 24 * 3600_000L

    private val relCue = Regex("""\b(?:em|in)\s+\d""")
    private val relHours = Regex("""(\d{1,3})\s*(?:hrs?|horas?|hours?|h)(?![a-z])""")
    private val relMins = Regex("""(\d{1,3})\s*(?:mins?|minutos?|minutes?|m)(?![a-z])""")

    private val clockA = Regex("""\b(\d{1,2}):(\d{2})\s*(a\.?m\.?|p\.?m\.?)?""")
    private val clockC = Regex("""\b(\d{1,2})\s*(a\.?m\.?|p\.?m\.?)(?![a-z])""")
    private val clockB = Regex("""\b(\d{1,2})\s*h(?:\s*(\d{2}))?(?![a-z])""")

    private val weekdays: List<Pair<Regex, Int>> = listOf(
        Regex("""\b(?:segunda|monday|mon)\b""") to Calendar.MONDAY,
        Regex("""\b(?:ter[çc]a|tuesday|tues|tue)\b""") to Calendar.TUESDAY,
        Regex("""\b(?:quarta|wednesday|wed)\b""") to Calendar.WEDNESDAY,
        Regex("""\b(?:quinta|thursday|thurs|thu)\b""") to Calendar.THURSDAY,
        Regex("""\b(?:sexta|friday|fri)\b""") to Calendar.FRIDAY,
        Regex("""\b(?:s[áa]bado|saturday|sat)\b""") to Calendar.SATURDAY,
        Regex("""\b(?:domingo|sunday|sun)\b""") to Calendar.SUNDAY
    )
    private val tomorrowRe = Regex("""\b(?:amanh[ãa]|tomorrow)\b""")
    private val todayRe = Regex("""\b(?:hoje|today)\b""")

    fun parse(src: String, now: Long): Long {
        val s = src.lowercase(Locale.ROOT).replace('\u00a0', ' ').replace('\u202f', ' ')
        if (s.isBlank()) return UNKNOWN

        // "em 2 horas e 30 minutos" / "in 2 hr 30 min"
        if (relCue.containsMatchIn(s)) {
            val h = relHours.find(s)?.groupValues?.get(1)?.toIntOrNull()
            val m = relMins.find(s)?.groupValues?.get(1)?.toIntOrNull()
            if (h != null || m != null) {
                val ms = (h ?: 0) * 3600_000L + (m ?: 0) * 60_000L
                return if (ms in 1..MAX_WEEK_MS) now + ms else UNKNOWN
            }
        }

        // horário do relógio: 17:00 · 5:00 PM · 5pm · 17h · 17h30
        var hh = -1
        var mm = 0
        var ap = ""
        val a = clockA.find(s)
        val c = clockC.find(s)
        val b = clockB.find(s)
        when {
            a != null -> {
                hh = a.groupValues[1].toInt(); mm = a.groupValues[2].toInt(); ap = a.groupValues[3]
            }
            c != null -> {
                hh = c.groupValues[1].toInt(); mm = 0; ap = c.groupValues[2]
            }
            b != null -> {
                hh = b.groupValues[1].toInt()
                mm = b.groupValues[2].toIntOrNull() ?: 0
            }
        }
        if (hh < 0 || mm !in 0..59) return UNKNOWN
        if (ap.startsWith("p") && hh < 12) hh += 12
        if (ap.startsWith("a") && hh == 12) hh = 0
        if (hh !in 0..23) return UNKNOWN

        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, hh)
        cal.set(Calendar.MINUTE, mm)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        // limite semanal: o aviso cita o dia da semana
        val wd = weekdays.firstOrNull { it.first.containsMatchIn(s) }?.second
        if (wd != null) {
            val add = (wd - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
            cal.add(Calendar.DAY_OF_YEAR, add)
            if (cal.timeInMillis <= now) {
                // é hoje e a hora já passou: aviso velho (recente) ou só na semana que vem
                if (now - cal.timeInMillis <= MAX_WAIT_MS) return PASSED
                cal.add(Calendar.DAY_OF_YEAR, 7)
            }
            return if (cal.timeInMillis - now <= MAX_WEEK_MS) cal.timeInMillis else UNKNOWN
        }

        if (tomorrowRe.containsMatchIn(s)) cal.add(Calendar.DAY_OF_YEAR, 1)

        if (cal.timeInMillis <= now) {
            if (todayRe.containsMatchIn(s)) return PASSED
            // só tem hora do dia: "amanhã" só vale se for uma espera plausível
            cal.add(Calendar.DAY_OF_YEAR, 1)
            return if (cal.timeInMillis - now <= MAX_WAIT_MS) cal.timeInMillis else PASSED
        }
        return cal.timeInMillis
    }
}
