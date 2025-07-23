package ir.dotin.test11

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
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
    private var lastRawResponse: String? = null

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val mainPanel = JPanel(BorderLayout(10, 10))
        val chatPanel = JPanel()
        chatPanel.layout = BoxLayout(chatPanel, BoxLayout.Y_AXIS)
        chatPanel.background = Color(43, 43, 43)

        val inputArea = JBTextArea().apply {
            rows = 5
            lineWrap = true
            wrapStyleWord = true
            font = Font("Monospaced", Font.PLAIN, 12)
        }

        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                val newText = inputArea.text
                if (newText.lines().size > 1) {
                    SwingUtilities.invokeLater {
                        handleCodePaste(project, newText, chatPanel)
                        inputArea.text = ""
                    }
                }
            }
            override fun removeUpdate(e: DocumentEvent) {}
            override fun changedUpdate(e: DocumentEvent) {}
        })

        val resultPane = JEditorPane("text/html", "").apply {
            isEditable = false
            putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
            background = Color(43, 43, 43)
        }

        val resultScrollPane = JBScrollPane(resultPane)
        val responseActionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        responseActionsPanel.background = Color(43, 43, 43)
        responseActionsPanel.isVisible = false

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
                resultPane.text = "<html><body><p>Waiting for response...</p></body></html>"
                responseActionsPanel.isVisible = false

                CoroutineScope(Dispatchers.IO).launch {
                    val response = queryChatGPT(fullPrompt.toString())
                    lastRawResponse = response
                    SwingUtilities.invokeLater {
                        resultPane.text = formatResponseText(response ?: "Error receiving response.")
                        resultPane.caretPosition = 0
                        if (!response.isNullOrBlank()) {
                            responseActionsPanel.isVisible = true
                        }
                    }
                }
            }
        }

        val acceptAllButton = JButton("Accept All").apply {
            addActionListener {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@addActionListener
                val document = editor.document

                lastRawResponse?.let { rawResponse ->
                    val codeToInsert = extractCodeFromResponse(rawResponse)
                    if (codeToInsert.isNotBlank()) {
                        WriteCommandAction.runWriteCommandAction(project) {
                            if (pastedCodeSnippets.isNotEmpty()) {
                                // Replacement logic
                                val sortedSnippets = pastedCodeSnippets.sortedBy { it.startLine }
                                val firstSnippet = sortedSnippets.first()
                                val lastSnippet = sortedSnippets.last()

                                val startOffset = document.getLineStartOffset(firstSnippet.startLine - 1)
                                val endOffset = document.getLineEndOffset(lastSnippet.endLine - 1)

                                document.replaceString(startOffset, endOffset, codeToInsert)
                            } else {
                                // Insertion logic
                                val caretModel = editor.caretModel
                                val offset = caretModel.offset
                                document.insertString(offset, codeToInsert)
                            }
                        }
                    }
                    // Cleanup UI
                    resultPane.text = ""
                    lastRawResponse = null
                    responseActionsPanel.isVisible = false
                    chatPanel.removeAll()
                    chatPanel.revalidate()
                    chatPanel.repaint()
                    pastedCodeSnippets.clear()
                }
            }
        }

        val rejectAllButton = JButton("Reject All").apply {
            addActionListener {
                resultPane.text = ""
                lastRawResponse = null
                responseActionsPanel.isVisible = false
            }
        }

        responseActionsPanel.add(acceptAllButton)
        responseActionsPanel.add(rejectAllButton)

        val inputScrollPane = JBScrollPane(inputArea)
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(inputScrollPane, BorderLayout.CENTER)
        bottomPanel.add(button, BorderLayout.EAST)

        val chatScrollPane = JBScrollPane(chatPanel).apply {
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        val responsePanel = JPanel(BorderLayout())
        responsePanel.add(resultScrollPane, BorderLayout.CENTER)
        responsePanel.add(responseActionsPanel, BorderLayout.SOUTH)

        mainPanel.add(chatScrollPane, BorderLayout.NORTH)
        mainPanel.add(responsePanel, BorderLayout.CENTER)
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)

        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }

    private fun handleCodePaste(project: Project, code: String, container: JPanel) {
        ApplicationManager.getApplication().runReadAction {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@runReadAction
            val document = editor.document
            val file = FileDocumentManager.getInstance().getFile(document) ?: return@runReadAction
            val fileText = document.text

            val codeTrimmed = code.trim()
            val startIndex = fileText.indexOf(codeTrimmed)
            if (startIndex != -1) {
                val startLine = document.getLineNumber(startIndex) + 1
                val endLine = document.getLineNumber(startIndex + codeTrimmed.length) + 1
                val fileName = file.name

                val snippet = CodeSnippet(fileName, startLine, endLine, codeTrimmed)
                pastedCodeSnippets.add(snippet)

                SwingUtilities.invokeLater {
                    val snippetComponent = createSnippetComponent(snippet) { component ->
                        pastedCodeSnippets.remove(snippet)
                        container.remove(component)
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
        panel.maximumSize = Dimension(Integer.MAX_VALUE, 150)
        panel.alignmentX = Component.LEFT_ALIGNMENT
        panel.background = Color(43, 43, 43)

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

    private val apiKey: String by lazy {
        val props = java.util.Properties()
        val inputStream = javaClass.classLoader.getResourceAsStream("config.properties")
            ?: throw IllegalStateException("config.properties not found in resources")
        props.load(inputStream)
        props.getProperty("openai.api.key") ?: throw IllegalStateException("API key not set in config.properties")
    }

    private fun queryChatGPT(inputText: String): String? {
        val url = "https://api.openai.com/v1/chat/completions"
        val mediaType = "application/json".toMediaType()
        val client = OkHttpClient()

        val json = Gson().toJson(
            mapOf(
                "model" to "gpt-4o-mini",
                "messages" to listOf(
                    mapOf(
                        "role" to "user",
                        "content" to inputText
                    )
                )
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
                if (!response.isSuccessful) return "HTTP error: ${response.code}\n${response.body?.string()}"

                val body = response.body?.string() ?: return "Empty response"
                val root = JsonParser.parseString(body).asJsonObject

                val choices = root.getAsJsonArray("choices")
                if (choices.size() == 0) return "No choices in response"

                val firstChoice = choices[0].asJsonObject
                val message = firstChoice.getAsJsonObject("message")
                message.get("content").asString
            }
        } catch (e: Exception) {
            "Exception: ${e.message}"
        }
    }

    private fun extractCodeFromResponse(rawResponse: String): String {
        val codeBlocks = mutableListOf<String>()
        val lines = rawResponse.lines()
        var inCodeBlock = false
        val codeBlockContent = StringBuilder()

        for (line in lines) {
            if (line.trim().startsWith("```")) {
                if (inCodeBlock) {
                    codeBlocks.add(codeBlockContent.toString())
                    codeBlockContent.clear()
                }
                inCodeBlock = !inCodeBlock
            } else if (inCodeBlock) {
                codeBlockContent.append(line).append("\n")
            }
        }
        return codeBlocks.joinToString("\n").trim()
    }

    private fun formatResponseText(raw: String): String {
        val builder = StringBuilder()
        builder.append("<html><body style=\"font-family: sans-serif; font-size: 12px; background-color: #2b2b2b; color: #a9b7c6; padding: 10px;\">")

        var inCodeBlock = false
        var codeLanguage = ""

        for (line in raw.lines()) {
            when {
                line.trim().startsWith("```") -> {
                    if (inCodeBlock) {
                        builder.append("</pre>")
                        inCodeBlock = false
                        codeLanguage = ""
                    } else {
                        inCodeBlock = true
                        codeLanguage = line.trim().removePrefix("```").lowercase()
                        builder.append("<pre style=\"background-color:#3c3f41; color:#a9b7c6; padding:10px; border: 1px solid #555;\">")
                    }
                }
                inCodeBlock -> {
                    val highlighted = when (codeLanguage) {
                        "java", "kotlin" -> highlightJava(line)
                        "bash", "shell" -> highlightBash(line)
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
                    builder.append("<p>$bolded</p>")
                }
                else -> {
                    builder.append("<p>${escapeHtml(line)}</p>")
                }
            }
        }

        if (inCodeBlock) {
            builder.append("</pre>")
        }

        builder.append("</body></html>")
        return builder.toString()
    }

    private fun highlightBash(code: String): String {
        val keywords = listOf("echo", "cd", "ls", "pwd", "rm", "mkdir", "touch", "cat", "sudo", "chmod", "chown", "git", "export")
        val variables = Regex("\\$[A-Za-z_][A-Za-z0-9_]*")

        var escaped = escapeHtml(code)

        escaped = escaped.replace(Regex("#.*")) {
            "<span style='color:#808080'>${it.value}</span>"
        }
        escaped = escaped.replace(Regex("\"(.*?)\"")) {
            "<span style='color:#6A8759'>&quot;${it.groupValues[1]}&quot;</span>"
        }
        escaped = escaped.replace(variables) {
            "<span style='color:#9876AA'>${it.value}</span>"
        }
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

        escaped = escaped.replace(Regex("\"(.*?)\"")) {
            "<span style='color:#6A8759'>&quot;${it.groupValues[1]}&quot;</span>"
        }
        escaped = escaped.replace(Regex("(//.*)$")) {
            "<span style='color:#808080'>${it.groupValues[1]}</span>"
        }
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