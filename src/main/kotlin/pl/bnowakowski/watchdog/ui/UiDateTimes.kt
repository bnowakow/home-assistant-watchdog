package pl.bnowakowski.watchdog.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.shared.Tooltip
import com.vaadin.flow.data.renderer.ComponentRenderer
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

object UiDateTimes {
	private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

	fun relativeCell(value: Instant?): Component =
		if (value == null) {
			Span("-")
		} else {
			Span(relative(value)).apply {
				Tooltip.forComponent(this).withText(timestamp(value))
			}
		}

	fun relativeText(value: Instant?): String =
		value?.let(::relative) ?: "-"

	fun timestampText(value: Instant?): String =
		value?.let(::timestamp) ?: "-"

	fun <T> relativeRenderer(valueProvider: (T) -> Instant?): ComponentRenderer<Component, T> =
		ComponentRenderer { row -> relativeCell(valueProvider(row)) }

	private fun relative(value: Instant, now: Instant = Instant.now()): String {
		val duration = Duration.between(value, now)
		val future = duration.isNegative
		val seconds = abs(duration.seconds)
		val amount = when {
			seconds < 60 -> seconds to "second"
			seconds < 3_600 -> seconds / 60 to "minute"
			seconds < 86_400 -> seconds / 3_600 to "hour"
			seconds < 2_592_000 -> seconds / 86_400 to "day"
			seconds < 31_536_000 -> seconds / 2_592_000 to "month"
			else -> seconds / 31_536_000 to "year"
		}
		val label = "${amount.first} ${amount.second}${if (amount.first == 1L) "" else "s"}"
		return if (future) "in $label" else "$label ago"
	}

	private fun timestamp(value: Instant): String =
		timestampFormatter.format(value.atZone(ZoneId.systemDefault()))
}
