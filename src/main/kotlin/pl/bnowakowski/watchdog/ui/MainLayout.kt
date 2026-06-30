package pl.bnowakowski.watchdog.ui

import com.vaadin.flow.component.applayout.AppLayout
import com.vaadin.flow.component.applayout.DrawerToggle
import com.vaadin.flow.component.html.H1
import com.vaadin.flow.component.html.Nav
import com.vaadin.flow.component.orderedlayout.Scroller
import com.vaadin.flow.component.sidenav.SideNav
import com.vaadin.flow.component.sidenav.SideNavItem
import com.vaadin.flow.router.Layout
import jakarta.annotation.security.PermitAll

@Layout
@PermitAll
class MainLayout : AppLayout() {
	init {
		primarySection = Section.DRAWER
		addToNavbar(DrawerToggle(), H1("Home Assistant Watchdog").apply {
			style
				.set("font-size", "var(--lumo-font-size-l)")
				.set("margin", "0")
		})
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
		)
	}
}
