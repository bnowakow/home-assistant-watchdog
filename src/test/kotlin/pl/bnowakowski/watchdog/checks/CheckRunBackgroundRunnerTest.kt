package pl.bnowakowski.watchdog.checks

import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import pl.bnowakowski.watchdog.domain.CheckRunTriggerType

class CheckRunBackgroundRunnerTest {
	private val checkRunService: CheckRunService = mock()

	@Test
	fun `starts check in background`() {
		val runStarted = CountDownLatch(1)
		val releaseRun = CountDownLatch(1)
		whenever(checkRunService.isRunning()).thenReturn(false)
		whenever(checkRunService.runCheck(CheckRunTriggerType.MANUAL)).thenAnswer {
			runStarted.countDown()
			assertTrue(releaseRun.await(2, TimeUnit.SECONDS))
			null
		}
		val runner = CheckRunBackgroundRunner(checkRunService)

		val result = runner.startCheck(CheckRunTriggerType.MANUAL)

		assertEquals(CheckRunStartResult.STARTED, result)
		assertTrue(runStarted.await(2, TimeUnit.SECONDS))
		releaseRun.countDown()
		runner.shutdown()
		verify(checkRunService).runCheck(CheckRunTriggerType.MANUAL)
	}

	@Test
	fun `rejects overlapping background checks`() {
		val runStarted = CountDownLatch(1)
		val releaseRun = CountDownLatch(1)
		whenever(checkRunService.isRunning()).thenReturn(false)
		whenever(checkRunService.runCheck(CheckRunTriggerType.MANUAL)).thenAnswer {
			runStarted.countDown()
			assertTrue(releaseRun.await(2, TimeUnit.SECONDS))
			null
		}
		val runner = CheckRunBackgroundRunner(checkRunService)

		assertEquals(CheckRunStartResult.STARTED, runner.startCheck(CheckRunTriggerType.MANUAL))
		assertTrue(runStarted.await(2, TimeUnit.SECONDS))
		assertEquals(CheckRunStartResult.ALREADY_RUNNING, runner.startCheck(CheckRunTriggerType.SCHEDULED))

		releaseRun.countDown()
		runner.shutdown()
	}

	@Test
	fun `scheduled runner queues scheduled checks`() {
		val backgroundRunner: CheckRunBackgroundRunner = mock()
		whenever(backgroundRunner.startCheck(CheckRunTriggerType.SCHEDULED)).thenReturn(CheckRunStartResult.STARTED)
		val scheduledRunner = ScheduledCheckRunner(backgroundRunner, CheckProperties())

		scheduledRunner.runScheduledCheck()

		verify(backgroundRunner).startCheck(eq(CheckRunTriggerType.SCHEDULED))
	}

	@Test
	fun `startup runner queues scheduled check when enabled`() {
		val backgroundRunner: CheckRunBackgroundRunner = mock()
		whenever(backgroundRunner.startCheck(CheckRunTriggerType.SCHEDULED)).thenReturn(CheckRunStartResult.STARTED)
		val scheduledRunner = ScheduledCheckRunner(backgroundRunner, CheckProperties(runOnStartup = true))

		scheduledRunner.runStartupCheck()

		verify(backgroundRunner).startCheck(eq(CheckRunTriggerType.SCHEDULED))
	}

	@Test
	fun `startup runner skips scheduled check by default`() {
		val backgroundRunner: CheckRunBackgroundRunner = mock()
		val scheduledRunner = ScheduledCheckRunner(backgroundRunner, CheckProperties(runTimeout = Duration.ofSeconds(1)))

		scheduledRunner.runStartupCheck()

		verify(backgroundRunner, never()).startCheck(eq(CheckRunTriggerType.SCHEDULED))
	}

	@Test
	fun `progress tracker notifies finish listeners`() {
		val tracker = CheckRunProgressTracker()
		val finishedRuns = mutableListOf<Long>()
		val listener = tracker.addFinishListener(finishedRuns::add)
		tracker.start(42, CheckRunTriggerType.MANUAL, 1, Instant.parse("2026-06-30T18:00:00Z"))

		tracker.finish(42)
		listener.close()
		tracker.finish(43)

		assertEquals(listOf(42L), finishedRuns)
		assertNull(tracker.snapshot())
	}
}
