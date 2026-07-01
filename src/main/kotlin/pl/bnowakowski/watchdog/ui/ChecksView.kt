package pl.bnowakowski.watchdog.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Pre
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.shared.Tooltip
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.Route
import com.vaadin.flow.router.RouteAlias
import jakarta.annotation.security.PermitAll
import pl.bnowakowski.watchdog.checks.CheckRunBackgroundRunner
import pl.bnowakowski.watchdog.checks.CheckRunProgressTracker
import pl.bnowakowski.watchdog.checks.CheckRunStartResult
import pl.bnowakowski.watchdog.domain.CheckRunStatus
import pl.bnowakowski.watchdog.domain.CheckRunTriggerType
import pl.bnowakowski.watchdog.domain.RuleCheckStatus
import tools.jackson.databind.JsonNode

@Route("", layout = MainLayout::class)
@RouteAlias("checks", layout = MainLayout::class)
@PermitAll
class ChecksView(
	private val uiQueries: UiQueries,
	private val checkRunBackgroundRunner: CheckRunBackgroundRunner,
	private val progressTracker: CheckRunProgressTracker,
) : VerticalLayout() {
	private val grid = Grid(CheckRunRow::class.java, false)
	private val detail = VerticalLayout()
	private val deviceResultsGrid = Grid(CheckRunDeviceResultRow::class.java, false)
	private val ruleResultsGrid = Grid(CheckRunRuleResultRow::class.java, false)
	private var selectedRun: CheckRunRow? = null
	private var finishListener: AutoCloseable? = null

	init {
		setSizeFull()
		configureRunsGrid()
		configureDetailGrids()
		detail.setWidthFull()
		add(
			Button("Run check now") { startManualCheck() },
			grid,
			detail,
		)
		expand(detail)
		refresh()
	}

	override fun onAttach(attachEvent: AttachEvent) {
		super.onAttach(attachEvent)
		val ui = attachEvent.ui
		finishListener = progressTracker.addFinishListener {
			ui.access { refresh() }
		}
	}

	override fun onDetach(detachEvent: DetachEvent) {
		finishListener?.close()
		finishListener = null
		super.onDetach(detachEvent)
	}

	private fun startManualCheck() {
		runCatching { checkRunBackgroundRunner.startCheck(CheckRunTriggerType.MANUAL) }
			.onSuccess {
				when (it) {
					CheckRunStartResult.STARTED -> {
						Notification.show("Check started")
					}
					CheckRunStartResult.ALREADY_RUNNING -> Notification.show("A check is already running")
				}
			}
			.onFailure { Notification.show("Check failed: ${it.message}") }
		refresh()
	}

	private fun configureRunsGrid() {
		grid.addColumn(CheckRunRow::id).setHeader("ID").setAutoWidth(true)
		grid.addColumn(CheckRunRow::triggerType).setHeader("Trigger").setAutoWidth(true)
		grid
			.addColumn(ComponentRenderer { row -> statusCell(row) })
			.setHeader("Status")
			.setAutoWidth(true)
		grid.addColumn(UiDateTimes.relativeRenderer<CheckRunRow> { it.startedAt }).setHeader("Started").setAutoWidth(true)
		grid.addColumn(UiDateTimes.relativeRenderer<CheckRunRow> { it.finishedAt }).setHeader("Finished").setAutoWidth(true)
		grid.addColumn { summaryLabel(it.summary, "healthy", "devices") }.setHeader("Devices")
		grid.addColumn { rulesPassedLabel(it.summary) }.setHeader("Rules passed")
		grid
			.addColumn(ComponentRenderer { row -> tooltipCell(summaryProblems(row.summary), "32rem") })
			.setHeader("Problems")
			.setAutoWidth(true)
			.setFlexGrow(1)
		grid.setHeight("15rem")
		grid.setMinHeight("15rem")
		grid.addSelectionListener { event ->
			event.firstSelectedItem.ifPresent { showRun(it) }
		}
	}

	private fun configureDetailGrids() {
		deviceResultsGrid.addColumn(CheckRunDeviceResultRow::deviceName).setHeader("Device").setAutoWidth(true)
		deviceResultsGrid.addColumn(CheckRunDeviceResultRow::criticality).setHeader("Criticality")
		deviceResultsGrid.addColumn(CheckRunDeviceResultRow::status).setHeader("Status")
		deviceResultsGrid.addColumn(UiDateTimes.relativeRenderer<CheckRunDeviceResultRow> { it.checkedAt }).setHeader("Checked").setAutoWidth(true)
		deviceResultsGrid.addColumn { "${it.matchedRuleCount}/${it.ruleCount}" }.setHeader("Rules passed")
		deviceResultsGrid.addColumn { it.mismatchedRuleCount + it.errorRuleCount }.setHeader("Failed")
		deviceResultsGrid.addColumn(CheckRunDeviceResultRow::skippedRuleCount).setHeader("Skipped")
		deviceResultsGrid.setAllRowsVisible(true)

		ruleResultsGrid.addColumn(CheckRunRuleResultRow::deviceName).setHeader("Device").setAutoWidth(true)
		ruleResultsGrid.addColumn(CheckRunRuleResultRow::groupName).setHeader("Group").setAutoWidth(true)
		ruleResultsGrid
			.addColumn(ComponentRenderer { row -> statusCell(row.status) })
			.setHeader("Status")
			.setAutoWidth(true)
		ruleResultsGrid
			.addColumn(ComponentRenderer { row ->
				row.fixStatus?.let { statusPill(it, PillStatus.SUCCESS) } ?: Span("-")
			})
			.setHeader("Fix")
			.setAutoWidth(true)
		ruleResultsGrid.addColumn { it.propertyPath ?: "-" }.setHeader("Property").setAutoWidth(true)
		ruleResultsGrid.addColumn { it.endpoint ?: "-" }.setHeader("Endpoint")
		ruleResultsGrid.addColumn { it.expectedValue?.toDisplayString() ?: "-" }.setHeader("Expected")
		ruleResultsGrid.addColumn { it.actualValue?.toDisplayString() ?: "-" }.setHeader("Actual")
		ruleResultsGrid
			.addColumn(ComponentRenderer { row -> messageCell(row) })
			.setHeader("Message")
			.setAutoWidth(true)
			.setFlexGrow(1)
		ruleResultsGrid.setItemDetailsRenderer(ComponentRenderer { row -> ruleResultDetails(row) })
		ruleResultsGrid.addItemClickListener { event ->
			val row = event.item
			ruleResultsGrid.setDetailsVisible(row, !ruleResultsGrid.isDetailsVisible(row))
		}
		ruleResultsGrid.setAllRowsVisible(true)
	}

	private fun refresh() {
		val rows = uiQueries.recentCheckRuns()
		grid.setItems(rows)
		val runToShow = selectedRun?.let { selected -> rows.firstOrNull { it.id == selected.id } } ?: rows.firstOrNull()
		if (runToShow == null) {
			selectedRun = null
			detail.removeAll()
			detail.add(Span("No checks have run yet."))
		} else {
			grid.select(runToShow)
			showRun(runToShow)
		}
	}

	private fun showRun(run: CheckRunRow) {
		selectedRun = run
		val deviceRows = uiQueries.checkRunDeviceResults(run.id)
		val ruleRows = uiQueries.checkRunRuleResults(run.id)

		deviceResultsGrid.setItems(deviceRows)
		ruleResultsGrid.setItems(ruleRows)

		detail.removeAll()
		detail.add(
			H2("Check #${run.id}"),
			HorizontalLayout(
				Span("${run.triggerType} ${run.status} / started"),
				UiDateTimes.relativeCell(run.startedAt),
				Span("/ finished"),
				UiDateTimes.relativeCell(run.finishedAt),
			),
			HorizontalLayout(
				summary("Devices", summaryValue(run.summary, "devices").toString()),
				summary("Healthy", summaryValue(run.summary, "healthy").toString()),
				summary("Skipped", summaryValue(run.summary, "skipped").toString()),
				summary("Needs attention", deviceRows.count { it.status.name !in setOf("HEALTHY", "SKIPPED") }.toString()),
				summary("Rules passed", summaryValue(run.summary, "rules_match").toString()),
				summary("Rules failed", (summaryValue(run.summary, "rules_mismatch") + summaryValue(run.summary, "rules_error")).toString()),
				summary("Rules skipped", summaryValue(run.summary, "rules_skipped").toString()),
			).apply { setWidthFull() },
		)
		run.summary?.get("error")?.text()?.let { detail.add(Span("Error: $it")) }
		detail.add(
			H3("Rules results"),
			if (ruleRows.isEmpty()) Span("No rules were evaluated.") else ruleResultsGrid,
			H3("Device results"),
			deviceResultsGrid,
		)
	}

	private fun summary(label: String, value: String) =
		VerticalLayout(Span(label), H3(value)).apply {
			width = "12rem"
			style.set("border", "1px solid var(--lumo-contrast-20pct)")
			style.set("border-radius", "8px")
			style.set("padding", "var(--lumo-space-m)")
		}

	private fun summaryLabel(summary: JsonNode?, passedKey: String, totalKey: String): String {
		val total = summaryValue(summary, totalKey)
		val passed = summaryValue(summary, passedKey)
		return "$passed/$total"
	}

	private fun rulesPassedLabel(summary: JsonNode?): String {
		val passed = summaryValue(summary, "rules_match")
		val total = passed +
			summaryValue(summary, "rules_mismatch") +
			summaryValue(summary, "rules_error") +
			summaryValue(summary, "rules_skipped")
		return "$passed/$total"
	}

	private fun summaryProblems(summary: JsonNode?): String {
		val failedRules = summaryValue(summary, "rules_mismatch") + summaryValue(summary, "rules_error")
		val skippedRules = summaryValue(summary, "rules_skipped")
		val offlineDevices = summaryValue(summary, "offline")
		val unknownDevices = summaryValue(summary, "unknown")
		val skippedDevices = summaryValue(summary, "skipped")
		return "$failedRules failed, $skippedRules rules skipped, $skippedDevices devices skipped, $offlineDevices offline, $unknownDevices unknown"
	}

	private fun summaryValue(summary: JsonNode?, field: String): Int =
		summary?.get(field)?.asInt(0) ?: 0

	private fun messageCell(row: CheckRunRuleResultRow): Span =
		tooltipCell(row.message ?: "-", "34rem", row.message != null)

	private fun statusCell(row: CheckRunRow): Span {
		val statusType = when (row.status) {
			CheckRunStatus.COMPLETED -> {
				val failedRules = summaryValue(row.summary, "rules_mismatch") + summaryValue(row.summary, "rules_error")
				if (failedRules > 0) PillStatus.ERROR else PillStatus.SUCCESS
			}
			CheckRunStatus.FAILED -> PillStatus.ERROR
			CheckRunStatus.TIMED_OUT,
			CheckRunStatus.STALE,
			-> PillStatus.WARNING
			CheckRunStatus.RUNNING -> PillStatus.INFO
		}
		return statusPill(row.status.name, statusType)
	}

	private fun tooltipCell(text: String, maxWidth: String, showTooltip: Boolean = true): Span {
		val cell = Span(text).apply {
			style.set("display", "block")
			style.set("max-width", maxWidth)
			style.set("overflow", "hidden")
			style.set("text-overflow", "ellipsis")
			style.set("white-space", "nowrap")
		}
		if (showTooltip) {
			Tooltip.forComponent(cell).withText(text)
		}
		return cell
	}

	private fun statusCell(status: RuleCheckStatus): Span =
		statusPill(
			status.name,
			when (status) {
				RuleCheckStatus.MATCH -> PillStatus.SUCCESS
				RuleCheckStatus.SKIPPED -> PillStatus.WARNING
				RuleCheckStatus.MISMATCH,
				RuleCheckStatus.ERROR,
				-> PillStatus.ERROR
			},
		)

	private fun statusPill(label: String, status: PillStatus): Span =
		Span(label).apply {
			style.set("display", "inline-flex")
			style.set("align-items", "center")
			style.set("min-width", "5.5rem")
			style.set("justify-content", "center")
			style.set("border-radius", "999px")
			style.set("font-size", "var(--lumo-font-size-xs)")
			style.set("font-weight", "700")
			style.set("padding", "0.15rem 0.5rem")
			style.set("border", "1px solid")
			when (status) {
				PillStatus.SUCCESS -> {
					style.set("background-color", "#dcfce7")
					style.set("border-color", "#86efac")
					style.set("color", "#166534")
				}
				PillStatus.WARNING -> {
					style.set("background-color", "#fef9c3")
					style.set("border-color", "#fde047")
					style.set("color", "#854d0e")
				}
				PillStatus.ERROR -> {
					style.set("background-color", "#fee2e2")
					style.set("border-color", "#fca5a5")
					style.set("color", "#991b1b")
				}
				PillStatus.INFO -> {
					style.set("background-color", "#dbeafe")
					style.set("border-color", "#93c5fd")
					style.set("color", "#1e40af")
				}
			}
		}

	private fun ruleResultDetails(row: CheckRunRuleResultRow): VerticalLayout =
		VerticalLayout(
			HorizontalLayout(
				detailField("Device", row.deviceName),
				detailField("Group", row.groupName),
				detailField("Status", row.status.name),
				detailField("Fix", row.fixStatus ?: "-"),
			).apply {
				setWidthFull()
				isSpacing = true
				style.set("gap", "var(--lumo-space-l)")
				style.set("flex-wrap", "wrap")
				style.set("margin-bottom", "var(--lumo-space-s)")
			},
			HorizontalLayout(
				detailField("Property", row.propertyPath ?: "-"),
				detailField("Endpoint", row.endpoint ?: "-"),
				detailField("Expected", row.expectedValue?.toDisplayString() ?: "-"),
				detailField("Actual", row.actualValue?.toDisplayString() ?: "-"),
			).apply {
				setWidthFull()
				isSpacing = true
				style.set("gap", "var(--lumo-space-l)")
				style.set("flex-wrap", "wrap")
			},
			Div(detailLabel("Message"), Pre(row.message ?: "-").apply {
				style.set("margin", "var(--lumo-space-xs) 0 0")
				style.set("max-height", "14rem")
				style.set("overflow", "auto")
				style.set("white-space", "pre-wrap")
				style.set("overflow-wrap", "anywhere")
				style.set("background", "var(--lumo-contrast-5pct)")
				style.set("border", "1px solid var(--lumo-contrast-20pct)")
				style.set("border-radius", "6px")
				style.set("padding", "var(--lumo-space-s)")
			}).apply { setWidthFull() },
		).apply {
			setWidthFull()
			style.set("padding", "var(--lumo-space-m)")
			style.set("gap", "var(--lumo-space-m)")
			style.set("background", "var(--lumo-contrast-5pct)")
			style.set("border-top", "1px solid var(--lumo-contrast-20pct)")
			style.set("border-bottom", "1px solid var(--lumo-contrast-20pct)")
		}

	private fun detailField(label: String, value: String): VerticalLayout =
		VerticalLayout(
			detailLabel(label),
			Span(value).apply {
				style.set("font-weight", "600")
				style.set("overflow-wrap", "anywhere")
				style.set("line-height", "1.35")
			},
		).apply {
			width = "14rem"
			style.set("gap", "var(--lumo-space-s)")
			style.set("padding", "0")
		}

	private fun detailLabel(label: String): Span =
		Span(label).apply {
			style.set("color", "var(--lumo-secondary-text-color)")
			style.set("font-size", "var(--lumo-font-size-xs)")
			style.set("font-weight", "700")
			style.set("letter-spacing", "0")
			style.set("text-transform", "uppercase")
		}

	private fun JsonNode.toDisplayString(): String =
		if (isValueNode) text() else toString()

	@Suppress("DEPRECATION")
	private fun JsonNode.text(): String =
		asText()

	private enum class PillStatus {
		SUCCESS,
		WARNING,
		ERROR,
		INFO,
	}
}
