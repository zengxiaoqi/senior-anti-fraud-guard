package com.antifraud.guard.util

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatDelegate
import com.antifraud.guard.R

/**
 * 本机外观偏好：字体大小 + 深浅主题。
 *
 * 为什么单独一个 SharedPreferences（`guard_ui_prefs`）而不放进 GuardConfig：
 * GuardConfig 的字段是会**上行到云端**的守护配置（换手机要跟着账号恢复）。
 * 而"这台手机字要大一点""用深色"是纯本机偏好 —— 老人机上装一份、
 * 子女机上装一份，两边本来就该不一样。混进云端配置会导致本机设置被
 * 另一台设备的偏好覆盖，属于典型的"同步了不该同步的东西"。
 *
 * 生命周期：GuardApp.onCreate 里 init 一次即可（Application.onCreate 一定
 * 早于任何 Activity 的 attachBaseContext）。这里仍然做了惰性兜底，
 * 避免将来有别的入口（Service/Receiver）在 init 之前读它时崩溃。
 */
object UiPrefs {

    private const val PREF_NAME = "guard_ui_prefs"
    private const val KEY_FONT_SCALE = "font_scale"
    private const val KEY_THEME_MODE = "theme_mode"

    // ── 字体档位 ──
    // 只给"标准/大/超大"三档，不给"小"：这是老人端，往小调没有任何使用场景，
    // 多一个档位就多一次误触机会。1.15 / 1.30 是实测能明显感知、
    // 又不至于把现有卡片撑破的倍率（卡片高度是 dp 写死的，只放大 sp）。
    const val FONT_STANDARD = 1.00f
    const val FONT_LARGE = 1.15f
    const val FONT_XLARGE = 1.30f

    val FONT_STEPS = listOf(
        FONT_STANDARD to "标准",
        FONT_LARGE to "大",
        FONT_XLARGE to "超大"
    )

    enum class ThemeMode(val stored: String, val label: String) {
        SYSTEM("system", "跟随系统"),
        LIGHT("light", "浅色"),
        DARK("dark", "深色")
    }

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    private fun prefs(context: Context): SharedPreferences {
        val p = prefs
        if (p != null) return p
        val created = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs = created
        return created
    }

    // ──────────────────────────────────────────
    //  字体
    // ──────────────────────────────────────────

    fun setFontScale(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_FONT_SCALE, value).apply()
    }

    fun fontScale(context: Context): Float =
        prefs(context).getFloat(KEY_FONT_SCALE, FONT_STANDARD)

    fun fontLabel(context: Context): String {
        val s = fontScale(context)
        return FONT_STEPS.firstOrNull { it.first == s }?.second ?: "标准"
    }

    /**
     * 把字体倍率写进 Configuration。
     *
     * 用**覆盖**而不是"叠加系统字体倍率"：很多老人已经在系统设置里调大了字体，
     * 若再叠加一层会到 1.5~1.8，卡片里的文字直接撑破布局。
     * 覆盖能保证"选了超大"在三台手机上看到的是同一个效果。
     */
    fun wrapContext(base: Context): Context {
        val scale = fontScale(base)
        if (scale == FONT_STANDARD) return base
        val config = Configuration(base.resources.configuration)
        config.fontScale = scale
        return base.createConfigurationContext(config)
    }

    // ──────────────────────────────────────────
    //  主题
    // ──────────────────────────────────────────

    fun themeMode(context: Context): ThemeMode {
        val s = prefs(context).getString(KEY_THEME_MODE, ThemeMode.SYSTEM.stored)
        return ThemeMode.values().firstOrNull { it.stored == s } ?: ThemeMode.SYSTEM
    }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        // 必须 commit()（同步落盘）：切主题会重启进程收尾，
        // apply() 是异步写盘，进程一退未落盘的值就丢了 —— 表现是"点了深色、重启回来还是浅色"
        prefs(context).edit().putString(KEY_THEME_MODE, mode.stored).commit()
        applyNightMode(context)
    }

    fun themeLabel(context: Context): String = themeMode(context).label

    /** 系统当前是否处于深色（用于「跟随系统」这一档） */
    private fun systemIsDark(context: Context): Boolean {
        val uiMode = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return uiMode == Configuration.UI_MODE_NIGHT_YES
    }

    /**
     * 同步 AppCompatDelegate 的日夜模式。
     *
     * 这一步不能省：光 setTheme 只改我们自己的 attr，
     * Material 组件（对话框、进度条、Snackbar）走的是 AppCompatDelegate 的 night 判定，
     * 不同步就会出现"页面深色、弹窗白底"。
     */
    fun applyNightMode(context: Context) {
        val delegateMode = when (themeMode(context)) {
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(delegateMode)
    }

    /**
     * 取当前主题下的语义颜色（如 R.attr.appText）。
     *
     * 代码里那些 `0xFF94A3B8.toInt()` 写死的颜色，在深色下是"次要文字灰"，
     * 切到浅色后同样的数值就成了"浅底上的浅灰"——看不见了。
     * 凡是**表达角色**（文字/次要文字/卡片/页面底色）而不是**表达状态**
     * （红黄绿这些语义色两套主题通用）的，都要走这里取。
     */
    @ColorInt
    fun color(context: Context, @AttrRes attr: Int): Int {
        val tv = android.util.TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(context, tv.resourceId)
        } else {
            tv.data
        }
    }

    // 常用语义色的快捷方式：调用点写起来短，也避免 attr id 写错
    @ColorInt fun textColor(context: Context) = color(context, R.attr.appText)
    @ColorInt fun dimColor(context: Context) = color(context, R.attr.appTextDim)
    @ColorInt fun cardColor(context: Context) = color(context, R.attr.appCard)
    @ColorInt fun bgColor(context: Context) = color(context, R.attr.appBg)

    @StyleRes
    fun themeRes(context: Context): Int = when (themeMode(context)) {
        ThemeMode.LIGHT -> R.style.Theme_AntiFraudGuard_Light
        ThemeMode.DARK -> R.style.Theme_AntiFraudGuard
        ThemeMode.SYSTEM -> if (systemIsDark(context)) R.style.Theme_AntiFraudGuard
        else R.style.Theme_AntiFraudGuard_Light
    }
}
