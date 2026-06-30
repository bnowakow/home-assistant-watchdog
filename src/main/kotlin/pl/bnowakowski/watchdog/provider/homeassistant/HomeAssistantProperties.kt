// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider.homeassistant

import jakarta.validation.constraints.NotBlank
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@Validated
@ConfigurationProperties("watchdog.home-assistant")
data class HomeAssistantProperties(
	val enabled: Boolean = false,
	@field:NotBlank
	val baseUrl: String = "https://home-assistant.localdomain.bnowakowski.pl:8123",
	val token: String = "",
	val connectTimeout: Duration = Duration.ofSeconds(3),
	val readTimeout: Duration = Duration.ofSeconds(10),
	val skipCertificateChecks: Boolean = false,
	val serviceCalls: List<HomeAssistantServiceCallMapping> = emptyList(),
) {
	fun normalizedBaseUrl(): String =
		baseUrl.trim().trimEnd('/')
}

data class HomeAssistantServiceCallMapping(
	val propertyPath: String,
	val domain: String,
	val service: String,
	val entityId: String? = null,
	val desiredValue: String? = null,
)
