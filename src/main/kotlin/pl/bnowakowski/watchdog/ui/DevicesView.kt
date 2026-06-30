package pl.bnowakowski.watchdog.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.Route
import jakarta.annotation.security.PermitAll
import org.springframework.data.repository.findByIdOrNull
import pl.bnowakowski.watchdog.domain.Criticality
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.PowerSource
import pl.bnowakowski.watchdog.persistence.DeviceRepository

@Route("devices", layout = MainLayout::class)
@PermitAll
class DevicesView(
	private val deviceRepository: DeviceRepository,
	private val uiQueries: UiQueries,
) : VerticalLayout() {
	private val grid = Grid(Device::class.java, false)

	init {
		setSizeFull()
		grid.addColumn(Device::id).setHeader("ID").setAutoWidth(true)
		grid.addColumn(Device::providerType).setHeader("Provider").setAutoWidth(true)
		grid.addColumn(Device::displayName).setHeader("Name").setAutoWidth(true)
		grid.addColumn(Device::providerDeviceId).setHeader("External ID").setAutoWidth(true)
		grid.addColumn(Device::powerSource).setHeader("Power")
		grid.addColumn(Device::criticality).setHeader("Criticality")
		grid.addColumn { batteryFor(it.id) }.setHeader("Battery")
		grid.addColumn { statusFor(it.id) }.setHeader("Availability")
		grid.addColumn { it.lastSeenAt?.toString() ?: "-" }.setHeader("Last seen").setAutoWidth(true)
		grid.addColumn { if (it.enabled) "Enabled" else "Disabled" }.setHeader("Monitoring")
		grid.addComponentColumn { device ->
			HorizontalLayout(
				Button("Detail") { ui.ifPresent { it.navigate(DeviceDetailView::class.java, device.id) } },
				Button(if (device.enabled) "Disable" else "Enable") {
					deviceRepository.save(device.copy(enabled = !device.enabled))
					refresh()
				},
				Button("Edit") { openEditor(device) },
			)
		}.setHeader("Actions").setAutoWidth(true)
		grid.setSizeFull()
		add(grid)
		expand(grid)
		refresh()
	}

	private fun openEditor(device: Device) {
		val name = TextField("Display name").apply { value = device.displayName }
		val power = ComboBox<PowerSource>("Power").apply {
			setItems(*PowerSource.entries.toTypedArray())
			value = device.powerSource
		}
		val criticality = ComboBox<Criticality>("Criticality").apply {
			setItems(*Criticality.entries.toTypedArray())
			value = device.criticality
		}
		val save = Button("Save") {
			deviceRepository.save(
				device.copy(
					displayName = name.value.trim().ifBlank { device.displayName },
					powerSource = power.value ?: device.powerSource,
					criticality = criticality.value ?: device.criticality,
				),
			)
			Notification.show("Device saved")
			refresh()
		}
		add(HorizontalLayout(name, power, criticality, save))
	}

	private fun refresh() {
		grid.setItems(deviceRepository.findAll().sortedBy { it.displayName })
	}

	private fun statusFor(deviceId: Long?): String =
		deviceId?.let { id -> uiQueries.latestDeviceStatuses().firstOrNull { it.id == id }?.status } ?: "UNKNOWN"

	private fun batteryFor(deviceId: Long?): String =
		deviceId
			?.let { uiQueries.parameterHistory(it, 1).firstOrNull { row -> row.propertyPath == "battery" }?.value?.toString() }
			?: "-"
}
