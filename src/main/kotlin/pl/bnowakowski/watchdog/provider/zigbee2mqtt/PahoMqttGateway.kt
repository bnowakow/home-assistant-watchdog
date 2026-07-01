// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider.zigbee2mqtt

import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "watchdog.mqtt", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class PahoMqttGateway(
	private val properties: MqttProperties,
) : MqttGateway, SmartLifecycle {
	private val logger = LoggerFactory.getLogger(javaClass)
	private val subscriptions = CopyOnWriteArrayList<Subscription>()
	private val reconnectExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
		Thread(runnable, "watchdog-mqtt-reconnect").also { it.isDaemon = true }
	}
	private val client = MqttAsyncClient(
		properties.brokerUri,
		properties.clientId,
		MemoryPersistence(),
	)
	@Volatile
	private var running = false

	override fun start() {
		if (running) {
			return
		}

		client.setCallback(object : MqttCallbackExtended {
			override fun connectComplete(reconnect: Boolean, serverURI: String?) {
				logger.info("Connected to MQTT broker {} reconnect={}", serverURI, reconnect)
				resubscribe()
			}

			override fun connectionLost(cause: Throwable?) {
				if (cause == null) {
					logger.debug("MQTT connection lost: unknown")
				} else {
					logger.debug("MQTT connection lost: {}: {}", cause.javaClass.name, cause.message, cause)
				}
			}

			override fun messageArrived(topic: String, message: MqttMessage) {
				subscriptions
					.filter { mqttTopicMatches(it.topicFilter, topic) }
					.forEach { it.handler.handle(topic, message.payload) }
			}

			override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
		})

		runCatching {
			client.connect(connectOptions()).waitForCompletion()
			running = true
		}.onFailure {
			logger.debug(
				"Could not connect to MQTT broker {}: {}; will retry in {}s. Set WATCHDOG_MQTT_ENABLED=false to disable MQTT locally.",
				properties.brokerUri,
				it.message,
				properties.reconnectDelaySeconds,
			)
			running = false
			scheduleReconnect()
		}
	}

	override fun stop() {
		if (!running) {
			reconnectExecutor.shutdownNow()
			return
		}
		runCatching {
			client.disconnect().waitForCompletion()
		}
		reconnectExecutor.shutdownNow()
		running = false
	}

	override fun isRunning(): Boolean = running

	override fun subscribe(topicFilter: String, handler: MqttMessageHandler) {
		subscriptions += Subscription(topicFilter, handler)
		if (client.isConnected) {
			client.subscribe(topicFilter, 0)
		}
	}

	override fun publish(topic: String, payload: ByteArray) {
		if (!client.isConnected) {
			throw MqttGatewayUnavailableException("MQTT client is not connected")
		}
		client.publish(topic, payload, 0, false)
		logger.debug("Published MQTT message to {}: {}", topic, payload.toString(StandardCharsets.UTF_8))
	}

	private fun resubscribe() {
		subscriptions.forEach { subscription ->
			runCatching {
				client.subscribe(subscription.topicFilter, 0)
			}.onFailure {
				logger.debug("Could not subscribe to {}: {}", subscription.topicFilter, it.message)
			}
		}
	}

	private fun scheduleReconnect() {
		if (reconnectExecutor.isShutdown) {
			return
		}

		reconnectExecutor.schedule(
			{
				if (!running) {
					start()
				}
			},
			properties.reconnectDelaySeconds.toLong(),
			TimeUnit.SECONDS,
		)
	}

	private fun connectOptions(): MqttConnectOptions =
		MqttConnectOptions().also {
			it.isAutomaticReconnect = true
			it.isCleanSession = true
			it.connectionTimeout = properties.connectionTimeoutSeconds
			it.keepAliveInterval = properties.keepAliveSeconds
			if (properties.username.isNotBlank()) {
				it.userName = properties.username
			}
			if (properties.password.isNotBlank()) {
				it.password = properties.password.toCharArray()
			}
		}

	private data class Subscription(
		val topicFilter: String,
		val handler: MqttMessageHandler,
	)
}

class MqttGatewayUnavailableException(message: String) : RuntimeException(message)

private fun mqttTopicMatches(filter: String, topic: String): Boolean {
	val filterParts = filter.split('/')
	val topicParts = topic.split('/')
	var topicIndex = 0

	for (filterPart in filterParts) {
		when (filterPart) {
			"#" -> return true
			"+" -> {
				if (topicIndex >= topicParts.size) {
					return false
				}
				topicIndex += 1
			}
			else -> {
				if (topicIndex >= topicParts.size || filterPart != topicParts[topicIndex]) {
					return false
				}
				topicIndex += 1
			}
		}
	}

	return topicIndex == topicParts.size
}
