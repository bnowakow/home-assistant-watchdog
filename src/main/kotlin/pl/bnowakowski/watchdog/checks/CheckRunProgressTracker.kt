package pl.bnowakowski.watchdog.checks

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.springframework.stereotype.Component
import pl.bnowakowski.watchdog.domain.CheckRunTriggerType

@Component
class CheckRunProgressTracker {
	private val current = AtomicReference<CheckRunProgress?>(null)
	private val finishListeners = CopyOnWriteArrayList<(Long) -> Unit>()

	fun start(checkRunId: Long, triggerType: CheckRunTriggerType, totalDevices: Int, startedAt: Instant) {
		current.set(
			CheckRunProgress(
				checkRunId = checkRunId,
				triggerType = triggerType,
				totalDevices = totalDevices,
				completedDevices = 0,
				currentDeviceName = null,
				startedAt = startedAt,
			),
		)
	}

	fun evaluating(deviceName: String) {
		current.updateAndGet { progress ->
			progress?.copy(currentDeviceName = deviceName)
		}
	}

	fun evaluationCompleted(deviceName: String) {
		current.updateAndGet { progress ->
			progress?.copy(
				completedDevices = (progress.completedDevices + 1).coerceAtMost(progress.totalDevices),
				currentDeviceName = deviceName,
			)
		}
	}

	fun finish(checkRunId: Long) {
		current.updateAndGet { progress ->
			progress?.takeUnless { it.checkRunId == checkRunId }
		}
		finishListeners.forEach { it(checkRunId) }
	}

	fun snapshot(): CheckRunProgress? =
		current.get()

	fun addFinishListener(listener: (Long) -> Unit): AutoCloseable {
		finishListeners.add(listener)
		return AutoCloseable { finishListeners.remove(listener) }
	}
}

data class CheckRunProgress(
	val checkRunId: Long,
	val triggerType: CheckRunTriggerType,
	val totalDevices: Int,
	val completedDevices: Int,
	val currentDeviceName: String?,
	val startedAt: Instant,
) {
	val fraction: Double
		get() = if (totalDevices == 0) 0.0 else completedDevices.toDouble() / totalDevices.toDouble()
}
