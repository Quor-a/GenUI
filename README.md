# GenUI

> A2UI · Android 原生生成式 UI 应用 —— **整个界面就是 AI 的回复**。
> 没有内置页面、没有内置模板、没有内置排版规则：AI 直接写出界面，
> App 只提供三件事 —— **壳（安全沙箱）、渲染（六通道渲染）、桥（真实功能对接）**。

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](./LICENSE)
[![Release](https://img.shields.io/github/v/release/Quor-a/GenUI)](https://github.com/Quor-a/GenUI/releases)
[![Platform](https://img.shields.io/badge/platform-Android%2010%2B-green.svg)](#构建)

---

## 一句话生成什么

对 GenUI 说一句话，AI 自主决定用哪种技术栈交付：

| 通道 | 交付物 | 端上渲染方式 |
|---|---|---|
| `html` | 完整网页 | WebView 流式渲染（SSE chunk → `document.write`，写一点渲染一点），可配 **31 种离线运行时**（框架/图表/3D/物理/真数据库/Excel/路由…） |
| `xml` | Android XML 布局 | 端上自解析 + 反射建 View → **真实原生控件**（无白名单） |
| `compose` | Compose 组件树 JSON | 映射为真实 Material 3 Compose 组件（26 种） |
| `canvas` | 绘制指令 JSON | GenCanvas 解释为真实原生画面（Yoga 布局 + Canvas 绘制 + 真动画 + 真命中测试） |
| `a2ui` | A2UI v0.10 JSONL | **谷歌官方 A2UI-Android 引擎**渲染安卓原生界面（生成式 UI 的安卓原生形态） |
| `miniapp` | 微信小程序语法三件套 | **自研小程序引擎**原生渲染（不走 WebView） |

- **多通道可混用**：HTML 负责整体排版与图表，原生控件负责系统级质感的表单/开关/滑杆。
- **代码呈现兜底**：kotlin/java/cpp/python 代码块自动接管为行号 + 语法高亮 + 复制的代码视图。

## Agent 对话模式（对话框就是交付面）

生成模式之外，GenUI 内置完整 Agent 对话（决策轮 + 渲染轮、多工具调用、流式气泡、思考时间线）。
**交付直接发生在对话框/画布里，不跳独立程序**：

- **小程序卡片内嵌**：AI 调 `create_miniapp` 一句话生成完整小程序（微信小程序语法），创建即校验
  （app.json / 页面完整性 / WXML 解析 / JS 引擎级语法预检），成功后**对话卡片与画布同时内嵌
  可交互的小程序本体**——多页导航（`wx.navigateTo`）、`wx.request`/存储/剪贴板/通知等 40+ API 真实可用。
- **A2UI 原生直出**：AI 在普通聊天里直接输出 A2UI v0.10 JSONL，端上用官方引擎渲染成
  真原生交互界面（生成式 UI 的安卓原生形态）。
- **原生组件唤起**：AI 页面经 `MoBridge.ui.widget` 唤起 Compose 可视化弹窗，结果回写页面。

## 架构

```
┌────────────────────────────────────────────────────────────┐
│ 壳 Scaffold        状态行 · 指令条 · 界面栈 · 思考时间线 · 安全配置 │
├────────────────────────────────────────────────────────────┤
│ 渲染 Render（六通道调度）                                      │
│   ├ html      LLM SSE chunk → document.write     WebView 流式  │
│   ├ xml       AI XML → XmlLayoutRenderer          真原生控件    │
│   ├ compose   AI JSON → ComposeDescRenderer       Material 3   │
│   ├ canvas    AI 绘制JSON → GenCanvas             Yoga+Canvas  │
│   ├ a2ui      A2UI JSONL → 官方 A2UI-Android 引擎  原生界面     │
│   └ miniapp   WXML/WXSS/JS → MiniAppEngine        自研原生渲染   │
├────────────────────────────────────────────────────────────┤
│ 桥 Bridge          window.MoBridge（Promise API）             │
│   time/store/net.proxy/notify/haptics/clipboard/device      │
│   ui.title/ui.widget/a2ui + 工具：日历·新闻·GitHub·社区·后端·Python │
├────────────────────────────────────────────────────────────┤
│ 逻辑 Agent         AgentLoop 决策轮+渲染轮 · ChatSession 对话轮  │
│   工具授权（ToolGate）· 事件时间线 · 专项模型分派 · 中断续写        │
└────────────────────────────────────────────────────────────┘
```

## 自研小程序引擎（miniapp-sdk）

不依赖 WebView、不依赖微信运行时，**纯 Kotlin + 自研 C++ JS 引擎**的小程序实现：

```
miniapp-sdk/
├── js/        JsEngine —— 自研 C++ JS 引擎（lexer/parser/interpreter/builtins，
│              经 JNI 桥接；支持 var/let/const、箭头函数、闭包、模板拼接等核心子集）
├── view/      WxmlParser（wx:for / wx:if / wx:elif / wx:else / bind* 事件）
│              WxssParser（选择器 → Style）、VDomBuilder、ExpressionEvaluator（{{ }} 绑定）
├── render/    FlexLayout（row/column/wrap/justify/align + rpx=viewport/750）
│              GLRenderer / CanvasPainter / TextMeasurer —— Skia 原生绘制
├── core/      MiniAppEngine（引擎门面）/ LogicRuntime（App/Page/setData，独立逻辑线程）
│              MiniAppView（Android View 宿主：对话框卡片与画布内嵌渲染）
└── nativeapi/ WxApi —— 40+ wx.* API（request / navigateTo / 存储 / 剪贴板 / 通知 …）
```

**创建即校验闭环**：`create_miniapp` 用引擎同一套解析器在创建时校验
（app.json 合法性 → 页面文件完整性 → WXML 可解析 → JS 语法预检），
不合格带定位错误打回 AI 同轮自纠；WXML 解析为软校验（fail-open，不阻塞交付）。

## 真实 Python（CPython 3.12）

`pybridge`（自研 JNI 桥）dlopen `libpython3.14.so` 完整嵌入 CPython，
`run_python` 工具提供完整标准库（json/urllib/hashlib/…）的脚本执行，
16KB 页大小合规（Android 15+ 设备必需）。

## 关键模块

| 模块 | 职责 |
|---|---|
| `agent/AgentLoop.kt` | 生成模式主循环：决策轮（带 tools）+ 渲染轮（流式）；工具授权、事件流、重复调用检测、中断续写 |
| `agent/ChatSession.kt` | 对话模式会话：流式气泡、思考折叠、小程序卡片/A2UI 直出检测、多轮上下文 |
| `agent/tools/BuiltinTools.kt` | 内置工具族（create_miniapp / run_python / 日历 / 新闻 / GitHub / 社区 / 后端…）与声明 |
| `llm/LLMClient.kt` | SSE 流式客户端：OpenAI 兼容 / Anthropic / Gemini 三协议；显式 max_tokens（防静默截断） |
| `llm/ProviderPresets.kt` | 15 家供应商预设（端点 + 模型 + 协议），一键填充 |
| `render/RenderChannel.kt` | 六通道识别与分派（与 CodeLangRegistry 同 id 对齐） |
| `render/A2UIRenderer.kt` | HTML 流式渲染引擎：`begin → writeChunk×N → end`，`replay()` 回放界面栈 |
| `render/CodeLangRegistry.kt` | AI 可用技术栈的**唯一事实来源**：提示词段落由它派生，端上分派按同 id 对齐 |
| `render/RuntimeRegistry.kt` | 31 种离线 JS 运行时注册表 + 字体，`selfCheck` 启动校验，清单注入提示词 |
| `bridge/MoBridgeHost.kt` | `window.MoBridge`（Promise API）：后台线程执行、限流 net.proxy、通知需授权 |
| `ui/shell/GenScaffold.kt` | 壳：状态行、指令条、原生渲染层（xml/compose/canvas/A2UI/miniapp 叠加）、界面栈 |
| `store/GenStore.kt` | 零依赖 JSON 持久化：供应商配置 / 专项模型分派 / 界面栈（上限 100 张纸）/ bridge KV |
| `perms/PermRegistry.kt` | Android 权限 ↔ 工具 ↔ 引导文案的单一事实来源 |

## 安全边界

- **输出即代码**：AI 产出直接渲染/执行，App 的边界是**系统沙箱**——网络经限流代理、
  通知需授权、危险工具走权限门禁（ToolGate 按工具族分级）、Python 无第三方库注入。
- **提示词契约 + 客户端硬兜底双保险**：输出契约由提示词声明，分派与校验由端上硬逻辑兜底。
- 第三方 JS 运行时全部**离线内置**（无 CDN 依赖），清单与用法由 `RuntimeRegistry` 注入提示词。

## 构建

```
JDK 21 · AGP 8.13 · Kotlin 2.3 · Gradle 8.14 · compileSdk 36 · NDK 27 + CMake 3.22
```

```bash
git clone https://github.com/Quor-a/GenUI.git
cd GenUI
./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk
```

- 双模块：`:app`（应用层）+ `:miniapp-sdk`（自研小程序引擎，含 C++ JS 引擎 NDK 构建）
- 首次使用在「模型服务」里配置任意 OpenAI 兼容 / Anthropic / Gemini 供应商即可开始生成
- 或直接从 [Releases](https://github.com/Quor-a/GenUI/releases) 下载预构建 APK

## 设计与致谢

- 设计概念来自 `genui-original-design.html`（打字机美学：暖黑 / 琥珀 / 稿纸）
- 架构参考 [Quor-a/ZorvAI](https://github.com/Quor-a/ZorvAI) 的模型配置体系
  （多供应商协议 / 专项模型 / 端侧引擎），并推向"零预置 UI"
- A2UI 渲染引擎基于 [Google A2UI](https://github.com/google/A2UI) 官方 Android 实现（vendored）

## 开源协议

- **自研代码**（`app/src/main/java/com/genui/app/`、`miniapp-sdk/`、`app/src/main/cpp/`、`assets/runtimes/genui-*.js`）：**Apache License 2.0**，100% 开源，见 [LICENSE](./LICENSE)。
- **第三方组件**（A2UI-Android 引擎、JS 运行时库、字体、Python 运行时等）：保留其**各自原始许可证**，未作任何变更——完整清单见 [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)。
