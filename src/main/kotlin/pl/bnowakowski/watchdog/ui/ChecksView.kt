package pl.bnowakowski.watchdog.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.Route
import jakarta.annotation.security.PermitAll
import pl.bnowakowski.watchdog.checks.CheckRunService

@Route("checks", layout = MainLayout::class)
@PermitAll
class ChecksView(
	private val uiQueries: UiQueries,
	private val checkRunService: CheckRunService,
) : VerticalLayout() {
	private val grid = Grid(CheckRunRow::class.java, false)

	init {
		setSizeFull()
		grid.addColumn(CheckRunRow::id).setHeader("ID")
		grid.addColumn(CheckRunRow::triggerType).setHeader("Trigger")
		grid.addColumn(CheckRunRow::status).setHeader("Status")
		grid.addColumn(CheckRunRow::startedAt).setHeader("Started")
		grid.addColumn { it.finishedAt?.toString() ?: "-" }.setHeader("Finished")
		grid.addColumn { it.summary?.toString() ?: "{}" }.setHeader("Summary")
		grid.setSizeFull()
		add(
			Button("Run check now") {
				runCatching { checkRunService.runManualCheck() }
					.onSuccess { Notification.show("Check run ${it.checkRunId} completed with ${it.status}") }
					.onFailure { Notification.show("Check failed: ${it.message}") }
				refresh()
			},
			grid,
		)
		expand(grid)
		refresh()
	}

	private fun refresh() {
		grid.setItems(uiQueries.recentCheckRuns())
	}
}
