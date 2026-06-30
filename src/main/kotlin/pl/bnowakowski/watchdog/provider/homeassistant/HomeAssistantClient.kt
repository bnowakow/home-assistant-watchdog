// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider.homeassistant

import java.net.http.HttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.JsonNodeFactory

interface HomeAssistantClient {
	fun states(): List<HomeAssistantEntityState>

	fun state(entityId: String): HomeAssistantEntityState?

	fun callService(
		domain: String,
		service: String,
		payload: JsonNode,
	): JsonNode
}

@Component
@ConditionalOnProperty(prefix = "watchdog.home-assistant", name = ["enabled"], havingValue = "true")
class RestHomeAssistantClient(
	private val properties: HomeAssistantProperties,
	private val objectMapper: ObjectMapper,
) : HomeAssistantClient {
	private val restClient: RestClient = restClientBuilder()
		.baseUrl(properties.normalizedBaseUrl())
		.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.token.trim()}")
		.defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
		.defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
		.build()

	private fun restClientBuilder(): RestClient.Builder {
		val builder = RestClient.builder()
		if (properties.skipCertificateChecks) {
			builder.requestFactory(JdkClientHttpRequestFactory(insecureHttpClient()))
		}
		return builder
	}

	override fun states(): List<HomeAssistantEntityState> =
		restClient.get()
			.uri("/api/states")
			.retrieve()
			.body(Array<HomeAssistantStateResponse>::class.java)
			.orEmpty()
			.map { it.toEntityState() }

	override fun state(entityId: String): HomeAssistantEntityState? =
		restClient.get()
			.uri("/api/states/{entity_id}", entityId)
			.retrieve()
			.body(HomeAssistantStateResponse::class.java)
			?.toEntityState()

	override fun callService(
		domain: String,
		service: String,
		payload: JsonNode,
	): JsonNode =
		restClient.post()
			.uri("/api/services/{domain}/{service}", domain, service)
			.body(payload)
			.retrieve()
			.body(JsonNode::class.java)
			?: objectMapper.createArrayNode()

	private fun insecureHttpClient(): HttpClient =
		HttpClient.newBuilder()
			.sslContext(insecureSslContext())
			.connectTimeout(properties.connectTimeout)
			.build()

	private fun insecureSslContext(): SSLContext =
		SSLContext.getInstance("TLS").apply {
			init(null, arrayOf(insecureTrustManager()), SecureRandom())
		}

	private fun insecureTrustManager(): X509TrustManager =
		object : X509TrustManager {
			override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

			override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

			override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
		}
}

data class HomeAssistantStateResponse(
	val entity_id: String = "",
	val state: String = "",
	val attributes: JsonNode = JsonNodeFactory.instance.objectNode(),
	val last_changed: String? = null,
	val last_updated: String? = null,
) {
	fun toEntityState(): HomeAssistantEntityState =
		HomeAssistantEntityState(
			entityId = entity_id,
			state = state,
			attributes = attributes,
			lastChanged = last_changed,
			lastUpdated = last_updated,
		)
}
