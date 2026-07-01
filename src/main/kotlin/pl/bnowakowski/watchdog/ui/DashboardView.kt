package pl.bnowakowski.watchdog.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.shared.Tooltip
import com.vaadin.flow.router.Route
import jakarta.annotation.security.PermitAll
import pl.bnowakowski.watchdog.checks.CheckRunBackgroundRunner
import pl.bnowakowski.watchdog.checks.CheckRunStartResult
import pl.bnowakowski.watchdog.domain.CheckRunTriggerType

@Route("dashboard", layout = MainLayout::class)
@PermitAll
class DashboardView(
	private val uiQueries: UiQueries,
	private val checkRunBackgroundRunner: CheckRunBackgroundRunner,
) : VerticalLayout() {
	private val statusGrid = Grid(DeviceStatusRow::class.java, false)

	init {
		setSizeFull()
		statusGrid.addColumn(DeviceStatusRow::displayName).setHeader("Device").setAutoWidth(true)
		statusGrid.addColumn(DeviceStatusRow::criticality).setHeader("Criticality")
		statusGrid.addColumn { it.status ?: "UNKNOWN" }.setHeader("Latest status")
		statusGrid.addColumn(UiDateTimes.relativeRenderer<DeviceStatusRow> { it.checkedAt }).setHeader("Checked")
		statusGrid.addColumn { if (it.enabled) "Enabled" else "Disabled" }.setHeader("Monitoring")
		statusGrid.setSizeFull()
		refresh()
	}

	private fun refresh() {
		removeAll()
		val statuses = uiQueries.latestDeviceStatuses()
		val lastRun = uiQueries.latestCheckRun()
		statusGrid.setItems(statuses)
		add(
			HorizontalLayout(
				summary("Healthy", statuses.count { it.status == "HEALTHY" }.toString()),
				summary("Degraded", statuses.count { it.status == "DEGRADED" }.toString()),
				summary("Offline", statuses.count { it.status == "OFFLINE" }.toString()),
				summary("Skipped", statuses.count { it.status == "SKIPPED" }.toString()),
				summary("Unknown", statuses.count { it.status == null || it.status == "UNKNOWN" }.toString()),
			),
			HorizontalLayout(
				Span("Last check: ${lastRun?.status ?: "none"} ${UiDateTimes.relativeText(lastRun?.finishedAt ?: lastRun?.startedAt)}").apply {
					lastRun?.let { Tooltip.forComponent(this).withText(UiDateTimes.timestampText(it.finishedAt ?: it.startedAt)) }
				},
				Button("Run check now") {
					runCatching { checkRunBackgroundRunner.startCheck(CheckRunTriggerType.MANUAL) }
						.onSuccess {
							when (it) {
								CheckRunStartResult.STARTED -> Notification.show("Check started")
								CheckRunStartResult.ALREADY_RUNNING -> Notification.show("A check is already running")
							}
						}
						.onFailure { Notification.show("Check failed: ${it.message}") }
					refresh()
				},
			),
			H2("Critical alerts"),
			Grid(DeviceStatusRow::class.java, false).apply {
				addColumn(DeviceStatusRow::displayName).setHeader("Device")
				addColumn(DeviceStatusRow::criticality).setHeader("Criticality")
				addColumn { it.status ?: "UNKNOWN" }.setHeader("Status")
				addColumn(UiDateTimes.relativeRenderer<DeviceStatusRow> { it.lastSeenAt }).setHeader("Last seen")
				setItems(statuses.filter { it.criticality == "CRITICAL" && it.status !in setOf("HEALTHY", "SKIPPED") })
				setAllRowsVisible(true)
			},
			H2("Devices"),
			statusGrid,
		)
		expand(statusGrid)
	}

	private fun summary(label: String, value: String) =
		VerticalLayout(Span(label), H2(value)).apply {
			width = "12rem"
			style.set("border", "1px solid var(--lumo-contrast-20pct)")
			style.set("border-radius", "8px")
			style.set("padding", "var(--lumo-space-m)")
		}
}
