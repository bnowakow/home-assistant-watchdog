// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider.zigbee2mqtt

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("watchdog.mqtt")
data class MqttProperties(
	val enabled: Boolean = true,
	@field:NotBlank
	val brokerUri: String = "tcp://core-mosquitto:1883",
	val username: String = "addons",
	val password: String = "",
	@field:NotBlank
	val clientId: String = "home-assistant-watchdog",
	@field:Positive
	val connectionTimeoutSeconds: Int = 10,
	@field:Positive
	val keepAliveSeconds: Int = 30,
	@field:Positive
	val reconnectDelaySeconds: Int = 30,
)

@Validated
@ConfigurationProperties("watchdog.zigbee2mqtt")
data class Zigbee2MqttProperties(
	@field:NotBlank
	val baseTopic: String = "zigbee2mqtt-2",
) {
	fun normalizedBaseTopic(): String = baseTopic.trim().trim('/')
}
