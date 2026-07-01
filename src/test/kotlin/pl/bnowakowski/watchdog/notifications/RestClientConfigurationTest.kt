// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.notifications

import kotlin.test.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper

class RestClientConfigurationTest {
	private val contextRunner = ApplicationContextRunner()
		.withUserConfiguration(RestClientConfiguration::class.java)
		.withBean(ObjectMapper::class.java, { ObjectMapper() })
		.withBean(NotificationProperties::class.java, {
			NotificationProperties(
				enabled = true,
				pushoverUserKeyEncryptionSecret = "1234567890123456",
				pushover = NotificationProperties.Pushover(appToken = "app-token"),
			)
		})
		.withBean(PushoverClient::class.java)

	@Test
	fun `provides rest client builder for pushover client`() {
		contextRunner.run { context ->
			kotlin.test.assertNotNull(context.getBean(RestClient.Builder::class.java))
			kotlin.test.assertNotNull(context.getBean(PushoverClient::class.java))
		}
	}
}
