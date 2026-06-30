package pl.bnowakowski.watchdog.ui

import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.Route
import jakarta.annotation.security.PermitAll
import pl.bnowakowski.watchdog.checks.CheckProperties
import pl.bnowakowski.watchdog.notifications.NotificationProperties

@Route("settings", layout = MainLayout::class)
@PermitAll
class SettingsView(
	private val checkProperties: CheckProperties,
	private val notificationProperties: NotificationProperties,
) : VerticalLayout() {
	init {
		add(
			H2("Scheduler"),
			Paragraph("Checks enabled: ${checkProperties.enabled}"),
			Paragraph("Interval: ${checkProperties.intervalSeconds} seconds"),
			H2("Notifications"),
			Paragraph("Notifications enabled: ${notificationProperties.enabled}"),
			Paragraph("Pushover configured: ${notificationProperties.pushoverConfigured}"),
		)
	}
}
