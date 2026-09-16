package org.a2ui.compose.protocol

object A2UISurfaceLayouts {

    @JvmStatic
    fun chatSurfaceComponents(): List<Map<String, Any?>> =
        rootContainerComponents(listOf("messages_container", "status_text")) + listOf(
            messagesContainerComponent(emptyList()),
            boundTextComponent(id = "status_text", path = "/statusText", variant = "caption"),
        )

    @JvmStatic
    fun streamingSurfaceComponents(textPath: String = "/streamingText"): List<Map<String, Any?>> =
        rootContainerComponents(listOf("streaming_text")) + listOf(
            boundTextComponent(id = "streaming_text", path = textPath, variant = "body"),
        )

    @JvmStatic
    fun responseCardComponents(textPath: String = "/response/text"): List<Map<String, Any?>> =
        rootContainerComponents(listOf("response_text", "response_dismiss")) + listOf(
            boundTextComponent(id = "response_text", path = textPath, variant = "body"),
            A2UIProtocol.component(
                id = "response_dismiss",
                component = "Button",
                attributes = linkedMapOf(
                    "text" to A2UIProtocol.literalValue("收起"),
                    "variant" to "text",
                    "action" to A2UIProtocol.actionEvent("dismiss"),
                ),
            ),
        )

    @JvmStatic
    fun messagesContainerComponent(messageIds: List<String>): Map<String, Any?> =
        A2UIProtocol.component(
            id = "messages_container",
            component = "Column",
            attributes = linkedMapOf(
                "children" to A2UIProtocol.arrayChildren(messageIds),
            ),
        )

    @JvmStatic
    fun chatMessageComponents(messageId: String, text: String, variant: String = "body"): List<Map<String, Any?>> {
        val textId = "${messageId}_text"
        return listOf(
            A2UIProtocol.component(
                id = messageId,
                component = "Card",
                attributes = linkedMapOf("child" to textId),
            ),
            A2UIProtocol.component(
                id = textId,
                component = "Text",
                attributes = linkedMapOf(
                    "text" to A2UIProtocol.literalValue(text),
                    "variant" to variant,
                ),
            ),
        )
    }

    private fun rootContainerComponents(contentChildren: List<String>): List<Map<String, Any?>> = listOf(
        A2UIProtocol.component(
            id = "root",
            component = "Card",
            attributes = linkedMapOf("child" to "content"),
        ),
        A2UIProtocol.component(
            id = "content",
            component = "Column",
            attributes = linkedMapOf(
                "children" to A2UIProtocol.arrayChildren(contentChildren),
            ),
        ),
    )

    private fun boundTextComponent(id: String, path: String, variant: String): Map<String, Any?> =
        A2UIProtocol.component(
            id = id,
            component = "Text",
            attributes = linkedMapOf(
                "text" to A2UIProtocol.pathValue(path),
                "variant" to variant,
            ),
        )
}
