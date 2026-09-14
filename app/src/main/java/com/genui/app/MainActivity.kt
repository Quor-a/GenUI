package com.genui.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.genui.app.store.GenStore
import com.genui.app.ui.settings.MemoryScreen
import com.genui.app.ui.settings.ModelConfigScreen
import com.genui.app.ui.settings.PermissionScreen
import com.genui.app.ui.settings.SettingsItem
import com.genui.app.ui.settings.SettingsScreen
import com.genui.app.ui.settings.SoulScreen
import com.genui.app.ui.shell.GenScaffold
import com.genui.app.ui.shell.NavTarget
import com.genui.app.ui.theme.GenTheme

/**
 * GenUI —— 生成式界面：整个界面就是 AI 的回复。
 *
 * Agent 子系统：灵魂孵化 / 记忆库 / 工具权限 / 模型服务 —— 主屏单字直达（魂/记/权/模）。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 启动自检：运行时资源（echarts/three/vue/字体…）若缺失，提示词会教模型写
        // 引用不存在的 <script>，导致页面静默白屏。这里在启动时就把问题暴露出来。
        val missingRuntimes = runCatching {
            com.genui.app.render.RuntimeRegistry.selfCheck(assets)
        }.getOrDefault(emptyList())

        val store = GenStore(this)
        setContent {
            Surface(Modifier.fillMaxSize(), color = GenTheme.Screen) {
                val ctx = LocalContext.current
                var screen by remember { mutableStateOf<NavTarget?>(null) }   // null = 主屏
                // 配置版本号：设置类页面里可能改了模型 / 灵魂 / 权限，
                // 关闭时 +1，让主屏的供应商缓存失效并重新读盘。
                var configVersion by remember { mutableStateOf(0) }

                // —— 手机系统权限：通知（Android 13+ 运行时请求，启动即问一次） ——
                val notifPerm = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { }
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED
                    ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                // 资源缺失只在开发期可见（debug 构建），不打扰真实用户
                LaunchedEffect(missingRuntimes) {
                    if (missingRuntimes.isNotEmpty() && BuildConfig.DEBUG) {
                        android.widget.Toast.makeText(
                            ctx,
                            "运行时资源缺失 ${missingRuntimes.size} 项：${missingRuntimes.joinToString("、")}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                }

                // —— 主屏始终保留在组合树中 ——
                // 关键：不能写成 when(screen){ ...; null -> GenScaffold() }。
                // 那样每次进设置页 GenScaffold 都会离开组合树 → WebView 被 DisposableEffect
                // 销毁 → 回到主屏时画布重建，用户刚生成的界面就没了。
                // 正确做法：主屏常驻，设置页叠在它上面（Box 层叠），返回只是把上层移除。
                Box(Modifier.fillMaxSize()) {
                    GenScaffold(
                        store = store,
                        configVersion = configVersion,
                        onNavigate = { screen = it }
                    )

                    // 借本地 val 保住智能转换：screen 是可变状态，直接写在 when 里
                    // 会被当成可空类型，多出一条永不可达的 null 分支。
                    val target = screen
                    if (target != null) {
                        // 关闭设置页的统一出口：先让主屏感知配置已变，再移除上层。
                        val close = {
                            configVersion++
                            screen = null
                        }
                        // 设置类页面自身是不透明背景，覆盖住下面的画布即可
                        Box(Modifier.fillMaxSize().background(GenTheme.Screen)) {
                            when (target) {
                                NavTarget.Soul       -> SoulScreen(store = store, onBack = { screen = NavTarget.Settings })
                                NavTarget.Memory     -> MemoryScreen(store = store, onBack = { screen = NavTarget.Settings })
                                NavTarget.Perms      -> PermissionScreen(store = store, onBack = { screen = NavTarget.Settings })
                                NavTarget.ModelConfig -> ModelConfigScreen(store = store, onBack = { screen = NavTarget.Settings })
                                NavTarget.Settings   -> SettingsScreen(
                                    store = store,
                                    onBack = close,
                                    onItem = { item ->
                                        screen = when (item) {
                                            SettingsItem.Soul -> NavTarget.Soul
                                            SettingsItem.Memory -> NavTarget.Memory
                                            SettingsItem.Perms -> NavTarget.Perms
                                            SettingsItem.ModelConfig -> NavTarget.ModelConfig
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
