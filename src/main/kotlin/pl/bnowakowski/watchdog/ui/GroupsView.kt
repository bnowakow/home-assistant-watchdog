package pl.bnowakowski.watchdog.ui

import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.notification.Notification
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
	private val objectMapper: ObjectMapper,
) : VerticalLayout() {
	private val groups = Grid(DeviceGroup::class.java, false)
	private val rules = Grid(DeviceGroupRule::class.java, false)
	private var selectedGroup: DeviceGroup? = null

	init {
		setSizeFull()
		configureGroups()
		configureRules()
		add(groupEditor(), groups, membershipEditor(), ruleEditor(), rules)
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
		val property = TextField("Property path")
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
			property,
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
						propertyPath = property.value.trim().takeIf { it.isNotBlank() },
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
		refreshRules()
	}

	private fun refreshRules() {
		val group = selectedGroup?.id?.let(groupRepository::findByIdOrNull)
		selectedGroup = group
		rules.setItems(group?.id?.let(ruleRepository::findAllByGroupId).orEmpty())
	}
}
