package pl.bnowakowski.watchdog.version

import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.stereotype.Component

@Component
class AppVersionInfoContributor(
	private val buildProperties: AppBuildProperties,
) : InfoContributor {
	override fun contribute(builder: Info.Builder) {
		builder.withDetail(
			"build",
			mapOf(
				"timestamp" to buildProperties.timestamp,
				"version" to buildProperties.version,
				"commit" to buildProperties.commit,
			),
		)
	}
}
