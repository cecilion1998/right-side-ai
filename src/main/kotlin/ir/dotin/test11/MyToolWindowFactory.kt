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
        val resultArea = JTextArea(10, 30).apply {
            isEditable = false
            lineWrap = false
            font = Font("Monospaced", Font.PLAIN, 12)
        }

        val scrollPane = JBScrollPane(resultArea)
        val button = JButton("Ask ChatGPT")

        button.addActionListener {
            val input = inputField.text.trim()
            if (input.isNotEmpty()) {
                CoroutineScope(Dispatchers.IO).launch {
                    val response = queryChatGPT(input)
                    SwingUtilities.invokeLater {
                        resultArea.text = response ?: "Error getting response"
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


}