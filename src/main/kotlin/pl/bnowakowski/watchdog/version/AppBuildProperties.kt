package pl.bnowakowski.watchdog.version

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.build")
data class AppBuildProperties(
	val timestamp: String,
	val version: String,
	val commit: String,
)
