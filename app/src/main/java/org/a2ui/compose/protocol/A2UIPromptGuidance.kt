package org.a2ui.compose.protocol

object A2UIPromptGuidance {
    @JvmStatic
    fun middlewareInstruction(surfaceId: String = "main"): String = """
# A2UI 输出规则（高优先级，覆盖旧模板或旧 JSON 约定）
- 默认优先直接给出自然语言回答，不要为了“像 UI”而强行输出结构化内容。
- 只有当卡片、列表、表单、面板、进度、按钮、状态提示等视觉化展示明显更有帮助时，才输出 A2UI v0.10 JSONL。
- 适用场景包括天气、路线导航、地点列表、票务、行程、媒体控制、车辆状态、诊断告警、任务清单、确认弹层、表单录入、对比摘要等动态场景。
- 最终展示的样式、大小、信息密度、布局层级应由当前场景决定，不要把所有结果都做成同一种卡片。
- 严禁输出旧格式，例如 {"ui":"weather_card","data":...}、uiTemplate、templateId、weather_card、navigation_card、media_player 等模板 JSON。
- 如果选择输出 A2UI，只能输出完整 JSON 对象序列；每行一个 JSON；不要输出 markdown、代码块标记或额外解释文字。
- 第一条必须是 createSurface，surfaceId 统一使用 "$surfaceId"。
- 根组件必须使用统一容器模型：root(Card) -> content(Column) -> children.array。
- 组件使用 component 字段；子节点使用 child 或 children:{"array":[...]}；数据绑定使用 {"path":"/..."}；静态文本使用 {"literal":"..."}。
- 可以分多条 updateComponents 逐步流式输出，最后再用 updateDataModel 填充数据。
- Any id referenced by child / children.array / componentId must be defined in updateComponents before the answer ends.
- If you bind fields such as price / change / range, you must also define visible components that read those paths.
- Tool domain must match user intent. Never use map / POI tools for stock, finance, securities, quote, or company market-data queries.
- 如果现有工具域与用户意图不匹配，或者没有可靠数据支撑，不要编造事实，也不要编造 UI，直接用自然语言说明限制。
- 如果信息本身更适合一句话或一段话表达，就不要输出 UI。

# A2UI 天气卡片示例
{"version":"v0.10","createSurface":{"surfaceId":"$surfaceId","catalogId":"standard"}}
{"version":"v0.10","updateComponents":{"surfaceId":"$surfaceId","components":[{"id":"root","component":"Card","child":"content"},{"id":"content","component":"Column","children":{"array":["title","summary","metrics"]}}]}}
{"version":"v0.10","updateComponents":{"surfaceId":"$surfaceId","components":[{"id":"title","component":"Text","text":{"literal":"天气信息"},"variant":"h2"},{"id":"summary","component":"Text","text":{"path":"/weather/summary"},"variant":"body"},{"id":"metrics","component":"Column","children":{"array":["temp","humidity","wind"]}},{"id":"temp","component":"Text","text":{"path":"/weather/temp"},"variant":"h1"},{"id":"humidity","component":"Text","text":{"path":"/weather/humidity"},"variant":"body"},{"id":"wind","component":"Text","text":{"path":"/weather/wind"},"variant":"body"}]}}
{"version":"v0.10","updateDataModel":{"surfaceId":"$surfaceId","path":"/weather","value":{"summary":"北京 晴","temp":"25°C","humidity":"湿度 45%","wind":"东风 3 级"}}}
""".trimIndent()

    @JvmStatic
    fun toolSummaryInstruction(sceneHints: List<A2UISceneHint> = emptyList()): String = buildString {
        appendLine("# 工具结果总结补充规则")
        appendLine("- 当工具结果包含天气、路线、地点、票务、媒体、车辆、诊断、表单、任务或其他结构化信息，且“看”会比“听”更直观时，优先输出 A2UI JSONL。")
        appendLine("- 这类场景下，最终回答不要重复“正在查询”或“已完成”之类过程描述，直接给最终 UI 或最终自然语言答案。")
        appendLine("- 请先判断当前结果适合哪种动态场景卡片：紧凑状态卡、列表卡、详情卡、行程卡、控制卡、告警卡、确认卡、表单卡或组合面板。")
        appendLine("- 展示样式、大小、风格、内容组织由当前场景决定，不要把所有结果都做成同一种卡片。")
        appendLine("- 天气结果若能提取城市、概况、温度、湿度、风速等字段，优先生成天气卡片。")
        appendLine("- 路线/导航结果若能提取起终点、ETA、距离、拥堵、关键转向，优先生成路线卡片。")
        appendLine("- 地点/餐厅/票务/行程结果若包含列表项，优先生成列表卡、票务卡或行程卡，而不是大段文本。")
        appendLine("- 车辆/诊断/安全结果若包含状态、风险等级、建议动作，优先生成车辆状态卡、诊断告警卡或操作卡。")
        appendLine("- 表单、确认、任务流这类强交互场景，可以生成按钮、开关、输入框、进度或任务列表。")
        appendLine("- 工具返回 JSON 时，应基于 JSON 字段组织 UI，不要把原始 JSON 或转义后的 JSON 字符串直接复述给用户。")
        appendLine("- If tool results do not match the user's domain or lack reliable structured fields, do not fabricate UI or facts; fall back to natural language and explain the limitation.")
        appendLine("- 只有在工具结果足以支撑稳定 UI 时才输出 A2UI；否则退回自然语言回答。")

        if (sceneHints.isNotEmpty()) {
            appendLine()
            appendLine("# 本轮场景判断")
            sceneHints.forEach { hint ->
                appendLine("- ${hint.scene.displayName}：${hint.reason}；推荐布局：${hint.scene.recommendedLayout}")
            }
        }
    }.trimIndent()
}
