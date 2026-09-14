# GenUI

> A2UI · Android 原生 AI 应用 —— **整个界面就是 AI 的回复**。
> 没有内置组件、没有内置模板、没有内置排版规则：AI 直接写出界面，
> App 只提供三件事 —— **壳（安全沙箱）、渲染（多通道渲染）、桥（真实功能对接）**。

**AI 画界面的技术栈由 AI 自己选**：

| 技术栈 | 端上怎么渲染 |
|---|---|
| `html` | WebView 流式渲染（默认，可配 27 种离线运行时：WebUI组件库/多层路由/框架/真数据库/Excel/图表/动画/3D/2.5D/物理/图标/探索引擎/后端框架/原子CSS） |
| `xml` | AI 写 Android XML 布局 → 端上**真实原生控件**渲染 |
| `compose` | AI 写 Compose 组件树描述 → 端上映射为**真实 Compose 组件** |
| `canvas` | AI 写绘制指令 JSON → GenCanvas 解释为**真实原生画面**（Yoga 布局 + Canvas 绘制 + 真动画 + 真命中测试），无 WebView / 无组件白名单 / 无编译 |
| `kotlin` / `java` / `cpp` / `python` | 作为**界面题材**呈现（代码视图 / 算法演示） |

多条通道可在**同一个界面里混用**：HTML 负责整体排版与图表，原生控件负责需要
系统级质感的部分（表单、开关、滑杆），各取所长。

关于最后一行：这四种语言端上**不能编译执行**（手机里没有 Kotlin/Java/C++ 编译器，
Android 也禁止运行期加载 dex；Python 需要 20–40MB 解释器且画不了 UI）。
它们的价值在于**成为界面的内容**——代码讲解页、算法演示器、JNI 结构说明图。
呈现方式由 AI 自己决定：**推荐**它用 HTML 画代码视图（风格与整页统一）；
如果它只给了裸代码块（`<script type="text/x-code" data-lang="kotlin">`），
端上会自动接管渲染成带行号 / 语法高亮 / 复制按钮的代码视图。

设计概念来自 `genui-original-design.html`（打字机美学：暖黑 / 琥珀 / 稿纸）。
架构参考 [Quor-a/ZorvAI](https://github.com/Quor-a/ZorvAI) 的模型配置体系
（多供应商协议 / 专项模型 / 端侧引擎）与 html 工件运行思路，并推向"零预置 UI"。

---

## 架构（App 只有三层）

```
┌─────────────────────────────────────────────────────┐
│ 壳 Scaffold        状态行 · 指令条 · 界面栈 · 安全配置     │
├─────────────────────────────────────────────────────┤
│ 渲染 Render        多通道渲染调度                       │
│   ├ 网页通道  LLM SSE chunk ──→ document.write(chunk) │  ← 浏览器原生流式解析，
│   │            （任意截断均安全，截断标签由解析器缓冲）       │     写一点、渲染一点
│   ├ 原生通道  AI 写 XML  ──→ XmlLayoutRenderer        │  ← 自解析 + 反射建 View，
│   │            （无白名单，AI 想写什么控件就建什么）         │     真原生控件
│   └ Compose   AI 写 JSON ──→ ComposeDescRenderer      │  ← 组件树映射为真 Compose
│   └ 代码呈现  裸代码块 ──→ CodeBlockView               │  ← 兜底：行号/高亮/复制
│   └ GenCanvas  AI 写绘制JSON ──→ io.gencanvas.runtime  │  ← Yoga布局+Canvas绘制+真动画
├─────────────────────────────────────────────────────┤
│ 桥 Bridge          window.MoBridge（Promise API）      │
│   time/store/net.proxy/notify/haptics/clipboard     │  ← AI 的 UI 按钮真的能
│   device/ui.title/ui.widget                         │     记账、发通知、查时间
└─────────────────────────────────────────────────────┘
```

UI 长什么样、用什么技术画，完全由模型决定 —— 同一个 App，两次生成可以风格迥异。

## 关键实现

| 文件 | 职责 |
|---|---|
| `render/A2UIRenderer.kt` | 流式渲染引擎：`begin → writeChunk×N → end`。在 `https://genui.local` origin 上 `document.open()` 开流式通道；chunk 早于页面就绪时走缓冲队列；`replay()` 回放界面栈 |
| `render/RenderChannel.kt` | 通道识别与分派：从产出里识别 `stack:xml` / `stack:compose` 标记，给出渲染计划 |
| `render/XmlLayoutRenderer.kt` | AI 写的 XML → 真实原生控件。自解析 DOM + 反射建 View（LayoutInflater 需要已编译资源，运行时 XML 用不了）；**不做标签/属性白名单**，设不上的属性静默跳过 |
| `render/ComposeDescRenderer.kt` | AI 写的 Compose 组件树描述 → 真实 Compose 组件（Material 3）。含 26 种组件与 `componentHelp`（同时注入提示词） |
| `render/CodeLangRegistry.kt` | AI 可用界面技术栈的**唯一事实来源**：提示词段落由它派生，端上分派按同 id 对齐 |
| `render/CodeBlockView.kt` | 代码呈现通道的兜底渲染：行号 + 零依赖语法高亮（Kotlin/Java/C++/Python/XML）+ 一键复制 + 双向滚动。仅在 AI 未自行排版时接管 |
| `render/CanvasNativeView.kt` | GenCanvas 通道：AI 写绘制指令 JSON → 端上解释为真实安卓原生画面（Yoga 布局 + Canvas 绘制 + 真动画 + 真命中测试），无 WebView / 无组件白名单 / 无编译 |
| `io/gencanvas/*` | 用户上传的 GenCanvas 引擎（已并入单模块）：`core-model` 数据模型 / `core-layout` Yoga 绑定 / `core-paint` 绘制解释器 / `core-runtime` 净化+组装+错误边界。骨架期 bug（SVG tint 失效、Yoga 百分比错配、无限递归、缺失 API）已修复 |
| `render/RuntimeRegistry.kt` | 离线 JS 运行时注册表 + 字体，`selfCheck` 启动校验。25 种：Bootstrap（WebUI基建）/Element Plus（Vue企业组件库）/Lucide（SVG图标）/Swiper（轮播）/Dexie（IndexedDB真库）/SheetJS（Excel读写）/GenUI Spa（多层界面路由）/Vue Router 4（Vue路由）、Vue/React+htm/Alpine（框架）、Mermaid/ECharts/Chart.js/D3（图表）、GSAP/Anime（动画）、Three/Babylon（3D）、Zdog/Obelisk（2.5D）、Matter（物理）、CountUp、Tailwind（原子样式） |
| `bridge/MoBridgeHost.kt` | `JS_WRAPPER`（页面侧 Promise 包装，先于 AI 脚本注入）+ `MoBridgeDispatcher`（后台线程执行、限流 net.proxy、通知需授权） |
| `llm/LLMClient.kt` | SSE 流式客户端，三种协议：OpenAI 兼容 / Anthropic / Gemini；`testDetailed()` 连接诊断 |
| `llm/Prompts.kt` | A2UI 系统提示词：输出契约、技术栈选择、设计准则、避免 AI 味、交付前自检 |
| `agent/AgentLoop.kt` | 决策轮（带 tools）+ 渲染轮（流式）；工具授权、事件流、中断续写 |
| `store/GenStore.kt` | 零依赖 JSON 持久化：供应商配置 / 专项模型分派 / 界面栈（上限 100 张纸）/ bridge KV |
| `ui/shell/GenScaffold.kt` | 壳：结构化状态行（阶段 / 秒表 / 通道徽标）、指令条、原生渲染层、思考时间线、界面栈 |
| `ui/shell/ThinkingSheet.kt` | 思考过程时间线抽屉（手绘字形 + 自动跟随最新） |
| `perms/PermRegistry.kt` | Android 权限 ↔ 工具 ↔ 引导文案的单一事实来源 |
| `llm/ProviderPresets.kt` | 15 家供应商预设（端点 + 模型 + 协议），一键填充 |

| `ui/settings/ModelConfigScreen.kt` | 模型服务：供应商增删改查、协议选择、连接测试、专项模型分派 |

## 安全边界

- WebView 禁文件/内容访问；AI 页面自身处于 `genui.local` origin，`fetch` 受 CORS 限制
- 真正的网络能力只经 `MoBridge.net.proxy`（https、5 次/分钟限流）
- 通知需系统授权；bridge KV 仅应用私有目录
- 端上不执行任何"AI 给的本地代码"—— AI 只产出网页，沙箱解释

## 构建

```
Android Studio 打开本项目（需 JDK 17）→ Sync → Run
或命令行：./gradlew assembleDebug
```

- minSdk 26 / targetSdk 36（与 ZorvAI 对齐）
- Kotlin 2.3 + Compose（BOM 2025.10）+ AGP 8.13
- 首次启动进入「模型服务」添加供应商即可（DeepSeek / GLM / Moonshot / OpenAI / 自建端点 / Ollama 均走 OpenAI 兼容协议）

## 路线图

- [ ] 端侧引擎接入（MNN / llama.cpp，对应配置页"端侧引擎"占位）
- [ ] net.proxy 域名白名单设置
- [x] 生成中断续写（停止/失败后可基于已有部分接着写）
- [x] 界面栈分享（导出 HTML）
- [ ] 多轮对话上下文（当前每条指令生成一张独立界面）

## v0.8.0 修复与增强

**桥接层（此前 5 个缺陷会让"原生能力"整条链路不可用）**

| 问题 | 后果 | 修复 |
|---|---|---|
| `JS_WRAPPER` 里 `ui:` 键定义了两次，后者覆盖前者 | `MoBridge.ui.widget` 是 `undefined`，提示词承诺的 8 种原生组件**全部不可达** | 合并为唯一一次定义；wrapper 改为幂等（可安全重复注入） |
| 页面侧从未注册 `__moHost.onmessage` | **所有** MoBridge 调用的 Promise 永久 pending（时间、存储、通知全部静默失败） | 注册 `onmessage` 处理器；原生侧改为 `postMessage` + `evaluateJavascript` 双通道回写 |
| `NativeWidgetSheet` 读 `items`/`points`，提示词教模型传 `data`/`trend` | 除 form/slider 外，6 种组件渲染**空白** | 统一 schema，加 `NativeWidgetSchema` 归一化；提示词改为精确字段说明 |
| 原生组件结果无回传通道 | 用户在表单/清单里的输入丢失 | 新增 `mo:widget` DOM 事件回传（form/slider/list 三种） |
| `store.del` / `list` 勾选态缺失 | — | 补 `GenStore.deletePageValue`、清单可勾选 |

**功能层**

- 生成中断**续写**：停止或失败后，壳底部出现「↻ 续写」入口，把已写出的部分 HTML 作为种子交回模型，只补完不重写（喂尾部 3000 字符而非全文，省 token）
- 界面栈**导出**：每张纸可导出为独立 `.html` 走系统分享
- **专项模型分派真正生效**：新增 `llm/FastClient.kt`，`fastProviderId` 承担三件轻任务 —— 空态指令建议（三个可点起点）、标题压缩、联网意图预检；未配置时静默回退主脑
- 权限屏补通讯录权限行；修 `ToolGate.LEVEL` 里 `clipboard` 重复键

**稳定性**

- 修复生成完成时 **WebView 被销毁重建**（`AndroidView` 的 factory 捕获了 `pages` 状态）—— 这是"生成完画布变白"的根因
- 修界面栈回放导致的栈无限增长；回放改为以该纸为新栈底，返回键不再穿透旧栈
- `stop()` 现在会 `document.close()` 并补齐闭合标签，中断的半成品可正常点按
- `A2UIRenderer` 暴露 `readHtml()`（取回画布完整 HTML）与 `writtenBytes`

**构建环境**

- 仓库未含 gradle wrapper，已补 `local.properties`；`settings.gradle.kts` 指向可达镜像
- 沙箱内 `dl.google.com` 不可达，已手工装好 `android-36` platform 与 `build-tools 35.0.0`

## 已知取舍

- API Key 明文存于应用私有目录（未接 Keystore 加密）
- `store.appendPage` 在主线程执行（页面小，暂可接受）
- Anthropic SSE 以行为单位解析，极端长行（>64K）场景未做分帧
- 界面栈导出用 `ACTION_SEND` + `EXTRA_TEXT` 投递 HTML 源码（不落盘、无需 FileProvider）；接收方 App 需能处理 `text/html`

### 关于原生通道的边界（重要）

**端上不编译代码**。这是刻意取舍，不是未完成：

| 技术 | 端上能力 | 原因 |
|---|---|---|
| Kotlin / Java / Compose 源码 | 不能编译执行 | 需嵌入 50MB+ 编译器，且 Android 运行时禁止动态加载新 dex |
| C / C++ | 不能编译 | 需 NDK 交叉编译链（数百 MB），且必须编进 APK |
| Python | 不能执行 | 需嵌入解释器（Chaquo 约 20–40MB），且无法渲染 UI |

所以 `kotlin` / `java` / `cpp` / `python` 作为**界面题材**支持（代码视图、算法演示、
编程讲解），而 `xml` / `compose` 是**真正渲染** —— AI 写的布局与组件树会变成本机上的
真实控件。Compose 通道用"组件树描述"而非源码，是因为声明式 UI 的本质就是
组件树 + 属性，数据化后能 1:1 映射到真组件，既避开了编译又拿到了原生渲染。

