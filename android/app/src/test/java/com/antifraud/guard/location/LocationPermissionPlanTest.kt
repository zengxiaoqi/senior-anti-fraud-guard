package com.antifraud.guard.location

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 后台定位权限申请策略的版本矩阵测试。
 *
 * ## 为什么这个类值得单独抽出来测
 * 线上真实故障：老人退出 App 后位置上报永久停止（2026-10-08 复现）。
 * 根因不是代码写错，而是**这段逻辑压根不存在** —— Manifest 声明了
 * ACCESS_BACKGROUND_LOCATION，但运行时只申请了 FINE+COARSE。
 * 而"该不该申请""怎么申请"完全取决于 Android 版本，是个极易漏的矩阵：
 *
 *   - Android 8/9  (API 26~28)：给前台定位 == 给后台定位，不用管
 *   - Android 10   (API 29)    ：可以一次性把 ACCESS_BACKGROUND_LOCATION 放进请求数组
 *   - Android 11+  (API 30+)   ：**系统明确禁止**把后台定位与首次前台请求打包，
 *                                必须先把前台拿到，再把用户引导到系统设置里单独勾选
 *
 * 这套判断以前散在 Activity 的 if/else 里、而且压根没写。现在抽成纯函数，
 * 任何人想改都能立刻看到影响哪几个版本。
 *
 * 注意：本类**不引用任何 android.* 类**，因此能在 JVM 上直接跑（见 app/build.gradle 的 testOptions）。
 */
class LocationPermissionPlanTest {

    private companion object {
        const val API_26 = 26   // Android 8.0
        const val API_28 = 28   // Android 9
        const val API_29 = 29   // Android 10
        const val API_30 = 30   // Android 11
        const val API_33 = 33   // Android 13
        const val API_34 = 34   // Android 14
    }

    // ── 场景 1：什么都没拿到 → 一律先要前台定位 ──────────────────────────
    // 这是新装 App 的首次启动路径，三种系统行为一致

    @Test
    fun `Android 8 前台未授权时请求前台定位`() {
        assertEquals(
            LocationPermissionPlan.Action.REQUEST_FOREGROUND,
            LocationPermissionPlan.plan(API_26, foregroundGranted = false, backgroundGranted = false)
        )
    }

    @Test
    fun `Android 10 前台未授权时请求前台定位`() {
        assertEquals(
            LocationPermissionPlan.Action.REQUEST_FOREGROUND,
            LocationPermissionPlan.plan(API_29, foregroundGranted = false, backgroundGranted = false)
        )
    }

    @Test
    fun `Android 13 前台未授权时请求前台定位`() {
        assertEquals(
            LocationPermissionPlan.Action.REQUEST_FOREGROUND,
            LocationPermissionPlan.plan(API_33, foregroundGranted = false, backgroundGranted = false)
        )
    }

    // ── 场景 2：前台拿到了，后台还没拿到 → 各版本策略不同，这是最容易写错的地方 ──

    @Test
    fun `Android 8 和 9 拿到前台就等于拿到后台 不需要额外动作`() {
        for (api in listOf(API_26, API_28)) {
            assertEquals(
                "API $api 不应该再有任何后台定位动作",
                LocationPermissionPlan.Action.ALREADY_GRANTED,
                LocationPermissionPlan.plan(api, foregroundGranted = true, backgroundGranted = false)
            )
        }
    }

    @Test
    fun `Android 10 可以把后台定位一起请求`() {
        assertEquals(
            LocationPermissionPlan.Action.REQUEST_FOREGROUND_AND_BACKGROUND,
            LocationPermissionPlan.plan(API_29, foregroundGranted = true, backgroundGranted = false)
        )
    }

    @Test
    fun `Android 11 及以上禁止打包请求 必须跳设置页`() {
        for (api in listOf(API_30, API_33, API_34)) {
            assertEquals(
                "API $api 必须引导到设置页手动选择始终允许",
                LocationPermissionPlan.Action.REDIRECT_TO_SETTINGS,
                LocationPermissionPlan.plan(api, foregroundGranted = true, backgroundGranted = false)
            )
        }
    }

    // ── 场景 3：全部拿到 → 不要打扰用户 ──────────────────────────────────

    @Test
    fun `后台定位已授权时不再重复打扰用户`() {
        for (api in listOf(API_26, API_28, API_29, API_30, API_33, API_34)) {
            assertEquals(
                "API $api 已经够了，不该再弹窗",
                LocationPermissionPlan.Action.ALREADY_GRANTED,
                LocationPermissionPlan.plan(api, foregroundGranted = true, backgroundGranted = true)
            )
        }
    }

    // ── 场景 4：后台拿到了但前台标志读不到（国产 ROM 偶发）─────────────────
    // 判据必须以 background 为准，否则会对着已授权的用户反复弹窗

    @Test
    fun `只要后台已授权就视为完成 即使前台标志异常`() {
        assertEquals(
            LocationPermissionPlan.Action.ALREADY_GRANTED,
            LocationPermissionPlan.plan(API_33, foregroundGranted = false, backgroundGranted = true)
        )
    }

    // ── 回归钉子：这条是本次线上故障的原始场景 ────────────────────────────
    // 老人机（假设 Android 13）授权了前台定位、没授权后台定位 —— 旧代码走到这里
    // 什么都不做，于是退出 App 后位置永久停止上报。必须走跳设置页。
    @Test
    fun `回归 线上故障场景 Android13 仅前台授权时必须跳设置页而不是静默`() {
        val action = LocationPermissionPlan.plan(
            sdkInt = API_33,
            foregroundGranted = true,
            backgroundGranted = false
        )
        assertEquals(LocationPermissionPlan.Action.REDIRECT_TO_SETTINGS, action)
        // 并且绝不能是"什么都不做"—— 那正是本次故障的成因
        assertEquals(
            "旧行为（无动作）会让位置在退到后台后永久停止上报",
            true,
            action != LocationPermissionPlan.Action.ALREADY_GRANTED
        )
    }

    // ── 边界：低于 minSdk 的版本不应崩溃，且按最保守路径处理 ────────────────

    @Test
    fun `低于 minSdk 的版本号不会崩溃`() {
        assertEquals(
            LocationPermissionPlan.Action.REQUEST_FOREGROUND,
            LocationPermissionPlan.plan(21, foregroundGranted = false, backgroundGranted = false)
        )
        assertEquals(
            LocationPermissionPlan.Action.ALREADY_GRANTED,
            LocationPermissionPlan.plan(21, foregroundGranted = true, backgroundGranted = false)
        )
    }
}