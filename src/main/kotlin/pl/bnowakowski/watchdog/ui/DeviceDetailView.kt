package pl.bnowakowski.watchdog.ui

import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Pre
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.Route
import jakarta.annotation.security.PermitAll
import java.time.Instant
import org.springframework.data.repository.findByIdOrNull
import pl.bnowakowski.watchdog.persistence.DeviceRepository
import pl.bnowakowski.watchdog.rules.EffectiveRuleResolver

@Route("devices/:deviceId", layout = MainLayout::class)
@PermitAll
class DeviceDetailView(
	private val deviceRepository: DeviceRepository,
	private val effectiveRuleResolver: EffectiveRuleResolver,
	private val uiQueries: UiQueries,
) : VerticalLayout(), BeforeEnterObserver {
	override fun beforeEnter(event: BeforeEnterEvent) {
		val parameter = event.routeParameters.get("deviceId").orElse(null)?.toLongOrNull()
		if (parameter == null) {
			removeAll()
			add(H2("Device not found"))
			return
		}
		showDevice(parameter)
	}

	private fun showDevice(parameter: Long) {
		removeAll()
		val device = deviceRepository.findByIdOrNull(parameter)
		if (device == null) {
			add(H2("Device not found"))
			return
		}

		add(
			H2(device.displayName),
			Span("${device.providerType} / ${device.providerDeviceId} / ${device.modelKey}"),
			H3("Current provider snapshot"),
			Pre(uiQueries.latestSnapshot(parameter)?.toPrettyString() ?: "{}"),
			H3("Group memberships"),
			grid(uiQueries.groupMemberships(parameter)),
			H3("Effective rules"),
			grid(effectiveRuleResolver.resolveForDevice(parameter)?.rules.orEmpty()),
			H3("Recent check results"),
			grid(uiQueries.recentRuleResults(parameter)),
			H3("Recent fix attempts"),
			grid(uiQueries.recentFixAttempts(parameter)),
			H3("Recent notifications"),
			grid(uiQueries.recentNotifications(parameter)),
			H3("Parameter history"),
			grid(uiQueries.parameterHistory(parameter)),
		)
	}

	private fun <T : Any> grid(rows: List<T>) =
		Grid(rows.firstOrNull()?.javaClass ?: Any::class.java, false).apply {
			if (rows.isEmpty()) {
				addColumn { "-" }.setHeader("No rows")
			} else {
				rows.first()::class.java.declaredFields.forEach { field ->
					field.isAccessible = true
					addComponentColumn { row ->
						when (val value = field.get(row)) {
							is Instant -> UiDateTimes.relativeCell(value)
							else -> Span(value?.toString() ?: "-")
						}
					}.setHeader(field.name)
				}
			}
			setItems(rows)
			setAllRowsVisible(true)
		}
}
