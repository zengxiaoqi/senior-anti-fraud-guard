package com.antifraud.guard.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「云端家基准变了，要不要重置内存里的基准」判定。
 *
 * ## 背景：一次性闩锁
 * `LocationGuardService.homeSet` 置 true 后永不回退，
 * 于是云端家基准改了之后，内存里的 `homeLat/homeLng` 永远停在旧值。
 * 子女端改了设置、界面上显示新坐标、实际仍按旧坐标判定 ——
 * 而且没有任何报错。这是本项目反复出现的同一类病：
 * "看起来生效了，其实没有"。
 *
 * ## 判定为什么不自己比对坐标
 * `GuardConfig.applySettingsFromServer` 已经返回了被实际覆盖的字段名，
 * 其中含「家的基准位置」。用那个返回值驱动重置，比自己比对坐标可靠：
 * 自己比对会漏掉"服务端值与本机相同、但内存里是更早的值"这种状态。
 */
class HomeBasePolicyTest {

    @Test
    fun `云端家基准变更且已设过基准时必须重置`() {
        assertTrue(
            HomeBasePolicy.shouldReset(
                wasHomeSet = true,
                cloudHomeChanged = true,
                serviceRunning = true
            )
        )
    }

    @Test
    fun `云端家基准没变时不重置`() {
        // 每次心跳/拉取都会走到这个判定，无脑重置会让 stayStartTime 被不断清零，
        // 「停留超 40 分钟」的告警永远不触发。
        assertFalse(
            "云端没变就重置会清掉停留计时，导致停留告警永不触发",
            HomeBasePolicy.shouldReset(
                wasHomeSet = true,
                cloudHomeChanged = false,
                serviceRunning = true
            )
        )
    }

    @Test
    fun `还没设过基准时不需要重置`() {
        // homeSet 本来就是 false，下次 handleLocation 自然会取云端值
        assertFalse(
            HomeBasePolicy.shouldReset(
                wasHomeSet = false,
                cloudHomeChanged = true,
                serviceRunning = true
            )
        )
    }

    @Test
    fun `服务未运行时不重置（GuardConfig 已落盘，下次启动自然生效）`() {
        // 没有服务进程，resetHomeBase() 无处可调。
        // 而 GuardConfig 是 SharedPreferences，服务下次启动时 homeSet 天然为 false。
        assertFalse(
            HomeBasePolicy.shouldReset(
                wasHomeSet = true,
                cloudHomeChanged = true,
                serviceRunning = false
            )
        )
    }

    @Test
    fun `清除家基准也算变更，需要重置回退路径`() {
        // 清除后 hasFamilyHome 变 false，重置才能走到"首次定位 + 一次性告知"分支
        assertTrue(
            HomeBasePolicy.shouldReset(
                wasHomeSet = true,
                cloudHomeChanged = true,
                serviceRunning = true
            )
        )
    }
}
