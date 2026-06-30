package pl.bnowakowski.watchdog.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository

@Configuration
class OAuthClientRegistrationConfig(
	private val securityProperties: SecurityProperties,
) {
	@Bean
	@ConditionalOnProperty(prefix = "app.security", name = ["enabled"], havingValue = "true")
	fun clientRegistrationRepository(): ClientRegistrationRepository {
		val clientId = securityProperties.googleClientId.trim()
		val clientSecret = securityProperties.googleClientSecret.trim()
		require(clientId.isNotBlank()) {
			"APP_SECURITY_ENABLED=true requires SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID"
		}
		require(clientSecret.isNotBlank()) {
			"APP_SECURITY_ENABLED=true requires SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_SECRET"
		}

		return InMemoryClientRegistrationRepository(
			CommonOAuth2Provider.GOOGLE
				.getBuilder("google")
				.clientId(clientId)
				.clientSecret(clientSecret)
				.scope("openid", "email", "profile")
				.build(),
		)
	}
}
