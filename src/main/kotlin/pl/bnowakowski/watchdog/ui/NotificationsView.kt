package pl.bnowakowski.watchdog.ui

import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.Route
import jakarta.annotation.security.PermitAll

@Route("notifications", layout = MainLayout::class)
@PermitAll
class NotificationsView(
	private val uiQueries: UiQueries,
) : VerticalLayout() {
	init {
		setSizeFull()
		val grid = Grid(NotificationRow::class.java, false).apply {
			addColumn(NotificationRow::createdAt).setHeader("Created")
			addColumn(NotificationRow::status).setHeader("Status")
			addColumn(NotificationRow::severity).setHeader("Severity")
			addColumn { it.deviceName ?: it.deviceId?.toString() ?: "-" }.setHeader("Device")
			addColumn { it.ruleId?.toString() ?: "-" }.setHeader("Rule")
			addColumn(NotificationRow::message).setHeader("Message")
			setItems(uiQueries.recentNotifications())
			setSizeFull()
		}
		add(
			H2("Pushover"),
			Paragraph("Per-user Pushover preferences are encrypted at rest by the notification service. Saving them from the UI will be enabled once authenticated app-user ids are exposed to Vaadin views."),
			H2("History"),
			grid,
		)
		expand(grid)
	}
}
