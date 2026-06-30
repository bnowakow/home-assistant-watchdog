// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider.zigbee2mqtt

fun interface MqttMessageHandler {
	fun handle(topic: String, payload: ByteArray)
}

interface MqttGateway {
	fun subscribe(topicFilter: String, handler: MqttMessageHandler)

	fun publish(topic: String, payload: ByteArray)
}
