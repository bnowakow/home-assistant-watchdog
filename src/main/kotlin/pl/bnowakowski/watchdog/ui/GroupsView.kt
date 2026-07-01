package pl.bnowakowski.watchdog.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasEnabled
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.IntegerField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.Route
import jakarta.annotation.security.PermitAll
import java.time.Instant
import org.springframework.data.repository.findByIdOrNull
import pl.bnowakowski.watchdog.domain.CheckMode
import pl.bnowakowski.watchdog.domain.ComparisonOperator
import pl.bnowakowski.watchdog.domain.Device
import pl.bnowakowski.watchdog.domain.DeviceGroup
import pl.bnowakowski.watchdog.domain.DeviceGroupRule
import pl.bnowakowski.watchdog.domain.JsonDefaults
import pl.bnowakowski.watchdog.domain.RuleType
import pl.bnowakowski.watchdog.domain.Severity
import pl.bnowakowski.watchdog.persistence.DeviceGroupRepository
import pl.bnowakowski.watchdog.persistence.DeviceGroupRuleRepository
import pl.bnowakowski.watchdog.persistence.DeviceRepository
import pl.bnowakowski.watchdog.provider.DeviceProviderRegistry
import pl.bnowakowski.watchdog.provider.UnknownDeviceProviderException
import pl.bnowakowski.watchdog.service.DeviceGroupService
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.JsonNodeFactory

@Route("groups", layout = MainLayout::class)
@PermitAll
class GroupsView(
	private val groupRepository: DeviceGroupRepository,
	private val ruleRepository: DeviceGroupRuleRepository,
	private val deviceRepository: DeviceRepository,
	private val groupService: DeviceGroupService,
	private val uiQueries: UiQueries,
	private val providerRegistry: DeviceProviderRegistry,
	private val objectMapper: ObjectMapper,
) : VerticalLayout() {
	private val groups = Grid(DeviceGroup::class.java, false)
	private val groupMembers = Grid(Device::class.java, false)
	private val rules = Grid(DeviceGroupRule::class.java, false)
	private val selectedGroupName = TextField("Selected group name")
	private val selectedGroupActions = HorizontalLayout()
	private val ruleType = ComboBox<RuleType>("Rule").apply {
		setItems(*RuleType.entries.toTypedArray())
		value = RuleType.DESIRED_PROPERTY
	}
	private val propertyPath = ComboBox<String>("Property path").apply {
		isAllowCustomValue = true
		addCustomValueSetListener {
			value = it.detail.trim()
			refreshCurrentValue()
		}
	}
	private val endpoint = ComboBox<String>("Endpoint").apply {
		isAllowCustomValue = true
		placeholder = "Optional"
		setWidth("160px")
		addCustomValueSetListener {
			value = it.detail.trim()
		}
	}
	private val currentValue = TextField("Current value").apply {
		isReadOnly = true
		placeholder = "Pick a property path"
		setWidth("min(420px, 100%)")
	}
	private val comparison = ComboBox<ComparisonOperator>("Comparison").apply {
		setItems(*ComparisonOperator.entries.toTypedArray())
		value = ComparisonOperator.EQUALS
	}
	private val desired = TextField("Desired value")
	private val mode = ComboBox<CheckMode>("Mode").apply {
		setItems(*CheckMode.entries.toTypedArray())
		value = CheckMode.OBSERVE_ONLY
	}
	private val severity = ComboBox<Severity>("Severity").apply {
		setItems(*Severity.entries.toTypedArray())
		value = Severity.WARNING
	}
	private val notifyMismatch = Checkbox("Mismatch")
	private val notifyRecovery = Checkbox("Recovery").apply { value = true }
	private val copyCurrentValue = Button("Copy") {
		val value = currentValue.value.takeIf { it.isNotBlank() }
		if (value == null) {
			Notification.show("No current value to copy")
			return@Button
		}
		ui.ifPresent { ui ->
			ui.page.executeJs("navigator.clipboard.writeText($0)", value)
			Notification.show("Current value copied")
		}
	}.apply {
		isEnabled = false
	}
	private var selectedGroup: DeviceGroup? = null
	private var selectedRule: DeviceGroupRule? = null

	init {
		setSizeFull()
		propertyPath.addValueChangeListener {
			refreshEndpointSuggestions()
			refreshCurrentValue()
		}
		configureGroups()
		configureGroupMembers()
		configureRules()
		add(
			groupEditor(),
			selectedGroupEditor(),
			groups,
			membershipEditor(),
			H3("Group devices"),
			groupMembers,
			ruleEditor(),
			rules,
		)
		expand(groups, groupMembers, rules)
		refresh()
	}

	private fun configureGroups() {
		groups.addColumn(DeviceGroup::name).setHeader("Group")
		groups.addColumn { it.providerType?.name ?: "-" }.setHeader("Locked provider")
		groups.addColumn { it.modelKey ?: "-" }.setHeader("Locked model")
		groups.addColumn(DeviceGroup::priority).setHeader("Priority")
		groups.addColumn { if (it.enabled) "Enabled" else "Disabled" }.setHeader("State")
		groups.addItemClickListener { event ->
			selectGroup(event.item)
		}
		groups.addComponentColumn { group ->
			HorizontalLayout(
				Button(if (group.enabled) "Disable" else "Enable") {
					groupRepository.save(group.copy(enabled = !group.enabled))
					refresh()
				},
				Button("Delete") {
					confirmDeleteGroup(group)
				}.apply {
					addThemeVariants(ButtonVariant.LUMO_ERROR)
				},
			)
		}.setHeader("Actions")
		groups.setSizeFull()
	}

	private fun selectGroup(group: DeviceGroup) {
		selectedGroup = group
		selectedRule = null
		clearRuleEditor()
		refreshSelectedGroupEditor()
		refreshPropertyPaths()
		refreshRules()
	}

	private fun configureRules() {
		rules.addColumn(DeviceGroupRule::ruleType).setHeader("Type")
		rules.addColumn { it.propertyPath ?: "-" }.setHeader("Property")
		rules.addColumn { it.endpoint ?: "-" }.setHeader("Endpoint")
		rules.addColumn(DeviceGroupRule::comparisonOperator).setHeader("Comparison")
		rules.addColumn { it.desiredValue?.toString() ?: "-" }.setHeader("Desired")
		rules.addColumn(DeviceGroupRule::checkMode).setHeader("Mode")
		rules.addColumn(DeviceGroupRule::severity).setHeader("Severity")
		rules.addColumn { if (it.enabled) "Enabled" else "Disabled" }.setHeader("State")
		rules.addComponentColumn { rule ->
			HorizontalLayout(
				Button("Edit") {
					selectedRule = rule
					loadRuleEditor(rule)
					Notification.show("Rule loaded for editing")
				},
				Button(if (rule.enabled) "Disable" else "Enable") {
					ruleRepository.save(rule.copy(enabled = !rule.enabled))
					refreshRules()
				},
				Button("Remove") {
					val ruleId = rule.id
					if (ruleId == null) {
						Notification.show("Rule has not been persisted yet")
						return@Button
					}
					ConfirmDialog().apply {
						setHeader("Remove rule")
						setText("Remove ${rule.ruleType} rule for ${rule.propertyPath ?: "this group"}?")
						setCancelable(true)
						setConfirmText("Remove")
						setConfirmButtonTheme("error primary")
						addConfirmListener {
							ruleRepository.deleteById(ruleId)
							Notification.show("Rule removed")
							refreshRules()
						}
					}.open()
				}.apply {
					addThemeVariants(ButtonVariant.LUMO_ERROR)
				},
			)
		}.setHeader("Actions")
		rules.setSizeFull()
	}

	private fun configureGroupMembers() {
		groupMembers.addColumn(Device::displayName).setHeader("Name").setAutoWidth(true)
		groupMembers.addColumn(Device::providerType).setHeader("Provider").setAutoWidth(true)
		groupMembers.addColumn(Device::providerDeviceId).setHeader("External ID").setAutoWidth(true)
		groupMembers.addColumn(Device::modelKey).setHeader("Model").setAutoWidth(true)
		groupMembers.addColumn(Device::powerSource).setHeader("Power")
		groupMembers.addColumn(Device::criticality).setHeader("Criticality")
		groupMembers.addColumn { if (it.enabled) "Enabled" else "Disabled" }.setHeader("Monitoring")
		groupMembers.addComponentColumn { checksStatusCell(it.skipChecks) }.setHeader("Checks")
		groupMembers.addComponentColumn { device ->
			HorizontalLayout(
				Button("Detail") { ui.ifPresent { ui -> device.id?.let { ui.navigate("devices/$it") } } },
				Button(if (device.skipChecks) "Check" else "Skip") {
					deviceRepository.save(device.copy(skipChecks = !device.skipChecks, updatedAt = Instant.now()))
					Notification.show(
						if (device.skipChecks) {
							"Device checks resumed"
						} else {
							"Device will be skipped in check runs"
						},
					)
					refreshGroupMembers()
				},
				Button("Remove") {
					val groupId = selectedGroup?.id
					val deviceId = device.id
					if (groupId != null && deviceId != null) {
						uiQueries.removeMembership(deviceId, groupId)
						Notification.show("Device removed from group")
						refresh()
					}
				}.apply {
					addThemeVariants(ButtonVariant.LUMO_ERROR)
				},
			)
		}.setHeader("Actions").setAutoWidth(true)
		groupMembers.setSizeFull()
	}

	private fun groupEditor(): HorizontalLayout {
		val name = TextField("Name")
		val priority = IntegerField("Priority").apply { value = 0 }
		return HorizontalLayout(
			name,
			priority,
			Button("Create group") {
				groupRepository.save(
					DeviceGroup(
						name = name.value.trim(),
						priority = priority.value ?: 0,
						notificationDefaults = JsonDefaults.emptyObject(),
					),
				)
				name.clear()
				refresh()
			},
		)
	}

	private fun selectedGroupEditor(): HorizontalLayout {
		selectedGroupName.placeholder = "Select a group"
		val save = Button("Save name") {
			val group = selectedGroup
			val newName = selectedGroupName.value.trim()
			if (group?.id == null) {
				Notification.show("Select a group first")
				return@Button
			}
			if (newName.isBlank()) {
				Notification.show("Group name cannot be blank")
				return@Button
			}
			selectedGroup = groupRepository.save(group.copy(name = newName))
			Notification.show("Group name updated")
			refresh()
		}
		val delete = Button("Delete group") {
			val group = selectedGroup
			if (group?.id == null) {
				Notification.show("Select a group first")
				return@Button
			}
			confirmDeleteGroup(group)
		}.apply {
			addThemeVariants(ButtonVariant.LUMO_ERROR)
		}
		selectedGroupActions.add(save, delete)
		return HorizontalLayout(selectedGroupName, selectedGroupActions).apply {
			alignItems = Alignment.END
		}
	}

	private fun membershipEditor(): HorizontalLayout {
		val device = ComboBox<Device>("Device").apply {
			setItemLabelGenerator(::deviceOptionLabel)
			setWidth("min(760px, 100%)")
		}
		val add = Button("Assign") {
			val groupId = selectedGroup?.id
			val deviceId = device.value?.id
			if (groupId == null || deviceId == null) {
				Notification.show("Select a group and a device first")
				return@Button
			}
			runCatching { groupService.addDeviceToGroup(deviceId, groupId) }
				.onSuccess { Notification.show("Device assigned") }
				.onFailure { Notification.show("Could not assign device: ${it.message}") }
			refresh()
		}
		val remove = Button("Remove") {
			val groupId = selectedGroup?.id
			val deviceId = device.value?.id
			if (groupId != null && deviceId != null) {
				uiQueries.removeMembership(deviceId, groupId)
				Notification.show("Device removed from group")
				refresh()
			}
		}
		return HorizontalLayout(device, add, remove).apply {
			width = "100%"
			addAttachListener {
				val devices = deviceRepository.findAll().sortedBy { it.displayName.lowercase() }
				device.setItems({ item, filter -> item.matchesDeviceFilter(filter) }, devices)
			}
		}
	}

	private fun deviceOptionLabel(device: Device): String {
		val addresses = listOfNotNull(device.ieeeAddress, device.networkAddress)
			.joinToString(", ")
			.takeIf { it.isNotBlank() }
		val model = "${device.providerType}/${device.modelKey}"
		return listOfNotNull(device.displayName, addresses, model)
			.joinToString(" - ")
	}

	private fun Device.matchesDeviceFilter(filter: String): Boolean {
		val normalizedFilter = filter.trim().lowercase()
		if (normalizedFilter.isBlank()) {
			return true
		}
		return listOfNotNull(
			displayName,
			friendlyName,
			providerDeviceId,
			ieeeAddress,
			networkAddress,
			modelKey,
			modelName,
		).any { it.lowercase().contains(normalizedFilter) }
	}

	private fun ruleEditor(): Component {
		val addRule = Button("Add rule") {
			val group = selectedGroup
			if (group?.id == null) {
				Notification.show("Select a group first")
				return@Button
			}
			ruleRepository.save(ruleFromEditor(group))
			clearRuleEditor()
			refreshRules()
		}
		val saveRule = Button("Save rule") {
			val group = selectedGroup
			val rule = selectedRule
			if (group?.id == null || rule?.id == null) {
				Notification.show("Select a persisted rule to edit")
				return@Button
			}
			ruleRepository.save(ruleFromEditor(group, rule))
			selectedRule = null
			clearRuleEditor()
			Notification.show("Rule updated")
			refreshRules()
		}
		val clearRule = Button("Clear") {
			selectedRule = null
			clearRuleEditor()
		}
		val currentValueWithCopy = HorizontalLayout(currentValue, copyCurrentValue).apply {
			alignItems = Alignment.END
			setWidth("min(520px, 100%)")
		}
		return VerticalLayout(
			HorizontalLayout(
				ruleType,
				propertyPath,
				endpoint,
				currentValueWithCopy,
				comparison,
			).apply {
				width = "100%"
				alignItems = Alignment.END
			},
			HorizontalLayout(
				desired,
				mode,
				severity,
				notifyMismatch,
				notifyRecovery,
				addRule,
				saveRule,
				clearRule,
			).apply {
				width = "100%"
				alignItems = Alignment.END
			},
		).apply {
			width = "100%"
			setPadding(false)
			setSpacing(false)
		}
	}

	private fun refresh() {
		groups.setItems(groupRepository.findAll().sortedWith(compareByDescending<DeviceGroup> { it.priority }.thenBy { it.name }))
		refreshSelectedGroupEditor()
		refreshRules()
	}

	private fun refreshRules() {
		val group = selectedGroup?.id?.let(groupRepository::findByIdOrNull)
		selectedGroup = group
		refreshSelectedGroupEditor()
		refreshGroupMembers()
		rules.setItems(group?.id?.let(ruleRepository::findAllByGroupId).orEmpty())
		refreshPropertyPaths()
	}

	private fun ruleFromEditor(group: DeviceGroup): DeviceGroupRule =
		DeviceGroupRule(
			groupId = group.id ?: error("Group must be persisted before rules can be saved"),
			providerType = group.providerType,
			ruleType = ruleType.value ?: RuleType.DESIRED_PROPERTY,
			propertyPath = propertyPath.value?.trim()?.takeIf { it.isNotBlank() },
			endpoint = endpoint.value?.trim()?.takeIf { it.isNotBlank() },
			comparisonOperator = comparison.value ?: ComparisonOperator.EQUALS,
			desiredValue = parseDesiredValue(objectMapper, desired.value),
			checkMode = mode.value ?: CheckMode.OBSERVE_ONLY,
			severity = severity.value ?: Severity.WARNING,
			notifyOnMismatch = notifyMismatch.value,
			notifyOnRecovery = notifyRecovery.value,
		)

	private fun ruleFromEditor(group: DeviceGroup, existing: DeviceGroupRule): DeviceGroupRule =
		existing.copy(
			groupId = group.id ?: error("Group must be persisted before rules can be saved"),
			providerType = group.providerType,
			ruleType = ruleType.value ?: RuleType.DESIRED_PROPERTY,
			propertyPath = propertyPath.value?.trim()?.takeIf { it.isNotBlank() },
			endpoint = endpoint.value?.trim()?.takeIf { it.isNotBlank() },
			comparisonOperator = comparison.value ?: ComparisonOperator.EQUALS,
			desiredValue = parseDesiredValue(objectMapper, desired.value),
			checkMode = mode.value ?: CheckMode.OBSERVE_ONLY,
			severity = severity.value ?: Severity.WARNING,
			notifyOnMismatch = notifyMismatch.value,
			notifyOnRecovery = notifyRecovery.value,
			updatedAt = Instant.now(),
		)

	private fun loadRuleEditor(rule: DeviceGroupRule) {
		ruleType.value = rule.ruleType
		propertyPath.value = rule.propertyPath
		endpoint.value = rule.endpoint.orEmpty()
		comparison.value = rule.comparisonOperator
		desired.value = rule.desiredValue?.toString().orEmpty()
		mode.value = rule.checkMode
		severity.value = rule.severity
		notifyMismatch.value = rule.notifyOnMismatch
		notifyRecovery.value = rule.notifyOnRecovery
		refreshCurrentValue()
	}

	private fun clearRuleEditor() {
		ruleType.value = RuleType.DESIRED_PROPERTY
		propertyPath.clear()
		endpoint.clear()
		comparison.value = ComparisonOperator.EQUALS
		desired.clear()
		mode.value = CheckMode.OBSERVE_ONLY
		severity.value = Severity.WARNING
		notifyMismatch.value = false
		notifyRecovery.value = true
		refreshCurrentValue()
	}

	private fun refreshGroupMembers() {
		val groupId = selectedGroup?.id
		if (groupId == null) {
			groupMembers.setItems(emptyList())
			return
		}
		val memberIds = uiQueries.groupMemberIds(groupId)
		groupMembers.setItems(
			deviceRepository.findAll()
				.filter { it.id in memberIds }
				.sortedBy { it.displayName.lowercase() },
		)
	}

	private fun confirmDeleteGroup(group: DeviceGroup) {
		val groupId = group.id
		if (groupId == null) {
			Notification.show("Group has not been persisted yet")
			return
		}
		ConfirmDialog().apply {
			setHeader("Delete group")
			setText("Delete ${group.name}? Rules and memberships for this group will also be removed.")
			setCancelable(true)
			setConfirmText("Delete")
			setConfirmButtonTheme("error primary")
			addConfirmListener {
				groupRepository.deleteById(groupId)
				if (selectedGroup?.id == groupId) {
					selectedGroup = null
					selectedRule = null
					clearRuleEditor()
				}
				Notification.show("Group deleted")
				refresh()
			}
		}.open()
	}

	private fun checksStatusCell(skipChecks: Boolean): Span =
		Span(if (skipChecks) "SKIPPED" else "CHECKED").apply {
			style.set("display", "inline-flex")
			style.set("align-items", "center")
			style.set("min-width", "5.5rem")
			style.set("justify-content", "center")
			style.set("border-radius", "999px")
			style.set("font-size", "var(--lumo-font-size-xs)")
			style.set("font-weight", "700")
			style.set("padding", "0.15rem 0.5rem")
			style.set("border", "1px solid")
			if (skipChecks) {
				style.set("background-color", "#fef9c3")
				style.set("border-color", "#fde047")
				style.set("color", "#854d0e")
			} else {
				style.set("background-color", "#dcfce7")
				style.set("border-color", "#86efac")
				style.set("color", "#166534")
			}
		}

	private fun refreshSelectedGroupEditor() {
		val group = selectedGroup
		selectedGroupName.value = group?.name.orEmpty()
		selectedGroupName.isEnabled = group != null
		selectedGroupActions.children.forEach { (it as? HasEnabled)?.isEnabled = group != null }
	}

	private fun refreshPropertyPaths() {
		val paths = selectedGroup?.let(::propertyPathsFor).orEmpty()
		propertyPath.setItems(paths)
		if (propertyPath.value !in paths) {
			propertyPath.clear()
		}
		refreshEndpointSuggestions()
		refreshCurrentValue()
	}

	private fun refreshEndpointSuggestions() {
		val path = propertyPath.value?.trim()?.takeIf { it.isNotBlank() }
		val options = if (path == null) {
			emptyList()
		} else {
			selectedGroup?.let { endpointOptionsFor(it, path) }.orEmpty()
		}
		val previous = endpoint.value?.trim()?.takeIf { it.isNotBlank() }
		endpoint.setItems(options)
		endpoint.placeholder = when {
			path == null -> "Optional"
			options.isEmpty() -> "No endpoint"
			options.size == 1 -> "Suggested"
			else -> "Choose"
		}
		when {
			options.size == 1 && previous != options.single() -> endpoint.value = options.single()
			previous != null && previous in options -> endpoint.value = previous
			previous != null && options.isEmpty() -> endpoint.value = previous
			previous == null -> endpoint.clear()
		}
	}

	private fun refreshCurrentValue() {
		val path = propertyPath.value?.trim()?.takeIf { it.isNotBlank() }
		val group = selectedGroup
		if (path == null || group?.id == null) {
			currentValue.value = ""
			currentValue.placeholder = "Pick a property path"
			copyCurrentValue.isEnabled = false
			return
		}

		val memberIds = uiQueries.groupMemberIds(group.id)
		val devices = deviceRepository.findAll()
			.filter { it.id in memberIds }
			.sortedBy { it.displayName.lowercase() }
		val values = devices.map { device ->
			val value = device.id?.let(uiQueries::latestSnapshot)
				?.let { snapshot -> propertyValue(snapshot, path) }
			device.displayName to value
		}

		if (values.isEmpty()) {
			currentValue.value = ""
			currentValue.placeholder = "No group members"
			copyCurrentValue.isEnabled = false
			return
		}

		val renderedValues = values.map { it.first to it.second?.let(objectMapper::writeValueAsString) }
		val presentValues = renderedValues.mapNotNull { it.second }.distinct()
		currentValue.value = when {
			renderedValues.all { it.second == null } -> ""
			presentValues.size == 1 && renderedValues.all { it.second != null } -> presentValues.single()
			else -> renderedValues.joinToString("; ") { (deviceName, value) -> "$deviceName: ${value ?: "missing"}" }
		}
		currentValue.placeholder = if (renderedValues.all { it.second == null }) "No latest value for $path" else ""
		copyCurrentValue.isEnabled = currentValue.value.isNotBlank()
	}

	private fun propertyValue(snapshot: JsonNode, propertyPath: String): JsonNode? {
		propertiesValue(snapshot["properties"], propertyPath)?.let { return it }
		dottedValue(snapshot["payload"], propertyPath)?.let { return it }
		return dottedValue(snapshot, propertyPath)
	}

	private fun propertiesValue(properties: JsonNode?, propertyPath: String): JsonNode? {
		if (properties == null || !properties.isObject) {
			return null
		}
		properties[propertyPath]?.let { return it }
		for ((_, value) in properties.properties()) {
			if (value.get("propertyPath")?.asText() == propertyPath) {
				return value.get("value") ?: value
			}
		}
		return null
	}

	private fun dottedValue(root: JsonNode?, propertyPath: String): JsonNode? =
		propertyPath.split('.').fold(root) { node, part ->
			node?.get(part)
		}

	private fun propertyPathsFor(group: DeviceGroup): List<String> {
		val groupId = group.id ?: return emptyList()
		val memberIds = uiQueries.groupMemberIds(groupId)
		if (memberIds.isEmpty()) {
			return emptyList()
		}
		val devices = deviceRepository.findAll()
			.filter { it.id in memberIds }
		val provider = try {
			group.providerType?.let(providerRegistry::providerFor)
		} catch (_: UnknownDeviceProviderException) {
			null
		} ?: return emptyList()
		return devices
			.flatMap(provider::supportedProperties)
			.groupingBy { it.ref.propertyPath }
			.eachCount()
			.filterValues { it == devices.size }
			.keys
			.sorted()
	}

	private fun endpointOptionsFor(group: DeviceGroup, propertyPath: String): List<String> {
		val groupId = group.id ?: return emptyList()
		val memberIds = uiQueries.groupMemberIds(groupId)
		if (memberIds.isEmpty()) {
			return emptyList()
		}
		val devices = deviceRepository.findAll()
			.filter { it.id in memberIds }
		val provider = try {
			group.providerType?.let(providerRegistry::providerFor)
		} catch (_: UnknownDeviceProviderException) {
			null
		} ?: return emptyList()

		return devices
			.flatMap { device ->
				provider.supportedProperties(device)
					.filter { it.ref.propertyPath == propertyPath }
					.mapNotNull { it.ref.endpoint }
					.distinct()
			}
			.groupingBy { it }
			.eachCount()
			.filterValues { it == devices.size }
			.keys
			.sorted()
	}
}

internal fun parseDesiredValue(objectMapper: ObjectMapper, value: String): JsonNode? {
	val trimmed = value.trim()
	if (trimmed.isBlank()) {
		return null
	}
	return runCatching { objectMapper.readTree(trimmed) }
		.getOrElse { JsonNodeFactory.instance.textNode(trimmed) }
}
