package org.a2ui.compose.protocol

data class A2UIConversationMessage(
    val role: String,
    val content: String? = null,
    val toolCallId: String? = null,
    val name: String? = null,
    val toolCalls: List<A2UIToolCall>? = null,
)

data class A2UIToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

object A2UIToolResultConversation {

    @JvmStatic
    fun buildFollowUpMessages(
        originalMessages: List<A2UIConversationMessage>,
        toolCalls: List<A2UIToolCall>,
        toolResults: Map<String, String>,
        includeSummaryInstruction: Boolean = true,
    ): List<A2UIConversationMessage> {
        if (toolCalls.isEmpty() && toolResults.isEmpty()) {
            return originalMessages
        }

        val messages = originalMessages.toMutableList()
        val remainingResults = LinkedHashMap(toolResults)

        if (toolCalls.isNotEmpty()) {
            messages += A2UIConversationMessage(
                role = "assistant",
                content = null,
                toolCalls = toolCalls,
            )
        }

        if (includeSummaryInstruction) {
            val sceneHints = A2UISceneInference.inferScenes(toolCalls, toolResults)
            messages += A2UIConversationMessage(
                role = "system",
                content = A2UIPromptGuidance.toolSummaryInstruction(sceneHints),
            )
        }

        toolCalls.forEach { toolCall ->
            val result = remainingResults.remove(toolCall.id) ?: return@forEach
            messages += A2UIConversationMessage(
                role = "tool",
                content = result,
                toolCallId = toolCall.id,
                name = toolCall.name,
            )
        }

        remainingResults.forEach { (toolCallId, result) ->
            messages += A2UIConversationMessage(
                role = "tool",
                content = result,
                toolCallId = toolCallId,
            )
        }

        return messages
    }
}
