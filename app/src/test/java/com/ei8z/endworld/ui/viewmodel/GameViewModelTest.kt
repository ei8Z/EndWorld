package com.ei8z.endworld.ui.viewmodel

import com.ei8z.endworld.data.local.entity.PlayerState
import com.ei8z.endworld.data.local.entity.WorldInfo
import com.ei8z.endworld.data.model.GameConfig
import com.ei8z.endworld.data.model.InitialStats
import com.ei8z.endworld.data.model.SpeedMode
import com.ei8z.endworld.data.model.SuggestionItem
import com.ei8z.endworld.data.repository.GameRepository
import com.ei8z.endworld.data.repository.GmStrategy
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P1-6/P1-7 的 ViewModel 接线：重启仍死亡、离开加速必须收尾、加速中拒绝行动。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: GameRepository
    private lateinit var player: MutableStateFlow<PlayerState?>

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = mockk(relaxed = true)
        player = MutableStateFlow(alivePlayer())
        every { repository.playerState } returns player
        every { repository.storyHistory } returns emptyFlow()
        every { repository.inventory } returns emptyFlow()
        every { repository.recentMemories } returns emptyFlow()
        every { repository.npcs } returns emptyFlow()
        every { repository.worldInfo } returns flowOf(testWorld())
        every { repository.loadSuggestions() } returns emptyList<SuggestionItem>()
        every { repository.loadStrategy() } returns GmStrategy.default()
        every { repository.sendActionStream(any(), any()) } returns emptyFlow()
        // 只保留仓库真正判死的一行语义：hp<=0 才算死，避免 relaxed mock 把空串当死因
        every { repository.checkPlayerDeath(any(), any(), any()) } answers {
            if (firstArg<Int>() <= 0) "致命伤势" else null
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun createViewModel() = GameViewModel(repository, gameConfig())

    private fun gameConfig() = GameConfig(
        personalities = emptyList(),
        doomLevels = emptyList(),
        doomTypes = emptyList(),
        genders = emptyList(),
        initialStats = InitialStats()
    )

    private fun alivePlayer() = testPlayer().copy(hp = 80)

    private fun testPlayer() = PlayerState(
        userId = "u1", name = "测试", genderPhysique = "男",
        personality = "冷静", adultProfile = "", baseDescription = "幸存者",
        doomType = "丧尸", mentalStateTags = listOf("健康"),
        innerThought = "", equippedItems = emptyList()
    )

    private fun testWorld() = WorldInfo(
        currentDate = "第1天", currentTime = "06:00",
        locationScene = "避难所", macroSituation = "",
        knownPointsJson = "[]"
    )

    @Test
    fun `restarted view model sees an already dead player`() = runTest(dispatcher.scheduler) {
        player.value = testPlayer().copy(hp = 0)

        val vm = createViewModel()
        advanceUntilIdle()

        assertTrue("重启后 HP 为 0 必须仍是死亡态", vm.isDead.value)
        assertEquals("致命伤势", vm.deathCause.value)
    }

    @Test
    fun `fake recovery is disabled after death`() = runTest(dispatcher.scheduler) {
        player.value = testPlayer().copy(hp = 0)
        val vm = createViewModel()
        advanceUntilIdle()
        assertTrue(vm.isDead.value)

        vm.inheritWorld()
        assertEquals("继承功能尚未开放（C4）", vm.error.value)
        assertTrue("继承不能假装复活", vm.isDead.value)

        vm.reloadFromSave()
        advanceUntilIdle()
        assertTrue("读档不能假装复活", vm.isDead.value)
        assertEquals("没有可用的死亡前存档", vm.error.value)
    }

    @Test
    fun `leaving acceleration for deceleration runs the exit and lands on deceleration`() =
        runTest(dispatcher.scheduler) {
            coEvery { repository.exitAcceleration(any(), any()) } returns "加速了1小时。"
            val vm = createViewModel()

            vm.setSpeedMode(SpeedMode.ACCELERATION)
            vm.setSpeedMode(SpeedMode.DECELERATION)
            advanceUntilIdle()

            assertEquals(SpeedMode.DECELERATION, vm.speedMode.value)
            coVerify(exactly = 1) { repository.exitAcceleration(any(), any()) }
        }

    @Test
    fun `acceleration rejects actions`() = runTest(dispatcher.scheduler) {
        val vm = createViewModel()

        vm.setSpeedMode(SpeedMode.ACCELERATION)
        vm.sendAction("等待", null)

        assertEquals("加速中无法行动，请先减速", vm.error.value)
        verify(exactly = 0) { repository.sendActionStream("等待", null) }

        // 收尾：真的离开加速，避免加速循环留在 viewModelScope 里
        vm.setSpeedMode(SpeedMode.NORMAL)
        advanceUntilIdle()
    }

    @Test
    fun `repeated exit taps during the exit window run the exit once`() =
        runTest(dispatcher.scheduler) {
            coEvery { repository.exitAcceleration(any(), any()) } returns "加速了1小时。"
            val vm = createViewModel()

            vm.setSpeedMode(SpeedMode.ACCELERATION)
            // 收尾协程要等 advanceUntilIdle 才跑，这里正好模拟收尾窗口里的连续点击
            vm.setSpeedMode(SpeedMode.NORMAL)
            vm.setSpeedMode(SpeedMode.NORMAL)
            vm.setSpeedMode(SpeedMode.DECELERATION)
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.exitAcceleration(any(), any()) }
        }

    @Test
    fun `exit with missing player still leaves acceleration mode`() =
        runTest(dispatcher.scheduler) {
            player.value = null
            val vm = createViewModel()

            vm.setSpeedMode(SpeedMode.ACCELERATION)
            vm.setSpeedMode(SpeedMode.NORMAL)
            advanceUntilIdle()

            // 收尾提前 return 也必须落到目标模式，否则输入/时钟永久卡在「加速中」
            assertEquals(SpeedMode.NORMAL, vm.speedMode.value)
        }

    @Test
    fun `restartGame clears death state and delegates to repository`() =
        runTest(dispatcher.scheduler) {
            // 先真的进入死亡态，否则「清死亡态」的断言永远成立，测不出回归
            player.value = testPlayer().copy(hp = 0)
            coEvery { repository.restartGame() } just Runs
            val vm = createViewModel()
            advanceUntilIdle()
            assertTrue("前置：开局必须是死亡态", vm.isDead.value)

            vm.restartGame()
            advanceUntilIdle()

            assertFalse(vm.isDead.value)
            assertEquals("", vm.deathCause.value)
            assertEquals("", vm.deathNarrative.value)
            coVerify(exactly = 1) { repository.restartGame() }
        }

    @Test
    fun `repeated restart taps only clear once`() = runTest(dispatcher.scheduler) {
        coEvery { repository.restartGame() } coAnswers { delay(50) }
        val vm = createViewModel()

        vm.restartGame()
        vm.restartGame()
        advanceUntilIdle()

        coVerify(exactly = 1) { repository.restartGame() }
    }
}
