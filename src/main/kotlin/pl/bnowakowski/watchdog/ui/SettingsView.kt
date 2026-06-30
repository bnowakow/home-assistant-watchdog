package pl.bnowakowski.watchdog.ui

import com.vaadin.flow.component.html.H2
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.Route
import jakarta.annotation.security.PermitAll
import pl.bnowakowski.watchdog.checks.CheckProperties
import pl.bnowakowski.watchdog.history.ParameterHistoryProperties
import pl.bnowakowski.watchdog.notifications.NotificationProperties
import pl.bnowakowski.watchdog.provider.homeassistant.HomeAssistantProperties
import pl.bnowakowski.watchdog.provider.zigbee2mqtt.MqttProperties
import pl.bnowakowski.watchdog.provider.zigbee2mqtt.Zigbee2MqttProperties

@Route("settings", layout = MainLayout::class)
@PermitAll
class SettingsView(
	private val checkProperties: CheckProperties,
	private val mqttProperties: MqttProperties,
	private val zigbee2MqttProperties: Zigbee2MqttProperties,
	private val homeAssistantProperties: HomeAssistantProperties,
	private val notificationProperties: NotificationProperties,
	private val parameterHistoryProperties: ParameterHistoryProperties,
) : VerticalLayout() {
	init {
		add(
			H2("Scheduler"),
			Paragraph("Checks enabled: ${checkProperties.enabled}"),
			Paragraph("Interval: ${checkProperties.intervalSeconds} seconds"),
			H2("MQTT"),
			Paragraph("MQTT enabled: ${mqttProperties.enabled}"),
			Paragraph("Broker URI: ${mqttProperties.brokerUri}"),
			Paragraph("Zigbee2MQTT base topic: ${zigbee2MqttProperties.baseTopic}"),
			H2("Home Assistant"),
			Paragraph("Home Assistant enabled: ${homeAssistantProperties.enabled}"),
			Paragraph("Base URL: ${homeAssistantProperties.baseUrl}"),
			Paragraph("Token configured: ${homeAssistantProperties.token.isNotBlank()}"),
			Paragraph("Custom service mappings: ${homeAssistantProperties.serviceCalls.size}"),
			H2("Notifications"),
			Paragraph("Notifications enabled: ${notificationProperties.enabled}"),
			Paragraph("Pushover configured: ${notificationProperties.pushoverConfigured}"),
			H2("Parameter History"),
			Paragraph("Retention: ${parameterHistoryProperties.retentionDays} days"),
			Paragraph("Record unchanged samples: ${parameterHistoryProperties.recordUnchanged}"),
		)
	}
}
