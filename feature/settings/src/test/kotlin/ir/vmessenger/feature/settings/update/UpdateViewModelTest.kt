package ir.vmessenger.feature.settings.update

import ir.vmessenger.core.common.AppError
import ir.vmessenger.core.common.AppResult
import ir.vmessenger.domain.model.DownloadProgress
import ir.vmessenger.domain.model.UpdateCheck
import ir.vmessenger.domain.usecase.update.CheckForUpdateUseCase
import ir.vmessenger.domain.usecase.update.DownloadUpdateUseCase
import ir.vmessenger.domain.usecase.update.ObserveUpdateStatusUseCase
import ir.vmessenger.domain.usecase.update.SaveUpdateApkUseCase
import ir.vmessenger.domain.usecase.update.SkipUpdateVersionUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The updater's state machine. The states that matter are the two Android forces on a
 * sideloaded app: needing install permission, and a signer that will not upgrade.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateViewModelTest {
    private lateinit var repository: FakeUpdateRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        repository = FakeUpdateRepository()
    }

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = UpdateViewModel(
        checkForUpdate = CheckForUpdateUseCase(repository),
        downloadUpdate = DownloadUpdateUseCase(repository),
        skipVersion = SkipUpdateVersionUseCase(repository),
        saveApk = SaveUpdateApkUseCase(repository),
        updateRepository = repository,
        observeStatus = ObserveUpdateStatusUseCase(repository),
    )

    @Test
    fun `opening the screen checks without forcing`() = runTest {
        repository.checkResult = AppResult.Success(UpdateCheck.Available(FakeUpdateRepository.UPDATE))

        val model = viewModel()
        advanceUntilIdle()

        assertIs<UpdateUiState.Available>(model.state.value)
        assertEquals(0, repository.forcedChecks)
    }

    @Test
    fun `a forced check is not swallowed by the one the screen runs on entry`() = runTest {
        val model = viewModel()

        // No advanceUntilIdle: the entry check is still in flight, exactly as it is when the
        // user taps "check now" the instant the screen appears.
        model.check(force = true)
        advanceUntilIdle()

        assertEquals(1, repository.forcedChecks)
    }

    @Test
    fun `a check with nothing newer reports up to date`() = runTest {
        val model = viewModel()

        model.check(force = true)
        advanceUntilIdle()

        assertIs<UpdateUiState.UpToDate>(model.state.value)
        assertEquals(1, repository.forcedChecks)
    }

    @Test
    fun `a rate limited check surfaces the error rather than looking up to date`() = runTest {
        repository.checkResult = AppResult.Error(AppError.UpdateRateLimited)
        val model = viewModel()

        model.check(force = true)
        advanceUntilIdle()

        assertEquals(UpdateUiState.Error(AppError.UpdateRateLimited), model.state.value)
    }

    @Test
    fun `cancelling a download goes back to the offer rather than to nothing`() = runTest {
        repository.downloadEmissions = listOf(DownloadProgress.Downloading(1, 10))
        val model = viewModel()
        model.download(FakeUpdateRepository.UPDATE)
        advanceUntilIdle()

        model.cancelDownload(FakeUpdateRepository.UPDATE)

        assertEquals(UpdateUiState.Available(FakeUpdateRepository.UPDATE), model.state.value)
    }

    @Test
    fun `a verified download is ready to install when the app may install packages`() = runTest {
        repository.checkResult = AppResult.Success(UpdateCheck.Available(FakeUpdateRepository.UPDATE))
        repository.downloadEmissions = listOf(
            DownloadProgress.Downloading(512, 1_024),
            DownloadProgress.Verifying,
            DownloadProgress.VerifiedFile("/tmp/update.apk"),
        )
        val model = viewModel()

        model.download(FakeUpdateRepository.UPDATE)
        advanceUntilIdle()

        assertEquals(UpdateUiState.ReadyToInstall(FakeUpdateRepository.UPDATE, "/tmp/update.apk"), model.state.value)
    }

    @Test
    fun `a verified download stops at the permission wall when it may not install`() = runTest {
        repository.canInstall = false
        repository.downloadEmissions = listOf(DownloadProgress.VerifiedFile("/tmp/update.apk"))
        val model = viewModel()

        model.download(FakeUpdateRepository.UPDATE)
        advanceUntilIdle()

        assertIs<UpdateUiState.NeedsInstallPermission>(model.state.value)
    }

    @Test
    fun `granting install permission outside the app is picked up on resume`() = runTest {
        repository.canInstall = false
        repository.downloadEmissions = listOf(DownloadProgress.VerifiedFile("/tmp/update.apk"))
        val model = viewModel()
        model.download(FakeUpdateRepository.UPDATE)
        advanceUntilIdle()

        repository.canInstall = true
        model.refreshInstallPermission()

        assertIs<UpdateUiState.ReadyToInstall>(model.state.value)
    }

    @Test
    fun `a failed verification never reaches an installable state`() = runTest {
        repository.downloadEmissions = listOf(DownloadProgress.Failed(FakeUpdateRepository.FAILURE))
        val model = viewModel()

        model.download(FakeUpdateRepository.UPDATE)
        advanceUntilIdle()

        assertEquals(UpdateUiState.Error(FakeUpdateRepository.FAILURE), model.state.value)
    }

    @Test
    fun `an installer that refuses the upgrade offers the file instead`() = runTest {
        val model = viewModel()

        model.onInstallRejected("/tmp/update.apk")

        assertEquals(UpdateUiState.ReinstallRequired("/tmp/update.apk"), model.state.value)
    }

    @Test
    fun `skipping a version records it and stops offering it`() = runTest {
        val model = viewModel()

        model.skip(FakeUpdateRepository.UPDATE)
        advanceUntilIdle()

        assertEquals("1.1.0", repository.skipped)
        assertIs<UpdateUiState.UpToDate>(model.state.value)
    }
}
