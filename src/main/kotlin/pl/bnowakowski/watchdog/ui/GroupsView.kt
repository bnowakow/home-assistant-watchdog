package pl.bnowakowski.watchdog.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.HasEnabled
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.IntegerField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.Route
import jakarta.annotation.security.PermitAll
import org.springframework.data.repository.findByIdOrNull
import pl.bnowakowski.watchdog.domain.CheckMode
import pl.bnowakowski.watchdog.domain.ComparisonOperator
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
import tools.jackson.databind.ObjectMapper

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
	private val rules = Grid(DeviceGroupRule::class.java, false)
	private val selectedGroupName = TextField("Selected group name")
	private val selectedGroupActions = HorizontalLayout()
	private val propertyPath = ComboBox<String>("Property path").apply {
		isAllowCustomValue = true
		addCustomValueSetListener { value = it.detail.trim() }
	}
	private var selectedGroup: DeviceGroup? = null

	init {
		setSizeFull()
		configureGroups()
		configureRules()
		add(groupEditor(), selectedGroupEditor(), groups, membershipEditor(), ruleEditor(), rules)
		expand(groups, rules)
		refresh()
	}

	private fun configureGroups() {
		groups.addColumn(DeviceGroup::name).setHeader("Group")
		groups.addColumn { it.providerType?.name ?: "-" }.setHeader("Locked provider")
		groups.addColumn { it.modelKey ?: "-" }.setHeader("Locked model")
		groups.addColumn(DeviceGroup::priority).setHeader("Priority")
		groups.addColumn { if (it.enabled) "Enabled" else "Disabled" }.setHeader("State")
		groups.addComponentColumn { group ->
			HorizontalLayout(
				Button("Select") {
					selectedGroup = group
					refreshSelectedGroupEditor()
					refreshPropertyPaths()
					refreshRules()
				},
				Button(if (group.enabled) "Disable" else "Enable") {
					groupRepository.save(group.copy(enabled = !group.enabled))
					refresh()
				},
			)
		}.setHeader("Actions")
		groups.setSizeFull()
	}

	private fun configureRules() {
		rules.addColumn(DeviceGroupRule::ruleType).setHeader("Type")
		rules.addColumn { it.propertyPath ?: "-" }.setHeader("Property")
		rules.addColumn(DeviceGroupRule::comparisonOperator).setHeader("Comparison")
		rules.addColumn { it.desiredValue?.toString() ?: "-" }.setHeader("Desired")
		rules.addColumn(DeviceGroupRule::checkMode).setHeader("Mode")
		rules.addColumn(DeviceGroupRule::severity).setHeader("Severity")
		rules.addColumn { if (it.enabled) "Enabled" else "Disabled" }.setHeader("State")
		rules.addComponentColumn { rule ->
			Button(if (rule.enabled) "Disable" else "Enable") {
				ruleRepository.save(rule.copy(enabled = !rule.enabled))
				refreshRules()
			}
		}.setHeader("Actions")
		rules.setSizeFull()
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
			ConfirmDialog().apply {
				setHeader("Delete group")
				setText("Delete ${group.name}? Rules and memberships for this group will also be removed.")
				setCancelable(true)
				setConfirmText("Delete")
				setConfirmButtonTheme("error primary")
				addConfirmListener {
					groupRepository.deleteById(group.id)
					selectedGroup = null
					Notification.show("Group deleted")
					refresh()
				}
			}.open()
		}.apply {
			addThemeVariants(ButtonVariant.LUMO_ERROR)
		}
		selectedGroupActions.add(save, delete)
		return HorizontalLayout(selectedGroupName, selectedGroupActions).apply {
			alignItems = Alignment.END
		}
	}

	private fun membershipEditor(): HorizontalLayout {
		val device = ComboBox<pl.bnowakowski.watchdog.domain.Device>("Device").apply {
			setItemLabelGenerator { "${it.displayName} (${it.providerType}/${it.modelKey})" }
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
				refresh()
			}
		}
		return HorizontalLayout(device, add, remove).apply {
			addAttachListener {
				device.setItems(deviceRepository.findAll().sortedBy { it.displayName })
			}
		}
	}

	private fun ruleEditor(): HorizontalLayout {
		val ruleType = ComboBox<RuleType>("Rule").apply {
			setItems(*RuleType.entries.toTypedArray())
			value = RuleType.DESIRED_PROPERTY
		}
		val comparison = ComboBox<ComparisonOperator>("Comparison").apply {
			setItems(*ComparisonOperator.entries.toTypedArray())
			value = ComparisonOperator.EQUALS
		}
		val desired = TextField("Desired JSON")
		val mode = ComboBox<CheckMode>("Mode").apply {
			setItems(*CheckMode.entries.toTypedArray())
			value = CheckMode.OBSERVE_ONLY
		}
		val severity = ComboBox<Severity>("Severity").apply {
			setItems(*Severity.entries.toTypedArray())
			value = Severity.WARNING
		}
		val notifyMismatch = Checkbox("Mismatch")
		val notifyRecovery = Checkbox("Recovery").apply { value = true }
		return HorizontalLayout(
			ruleType,
			propertyPath,
			comparison,
			desired,
			mode,
			severity,
			notifyMismatch,
			notifyRecovery,
			Button("Add rule") {
				val group = selectedGroup
				if (group?.id == null) {
					Notification.show("Select a group first")
					return@Button
				}
				val desiredJson = desired.value.trim().takeIf { it.isNotBlank() }?.let(objectMapper::readTree)
				ruleRepository.save(
					DeviceGroupRule(
						groupId = group.id,
						providerType = group.providerType,
						ruleType = ruleType.value ?: RuleType.DESIRED_PROPERTY,
						propertyPath = propertyPath.value?.trim()?.takeIf { it.isNotBlank() },
						comparisonOperator = comparison.value ?: ComparisonOperator.EQUALS,
						desiredValue = desiredJson,
						checkMode = mode.value ?: CheckMode.OBSERVE_ONLY,
						severity = severity.value ?: Severity.WARNING,
						notifyOnMismatch = notifyMismatch.value,
						notifyOnRecovery = notifyRecovery.value,
					),
				)
				refreshRules()
			},
		)
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
		rules.setItems(group?.id?.let(ruleRepository::findAllByGroupId).orEmpty())
		refreshPropertyPaths()
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
}
