// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider.zigbee2mqtt

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "watchdog.mqtt", name = ["enabled"], havingValue = "false")
class NoopMqttGateway : MqttGateway {
	override fun subscribe(topicFilter: String, handler: MqttMessageHandler) = Unit

	override fun publish(topic: String, payload: ByteArray) {
		throw MqttGatewayUnavailableException("MQTT is disabled")
	}
}
