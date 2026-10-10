package com.antifraud.guard

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.antifraud.guard.util.UiPrefs

/**
 * 所有界面的基类：只做两件全局生效的事 —— 字体倍率与深浅主题。
 *
 * 为什么必须有基类而不是在每个 Activity 里写一遍：
 * 这两个设置要**每个界面都生效**，靠人记住"新建页面时加一段 attachBaseContext"
 * 迟早会漏，漏掉的那个页面就会字突然变小 / 配色和别人不一样。
 * 收敛到基类后，新建 Activity 只要继承 BaseActivity 就自动带上。
 *
 * 顺序上有硬约束，写反了就静默失效：
 *   1. attachBaseContext —— 必须在这里套 fontScale，晚于这一步 Configuration 已定型
 *   2. setTheme          —— 必须在 super.onCreate **之前**，否则主题不生效（无报错，只是不变）
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(UiPrefs.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        UiPrefs.applyNightMode(this)
        setTheme(UiPrefs.themeRes(this))
        super.onCreate(savedInstanceState)
    }
}
