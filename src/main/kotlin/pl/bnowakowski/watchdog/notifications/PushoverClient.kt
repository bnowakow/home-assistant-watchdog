// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.notifications

import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.ObjectMapper

@Component
class PushoverClient(
	private val properties: NotificationProperties,
	restClientBuilder: RestClient.Builder,
	private val objectMapper: ObjectMapper,
) {
	private val restClient: RestClient = restClientBuilder
		.baseUrl(properties.pushover.baseUrl.trimEnd('/'))
		.requestFactory(
			SimpleClientHttpRequestFactory().apply {
				setConnectTimeout(properties.pushover.connectTimeout.toMillis().toInt())
				setReadTimeout(properties.pushover.readTimeout.toMillis().toInt())
			},
		)
		.build()

	fun validateUser(userKey: String, devices: Collection<String>): PushoverUserValidation {
		ensureConfigured()
		val response = post("/1/users/validate.json", baseForm(userKey), "validate Pushover user")
		if (response.status != 1) {
			throw PushoverException("Pushover user validation failed: ${response.errorMessage()}")
		}
		val availableDevices = PushoverDevices.normalize(response.devices.orEmpty())
		val unknownDevices = PushoverDevices.normalize(devices)
			.filterNot { it in availableDevices }
		if (unknownDevices.isNotEmpty()) {
			throw PushoverException("Pushover device not valid for user: ${unknownDevices.joinToString(", ")}")
		}
		return PushoverUserValidation(availableDevices)
	}

	fun send(message: PushoverMessage) {
		ensureConfigured()
		val form = baseForm(message.userKey)
		form.add("title", message.title)
		form.add("message", message.message)
		form.add("priority", message.priority.toString())
		PushoverDevices.format(message.devices)?.let { form.add("device", it) }
		val response = post("/1/messages.json", form, "send Pushover notification")
		if (response.status != 1) {
			throw PushoverException("Pushover notification failed: ${response.errorMessage()}")
		}
	}

	private fun baseForm(userKey: String): LinkedMultiValueMap<String, String> =
		LinkedMultiValueMap<String, String>().apply {
			add("token", properties.pushover.appToken.trim())
			add("user", userKey.trim())
		}

	private fun post(path: String, form: LinkedMultiValueMap<String, String>, action: String): PushoverApiResponse =
		try {
			restClient.post()
				.uri(path)
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.body(form)
				.retrieve()
				.body(PushoverApiResponse::class.java)
				?: throw PushoverException("Pushover did not return a response")
		} catch (ex: RestClientResponseException) {
			val detail = responseErrorDetail(ex)
			throw PushoverException("Could not $action: HTTP ${ex.statusCode.value()}${detail?.let { ": $it" } ?: ""}", ex)
		} catch (ex: RestClientException) {
			throw PushoverException("Could not $action: ${ex.message ?: ex.javaClass.simpleName}", ex)
		}

	private fun responseErrorDetail(ex: RestClientResponseException): String? {
		val body = ex.responseBodyAsString.trim().takeIf { it.isNotBlank() } ?: return null
		return runCatching {
			objectMapper.readValue(body, PushoverApiResponse::class.java)
				.errorMessage()
				.takeIf { it.isNotBlank() && !it.startsWith("status=") }
		}.getOrNull() ?: body.replace(Regex("\\s+"), " ").take(MAX_ERROR_DETAIL_LENGTH)
	}

	private fun ensureConfigured() {
		require(properties.enabled) { "Notifications are disabled" }
		require(properties.pushover.appToken.isNotBlank()) {
			"app.notifications.pushover.app-token must be configured"
		}
	}

	private companion object {
		const val MAX_ERROR_DETAIL_LENGTH = 300
	}
}

class PushoverException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class PushoverApiResponse(
	val status: Int = 0,
	val errors: List<String>? = null,
	val devices: List<String>? = null,
	val request: String? = null,
) {
	fun errorMessage(): String =
		errors?.joinToString("; ")?.takeIf { it.isNotBlank() } ?: "status=$status"
}
