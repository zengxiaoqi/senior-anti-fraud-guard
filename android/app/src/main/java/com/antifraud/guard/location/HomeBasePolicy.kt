package com.antifraud.guard.location

/**
 * 「云端家基准变了，要不要重置内存里的基准」判定（纯逻辑，可在 JVM 上直接测）。
 *
 * ## 要解决的缺陷
 * `LocationGuardService` 里：
 *
 * ```kotlin
 * if (!homeSet) {
 *     if (GuardConfig.hasFamilyHome) { homeLat = ...; homeLng = ... }
 *     else { homeLat = lat; homeLng = lng; notifyHomeBaseSet(lat, lng) }
 *     homeSet = true
 *     stayStartTime = System.currentTimeMillis()
 * }
 * ```
 *
 * `homeSet` 是一次性闩锁，置 true 后永不回退。于是子女端改了家基准，
 * `GuardConfig.homeLat` 被更新了，内存里的 `homeLat` 却还是旧值 ——
 * 界面上显示新坐标，实际按旧坐标判定，没有任何报错。
 *
 * 只修"下发"不修"重置"，等于什么都没修。
 *
 * ## 为什么三个条件都要
 * - `wasHomeSet`：没设过时 `homeSet` 本来就是 false，下次定位自然取云端值
 * - `cloudHomeChanged`：无条件重置会不断清零 `stayStartTime`，
 *   「在陌生地点停留超 40 分钟」的告警就永远不触发了
 * - `serviceRunning`：没有服务进程时无处可调；且 `GuardConfig` 是
 *   SharedPreferences 已落盘，服务下次启动自然读到新值
 */
object HomeBasePolicy {

    @JvmStatic
    fun shouldReset(wasHomeSet: Boolean, cloudHomeChanged: Boolean, serviceRunning: Boolean): Boolean =
        wasHomeSet && cloudHomeChanged && serviceRunning
}
