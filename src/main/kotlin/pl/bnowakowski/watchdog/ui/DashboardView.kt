package pl.bnowakowski.watchdog.ui

import com.vaadin.flow.component.html.H1
import com.vaadin.flow.component.html.Paragraph
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.auth.AnonymousAllowed

@Route("")
@AnonymousAllowed
class DashboardView : VerticalLayout() {
	init {
		setSizeFull()
		add(
			H1("Home Assistant Watchdog"),
			Paragraph("Phase 1 foundation is running. Device discovery, groups, rules, and checks come next."),
		)
	}
}
