package pl.bnowakowski.watchdog.security

import com.vaadin.flow.spring.security.VaadinSecurityConfigurer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig(
	private val securityProperties: SecurityProperties,
) {
	@Bean
	fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
		if (!securityProperties.enabled) {
			return http
				.csrf { csrf -> csrf.disable() }
				.authorizeHttpRequests { auth -> auth.anyRequest().permitAll() }
				.build()
		}

		http
			.authorizeHttpRequests { auth ->
				auth
					.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
			}
			.with(VaadinSecurityConfigurer.vaadin()) { vaadin ->
				vaadin.loginView("/oauth2/authorization/google")
			}
			.oauth2Login { }
			.logout { logout ->
				logout.logoutSuccessUrl("/")
			}

		return http.build()
	}
}
