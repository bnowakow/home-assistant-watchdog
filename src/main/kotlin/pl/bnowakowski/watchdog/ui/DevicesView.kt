package pl.bnowakowski.watchdog.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
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
import pl.bnowakowski.watchdog.domain.ProviderType
import pl.bnowakowski.watchdog.persistence.DeviceRepository
import pl.bnowakowski.watchdog.service.DeviceDiscoveryImportService
import pl.bnowakowski.watchdog.service.DeviceInventoryService

@Route("devices", layout = MainLayout::class)
@PermitAll
class DevicesView(
	private val deviceRepository: DeviceRepository,
	private val deviceDiscoveryImportService: DeviceDiscoveryImportService,
	private val deviceInventoryService: DeviceInventoryService,
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
				Button("Detail") { ui.ifPresent { ui -> device.id?.let { ui.navigate("devices/$it") } } },
				Button(if (device.enabled) "Disable" else "Enable") {
					deviceRepository.save(device.copy(enabled = !device.enabled))
					refresh()
				},
				Button("Edit") { openEditor(device) },
			)
		}.setHeader("Actions").setAutoWidth(true)
		grid.setSizeFull()
		add(importDeviceEditor(), createDeviceEditor(), grid)
		expand(grid)
		refresh()
	}

	private fun importDeviceEditor(): HorizontalLayout {
		val provider = ComboBox<ProviderType>("Import from").apply {
			setItems(*ProviderType.entries.toTypedArray())
			placeholder = "All providers"
		}
		val import = Button("Import discovered devices") {
			runCatching {
				deviceDiscoveryImportService.importDiscoveredDevices(provider.value)
			}.onSuccess { report ->
				Notification.show(
					"Discovered ${report.discoveredCount}, imported ${report.importedCount}, " +
						"skipped ${report.skippedExistingCount}, failed ${report.failedCount}",
				)
				refresh()
			}.onFailure {
				Notification.show("Could not import devices: ${it.message}")
			}
		}

		return HorizontalLayout(provider, import)
	}

	private fun createDeviceEditor(): VerticalLayout {
		val provider = ComboBox<ProviderType>("Provider").apply {
			setItems(*ProviderType.entries.toTypedArray())
			value = ProviderType.ZIGBEE2MQTT
		}
		val providerDeviceId = TextField("External ID")
		val ieeeAddress = TextField("IEEE address")
		val friendlyName = TextField("Friendly name")
		val displayName = TextField("Display name")
		val modelKey = TextField("Model key")
		val modelName = TextField("Model name")
		val power = ComboBox<PowerSource>("Power").apply {
			setItems(*PowerSource.entries.toTypedArray())
			value = PowerSource.UNKNOWN
		}
		val criticality = ComboBox<Criticality>("Criticality").apply {
			setItems(*Criticality.entries.toTypedArray())
			value = Criticality.NORMAL
		}
		val enabled = Checkbox("Enabled").apply { value = true }
		val create = Button("Create device") {
			val selectedProvider = provider.value ?: ProviderType.ZIGBEE2MQTT
			val externalId = providerDeviceId.value.trim()
			val fallbackName = displayName.value.trim()
				.ifBlank { friendlyName.value.trim() }
				.ifBlank { externalId }
			val zigbeeIeeeAddress = ieeeAddress.value.trim()
				.ifBlank { if (selectedProvider == ProviderType.ZIGBEE2MQTT) externalId else "" }
			runCatching {
				require(modelKey.value.trim().isNotBlank()) { "Model key is required" }
				deviceInventoryService.save(
					Device(
						providerType = selectedProvider,
						providerDeviceId = externalId,
						ieeeAddress = zigbeeIeeeAddress.takeIf { it.isNotBlank() },
						friendlyName = friendlyName.value.trim().ifBlank { fallbackName },
						displayName = fallbackName,
						modelKey = modelKey.value.trim(),
						modelName = modelName.value.trim().takeIf { it.isNotBlank() },
						powerSource = power.value ?: PowerSource.UNKNOWN,
						criticality = criticality.value ?: Criticality.NORMAL,
						enabled = enabled.value,
					),
				)
			}.onSuccess {
				listOf(providerDeviceId, ieeeAddress, friendlyName, displayName, modelKey, modelName).forEach(TextField::clear)
				power.value = PowerSource.UNKNOWN
				criticality.value = Criticality.NORMAL
				enabled.value = true
				Notification.show("Device created")
				refresh()
			}.onFailure {
				Notification.show("Could not create device: ${it.message}")
			}
		}

		return VerticalLayout(
			HorizontalLayout(provider, providerDeviceId, ieeeAddress, friendlyName, displayName),
			HorizontalLayout(modelKey, modelName, power, criticality, enabled, create),
		).apply {
			setWidthFull()
		}
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
