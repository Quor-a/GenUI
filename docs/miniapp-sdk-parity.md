# miniapp-sdk 企业级对标报告（对标微信小程序 SDK）

> v0.28.0 基线 · 对标对象：微信小程序双线程框架（WebView/Skyline 双渲染 + AppService 逻辑层 + 基础组件库 + wx API 面）
> 结论先行：**架构主干已达标**（逻辑/视图分离、双渲染引擎、页面栈、包格式），差距集中在**渲染细节、JS 语言能力、组件完备度**三条线。

## 一、能力矩阵（现状 × 差距）

### 1. 架构层
| 能力 | 微信 | miniapp-sdk | 状态 |
|---|---|---|---|
| 逻辑/视图分离 | AppService(独立 JS 上下文) + 渲染层 | 自研 C++ JS 引擎(逻辑线程) + Skia 视图层 | ✅ 达标 |
| 双渲染引擎 | WebView(传统) + Skyline(自研) | WebView 通道 + 自研原生引擎 | ✅ 达标（27.0） |
| 页面级引擎切换 | renderer 字段，页面/分包粒度 | app.json/page.json renderer + 容器级混跳 | ✅ 骨架达标 |
| 页面共享渲染实例 | Skyline 单实例 | 整包单引擎实例 | ✅ 达标 |
| 序列化桥开销 | 双线程必须序列化 | 同进程直调（原生引擎） | ✅ 优于 |

### 2. 视图层（渲染引擎）
| 能力 | 状态 | 差距/计划 |
|---|---|---|
| Flex 布局 | ✅ direction/wrap/justify/align/gap/min-max | align-content、order、flex-basis 精确语义 |
| rpx 响应式 | ✅ viewport/750 | — |
| 绝对定位 | ✅ v0.27.3 四轴+px/rpx/% | transform（translate/rotate/scale） |
| 背景渐变 | ✅ v0.27.3 linear-gradient | radial-gradient、background-image:url() |
| 图片 | ✅ v0.28.0 网络本地data-uri+缓存 | mode 语义（aspectFill/Fit/widthFix）、gif 帧动画 |
| 滚动 | ✅ 拖动 + v0.28.0 惯性 fling | 横向滚动、下拉刷新、触底加载、回弹 |
| 文本 | ✅ 换行/字号/字重/颜色/行高 | 富文本 nodes、文字选择、自定义字体加载 |
| 动画 | ❌ 无 | **CSS transition/keyframes → v0.28.x**；Worklet 动画线程（UI 线程手势驱动）→ v0.29 |
| 输入 | ✅ EditText 覆盖+软键盘+bindinput | focus/blur 事件对、textarea 多行、自动高度 |
| 触摸手势 | ✅ tap（冒泡） | 长按、touchstart/move/end 全量、多指 pinch |
| 命中测试 | ✅ 逆序遍历+滚动容器 | — |

### 3. 逻辑层（JS 引擎）
| 能力 | 状态 | 差距/计划 |
|---|---|---|
| ES 基础 | ✅ var/let/const/函数/箭头/闭包/模板字符串/解构… | — |
| class / async-await / 生成器 | ❌ 自研解释器未实现 | **短期**：复杂逻辑走 WebView 通道（全语法）；**中期**：解释器补 Promise + async 糖（解释为状态机） |
| Promise | ❌ | async 路线的前置 |
| 内置对象 | ✅ JSON/Math/Date/数组基础 | Array 高阶全量、Map/Set、正则、String 全量 |
| 模块化 require/exports | ❌ 单文件 | 多文件模块解析 → v0.28.x |
| 自定义组件 Component() | ❌ | 组件化 → v0.29（配套 slot/mixin） |

### 4. API 面（wx.*）
| 类别 | 状态 |
|---|---|
| 网络 request/upload/download/WebSocket | request ✅；其余 ❌ |
| 存储 set/get/remove/clear StorageSync | ✅ |
| 交互 toast/loading/modal/actionSheet | ✅ 部分 |
| 导航 navigateTo/redirectTo/navigateBack/switchTab | ✅ 前三；tabBar ❌ |
| 设备 系统信息/震动/剪贴板/亮度 | ✅ 部分 |
| 媒体/文件/位置/扫码 | ❌ v0.29+ |
| 登录/支付/开放能力 | 超范围（GenUI 无小程序账号体系） |

### 5. 工程能力
| 能力 | 状态 |
|---|---|
| 包格式（app.json 三件套+pages） | ✅ |
| 分包/预下载 | ❌ v0.29 |
| 全局生命周期（App onError/onPageNotFound） | 部分 |
| 调试（log/错误回传/性能面板） | ✅ 错误回传（27.1）；console 面板 ❌ |

## 二、v0.28.0 本轮落地
1. **image 网络加载**：内置加载器（http(s)/data-uri/本地文件，3 线程池 + 24 图 LruCache + 在途去重 + 完成异步重绘）——此前 imageProvider 无注入，全部 image 节点空白
2. **滚动惯性 fling**：VelocityTracker 采样 + 指数衰减动画（之前松手即停，长列表手感硬伤）
3. 本报告入库，作为后续迭代基线

## 三、路线图
- **v0.28.x**：CSS transition/keyframes（属性插值+定时器驱动）、image mode 语义、横向滚动、Array 高阶补全
- **v0.29**：Promise + async/await（解释器状态机实现）、多文件 require、自定义组件 Component()+slot、tabBar
- **v0.30**：下拉刷新/触底加载、radial-gradient、富文本、Worklet 手势动画线程、分包
- **原则**：每步保持「双引擎并存、AI 主权、交付优先」三铁律；新能力先过 assets 真实样例冒烟再默认启用

## 四、资源依赖体系（v0.28.2 补齐）
| 能力 | 之前 | 现在 |
|---|---|---|
| 包内二进制资源 | ❌ MiniPackage 纯文本 Map，图片/字体/音频**无法进包** | ✅ `resources: Map<String, ByteArray>`，三种来源（assets/目录/zip）按扩展名自动分流 |
| image 相对路径 | ❌ 只认 http/data-uri/绝对路径 | ✅ 相对路径 → 包内资源同步直读 |
| 字体/媒体资源 | ❌ | ✅ 随包分发（ttf/otf/woff/mp3/mp4…），字体绘制接入 v0.29 |
| 资源引用方式 | — | WXML `image src="/assets/icon.png"` 相对包根，与微信语义一致 |

**待建**：依赖声明体系（app.json 声明第三方组件库/资源包，引擎按需加载）——对齐 npm/package.json 语义，进 v0.29 路线图。

## 五、GPU / CPU / 系统完整性（v0.28.3 实查补章）

### GPU 对接
| 层 | 现状 | 行动 |
|---|---|---|
| SurfaceView Canvas 路径（主路径） | **v0.28.3 起 lockHardwareCanvas 优先**：Skia 硬件光栅化 + GPU 合成（API 26+）；box-shadow 帧自动回退软件画布（ShadowLayer 在 HW canvas 不生效） | ✅ 已接线 |
| GLRenderer（GLES20 管线） | 377 行真实现（shader/纹理图集/文本/图片节点绘制 + RenderBackend.OPENGL_ES 枚举）——**已实现未接线** | v0.30：GLSurfaceView 绘制循环接入 + 渐变/阴影/形态组件的 GL 化 |
| Vulkan | ❌ | v0.31+ 评估（Skia Vulkan 后端） |

### CPU / 线程模型
| 线程 | 职责 | 状态 |
|---|---|---|
| miniapp-render | 独立渲染线程（SurfaceView 绘制循环） | ✅ |
| 逻辑线程 | 自研 C++ JS 引擎（HandlerThread） | ✅ |
| 图片池 | 3 线程并发加载 + LruCache | ✅ |
| UI 线程 | 事件分发/输入/Toast | ✅ |
| 多核利用 | 逻辑/渲染/IO 三线并行已覆盖双核以上 | ✅；渲染线程内帧任务尚无并行分片（v0.30 Worklet） |

### 安卓系统能力调用完整性
| 维度 | 已接 | 未接（路线图） |
|---|---|---|
| 基础 | 网络/剪贴板/震动/通知/存储/系统信息/软键盘 | — |
| 媒体 | 图片(包内/网络/data-uri) | 相机/录音/音视频播放/相册选择 |
| 硬件 | — | 位置/传感器(加速度/罗盘)/指纹/BLE/屏幕亮度 |
| 安全 | 工具级权限门禁(PermRegistry) | wx 层域名白名单/敏感 API 二次授权 |
