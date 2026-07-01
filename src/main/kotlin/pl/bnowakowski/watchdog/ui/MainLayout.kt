package pl.bnowakowski.watchdog.ui

import com.vaadin.flow.component.applayout.AppLayout
import com.vaadin.flow.component.applayout.DrawerToggle
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H1
import com.vaadin.flow.component.html.Nav
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.Scroller
import com.vaadin.flow.component.progressbar.ProgressBar
import com.vaadin.flow.component.sidenav.SideNav
import com.vaadin.flow.component.sidenav.SideNavItem
import com.vaadin.flow.router.Layout
import com.vaadin.flow.shared.Registration
import jakarta.annotation.security.PermitAll
import pl.bnowakowski.watchdog.checks.CheckRunProgressTracker
import pl.bnowakowski.watchdog.version.AppBuildProperties

@Layout
@PermitAll
class MainLayout(
	private val progressTracker: CheckRunProgressTracker,
	private val buildProperties: AppBuildProperties,
) : AppLayout() {
	private val progressText = Span()
	private val progressBar = ProgressBar(0.0, 1.0)
	private val progressIndicator = HorizontalLayout(progressText, progressBar)
	private var pollRegistration: Registration? = null

	init {
		primarySection = Section.DRAWER
		progressBar.width = "10rem"
		progressIndicator.apply {
			alignItems = com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER
			isVisible = false
			style
				.set("margin-left", "auto")
				.set("gap", "var(--lumo-space-s)")
		}
		progressText.style
			.set("font-size", "var(--lumo-font-size-s)")
			.set("color", "var(--lumo-secondary-text-color)")
			.set("white-space", "nowrap")
		addToNavbar(
			DrawerToggle(),
			H1("Home Assistant Watchdog").apply {
				style
					.set("font-size", "var(--lumo-font-size-l)")
					.set("margin", "0")
			},
			progressIndicator,
		)
		addToDrawer(
			Scroller(
				Nav(
					SideNav().apply {
						addItem(SideNavItem("Dashboard", DashboardView::class.java))
						addItem(SideNavItem("Devices", DevicesView::class.java))
						addItem(SideNavItem("Groups", GroupsView::class.java))
						addItem(SideNavItem("Checks", ChecksView::class.java))
						addItem(SideNavItem("Notifications", NotificationsView::class.java))
						addItem(SideNavItem("Settings", SettingsView::class.java))
					},
				),
			),
			buildVersionBadge(),
		)
		updateCheckProgress()
	}

	override fun onAttach(attachEvent: AttachEvent) {
		super.onAttach(attachEvent)
		attachEvent.ui.pollInterval = 1_000
		pollRegistration = attachEvent.ui.addPollListener { updateCheckProgress() }
		updateCheckProgress()
	}

	override fun onDetach(detachEvent: DetachEvent) {
		pollRegistration?.remove()
		pollRegistration = null
		super.onDetach(detachEvent)
	}

	private fun updateCheckProgress() {
		val progress = progressTracker.snapshot()
		progressIndicator.isVisible = progress != null
		if (progress == null) {
			return
		}
		progressBar.value = progress.fraction
		val currentDevice = progress.currentDeviceName?.let { " / $it" } ?: ""
		progressText.text = "Check #${progress.checkRunId}: ${progress.completedDevices}/${progress.totalDevices}$currentDevice"
	}

	private fun buildVersionBadge(): Div {
		val versionFooter = Span("v${buildProperties.displayVersion}")
		versionFooter.element.style.set("font-size", "13px")
		versionFooter.element.style.set("color", "var(--lumo-tertiary-text-color)")
		versionFooter.element.style.set("line-height", "1")

		val badge = Div(versionFooter)
		badge.element.style.set("position", "fixed")
		badge.element.style.set("left", "8px")
		badge.element.style.set("bottom", "4px")
		badge.element.style.set("z-index", "900")
		badge.element.style.set("padding", "0")
		badge.element.style.set("background", "transparent")
		badge.element.style.set("border", "0")
		badge.element.style.set("box-shadow", "none")
		badge.element.style.set("opacity", "0.58")
		return badge
	}
}
