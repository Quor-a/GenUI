package org.a2ui.compose.protocol

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonSyntaxException

class A2UIStreamingJsonlProcessor(
    private val dispatchMessage: (String) -> Unit,
    private val executeBatch: ((() -> Unit)) -> Unit = { it() },
    private val tag: String = DEFAULT_TAG,
) {

    companion object {
        private const val DEFAULT_TAG = "A2UIStreamingJsonlProcessor"
    }

    private val gson = Gson()
    private val objectExtractor = A2UIJsonObjectExtractor()

    var completedLineCount: Int = 0
        private set

    var dispatchedMessageCount: Int = 0
        private set

    fun appendChunk(chunk: String) {
        if (chunk.isEmpty()) return
        objectExtractor.append(chunk)
        dispatchExtractedObjects(objectExtractor.drainCompleteObjects())
    }

    fun flush() {
        dispatchExtractedObjects(objectExtractor.flush())
    }

    fun processLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) {
            return false
        }

        return try {
            val json = gson.fromJson(trimmed, JsonObject::class.java)
            if (json.has("version") && json.get("version").asString == A2UIProtocol.VERSION) {
                when {
                    json.has("createSurface") ||
                        json.has("updateComponents") ||
                        json.has("updateDataModel") ||
                        json.has("deleteSurface") -> dispatch(trimmed)

                    else -> false
                }
            } else {
                dispatch(trimmed)
            }
        } catch (_: JsonSyntaxException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    private fun dispatchExtractedObjects(objects: List<String>) {
        if (objects.isEmpty()) return

        completedLineCount += objects.size
        executeBatch {
            objects.forEach(::processLine)
        }
    }

    private fun dispatch(message: String): Boolean {
        dispatchMessage(message)
        dispatchedMessageCount += 1
        return true
    }
}
