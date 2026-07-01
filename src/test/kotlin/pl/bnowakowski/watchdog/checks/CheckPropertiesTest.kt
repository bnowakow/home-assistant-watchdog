// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.watchdog.checks

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class CheckPropertiesTest {
	private val contextRunner = ApplicationContextRunner()
		.withUserConfiguration(TestConfiguration::class.java)

	@Test
	fun `keeps scheduled checks enabled by default`() {
		contextRunner.run { context ->
			assertTrue(context.getBean(CheckProperties::class.java).scheduledEnabled)
			assertFalse(context.getBean(CheckProperties::class.java).runOnStartup)
			assertEquals(Duration.ofMinutes(2), context.getBean(CheckProperties::class.java).runTimeout)
		}
	}

	@Test
	fun `binds explicit run timeout`() {
		contextRunner
			.withPropertyValues("watchdog.check.run-timeout=30s")
			.run { context ->
				assertEquals(Duration.ofSeconds(30), context.getBean(CheckProperties::class.java).runTimeout)
			}
	}

	@Test
	fun `binds explicit scheduled check disable flag`() {
		contextRunner
			.withPropertyValues("watchdog.check.scheduled-enabled=false")
			.run { context ->
				assertFalse(context.getBean(CheckProperties::class.java).scheduledEnabled)
			}
	}

	@Test
	fun `binds explicit startup check flag`() {
		contextRunner
			.withPropertyValues("watchdog.check.run-on-startup=true")
			.run { context ->
				assertTrue(context.getBean(CheckProperties::class.java).runOnStartup)
			}
	}

	@Configuration
	@EnableConfigurationProperties(CheckProperties::class)
	private class TestConfiguration
}
