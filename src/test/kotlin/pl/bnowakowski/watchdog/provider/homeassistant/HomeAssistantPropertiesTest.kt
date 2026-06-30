// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.provider.homeassistant

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class HomeAssistantPropertiesTest {
	private val contextRunner = ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration::class.java)

	@Test
	fun `keeps certificate checks enabled by default`() {
		contextRunner.run { context ->
			assertFalse(context.getBean(HomeAssistantProperties::class.java).skipCertificateChecks)
		}
	}

	@Test
	fun `binds explicit certificate check skip flag`() {
		contextRunner
			.withPropertyValues("watchdog.home-assistant.skip-certificate-checks=true")
			.run { context ->
				assertTrue(context.getBean(HomeAssistantProperties::class.java).skipCertificateChecks)
			}
	}

	@Configuration
	@EnableConfigurationProperties(HomeAssistantProperties::class)
	private class TestConfiguration
}
