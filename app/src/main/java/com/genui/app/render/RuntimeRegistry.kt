package com.genui.app.render

/**
 * 运行时注册表 —— AI 可用的离线技术架构的【唯一事实来源】。
 *
 * 为什么要有这个类：提示词里教模型"可以用哪些库"、和 assets 里实际存在的文件，
 * 是两处容易脱节的地方（改了资源忘了改提示词 → 模型写出引用不存在的 <script>，
 * 页面静默坏掉）。这里把清单集中定义，[promptSection] 直接由它生成提示词段落，
 * 同时 [selfCheck] 可在启动时校验资源确实存在。
 *
 * 全部走 /assets/runtimes/（WebViewAssetLoader 提供，域名 <origin>/assets/），
 * 不依赖任何外网 CDN —— 断网也能用。
 */
object RuntimeRegistry {

    /**
     * 一个可用运行时。
     * @param id 提示词里的形态标记（<!--gen:xxx-->）
     * @param name 展示名
     * @param files 需要引入的脚本路径（相对 /assets/runtimes/）
     * @param usage 给模型看的用法要点
     * @param whenToUse 什么场景该选它
     */
    data class Runtime(
        val id: String,
        val name: String,
        val files: List<String>,
        val usage: String,
        val whenToUse: String
    )

    private const val BASE = "/assets/runtimes/"

    val all: List<Runtime> = listOf(
        Runtime(
            id = "html",
            name = "原生 HTML/CSS/JS",
            files = emptyList(),
            usage = "直接用 HTML + CSS + JS。样式写在 <style>，逻辑写在 body 末尾的 <script>。",
            whenToUse = "展示、表单、单页小工具、内容页。简单任务不要过度工程化。"
        ),
        Runtime(
            id = "vue",
            name = "Vue 3",
            files = listOf("vue.global.prod.js"),
            usage = "<head> 里引入 vue，然后写模板 + createApp({setup(){...}})。全局对象是 window.Vue。",
            whenToUse = "响应式状态、列表联动、双向绑定、多视图切换（tab/步骤条）。"
        ),
        Runtime(
            id = "react",
            name = "React 18 + htm",
            files = listOf("react.production.min.js", "react-dom.production.min.js", "htm.umd.js"),
            usage = "const html = htm.bind(React.createElement); 然后 ReactDOM.createRoot(el).render(html`<\${App}/>`)。" +
                "全局对象是 window.React / window.ReactDOM / window.htm。",
            whenToUse = "组件化架构、复杂状态管理。"
        ),
        Runtime(
            id = "mermaid",
            name = "Mermaid",
            files = listOf("mermaid.min.js"),
            usage = "图表写在 <pre class=\"mermaid\">…</pre>，末尾 mermaid.initialize({startOnLoad:true})。" +
                "可与普通 HTML 混用（文档+图表）。",
            whenToUse = "流程图、时序图、架构图、甘特图、状态图。"
        ),
        Runtime(
            id = "echarts",
            name = "ECharts 5（数据可视化）",
            files = listOf("echarts.min.js"),
            usage = "const chart = echarts.init(document.getElementById('x')); chart.setOption({...})。" +
                "容器必须有明确高度（如 style=\"height:220px\"），否则不显示。" +
                "窗口尺寸变化时调 chart.resize()。暗色主题可传 setOption(opt, true) 或自定义 color 数组。",
            whenToUse = "折线/柱状/饼图/雷达/热力/桑基/仪表盘等任何数据可视化。" +
                "比手写 SVG 更快也更专业 —— 有数据要展示时优先用它。"
        ),
        Runtime(
            id = "gsap",
            name = "GSAP 3（动画引擎）",
            files = listOf("gsap.min.js"),
            usage = "gsap.to(sel, {duration:.6, y:0, opacity:1, ease:'power2.out'})；时间线 gsap.timeline()。" +
                "入场动画推荐 gsap.from(sel,{y:20,opacity:0,stagger:.06})。全局对象 window.gsap。",
            whenToUse = "多元素错峰入场、序列动画、数值滚动、SVG 路径动画、精细缓动曲线。"
        ),
        Runtime(
            id = "anime",
            name = "Anime.js（轻量动画）",
            files = listOf("anime.min.js"),
            usage = "anime({targets:'.el', translateY:[20,0], opacity:[0,1], duration:600, delay:anime.stagger(60)})。" +
                "全局对象 window.anime。",
            whenToUse = "比 GSAP 更轻的动画需求；简单补间、SVG 形变。"
        ),
        Runtime(
            id = "three",
            name = "Three.js（3D / WebGL）",
            files = listOf("three.min.js"),
            usage = "renderer = new THREE.WebGLRenderer({antialias:true}); " +
                "scene = new THREE.Scene(); camera = new THREE.PerspectiveCamera(60, w/h, .1, 1000)。" +
                "务必用 requestAnimationFrame 渲染循环，并在容器上设置 canvas 尺寸随 window 变化。" +
                "移动端性能有限：控制多边形数量，避免大尺寸阴影贴图。全局对象 window.THREE。",
            whenToUse = "3D 展示、粒子效果、空间可视化、炫技型首页。谨慎使用——耗电且性能敏感，非必要不上。"
        ),
        Runtime(
            id = "babylon",
            name = "Babylon.js（引擎级 3D）",
            files = listOf("babylon.min.js"),
            usage = "const engine = new BABYLON.Engine(canvas, true); const scene = new BABYLON.Scene(engine);" +
                "engine.runRenderLoop(()=>scene.render()); window.addEventListener('resize',()=>engine.resize())。" +
                "自由视角用 new BABYLON.ArcRotateCamera(...) + camera.attachControl(canvas,true)。" +
                "移动端务必克制：Mesh/灯光数量收敛，别开体积光、SSAO 等重型后处理。全局对象 window.BABYLON。",
            whenToUse = "引擎级 3D：物体查看器、光照/材质演示、3D 场景搭建；需要比 Three 更完整的引擎能力（拾取/物理/粒子系统）时。"
        ),
        Runtime(
            id = "zdog",
            name = "Zdog（2.5D 扁平伪 3D）",
            files = listOf("zdog.dist.min.js"),
            usage = "new Zdog.Illustration({element:'.zdog-canvas', resize:'fullscreen'});" +
                "用 Zdog.Shape/Box/Cone/Sphere 搭积木，最后 illustration.updateRenderGraph() 渲染。" +
                "旋转动画：illustration.rotate.y += 0.01 + requestAnimationFrame 循环。全局对象 window.Zdog。",
            whenToUse = "插画感 2.5D：产品示意、几何图标、扁平立体场景。轻量省电，风格化立体首选，比 Three 便宜一个数量级。"
        ),
        Runtime(
            id = "obelisk",
            name = "Obelisk.js（2.5D 等距像素）",
            files = listOf("obelisk.min.js"),
            usage = "const pixelView = new obelisk.PixelView(ctx, new obelisk.Point(80,80));" +
                "new obelisk.Cube(new obelisk.CubeDimension(w,d,h), color) + pixelView.renderObject(cube) 摆方块。" +
                "全局对象 window.obelisk。",
            whenToUse = "等距像素风：城市/建筑/关卡示意图、复古 2.5D 信息图、像素游戏场景。"
        ),
        Runtime(
            id = "matter",
            name = "Matter.js（2D 物理引擎）",
            files = listOf("matter.min.js"),
            usage = "const engine = Matter.Engine.create(); Matter.Composite.add(engine.world, bodies);" +
                "循环里 Matter.Engine.update(engine, 1000/60)。" +
                "可不用自带 Render：自己在 canvas 上读 body.vertices 绘制，风格与页面统一。全局对象 window.Matter。",
            whenToUse = "物理演示、下落/碰撞/弹簧交互、小游戏。"
        ),
        Runtime(
            id = "chartjs",
            name = "Chart.js（轻量图表）",
            files = listOf("chart.umd.min.js"),
            usage = "new Chart(ctx, {type:'bar', data:{...}, options:{responsive:true, maintainAspectRatio:false}})。" +
                "容器必须定高。全局对象 window.Chart。",
            whenToUse = "常规图表（柱/线/饼/环/雷达）且想要更轻体积时；复杂可视化仍优先 echarts。"
        ),
        Runtime(
            id = "d3",
            name = "D3.js v7（数据驱动文档）",
            files = listOf("d3.min.js"),
            usage = "d3.select + 比例尺（d3.scaleLinear）+ 坐标轴（d3.axisBottom）+ selection.data() 数据绑定。" +
                "需要手写 SVG 结构，自由度最高。全局对象 window.d3。",
            whenToUse = "ECharts/Chart.js 画不了的图：力导向关系图、树图、自定义几何可视化。"
        ),
        Runtime(
            id = "alpine",
            name = "Alpine.js（轻响应式）",
            files = listOf("alpine.min.js"),
            usage = "<div x-data=\"{open:false}\"><button @click=\"open=!open\">…</button><span x-show=\"open\">…</span></div>；" +
                "脚本加载后自动启动。全局对象 window.Alpine。",
            whenToUse = "比 Vue 更轻的局部交互（折叠/切换/步进器/标签页），不想为这点交互引入整个框架时。"
        ),
        Runtime(
            id = "tailwind",
            name = "Tailwind CSS（原子样式）",
            files = listOf("tailwind.js"),
            usage = "引入后直接写 class（flex gap-2 rounded-xl bg-slate-800 text-sm）即生效，JIT 引擎会观察 DOM 动态生成样式；" +
                "可与 html/vue/react/alpine 任意叠加。深色界面推荐 slate/gray 色阶。",
            whenToUse = "追求精致排版与响应式布局、或需要大量间距/圆角/阴影等原子组合时；普通短页面用内联 style 更快。"
        ),
        Runtime(
            id = "bootstrap",
            name = "Bootstrap 5（WebUI 基建）",
            files = listOf("bootstrap.min.css", "bootstrap.bundle.min.js"),
            usage = "<link rel=\"stylesheet\" href=\"/assets/runtimes/bootstrap.min.css\"> 后直接写 class：" +
                "栅格 container/row/col-md-6、组件 card/btn btn-primary/navbar/badge/d-flex/gap-3。" +
                "模态框/下拉/折叠用 data-bs-toggle 属性即用，无需初始化。暗色主题：<html data-bs-theme=\"dark\">。" +
                "与 Tailwind 别在同一元素混用 class。",
            whenToUse = "标准 WebUI：后台面板、表单页、卡片流、官网落地页 —— 模型最熟悉的 Web 界面全家桶，画网站默认先考虑。"
        ),
        Runtime(
            id = "element",
            name = "Element Plus（Vue 3 企业组件库）",
            files = listOf("element-plus.css", "vue.global.prod.js", "element-plus.min.js"),
            usage = "必须先引 vue 再引 element（files 已按序）。" +
                "const app = Vue.createApp({...}); app.use(ElementPlus); app.mount('#app')。" +
                "组件 el-button/el-table/el-form/el-dialog/el-menu/el-tabs/el-pagination… " +
                "全局对象 window.ElementPlus。图标组件未打包，需要图标时叠加 lucide 运行时。",
            whenToUse = "管理后台、数据表格、复杂表单校验、仪表盘 —— 需要成套企业级交互组件时，比 Bootstrap 更完整。"
        ),
        Runtime(
            id = "lucide",
            name = "Lucide（SVG 图标库）",
            files = listOf("lucide.min.js"),
            usage = "<i data-lucide=\"settings\"></i> 写占位标签，页面末尾 lucide.createIcons() 渲染成内联 SVG。" +
                "常用名：menu/search/bell/user/settings/chevron-right/plus/trash/edit/refresh-cw。" +
                "尺寸用 CSS 控制该 svg 的 width/height。全局对象 window.lucide。",
            whenToUse = "任何需要图标的界面：顶栏、按钮、侧边导航、空状态 —— 不要用 emoji 凑数。"
        ),
        Runtime(
            id = "swiper",
            name = "Swiper 11（触摸轮播）",
            files = listOf("swiper-bundle.min.css", "swiper-bundle.min.js"),
            usage = "先引 css。结构 .swiper > .swiper-wrapper > .swiper-slide*N，" +
                "new Swiper('.swiper', {loop:true, autoplay:{delay:3000}, pagination:{el:'.swiper-pagination'}, " +
                "navigation:{nextEl:'.swiper-button-next', prevEl:'.swiper-button-prev'}})。" +
                "容器必须定高。全局对象 window.Swiper。",
            whenToUse = "轮播图、引导页、可滑动卡片浏览 —— 移动端触摸友好的标准轮播方案。"
        ),
        Runtime(
            id = "spa",
            name = "GenUI Spa（多层界面路由 · 零依赖）",
            files = listOf("genui-spa.js"),
            usage = "页面声明：<section data-page=\"home\" data-root>首页</section>、" +
                "<section data-page=\"detail\" data-parent=\"home\">详情（子页）</section>。" +
                "根页（data-root）之间互切 = 标签切换；子页从根页下钻进入，栈式返回。" +
                "导航零事件代码：任意元素写 data-go=\"目标id\" 即点击跳转，data-back 即返回上一层。" +
                "JS：Spa.go(id)/Spa.back()/Spa.current()。深链 #/page-id 刷新不丢位置。",
            whenToUse = "网站式多层界面：首页/功能介绍/产品下载各自成页、按钮互切、点进详情再返回 —— vanilla 页面的首选，比手写 display 切换可靠。"
        ),
        Runtime(
            id = "vuerouter",
            name = "Vue Router 4（Vue 多层路由）",
            files = listOf("vue.global.prod.js", "vue-router.global.prod.js"),
            usage = "必须先引 vue 再引 router（files 已按序）。" +
                "const routes=[{path:'/',component:Home},{path:'/detail/:id',component:Detail}];" +
                "const router=VueRouter.createRouter({history:VueRouter.createWebHashHistory(),routes});" +
                "app.use(router)。模板里 <router-view/> 渲染当前页，<router-link to=\"/detail/1\"> 或 " +
                "router.push('/detail/1') 导航，router.back() 返回。全局对象 window.VueRouter。",
            whenToUse = "Vue 应用的多层界面：需要路由参数（详情页带 id）、嵌套布局、编程式导航时用 Vue Router，别再手写 v-if 换页。"
        ),
        Runtime(
            id = "explore",
            name = "GenUI Explore（新闻/社区/GitHub 探索引擎）",
            files = listOf("genui-explore.js"),
            usage = "Explore.news(q,12) → {items:[{title,url,source,date,snippet}],diagnostics}（Google News+Bing News）；" +
                "Explore.community(q,10) → hackernews/stackoverflow/reddit 三源（带 score/author）；" +
                "Explore.github(q,'repositories'|'users',10) → {total,items:[{title,url,stars,language,date,snippet}]}（按 star 排序）。" +
                "全部真实数据、多源并发、单源故障不空手（看 diagnostics）。全局对象 window.Explore。",
            whenToUse = "新闻聚合页、技术社区雷达、GitHub 趋势/选型检索页、AI 动态看板 —— 任何要真实外部内容的探索型界面。"
        ),
        Runtime(
            id = "backend",
            name = "GenUI Backend（AI 自写后端扩展框架）",
            files = listOf("genui-backend.js"),
            usage = "Backend.route('GET','/api/weather', async (req)=>{...}) 注册后端路由；" +
                "界面统一 await Backend.call('GET','/api/weather') 取数，前后端分层。" +
                "配套：Backend.cache(key,ttl,fetcher) 缓存、Backend.retry(fn,3) 重试、" +
                "Backend.geo() 真实定位、Backend.queue.push(fn) 离线队列、Backend.logs() 日志面板、" +
                "Backend.task.cron(id,分钟,fn) 常驻定时（画布就绪即触发，重开补跑，配合 Dexie 做离线缓存刷新）。" +
                "全局对象 window.Backend。",
            whenToUse = "真实功能型应用的后端层：天气/行情/资讯类拉数、离线优先应用、需要定时刷新或失败重试的数据管道 —— 让 AI 写的页面拥有自己可扩展的后端。"
        ),
        Runtime(
            id = "dexie",
            name = "Dexie.js（IndexedDB 真数据库）",
            files = listOf("dexie.min.js"),
            usage = "const db = new Dexie('myapp'); db.version(1).stores({items:'++id, name, date'});" +
                "await db.items.add({...}) / db.items.where('name').equals(x).toArray() / db.items.put / delete。" +
                "容量远超 localStorage（可存上万条结构化记录）。全局对象 window.Dexie。",
            whenToUse = "真实数据型应用：记账、库存、笔记库、离线缓存大列表 —— 需要索引查询和大数据量时用它，别再用 JSON 数组硬扛。"
        ),
        Runtime(
            id = "xlsx",
            name = "SheetJS（Excel 读写）",
            files = listOf("xlsx.full.min.js"),
            usage = "读：XLSX.read(arrayBuffer, {type:'array'}) → XLSX.utils.sheet_to_json(ws)。" +
                "写：XLSX.utils.json_to_sheet(data) → XLSX.utils.book_new()/book_append_sheet → XLSX.write(wb,{type:'array'}) " +
                "→ 配合 MoBridge.save(new Blob([out]), '报表.xlsx') 落到系统下载目录。全局对象 window.XLSX。",
            whenToUse = "表格数据的真实导入/导出：把页面里的数据变成真正的 .xlsx 文件交给用户，或解析用户数据文件。"
        ),
        Runtime(
            id = "countup",
            name = "CountUp.js（数字滚动）",
            files = listOf("countup.umd.js"),
            usage = "new countUp.CountUp(el, 2146, {decimalPlaces:0, duration:1.2}).start()。" +
                "全局对象 window.countUp（注意是 countUp.CountUp）。",
            whenToUse = "KPI 大数字入场滚动、统计值变化过渡。"
        )
    )

    /** 字体（离线 woff2，经 @font-face 注册，见 [fontFaceCss]） */
    val fonts: List<Pair<String, String>> = listOf(
        "Inter" to "fonts/Inter-400.woff2",
        "Inter" to "fonts/Inter-600.woff2",
        "JetBrains Mono" to "fonts/JetBrainsMono-400.woff2",
        "JetBrains Mono" to "fonts/JetBrainsMono-500.woff2",
        "Space Grotesk" to "fonts/SpaceGrotesk-500.woff2",
        "Space Grotesk" to "fonts/SpaceGrotesk-700.woff2"
    )

    /**
     * 生成提示词里的"技术形态"段落。
     * 由本注册表派生，改资源只需改这里一处。
     */
    /**
     * 原创包管理目录：端上已装运行时包的结构化清单（名称/文件/用途）。
     * 经 MoBridge.packages.list 暴露给 AI 的页面 —— 页面可据此自检依赖是否可用。
     */
    fun catalogJson(): org.json.JSONArray {
        val arr = org.json.JSONArray()
        all.forEach { r ->
            arr.put(org.json.JSONObject()
                .put("id", r.id).put("name", r.name)
                .put("files", org.json.JSONArray(r.files))
                .put("base", BASE)
                .put("whenToUse", r.whenToUse))
        }
        return arr
    }

    fun promptSection(): String = buildString {
        appendLine("在 `<!DOCTYPE html>` 下一行写形态标记注释（如 `<!--gen:echarts-->`）表明你的选择。多个形态可叠加，写多个标记。")
        appendLine()
        all.forEach { r ->
            appendLine("## <!--gen:${r.id}--> ${r.name}")
            appendLine("   何时用：${r.whenToUse}")
            if (r.files.isNotEmpty()) {
                append("   <head> 内引入：")
                appendLine(r.files.joinToString(" ") {
                    // CSS 库必须用 <link>，写成 <script> 会静默失效 —— 这是"页面白屏"级 bug 的来源
                    if (it.endsWith(".css")) "<link rel=\"stylesheet\" href=\"$BASE$it\">"
                    else "<script src=\"$BASE$it\"></script>"
                })
            }
            appendLine("   用法：${r.usage}")
            appendLine()
        }
        appendLine("## 离线字体（已由端上预注入 @font-face，直接用 font-family 即可）")
        appendLine("   'Inter'          无衬线正文/数字，中性现代")
        appendLine("   'JetBrains Mono' 等宽，适合代码/数字/标签/技术感文本")
        appendLine("   'Space Grotesk'  几何标题字，适合大标题与品牌感")
        appendLine("   中文请用系统字体：font-family:'Inter',system-ui,sans-serif（不要引用任何中文 webfont，会拖慢首屏）")
    }

    /** 预注入的 @font-face CSS（在文档头写入，先于 AI 的任何样式） */
    fun fontFaceCss(): String = buildString {
        append("<style>")
        fonts.groupBy({ it.first }, { it.second }).forEach { (family, files) ->
            files.forEach { f ->
                append("@font-face{font-family:'$family';src:url('$BASE$f') format('woff2');")
                append("font-display:swap;}")
            }
        }
        append("</style>")
    }

    /**
     * 启动自检：校验 assets 里文件确实存在。
     * 返回缺失清单（空 = 全部正常）。只读一次，开销可忽略。
     */
    fun selfCheck(assets: android.content.res.AssetManager): List<String> {
        val want = all.flatMap { it.files.map { f -> "runtimes/$f" } } +
            fonts.map { "runtimes/${it.second}" }
        return want.distinct().filter { path ->
            runCatching { assets.open(path).close(); false }.getOrDefault(true)
        }
    }
}
