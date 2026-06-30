package pl.bnowakowski.watchdog

import com.vaadin.flow.component.dependency.StyleSheet
import com.vaadin.flow.component.page.AppShellConfigurator
import com.vaadin.flow.theme.aura.Aura
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@StyleSheet(Aura.STYLESHEET)
class HomeAssistantWatchdogApplication : AppShellConfigurator

fun main(args: Array<String>) {
	runApplication<HomeAssistantWatchdogApplication>(*args)
}
