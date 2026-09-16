package org.a2ui.compose.protocol

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser

object A2UIProtocol {
    const val VERSION: String = "v0.10"

    private val gson = Gson()

    @JvmStatic
    fun createSurfaceMessage(
        surfaceId: String,
        catalogId: String = "standard",
        primaryColor: String? = null,
    ): String {
        val createSurface = linkedMapOf<String, Any>(
            "surfaceId" to surfaceId,
            "catalogId" to catalogId,
        )
        if (primaryColor != null) {
            createSurface["theme"] = linkedMapOf("primaryColor" to primaryColor)
        }
        return envelope("createSurface", createSurface)
    }

    @JvmStatic
    fun updateComponentsMessage(
        surfaceId: String,
        components: List<Map<String, Any?>>,
    ): String {
        return envelope(
            kind = "updateComponents",
            payload = linkedMapOf(
                "surfaceId" to surfaceId,
                "components" to components.map(::filterNullValues),
            ),
        )
    }

    @JvmStatic
    fun componentsJson(components: List<Map<String, Any?>>): String {
        return gson.toJson(components.map(::filterNullValues))
    }

    @JvmStatic
    fun updateComponentsRawMessage(surfaceId: String, componentsJson: String): String {
        val payload = JsonObject().apply {
            addProperty("version", VERSION)
            add(
                "updateComponents",
                JsonObject().apply {
                    addProperty("surfaceId", surfaceId)
                    add("components", JsonParser.parseString(componentsJson))
                },
            )
        }
        return payload.toString()
    }

    @JvmStatic
    fun updateDataModelMessage(surfaceId: String, path: String, value: Any?): String {
        val payload = JsonObject().apply {
            addProperty("version", VERSION)
            add(
                "updateDataModel",
                JsonObject().apply {
                    addProperty("surfaceId", surfaceId)
                    addProperty("path", path)
                    add("value", toJsonElement(value))
                },
            )
        }
        return payload.toString()
    }

    @JvmStatic
    fun deleteSurfaceMessage(surfaceId: String): String {
        return envelope("deleteSurface", linkedMapOf("surfaceId" to surfaceId))
    }

    @JvmStatic
    fun isA2UIMessage(message: String): Boolean {
        val trimmed = message.trim()
        if (!trimmed.startsWith("{")) return false

        return runCatching {
            val json = JsonParser.parseString(trimmed).asJsonObject
            json.get("version")?.asString == VERSION && (
                json.has("createSurface") ||
                    json.has("updateComponents") ||
                    json.has("updateDataModel") ||
                    json.has("deleteSurface")
                )
        }.getOrDefault(false)
    }

    @JvmStatic
    fun containsA2UIMessages(payload: String): Boolean {
        return A2UIJsonObjectExtractor.extract(payload).any(::isA2UIMessage)
    }

    @JvmStatic
    fun arrayChildren(ids: List<String>): JsonObject {
        return JsonObject().apply {
            add("array", gson.toJsonTree(ids))
        }
    }

    @JvmStatic
    fun objectChildren(path: String, componentId: String): JsonObject {
        return JsonObject().apply {
            addProperty("path", path)
            addProperty("componentId", componentId)
        }
    }

    @JvmStatic
    fun pathValue(path: String): Map<String, String> = linkedMapOf("path" to path)

    @JvmStatic
    fun literalValue(value: Any?): Map<String, Any?> = linkedMapOf("literal" to value)

    @JvmStatic
    fun actionEvent(name: String, context: Map<String, Any?> = emptyMap()): Map<String, Any> {
        val event = linkedMapOf<String, Any>("name" to name)
        if (context.isNotEmpty()) {
            event["context"] = context
        }
        return linkedMapOf("event" to event)
    }

    @JvmStatic
    fun component(
        id: String,
        component: String,
        attributes: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>(
            "id" to id,
            "component" to component,
        )
        result.putAll(attributes)
        return filterNullValues(result)
    }

    private fun envelope(kind: String, payload: Map<String, Any?>): String {
        return gson.toJson(
            linkedMapOf(
                "version" to VERSION,
                kind to filterNullValues(payload),
            ),
        )
    }

    private fun filterNullValues(map: Map<String, Any?>): LinkedHashMap<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        map.forEach { (key, value) ->
            if (value != null) {
                result[key] = normalizeValue(value)
            }
        }
        return result
    }

    private fun normalizeValue(value: Any): Any = when (value) {
        is JsonElement -> value
        is Map<*, *> -> value.entries
            .filter { it.key is String && it.value != null }
            .associate { it.key as String to normalizeValue(it.value!!) }
        is List<*> -> value.filterNotNull().map(::normalizeValue)
        else -> value
    }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull.INSTANCE
        is JsonElement -> value
        else -> gson.toJsonTree(value)
    }
}
