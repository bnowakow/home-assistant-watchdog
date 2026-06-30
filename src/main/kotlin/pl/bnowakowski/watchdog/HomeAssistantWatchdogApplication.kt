package pl.bnowakowski.watchdog

import com.vaadin.flow.component.dependency.StyleSheet
import com.vaadin.flow.component.page.Inline.Wrapping.STYLESHEET
import com.vaadin.flow.component.page.AppShellConfigurator
import com.vaadin.flow.server.AppShellSettings
import com.vaadin.flow.theme.aura.Aura
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@StyleSheet(Aura.STYLESHEET)
class HomeAssistantWatchdogApplication : AppShellConfigurator {
	override fun configurePage(settings: AppShellSettings) {
		settings.addInlineWithContents(":root { color-scheme: dark; }", STYLESHEET)
	}
}

fun main(args: Array<String>) {
	runApplication<HomeAssistantWatchdogApplication>(*args)
}
