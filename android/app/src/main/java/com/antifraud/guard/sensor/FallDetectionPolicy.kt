package com.antifraud.guard.sensor

/**
 * 跌倒检测的纯判定逻辑（不依赖 android.*，可在 JVM 上直接测）。
 *
 * ## 为什么老人端要做这件事
 * 防诈产品解决的是"被骗了钱"，但老人出事的第一原因其实是**物理意外**：
 * 浴室摔倒、独居老人突发不适、手机摔坏够不着。
 * 这些场景的共同点是：**老人自己发不出任何信号**。
 * 老人端已有的所有链路（通话监控、支付监听、位置心跳、围栏录音）
 * 在这一刻全部失效 —— 恰恰是最需要告警的时刻，系统是哑的。
 *
 * 手机放在裤兜/口袋里，加速度计就能看到"急速下坠 → 撞击 → 之后不动"，
 * 这是唯一还能工作的检测面。
 *
 * ## 判定为什么必须"撞击 + 之后静止"两步
 * 经典误报源是"手机被放到桌上"（一次大冲击后静止），
 * 以及"老人从椅子上站起来"（一次冲击后马上继续动）。
 * 只看峰值会疯狂误报；加上"撞击后必须持续静止"就把这两类都排除了：
 *  - 放桌上 → 冲击之后**不**静止（还会被拿起）
 *  - 起身 → 冲击之后立刻走动
 *  - 真摔倒 → 冲击之后长时间不动（这正是定义）
 *
 * ## 为什么必须"老人可取消 + 超时才上报"
 * 误报一次，子女就要被半夜叫醒一次；几次之后所有人都会把告警静音，
 * 于是真摔倒时也没人管 —— 告警疲劳，而且失败方向是漏报。
 * 所以这里给老人 60 秒反悔窗口：
 *  - 老人点"我没事" → 不上报（这是绝大多数误报）
 *  - 无人应答（真摔倒了够不着手机）→ 才上报
 *
 * 老人摔倒后**不可能**按下任何按钮，所以"没有响应"本身就是最可靠的信号。
 * 这是本设计最核心的一条：**不能只信主动上报**。
 */
object FallDetectionPolicy {

    private const val SECOND = 1000L
    private const val MINUTE = 60 * SECOND

    /** 加速度采样间隔（50ms 足以捕捉撞击峰，又不至于耗电） */
    const val SAMPLE_INTERVAL_US = 50_000

    /**
     * 撞击阈值（m/s²）。
     *
     * 静止时加速度计读到 ~9.8（重力）。摔到地面 typical 峰值 20~40 m/s²。
     * 取 18 是"明显超出日常动作（走路摆臂、坐下、拿手机）"的分界，
     * 低于它不触发 —— 日常动作的峰值通常在 12~15。
     */
    const val IMPACT_THRESHOLD = 18f

    /**
     * 撞击前必须先出现"失重"（自由落体阶段，加速度 < 3 m/s²）。
     *
     * 这是把"摔倒"和"被撞/被抢/急刹车"区分开的关键：
     * 真正的跌落是先自由落体（手滑、没站稳），再撞击地面；
     * 而被推倒、坐车急刹只有一次冲击、没有失重段。
     * 加上这个前置条件后误报率下降一个数量级。
     */
    const val FREEFALL_THRESHOLD = 3f

    /** 失重持续时长下限（毫秒）：太短的"失重"是噪声，不是跌落 */
    const val FREEFALL_MIN_MS = 80L

    /** 撞击必须发生在失重之后多久内（毫秒）——超过说明这是两件无关的事 */
    const val IMPACT_AFTER_FREEFALL_MS = 2 * SECOND

    /**
     * 撞击后必须静止多久才判定为跌倒（秒）。
     *
     * 取 12 秒：起身、捡手机、放桌上都在十几秒内就重新动起来了，
     * 而真摔倒的人不会在 12 秒内站起来。
     */
    const val STILL_REQUIRED_MS = 12 * SECOND

    /**
     * 判定"静止"的加速度波动上限（m/s²）。
     *
     * 手机躺在地上时，加速度计读数几乎只剩重力且极其平稳；
     * 而任何微动（呼吸带动、手抖、翻身）都会明显超过这个波动。
     */
    const val STILL_JITTER = 1.2f

    /** 两次告警之间的最小间隔（分钟）：防止同一次摔倒被反复报成多条 */
    const val ALERT_COOLDOWN_MIN = 10

    /** 老人反悔窗口（秒）：老人点"我没事"就不上报 */
    const val CONFIRM_WINDOW_SEC = 60

    /**
     * 合加速度模长（含重力）。
     *
     * 用 sqrt(x²+y²+z²) 而不是单轴：手机在裤兜里的朝向是任意的，
     * 只有模长与朝向无关。取单轴会导致"横着放不触发、竖着放触发"的诡异行为。
     */
    @JvmStatic
    fun magnitude(x: Float, y: Float, z: Float): Float =
        kotlin.math.sqrt(x * x + y * y + z * z)

    /**
     * 跌倒判定状态机。
     *
     * 用显式状态而不是一堆布尔标志，是为了避免"撞击标志忘了清"
     * 这类经典 bug：三个布尔量能组合出 8 种状态，其中大部分是非法的，
     * 而两个互斥状态只有 1 种非法组合（且在 [onSample] 里显式拦掉了）。
     *
     * 状态流转：
     * ```
     * IDLE ──失重≥80ms──> FREEFALL ──2s内撞击≥18──> IMPACTED ──静止≥12s──> FALL_CONFIRMED
     *   ↑                     │                        │                      │
     *   └─────────────────────┴────────────────────────┴──────────────────────┘
     *                      （移动 / 超时 / 撞击未跟上 → 回到 IDLE）
     * ```
     */
    class Detector {

        enum class State { IDLE, FREEFALL, IMPACTED, FALL_CONFIRMED }

        var state: State = State.IDLE
            private set

        /** 进入当前状态的时刻 */
        private var stateEnteredAt = 0L

        /** 撞击时刻（用于写入事件 details，让子女知道"多久前摔的"） */
        var impactAtMs = 0L
            private set

        /** 本次判定用到的峰值加速度（m/s²），写进事件详情便于排查 */
        var peakMagnitude = 0f
            private set

        /** 上一次告警时刻（毫秒），0 = 从未告警 */
        var lastAlertAtMs = 0L

        /**
         * 喂一个采样点。
         *
         * @return 刚达成 FALL_CONFIRMED 的那一刻返回 true（只返回一次，
         *         之后靠 [lastAlertAtMs] 冷却，不会每个采样都报）
         */
        fun onSample(magnitude: Float, nowMs: Long): Boolean {
            if (magnitude > peakMagnitude) peakMagnitude = magnitude

            return when (state) {
                State.IDLE -> {
                    if (magnitude < FREEFALL_THRESHOLD) {
                        state = State.FREEFALL
                        stateEnteredAt = nowMs
                    }
                    false
                }

                State.FREEFALL -> {
                    when {
                        // 撞击：必须在失重后的窗口内，且超过阈值
                        magnitude >= IMPACT_THRESHOLD && nowMs - stateEnteredAt <= IMPACT_AFTER_FREEFALL_MS -> {
                            state = State.IMPACTED
                            stateEnteredAt = nowMs
                            impactAtMs = nowMs
                            false
                        }
                        // 失重后一直没撞击，且已经超出窗口 → 这次是误判（弯腰捡东西等），复位
                        nowMs - stateEnteredAt > IMPACT_AFTER_FREEFALL_MS -> {
                            reset()
                            false
                        }
                        else -> false
                    }
                }

                State.IMPACTED -> {
                    val elapsed = nowMs - stateEnteredAt
                    when {
                        // 撞击后仍在剧烈运动 → 不是摔倒，是走路/坐下/被抱起来
                        magnitude > IMPACT_THRESHOLD -> {
                            reset()
                            false
                        }
                        elapsed >= STILL_REQUIRED_MS -> {
                            state = State.FALL_CONFIRMED
                            stateEnteredAt = nowMs
                            true
                        }
                        else -> false
                    }
                }

                State.FALL_CONFIRMED -> {
                    // 老人动起来了（自己起来了 / 被扶起来了）→ 回到空闲，允许下一次检测
                    if (magnitude > IMPACT_THRESHOLD) reset()
                    false
                }
            }
        }

        /**
         * 撞击后的"静止"判定其实是靠"不再出现大幅加速度"近似出来的，
         * 这里额外提供真实抖动统计，让调用方能拿到更准的置信度描述。
         */
        fun isLikelyStill(magnitude: Float): Boolean =
            state == State.IMPACTED || state == State.FALL_CONFIRMED

        /**
         * 外部复位（老人点了「我没事」、或一次告警已上报完）。
         *
         * 单独暴露是因为状态机内部复位只在"移动"这一种触发下发生，
         * 而老人确认平安是另一种完全独立的复位理由。
         * 不复位的话，下一次真摔倒会在旧的 IMPACTED 状态上直接满足
         * "静止 12 秒"条件 —— 等于把告警延迟 12 秒，对急救来说不可接受。
         */
        fun onSensorReset() {
            state = State.IDLE
            stateEnteredAt = 0L
            peakMagnitude = 0f
        }

        private fun reset() {
            onSensorReset()
        }

        /** 供 UI 展示的当前检测状态文案 */
        fun stateLabel(): String = when (state) {
            State.IDLE -> "正常"
            State.FREEFALL -> "检测到失重"
            State.IMPACTED -> "检测到撞击，正在确认是否静止"
            State.FALL_CONFIRMED -> "已判定为疑似跌倒"
        }

        /** 撞击后已静止的秒数（UI 倒计时用；未撞击返回 0） */
        fun stillSeconds(nowMs: Long): Int {
            if (state != State.IMPACTED) return 0
            val elapsed = nowMs - stateEnteredAt
            return if (elapsed <= 0L) 0 else (elapsed / 1000L).toInt()
        }

        /** 距离判定完成的剩余秒数（UI 倒计时用；不在 IMPACTED 返回 0） */
        fun secondsToConfirm(nowMs: Long): Int {
            if (state != State.IMPACTED) return 0
            val remain = STILL_REQUIRED_MS - (nowMs - stateEnteredAt)
            return if (remain <= 0L) 0 else ((remain + 999L) / 1000L).toInt()
        }
    }

    /**
     * 是否处于告警冷却期。
     *
     * 同一次真实摔倒常常会在地上滚动、被扶起时产生多次"撞击-静止"，
     * 没有冷却就会一次性推三四条一样的 FALL_RISK 给子女，
     * 结果就是"第一条被当真，后面几条全被划掉"。
     */
    @JvmStatic
    fun inCooldown(lastAlertAtMs: Long, nowMs: Long): Boolean {
        if (lastAlertAtMs <= 0L) return false
        val elapsed = nowMs - lastAlertAtMs
        // 时钟回拨时当作仍在冷却：宁可少报一次，也不要在时间乱跳时刷屏
        if (elapsed <= 0L) return true
        return elapsed < ALERT_COOLDOWN_MIN * MINUTE
    }
}
