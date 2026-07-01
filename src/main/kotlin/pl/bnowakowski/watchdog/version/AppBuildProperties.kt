package pl.bnowakowski.watchdog.version

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.build")
data class AppBuildProperties(
	val timestamp: String,
	val version: String,
	val commit: String,
) {
	val displayVersion: String
		get() = commit
			.takeUnless { it.isBlank() || it == "unknown" || it.startsWith("\${") }
			?.let { "$version+$it" }
			?: version
}
