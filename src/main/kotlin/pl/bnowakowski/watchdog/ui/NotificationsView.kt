package pl.bnowakowski.watchdog.ui

import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.textfield.PasswordField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.Route
import jakarta.annotation.security.PermitAll
import pl.bnowakowski.watchdog.notifications.NotificationPreferenceInput
import pl.bnowakowski.watchdog.notifications.NotificationPreferenceService
import pl.bnowakowski.watchdog.notifications.NotificationQueries
import pl.bnowakowski.watchdog.security.CurrentAppUserService

@Route("notifications", layout = MainLayout::class)
@PermitAll
class NotificationsView(
	private val uiQueries: UiQueries,
	private val notificationQueries: NotificationQueries,
	private val preferenceService: NotificationPreferenceService,
	private val currentAppUserService: CurrentAppUserService,
) : VerticalLayout() {
	init {
		setSizeFull()
		val appUserId = currentAppUserService.currentAppUserId()
		val preference = notificationQueries.findPushoverPreference(appUserId)
		val userKey = PasswordField("Pushover user key").apply {
			placeholder = preference?.pushoverUserKeySuffix?.let { "Saved key ending in $it" } ?: ""
		}
		val devices = TextField("Device targets").apply {
			value = preference?.pushoverDevices?.joinToString(", ").orEmpty()
		}
		val importDevices = Button("Import devices from Pushover") {
			runCatching {
				preferenceService.importPushoverDevices(userKey.value)
			}.onSuccess { importedDevices ->
				devices.value = importedDevices.joinToString(", ")
				Notification.show("Imported ${importedDevices.size} Pushover devices")
			}.onFailure {
				Notification.show("Could not import Pushover devices: ${it.message}")
			}
		}
		val recovery = Checkbox("Recovery notifications").apply {
			value = preference?.notifyRecoveryEnabled ?: true
		}
		val save = Button("Save Pushover") {
			runCatching {
				preferenceService.savePushoverPreference(
					appUserId = appUserId,
					input = NotificationPreferenceInput(
						pushoverUserKey = userKey.value,
						pushoverDevices = listOf(devices.value),
						notifyRecoveryEnabled = recovery.value,
					),
				)
			}.onSuccess {
				userKey.clear()
				userKey.placeholder = "Saved key ending in ${it.pushoverUserKeySuffix}"
				Notification.show("Pushover preferences saved")
			}.onFailure {
				Notification.show("Could not save Pushover preferences: ${it.message}")
			}
		}
		val grid = Grid(NotificationRow::class.java, false).apply {
			addColumn(UiDateTimes.relativeRenderer<NotificationRow> { it.createdAt }).setHeader("Created")
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
			userKey,
			HorizontalLayout(devices, importDevices),
			recovery,
			save,
			H2("History"),
			grid,
		)
		expand(grid)
	}
}
