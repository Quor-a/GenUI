# 第三方组件声明（THIRD-PARTY NOTICES）

本仓库由**自研代码**与**第三方组件**两部分构成：

- **自研部分**（`app/src/main/java/com/genui/app/`、`miniapp-sdk/`、
  `app/src/main/cpp/`、`app/src/main/assets/runtimes/genui-*.js`）
  以仓库根目录的 **Apache License 2.0** 100% 开源。
- **第三方部分**保留其**各自的原始许可证**，本仓库不作变更。
  各组件的许可证以其源文件头部声明为准，本文件仅作汇总索引。

---

## 1. Vendored：A2UI-Android 官方引擎

- 路径：`app/src/main/java/org/a2ui/`
- 许可证：**Apache License 2.0**（随附于 `app/src/main/java/org/a2ui/LICENSE`，原文保留）
- 来源：Google A2UI（Agent-to-UI Protocol）Android 渲染引擎

## 2. Vendored：JS 运行时库（`app/src/main/assets/runtimes/`）

以下库文件保留其源码头部声明的原始许可证，各归其主：

| 组件 | 许可证 | 版权/项目 |
|---|---|---|
| alpine.min.js | MIT | Alpine.js |
| anime.min.js | MIT | anime.js |
| babylon.min.js | Apache-2.0 | Babylon.js |
| bootstrap.bundle.min.js / bootstrap.min.css | MIT | Bootstrap |
| chart.umd.min.js | MIT | Chart.js |
| countup.umd.js | MIT | CountUp.js |
| d3.min.js | ISC | D3 (Mike Bostock) |
| dexie.min.js | Apache-2.0 | Dexie.js |
| echarts.min.js | Apache-2.0 | Apache ECharts (ASF) |
| element-plus.min.js / element-plus.css | MIT | Element Plus |
| github-dark.min.css | MIT | highlight.js 主题 |
| gsap.min.js | GreenSock 标准许可（见文件头） | GreenSock |
| htm.umd.js | Apache-2.0 | htm |
| highlight.min.js | MIT | highlight.js |
| lucide.min.js | ISC | Lucide Icons |
| marked.min.js | MIT | marked (Christopher Jeffrey) |
| matter.min.js | MIT | Matter.js |
| mermaid.min.js | MIT | Mermaid |
| p5.min.js | LGPL-2.1 | p5.js |
| react.production.min.js / react-dom.production.min.js | MIT | React (Meta) |
| swiper-bundle.min.js / swiper-bundle.min.css | MIT | Swiper |
| tailwind.js | MIT | Tailwind CSS |
| three.min.js | MIT | three.js |
| vue.global.prod.js / vue-router.global.prod.js | MIT | Vue.js |

> 注：`genui-backend.js`、`genui-explore.js`、`genui-spa.js` 为本项目自研，
> 适用根目录 Apache License 2.0。

## 3. Vendored：字体（`app/src/main/assets/runtimes/fonts/`）

- Inter、JetBrains Mono、Space Grotesk —— **SIL Open Font License 1.1**

## 4. Vendored：Python 运行时（`app/src/main/assets/python/`）

- CPython —— **Python Software Foundation License 2.0**（PSF-2.0）
- Chaquopy（Python-Android 集成框架）—— **MIT**
  （经 Gradle 依赖引入，二进制分发，未修改其源码）

## 5. Gradle 构建期第三方依赖

AndroidX / Kotlin / Compose / AGP 等经 Maven 坐标引入的构建期依赖，
不随本仓库源码分发，适用各自上游许可证（Apache-2.0 为主）。
