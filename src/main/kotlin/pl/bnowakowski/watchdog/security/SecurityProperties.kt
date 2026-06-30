package pl.bnowakowski.watchdog.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.security")
data class SecurityProperties(
	val enabled: Boolean = false,
	val googleClientId: String = "",
	val googleClientSecret: String = "",
)
