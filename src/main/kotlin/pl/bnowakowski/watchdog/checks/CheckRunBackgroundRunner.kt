package pl.bnowakowski.watchdog.checks

import jakarta.annotation.PreDestroy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.bnowakowski.watchdog.domain.CheckRunTriggerType

@Service
class CheckRunBackgroundRunner(
	private val checkRunService: CheckRunService,
) {
	private val logger = LoggerFactory.getLogger(javaClass)
	private val running = AtomicBoolean(false)
	private val executor = Executors.newSingleThreadExecutor { runnable ->
		Thread(runnable, "check-runner").apply { isDaemon = true }
	}

	fun startCheck(triggerType: CheckRunTriggerType): CheckRunStartResult {
		if (checkRunService.isRunning() || !running.compareAndSet(false, true)) {
			return CheckRunStartResult.ALREADY_RUNNING
		}
		return try {
			executor.submit {
				try {
					checkRunService.runCheck(triggerType)
				} catch (error: CheckRunAlreadyRunningException) {
					logger.info("{} check skipped because another check is already running", triggerType)
				} catch (error: Exception) {
					logger.warn("{} check failed", triggerType, error)
				} finally {
					running.set(false)
				}
			}
			CheckRunStartResult.STARTED
		} catch (error: RuntimeException) {
			running.set(false)
			throw error
		}
	}

	@PreDestroy
	fun shutdown() {
		executor.shutdownNow()
	}
}

enum class CheckRunStartResult {
	STARTED,
	ALREADY_RUNNING,
}
