// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "4.0.6"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.vaadin") version "25.1.4"
}

group = "pl.bnowakowski"
version = "0.1.23-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

extra["vaadinVersion"] = "25.1.4"

dependencies {
	implementation("com.vaadin:vaadin-spring-boot-starter")
	implementation("io.micrometer:micrometer-tracing-bridge-brave")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.springframework.boot:spring-boot-micrometer-tracing-brave")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
	implementation("org.postgresql:postgresql")
	implementation("tools.jackson.module:jackson-module-kotlin")

	developmentOnly("com.vaadin:vaadin-dev")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")

	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
	imports {
		mavenBom("com.vaadin:vaadin-bom:${property("vaadinVersion")}")
	}
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	optionalIntSetting("WATCHDOG_TEST_MAX_PARALLEL_FORKS")?.let {
		maxParallelForks = it.coerceAtLeast(1)
	}
	optionalLongSetting("WATCHDOG_TEST_TIMEOUT_MINUTES")?.let {
		timeout.set(Duration.ofMinutes(it.coerceAtLeast(1)))
	}
	systemProperty("app.security.enabled", "false")
	systemProperty("vaadin.launch-browser", "false")
}

fun optionalIntSetting(name: String): Int? =
	optionalStringSetting(name)?.toIntOrNull()

fun optionalLongSetting(name: String): Long? =
	optionalStringSetting(name)?.toLongOrNull()

fun optionalStringSetting(name: String): String? =
	providers.gradleProperty(name)
		.orElse(providers.environmentVariable(name))
		.orNull
		?.trim()
		?.takeIf { it.isNotBlank() }

tasks.named<ProcessResources>("processResources") {
	val timestamp = Instant.now().toString()
	val versionName = project.version.toString().removeSuffix("-SNAPSHOT")
	val commit = explicitBuildCommit() ?: currentGitCommit()
	inputs.property("appBuildTimestamp", timestamp)
	inputs.property("appBuildVersion", versionName)
	inputs.property("appBuildCommit", commit)
	filesMatching("application.properties") {
		filter { line ->
			line
				.replace("\${appBuildTimestamp}", timestamp)
				.replace("\${appBuildVersion}", versionName)
				.replace("\${appBuildCommit}", commit)
		}
	}
}

fun explicitBuildCommit(): String? =
	providers.gradleProperty("appBuildCommit")
		.orElse(providers.environmentVariable("APP_BUILD_COMMIT"))
		.orNull
		?.trim()
		?.takeIf { it.isNotBlank() && it != "unknown" }

fun currentGitCommit(): String =
	runCatching {
		val process = ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
			.directory(rootDir)
			.redirectErrorStream(true)
			.start()
		if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
			"unknown"
		} else {
			process.inputStream.bufferedReader().readText().trim().ifBlank { "unknown" }
		}
	}.getOrDefault("unknown")
