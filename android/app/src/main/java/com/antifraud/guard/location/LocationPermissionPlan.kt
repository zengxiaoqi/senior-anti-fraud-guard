package com.antifraud.guard.location

/**
 * 后台定位权限申请策略（纯逻辑，不依赖任何 android.* 类，可在 JVM 上直接测）。
 *
 * ## 背景：一次线上故障
 * 2026-10-08 复现：老人退出 App 后位置上报**永久停止**，服务与通知栏都还在，
 * 但再也没有 LOCATION_UPDATE 事件。子女端看到的是"什么都没发生"——
 * 与"一切正常"无法区分。
 *
 * 根因不是代码写错，而是**这段逻辑压根没写**：
 * `MainActivity.requestLocationPermission()` 只申请了 FINE + COARSE，
 * `ACCESS_BACKGROUND_LOCATION` 在 Manifest 里声明了却从未在运行时申请。
 *
 * ## Android 的坑在哪
 * `LocationManager.requestLocationUpdates()` 的回调**只在 App 处于前台时投递**，
 * 除非持有 `ACCESS_BACKGROUND_LOCATION`。所以只给前台定位的话，
 * App 一退到后台，位置就静默了 —— 而前台服务持有通知栏，界面上看不出任何异常。
 *
 * 而"怎么申请后台定位"**完全取决于 Android 版本**，极易漏：
 *
 * | 版本 | API | 规则 |
 * |---|---|---|
 * | Android 8.0~9 | 26~28 | 给前台定位就等于给了后台，不需要额外动作 |
 * | Android 10 | 29 | 可以把 `ACCESS_BACKGROUND_LOCATION` 放进同一个请求数组 |
 * | Android 11+ | 30+ | **系统明令禁止打包**，必须先拿前台，再引导用户到设置页单独勾选"始终允许" |
 *
 * 抽成纯函数后，任何人改动都能立刻看到影响哪几个系统版本 ——
 * 这类"按版本分支"的逻辑正是最容易出错、最不可能靠人肉 review 发现的部分。
 */
object LocationPermissionPlan {

    /** Android 10 = API 29，是后台定位申请规则的分水岭 */
    private const val API_Q_ANDROID_10 = 29

    /** Android 11 = API 30，从这里开始禁止打包请求后台定位 */
    private const val API_R_ANDROID_11 = 30

    enum class Action {
        /** 前台与后台定位都已拿到（或 Android 9 及以前，前台即等价后台），不需要打扰用户 */
        ALREADY_GRANTED,

        /** 尚未拿到任何定位权限：正常弹运行时权限框即可 */
        REQUEST_FOREGROUND,

        /**
         * Android 10：把 ACCESS_BACKGROUND_LOCATION 与前台权限放进同一次请求。
         * 这是唯一一个允许这么做的版本。
         */
        REQUEST_FOREGROUND_AND_BACKGROUND,

        /**
         * Android 11+：系统禁止把后台定位与首次前台请求打包，
         * 必须把用户引导到系统设置页，由他自己选择"始终允许"。
         * App 无法代替用户做这个选择。
         */
        REDIRECT_TO_SETTINGS
    }

    /**
     * 计算下一步该做什么。
     *
     * @param sdkInt             Build.VERSION.SDK_INT
     * @param foregroundGranted  是否已授予「仅在使用中允许」的前台定位
     * @param backgroundGranted  是否已授予「始终允许」的后台定位
     */
    @JvmStatic
    fun plan(sdkInt: Int, foregroundGranted: Boolean, backgroundGranted: Boolean): Action {
        // 后台定位是最终目标：一旦拿到就收工。
        // 之所以放在最前面而不是"前台 && 后台"，是因为部分国产 ROM 会出现
        // 两个标志读数不一致的情况，此时应当以更强的后台权限为准，
        // 否则会对一个已经配好的用户反复弹窗。
        if (backgroundGranted) return Action.ALREADY_GRANTED

        // Android 9 及以前：拿到前台定位就已经包含后台能力
        if (sdkInt < API_Q_ANDROID_10) {
            return if (foregroundGranted) Action.ALREADY_GRANTED else Action.REQUEST_FOREGROUND
        }

        if (!foregroundGranted) {
            // 任何版本都先把前台拿到手，这是拿后台的前提
            return Action.REQUEST_FOREGROUND
        }

        // 前台已给、后台未给：分版本
        return if (sdkInt < API_R_ANDROID_11) {
            Action.REQUEST_FOREGROUND_AND_BACKGROUND
        } else {
            Action.REDIRECT_TO_SETTINGS
        }
    }
}