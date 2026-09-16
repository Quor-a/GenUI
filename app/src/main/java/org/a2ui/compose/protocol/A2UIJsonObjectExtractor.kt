package org.a2ui.compose.protocol

class A2UIJsonObjectExtractor {
    private val buffer = StringBuilder()

    fun append(chunk: String) {
        if (chunk.isNotEmpty()) {
            buffer.append(chunk)
        }
    }

    fun drainCompleteObjects(): List<String> = extractCompleteObjects(deleteConsumed = true)

    fun flush(): List<String> = extractCompleteObjects(deleteConsumed = true)

    private fun extractCompleteObjects(deleteConsumed: Boolean): List<String> {
        if (buffer.isEmpty()) return emptyList()

        val objects = mutableListOf<String>()
        var startIndex = -1
        var depth = 0
        var inString = false
        var escaped = false
        var lastConsumedIndex = 0

        for (index in 0 until buffer.length) {
            val currentChar = buffer[index]
            if (startIndex == -1) {
                if (currentChar == '{') {
                    startIndex = index
                    depth = 1
                    inString = false
                    escaped = false
                }
                continue
            }

            if (escaped) {
                escaped = false
                continue
            }

            if (inString && currentChar.code == 92) {
                escaped = true
                continue
            }

            if (currentChar.code == 34) {
                inString = !inString
                continue
            }

            if (!inString) {
                when (currentChar) {
                    '{' -> depth += 1
                    '}' -> {
                        depth -= 1
                        if (depth == 0) {
                            objects += buffer.substring(startIndex, index + 1)
                            lastConsumedIndex = index + 1
                            startIndex = -1
                        }
                    }
                }
            }
        }

        if (deleteConsumed && lastConsumedIndex > 0) {
            buffer.delete(0, lastConsumedIndex)
        }

        return objects
    }

    companion object {
        @JvmStatic
        fun extract(payload: String): List<String> {
            if (payload.isBlank()) return emptyList()
            return A2UIJsonObjectExtractor().apply { append(payload) }.flush()
        }
    }
}
