package ir.dotin.test11

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import com.google.gson.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class MyToolWindowFactory : ToolWindowFactory {

    private val pastedCodeSnippets = mutableListOf<CodeSnippet>()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val mainPanel = JPanel(BorderLayout(10, 10))
        val chatPanel = JPanel()
        chatPanel.layout = BoxLayout(chatPanel, BoxLayout.Y_AXIS)
        chatPanel.background = Color(43, 43, 43) // Dark background for the chat panel

        val inputArea = JBTextArea().apply {
            rows = 5
            lineWrap = true
            wrapStyleWord = true
            font = Font("Monospaced", Font.PLAIN, 12)
        }

        // Listener to detect pasted code
        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                val newText = inputArea.text
                if (newText.lines().size > 1) { // Simple check for multi-line paste
                    SwingUtilities.invokeLater {
                        handleCodePaste(project, newText, chatPanel)
                        inputArea.text = "" // Clear input area after processing
                    }
                }
            }
            override fun removeUpdate(e: DocumentEvent) {}
            override fun changedUpdate(e: DocumentEvent) {}
        })

        val resultPane = JEditorPane("text/html", "").apply {
            isEditable = false
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        }

        val button = JButton("Ask ChatGPT")
        button.addActionListener {
            val prompt = inputArea.text.trim()
            val fullPrompt = StringBuilder(prompt)

            if (pastedCodeSnippets.isNotEmpty()) {
                fullPrompt.append("\n\n--- Code Snippets ---\n")
                pastedCodeSnippets.forEach { snippet ->
                    fullPrompt.append("\n// From ${snippet.fileName} (lines ${snippet.startLine}-${snippet.endLine})\n")
                    fullPrompt.append(snippet.code)
                }
            }

            if (fullPrompt.isNotBlank()) {
                // Clear the UI and the list for the next interaction
                chatPanel.removeAll()
                chatPanel.revalidate()
                chatPanel.repaint()
                pastedCodeSnippets.clear()

                CoroutineScope(Dispatchers.IO).launch {
                    val response = queryChatGPT(fullPrompt.toString())
                    SwingUtilities.invokeLater {
                        resultPane.text = formatResponseText(response ?: "Error receiving response.")
                    }
                }
            }
        }

        val inputScrollPane = JBScrollPane(inputArea)
        val resultScrollPane = JBScrollPane(resultPane)

        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(inputScrollPane, BorderLayout.CENTER)
        bottomPanel.add(button, BorderLayout.EAST)

        val chatScrollPane = JBScrollPane(chatPanel).apply {
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        mainPanel.add(chatScrollPane, BorderLayout.CENTER)
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)
        mainPanel.add(resultScrollPane, BorderLayout.NORTH)


        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun handleCodePaste(project: Project, code: String, container: JPanel) {
        ApplicationManager.getApplication().runReadAction {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@runReadAction
            val document = editor.document
            val file = FileDocumentManager.getInstance().getFile(document) ?: return@runReadAction
            val fileText = document.text

            val startIndex = fileText.indexOf(code.trim())
            if (startIndex != -1) {
                val startLine = document.getLineNumber(startIndex) + 1
                val endLine = document.getLineNumber(startIndex + code.trim().length) + 1
                val fileName = file.name

                val snippet = CodeSnippet(fileName, startLine, endLine, code.trim())
                pastedCodeSnippets.add(snippet)

                // Create and add the UI component for the snippet
                SwingUtilities.invokeLater {
                    val snippetComponent = createSnippetComponent(snippet) {
                        pastedCodeSnippets.remove(snippet)
                        container.remove(it)
                        container.revalidate()
                        container.repaint()
                    }
                    container.add(snippetComponent)
                    container.revalidate()
                    container.repaint()
                }
            }
        }
    }

    private fun createSnippetComponent(snippet: CodeSnippet, onRemove: (JComponent) -> Unit): JComponent {
        val panel = JPanel(BorderLayout(5, 5))
        panel.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Color.DARK_GRAY),
            BorderFactory.createEmptyBorder(10, 10, 10, 10)
        )
        panel.maximumSize = Dimension(Integer.MAX_VALUE, 150) // Constrain height
        panel.alignmentX = Component.LEFT_ALIGNMENT
        panel.background = Color(43, 43, 43)

        // Top panel for file info and remove button
        val topPanel = JPanel(BorderLayout())
        topPanel.background = Color(43, 43, 43)
        val fileLabel = JLabel("${snippet.fileName} (lines ${snippet.startLine}-${snippet.endLine})")
        fileLabel.font = fileLabel.font.deriveFont(Font.BOLD)
        fileLabel.foreground = Color(169, 183, 198)

        val removeButton = JButton("x").apply {
            isContentAreaFilled = false
            isFocusPainted = false
            border = BorderFactory.createEmptyBorder(2, 5, 2, 5)
            foreground = Color(169, 183, 198)
            addActionListener {
                onRemove(panel)
            }
        }
        topPanel.add(fileLabel, BorderLayout.CENTER)
        topPanel.add(removeButton, BorderLayout.EAST)

        // JEditorPane for syntax-highlighted code
        val codePane = JEditorPane("text/html", formatCodeToHtml(snippet)).apply {
            isEditable = false
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            background = Color(43, 43, 43)
        }

        panel.add(topPanel, BorderLayout.NORTH)
        panel.add(JBScrollPane(codePane), BorderLayout.CENTER)

        return panel
    }

    private fun formatCodeToHtml(snippet: CodeSnippet): String {
        val language = when {
            snippet.fileName.endsWith(".java") || snippet.fileName.endsWith(".kt") -> "java"
            snippet.fileName.endsWith(".sh") -> "bash"
            else -> ""
        }

        val highlightedCode = when (language) {
            "java" -> highlightJava(snippet.code)
            "bash" -> highlightBash(snippet.code)
            else -> escapeHtml(snippet.code)
        }

        return """
            <html>
                <body style="font-family: monospace; font-size: 12px; background-color: #2b2b2b; color: #a9b7c6;">
                    <pre style="margin: 0; padding: 5px;">$highlightedCode</pre>
                </body>
            </html>
        """.trimIndent()
    }

    // Your existing queryChatGPT, formatResponseText, and other helper methods remain here.
    // Make sure to add them back in.
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
        val builder = StringBuilder()
        builder.append("<html><body style=\"font-family: monospace; font-size: 12px; background-color: #2b2b2b; color: #a9b7c6;\">")

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
                    builder.append("<pre style=\"background-color:#3c3f41; color:#a9b7c6; padding:10px; border-radius: 4px;\">")
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
                    builder.append("<div>${escapeHtml(line)}</div>")
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
            "<span style='color:#808080'>${it.value}</span>"
        }

        // Highlight strings
        escaped = escaped.replace(Regex("\"(.*?)\"")) {
            "<span style='color:#6A8759'>&quot;${it.groupValues[1]}&quot;</span>"
        }

        // Highlight variables
        escaped = escaped.replace(variables) {
            "<span style='color:#9876AA'>${it.value}</span>"
        }

        // Highlight keywords
        for (kw in keywords) {
            escaped = escaped.replace(Regex("\\b$kw\\b")) {
                "<span style='color:#CC7832'>${it.value}</span>"
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
            "return", "new", "private", "protected", "boolean", "String",
            "package", "import", "override", "fun", "val", "var"
        )

        var escaped = escapeHtml(code)

        // Highlight strings
        escaped = escaped.replace(Regex("\"(.*?)\"")) {
            "<span style='color:#6A8759'>&quot;${it.groupValues[1]}&quot;</span>"
        }

        // Highlight comments
        escaped = escaped.replace(Regex("(//.*)$")) {
            "<span style='color:#808080'>${it.groupValues[1]}</span>"
        }

        // Highlight keywords
        for (kw in keywords) {
            escaped = escaped.replace(Regex("\\b$kw\\b")) {
                "<span style='color:#CC7832'>${it.value}</span>"
            }
        }

        return escaped
    }
}

data class CodeSnippet(
    val fileName: String,
    val startLine: Int,
    val endLine: Int,
    val code: String
)
