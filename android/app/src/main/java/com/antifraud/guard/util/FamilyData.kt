package com.antifraud.guard.util

/**
 * 各品牌「广告与推荐开关」直达入口表。
 *
 * 数据全部来自 android-ad-cleaner（MIT）`tool/entries.py` 的实测记录——
 * 每个包名/Activity 都在某台真机上验证过可达（其 tests/test_entries.py 守着出处），
 * 不是猜的。menu / searchHint 来自该仓库 docs/清单 的手动路径。
 *
 * 三档降级在 [com.antifraud.guard.AdSourceOffActivity.jump]：
 * directs（组件直达）→ actions/uris（厂商私有深链）→ app（拉起对应 App）→ 系统设置+路径提示。
 *
 * 品牌家族映射（ro.product.brand 小写）与上游一致：redmi/poco 归小米、oneplus/realme 归 OPPO、
 * iqoo 归 vivo。上游没有记录的分组就不写——猜的包名会跳到别处。
 */
object FamilyData {

    data class Direct(val pkg: String, val activity: String)

    data class Action(val action: String, val pkg: String = "")

    data class EntryRow(
        val name: String,
        val menu: String,
        val directs: List<Direct> = emptyList(),
        val actions: List<Action> = emptyList(),
        val uris: List<String> = emptyList(),
        val app: String = "",
        val searchHint: String = "",
        val note: String = ""
    )

    /** ro.product.brand 小写 → 家族 id；返回 null 表示上游没有该家族的记录 */
    fun familyOf(brand: String): String? = FAMILY[brand.trim().lowercase()]

    fun label(family: String): String = LABEL[family] ?: family

    fun rowsFor(family: String?): List<EntryRow>? = ROWS[family]

    private val FAMILY = mapOf(
        "xiaomi" to "xiaomi", "redmi" to "xiaomi", "poco" to "xiaomi",
        "oppo" to "oppo", "oneplus" to "oppo", "realme" to "oppo",
        "vivo" to "vivo", "iqoo" to "vivo",
        "honor" to "honor", "huawei" to "huawei",
        "meizu" to "meizu", "samsung" to "samsung"
    )

    private val LABEL = mapOf(
        "xiaomi" to "MIUI/HyperOS", "oppo" to "ColorOS", "vivo" to "OriginOS",
        "honor" to "MagicOS", "huawei" to "HarmonyOS/EMUI", "meizu" to "Flyme",
        "samsung" to "One UI"
    )

    private val ROWS: Map<String, List<EntryRow>> = mapOf(
        "xiaomi" to listOf(
            EntryRow(
                name = "系统广告总闸",
                menu = "设置 → 隐私与安全 → 更多安全设置 → 广告服务",
                directs = listOf(Direct("com.android.settings", "com.android.settings.ad.AdServiceSettings")),
                searchHint = "系统广告 或 广告服务",
                note = "「系统工具广告」也在这类页面里，一并关掉"
            ),
            EntryRow(
                name = "应用商店推广位",
                menu = "应用商店 → 我的 → 设置（通知设置 / 隐私设置）",
                directs = listOf(Direct("com.xiaomi.market", "com.xiaomi.market.ui.MarketPreferenceActivity")),
                app = "com.xiaomi.market"
            ),
            EntryRow(
                name = "手机管家推荐",
                menu = "手机管家 → 右上齿轮（隐私设置在最底部）",
                directs = listOf(Direct("com.miui.securitycenter", "com.miui.securityscan.ui.settings.SettingsActivity")),
                app = "com.miui.securitycenter"
            ),
            EntryRow(
                name = "下载管理信息流",
                menu = "下载管理 → 右上「更多」→ 设置 → 信息流设置",
                directs = listOf(Direct("com.android.providers.downloads.ui", "com.android.providers.downloads.ui.activity.InfoFlowSettingActivity")),
                app = "com.android.providers.downloads.ui"
            ),
            EntryRow(
                name = "负一屏推荐卡片",
                menu = "负一屏 → 设置 → 推荐卡片、热门活动；隐私设置 → 个性化推荐",
                directs = listOf(Direct("com.miui.personalassistant", "com.miui.personalassistant.settings.PASettingActivity")),
                app = "com.miui.personalassistant"
            ),
            EntryRow(
                name = "浏览器个性化推荐",
                menu = "浏览器 → 菜单/我的 → 设置（个性化内容在「个性化推荐设置」独立页）",
                directs = listOf(
                    Direct("com.android.browser", "com.android.browser.preferences.activity.CustomizationPreferenceActivity"),
                    Direct("com.android.browser", "com.android.browser.BrowserSettingsActivity")
                ),
                searchHint = "浏览器",
                app = "com.android.browser"
            ),
            EntryRow(
                name = "全局搜索推荐",
                menu = "桌面下滑 → 搜索页右上齿轮",
                directs = listOf(Direct("com.android.quicksearchbox", "com.android.quicksearchbox.preferences.SearchSettingsPreferenceActivity")),
                app = "com.android.quicksearchbox"
            ),
            EntryRow(
                name = "日历/天气体验计划",
                menu = "天气 → 更多设置 → 用户体验计划；日历 → 设置 → 用户体验计划",
                directs = listOf(Direct("com.android.calendar", "com.android.calendar.settings.UserExperienceActionBarActivity")),
                app = "com.miui.weather2"
            ),
            EntryRow(
                name = "主题商店推荐",
                menu = "主题 → 我的 → 右上设置",
                directs = listOf(
                    Direct("com.android.thememanager", "com.android.thememanager.ThemeResourceProxyTabActivity"),
                    Direct("com.miui.themestore", "com.miui.thememanagerstore.ThemeResourceTabActivity")
                ),
                app = "com.android.thememanager"
            )
        ),
        "oppo" to listOf(
            EntryRow(
                name = "广告跟踪（设备标识）",
                menu = "设置 → 隐私与安全 → 设备标识与广告 → 广告跟踪（列表可右上「全部关闭」）",
                searchHint = "广告跟踪 或 设备标识与广告",
                note = "ColorOS 17 部分版本搜得到但点了不跳，按路径走菜单"
            ),
            EntryRow(
                name = "负一屏热点资讯",
                menu = "桌面左边缘右滑 → 左上头像 → 设置",
                directs = listOf(Direct("com.coloros.assistantscreen", "com.oplus.assistantscreen.card.infoflow.setting.activity.InfoFlowSettingActivity")),
                note = "设置总页受系统权限保护跳不进去，直达的是「热点资讯」子页"
            ),
            EntryRow(
                name = "软件商店推荐",
                menu = "软件商店 → 我的 → 右上齿轮",
                actions = listOf(Action("oppo.intent.action.APP_SETTINGS", "com.heytap.market")),
                app = "com.heytap.market"
            ),
            EntryRow(
                name = "游戏中心个性化推荐",
                menu = "游戏中心 → 我的 → 设置 → 隐私开关设置 → 个性化推荐管理",
                actions = listOf(Action("oppo.intent.action.APP_SETTINGS", "com.nearme.gamecenter")),
                app = "com.nearme.gamecenter"
            ),
            EntryRow(
                name = "主题商店推荐",
                menu = "主题商店 → 我的 → 右上设置",
                actions = listOf(Action("oppo.intent.action.APP_SETTINGS", "com.heytap.themestore")),
                app = "com.heytap.themestore"
            ),
            EntryRow(
                name = "视频个性化内容",
                menu = "视频 → 我的 → 设置 → 个性化内容管理",
                uris = listOf("yoli://yoli.com/mine_act_lib_yoli/settings"),
                app = "com.heytap.yoli"
            ),
            EntryRow(
                name = "浏览器设置",
                menu = "浏览器 → 底部 ☰ 或「我的」→ 设置",
                directs = listOf(Direct("com.heytap.browser", "com.heytap.browser.settings.component.BrowserPreferencesPage")),
                app = "com.heytap.browser"
            ),
            EntryRow(
                name = "下拉搜索推荐",
                menu = "桌面中间下滑 → 搜索页底部「设置」",
                directs = listOf(Direct("com.heytap.quicksearchbox", "com.heytap.quicksearchbox.ui.activity.SearchHomeActivity")),
                note = "设置子页没导出，直达的是搜索首页，再点最下面的「设置」"
            )
        ),
        "vivo" to listOf(
            EntryRow(
                name = "系统广告与隐私",
                menu = "设置 → 安全 → 更多安全设置 → 广告与隐私",
                directs = listOf(Direct("com.android.settings", "com.android.settings.Settings\$AdAndPrivacyActivityForOsTwo")),
                searchHint = "广告与隐私",
                note = "个性化广告、个性化推荐、智能推荐服务三个开关在同一页"
            ),
            EntryRow(
                name = "阅图锁屏广告",
                menu = "设置 → 桌面、锁屏与壁纸 → 锁屏设置 → 阅图锁屏",
                directs = listOf(Direct("com.vivo.magazine", "com.vivo.magazine.ui.MagazineSettings")),
                app = "com.vivo.magazine"
            ),
            EntryRow(
                name = "全局搜索推荐",
                menu = "设置 → 系统管理与升级 → 全局搜索",
                directs = listOf(Direct("com.vivo.globalsearch", "com.vivo.globalsearch.view.SearchSettingsActivity")),
                app = "com.vivo.globalsearch"
            ),
            EntryRow(
                name = "应用商店推荐",
                menu = "应用商店 → 我的 → 设置",
                directs = listOf(Direct("com.bbk.appstore", "com.bbk.appstore.ui.menu.AppstoreSettingsActivity")),
                app = "com.bbk.appstore"
            ),
            EntryRow(
                name = "i管家个性化设置",
                menu = "i管家 → 设置 → 关于 → 个性化设置",
                directs = listOf(Direct("com.iqoo.secure", "com.iqoo.secure.MainSettings")),
                app = "com.iqoo.secure"
            ),
            EntryRow(
                name = "天气服务推荐",
                menu = "天气 → 设置 → 天气服务",
                directs = listOf(Direct("com.vivo.weather", "com.vivo.weather.WeatherSettings")),
                app = "com.vivo.weather"
            ),
            EntryRow(
                name = "浏览器设置",
                menu = "浏览器 → 我的 → 齿轮",
                app = "com.vivo.browser"
            ),
            EntryRow(
                name = "负一屏个性化推荐",
                menu = "桌面中间右滑 → 右上齿轮 → 更多设置 → 个性化推荐",
                note = "设置页未导出，只能按路径手动进入"
            )
        ),
        "honor" to listOf(
            EntryRow(
                name = "应用广告跟踪管理",
                menu = "设置 → 搜索「广告」→ 广告与隐私",
                directs = listOf(Direct("com.hihonor.id", "com.hihonor.id.oaid.ui.AdvertisingPrivacyActivity")),
                searchHint = "广告"
            ),
            EntryRow(
                name = "智能推荐服务",
                menu = "设置 → 广告与隐私 → 智能推荐服务",
                directs = listOf(Direct("com.hihonor.id", "com.hihonor.id.oaid.ui.IntelligentRecommendActivity")),
                searchHint = "广告"
            ),
            EntryRow(
                name = "桌面文件夹热门推荐",
                menu = "打开桌面文件夹 → 底部「更多推荐」→ 设置",
                directs = listOf(
                    Direct("com.hihonor.appmarket", "com.hihonor.appmarket.module.topapps.TopAppsSettingActivity"),
                    Direct("com.hihonor.appmarket", "com.hihonor.appmarket.popularapps.activity.PopularAppsSettingActivity")
                ),
                app = "com.hihonor.appmarket"
            ),
            EntryRow(
                name = "系统管家推荐",
                menu = "系统管家 → 右上齿轮",
                directs = listOf(Direct("com.hihonor.systemmanager", "com.hihonor.systemmanager.mainscreen.SettingActivity")),
                app = "com.hihonor.systemmanager"
            ),
            EntryRow(
                name = "YOYO 新闻资讯",
                menu = "设置 → 搜索「新闻资讯」",
                searchHint = "新闻资讯"
            ),
            EntryRow(
                name = "魔法画报",
                menu = "设置 → 搜索「杂志」→ 魔法画报",
                searchHint = "杂志 或 画报"
            ),
            EntryRow(
                name = "负一屏设置",
                menu = "桌面左边缘右滑 → 头像 → 设置",
                directs = listOf(Direct("com.hihonor.hiboard", "com.hihonor.intelligent.LauncherActivity")),
                note = "直达的是负一屏首页，设置入口在右上头像"
            ),
            EntryRow(
                name = "下拉搜索推荐",
                menu = "桌面中间下滑 → 「更多」→ 设置",
                directs = listOf(Direct("com.hihonor.search", "com.hihonor.search.feature.mainpage.presentation.page.HomeActivity")),
                note = "设置子页没导出，直达搜索首页后点「更多 → 设置」"
            ),
            EntryRow(
                name = "应用市场推荐",
                menu = "应用市场 → 我的 → 设置",
                app = "com.hihonor.appmarket"
            ),
            EntryRow(
                name = "浏览器设置",
                menu = "浏览器 → 我的/更多 → 设置",
                app = "com.hihonor.baidu.browser"
            )
        ),
        "huawei" to listOf(
            EntryRow(
                name = "华为广告 · 智能推荐服务",
                menu = "设置 → 隐私 → 华为广告 → 智能推荐服务",
                searchHint = "华为广告 或 智能推荐服务"
            ),
            EntryRow(
                name = "跟踪管理",
                menu = "设置 → 隐私 → 跟踪管理 → 允许全部应用跟踪（关掉）",
                searchHint = "跟踪管理"
            ),
            EntryRow(
                name = "通知管理（营销通知主要来源）",
                menu = "设置 → 通知 → 批量管理 → 逐个关掉不需要的 App 通知",
                directs = listOf(Direct("com.android.settings", "com.android.settings.notification.NotificationSettings")),
                searchHint = "通知管理",
                note = "华为部分版本直达页受限，打不开就搜索「通知」"
            )
        ),
        "meizu" to listOf(
            EntryRow(
                name = "应用跟踪控制",
                menu = "设置 → 权限和隐私 → 应用跟踪控制",
                searchHint = "应用跟踪 或 跟踪"
            ),
            EntryRow(
                name = "应用商店个性化推荐",
                menu = "应用商店 → 我的 → 设置 → 个性化推荐",
                app = "com.meizu.mstore"
            ),
            EntryRow(
                name = "手机管家设置",
                menu = "手机管家 → 设置",
                app = "com.meizu.safe"
            ),
            EntryRow(
                name = "锁屏画报",
                menu = "主题美化 → 头像 → 锁屏画报 → 设置 → 个性化推荐",
                searchHint = "锁屏画报"
            ),
            EntryRow(
                name = "浏览器设置",
                menu = "浏览器 → 工具 → 设置",
                app = "com.meizu.browser"
            )
        ),
        "samsung" to listOf(
            EntryRow(
                name = "Google 广告个性化",
                menu = "设置 → Google → 广告 → 选择停用广告个性化功能（点整行标题，弹确认点「确定」）",
                directs = listOf(Direct("com.google.android.gms", "com.google.android.gms.adid.settings.AdsSettingsActivity")),
                note = "必须点整行标题才弹确认；关掉后广告 ID 变全零"
            ),
            EntryRow(
                name = "Galaxy 商店个性化",
                menu = "Galaxy Store → 侧边菜单 → 设置 → 允许使用个人信息 / 获取新闻和特惠",
                app = "com.sec.android.app.samsungapps"
            ),
            EntryRow(
                name = "通知管理（营销通知主要来源）",
                menu = "设置 → 应用程序通知 → 选择应用 → 关掉营销类渠道",
                directs = listOf(Direct("com.android.settings", "com.android.settings.Settings\$NotificationAssistanceSettingsActivity")),
                searchHint = "应用程序通知"
            )
        )
    )
}
