package ir.dotin.test11

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*
import com.google.gson.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.Font


class MyToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JPanel(BorderLayout(10, 10))
        val inputField = JBTextField()
        val resultPane = JEditorPane("text/html", "").apply {
            isEditable = false
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            font = Font("Monospaced", Font.PLAIN, 12)
        }



        val scrollPane = JBScrollPane(resultPane)
        val button = JButton("Ask ChatGPT")

        button.addActionListener {
            val input = inputField.text.trim()
            if (input.isNotEmpty()) {
                CoroutineScope(Dispatchers.IO).launch {
                    val response = queryChatGPT(input)
                    SwingUtilities.invokeLater {
                        val formattedHtml = formatResponseText(response ?: "Error")
                        println("=== HTML OUTPUT START ===")
                        println(formattedHtml)
                        println("=== HTML OUTPUT END ===")
                        resultPane.text = formattedHtml
                    }
                }
            }
        }

        val inputPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(inputField)
            add(Box.createHorizontalStrut(10))
            add(button)
        }

        inputField.preferredSize = Dimension(200, 30)
        panel.add(inputPanel, BorderLayout.NORTH)
        panel.add(scrollPane, BorderLayout.CENTER)

        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private val apiKey: String by lazy {
        val props = java.util.Properties()
        val inputStream = javaClass.classLoader.getResourceAsStream("config.properties")
            ?: throw IllegalStateException("config.properties not found in resources")
        props.load(inputStream)
        props.getProperty("openai.api.key") ?: throw IllegalStateException("API key not set in config.properties")
    }

    private fun queryChatGPT(inputText: String): String? {

        val url = "https://api.openai.com/v1/responses"
        val mediaType = "application/json".toMediaType()
        val client = OkHttpClient()

        val json = Gson().toJson(
            mapOf(
                "model" to "gpt-4o-mini",
                "input" to inputText
            )
        )

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(json.toRequestBody(mediaType))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return "HTTP error: ${response.code}"

                val body = response.body?.string() ?: return "Empty response"
                val root = JsonParser.parseString(body).asJsonObject

                val outputArray = root.getAsJsonArray("output")
                if (outputArray.size() == 0) return "No output received"

                val firstOutput = outputArray[0].asJsonObject
                val contentArray = firstOutput.getAsJsonArray("content")
                if (contentArray.size() == 0) return "No content received"

                val textObj = contentArray[0].asJsonObject
                val text = textObj.get("text").asString
                // Detect if it's likely to be code (simple heuristic)
                return if (looksLikeCode(text)) {
                    "```\n$text\n```"
                } else {
                    text
                }
            }
        } catch (e: Exception) {
            "Exception: ${e.message}"
        }
    }
    private fun looksLikeCode(text: String): Boolean {
        val codeKeywords = listOf("public ", "class ", "def ", "function ", "val ", "var ", "if (", "for (", "while (")
        return codeKeywords.any { text.contains(it) } || text.contains("{") && text.contains("}")
    }
    private fun formatResponseText(raw: String): String {
        println("=== RAW RESPONSE START ===")
        println(raw)
        println("=== RAW RESPONSE END ===")

        val builder = StringBuilder()
        builder.append("<html><body style=\"font-family: monospace; font-size: 12px;\">")

        var inCodeBlock = false
        var codeLanguage = ""

        for (line in raw.lines()) {
            when {
                line.trim() == "```" -> {
                    builder.append("</pre>")
                    inCodeBlock = false
                    codeLanguage = ""
                }

                line.trim().startsWith("```java") || line.trim().startsWith("```bash") -> {
                    codeLanguage = line.trim().removePrefix("```").lowercase()
                    builder.append("<pre style=\"background-color:#1e1e1e; color:#dcdcdc; padding:10px;\">")
                    inCodeBlock = true
                }

                inCodeBlock -> {
                    val highlighted = when (codeLanguage) {
                        "java" -> highlightJava(line)
                        "bash" -> highlightBash(line)
                        else -> escapeHtml(line)
                    }
                    builder.append(highlighted).append("\n")
                }

                line.trim().startsWith("###") -> {
                    val heading = line.removePrefix("###").trim()
                    builder.append("<h3>$heading</h3>")
                }

                "**" in line -> {
                    val bolded = line.replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
                    builder.append("<div>$bolded</div>")
                }

                else -> {
                    builder.append("<div>${line}</div>")
                }
            }
        }

        builder.append("</body></html>")
        return builder.toString()
    }
    private fun highlightBash(code: String): String {
        val keywords = listOf("echo", "cd", "ls", "pwd", "rm", "mkdir", "touch", "cat", "sudo", "chmod", "chown", "git", "export")
        val variables = Regex("\\$[A-Za-z_][A-Za-z0-9_]*")

        var escaped = escapeHtml(code)

        // Highlight comments
        escaped = escaped.replace(Regex("#.*")) {
            "<span style='color:#6a9955'>${it.value}</span>"
        }

        // Highlight strings
        escaped = escaped.replace(Regex("\"(.*?)\"")) {
            "<span style='color:#ce9178'>&quot;${it.groupValues[1]}&quot;</span>"
        }

        // Highlight variables
        escaped = escaped.replace(variables) {
            "<span style='color:#dcdcaa'>${it.value}</span>"
        }

        // Highlight keywords
        for (kw in keywords) {
            escaped = escaped.replace(Regex("\\b$kw\\b")) {
                "<span style='color:#569cd6'>${it.value}</span>"
            }
        }

        return escaped
    }
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }
    private fun highlightJava(code: String): String {
        val keywords = listOf(
            "public", "class", "static", "void", "int", "long", "if", "else",
            "return", "new", "private", "protected", "boolean", "String"
        )

        var escaped = code
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

        // Highlight strings
        escaped = escaped.replace(Regex("\"(.*?)\"")) {
            "<span style='color:#ce9178'>&quot;${it.groupValues[1]}&quot;</span>"
        }

        // Highlight comments
        escaped = escaped.replace(Regex("(//.*)$")) {
            "<span style='color:#6a9955'>${it.groupValues[1]}</span>"
        }

        // Highlight keywords
        for (kw in keywords) {
            escaped = escaped.replace(Regex("\\b$kw\\b")) {
                "<span style='color:#569cd6'>${it.value}</span>"
            }
        }

        return escaped
    }

}