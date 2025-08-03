package ir.dotin.AIPlugin.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import ir.dotin.AIPlugin.data.CodeSnippet
import ir.dotin.AIPlugin.service.CodeSnippetManager
import ir.dotin.AIPlugin.service.RemoteModelService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class ChatPanel(private val project: Project) : JPanel(BorderLayout(10, 10)) {

    private val modelService = RemoteModelService()
    private val snippetManager = CodeSnippetManager()
    private var lastRawResponse: String? = null

    private val chatPanel = createChatPanel()
    private val inputArea = createInputArea()
    private val resultPane = createStyledEditorPane("text/html", "")
    private val responseActionsPanel = createResponseActionsPanel()

    init {
        val bottomPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(inputArea), BorderLayout.CENTER)
            add(createAskButton(), BorderLayout.EAST)
        }

        val responsePanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(resultPane), BorderLayout.CENTER)
            add(responseActionsPanel, BorderLayout.SOUTH)
        }

        add(JBScrollPane(chatPanel), BorderLayout.NORTH)
        add(responsePanel, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun createChatPanel() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = AppColors.BACKGROUND
    }

    private fun createInputArea() = JBTextArea().apply {
        rows = 5
        lineWrap = true
        wrapStyleWord = true
        font = AppFonts.MONO
        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) {
                if (text.lines().size > 1) {
                    SwingUtilities.invokeLater {
                        handleCodePaste(text)
                        text = ""
                    }
                }
            }
            override fun removeUpdate(e: DocumentEvent) {}
            override fun changedUpdate(e: DocumentEvent) {}
        })
    }

    private fun createResponseActionsPanel() = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
        background = AppColors.BACKGROUND
        isVisible = false
        add(createAcceptAllButton())
        add(createRejectAllButton())
    }

    private fun createAskButton() = JButton("Ask AI").apply {
        addActionListener {
            val prompt = inputArea.text.trim()
            val fullPrompt = prompt + snippetManager.getSnippetsForPrompt()

            if (fullPrompt.isNotBlank()) {
                resultPane.text = "<html><body><p>Contacting server...</p></body></html>"
                responseActionsPanel.isVisible = false

                CoroutineScope(Dispatchers.IO).launch {
                    val response = modelService.queryRemoteModel(fullPrompt)
                    lastRawResponse = response

                    withContext(Dispatchers.Swing) {
                        resultPane.text = formatResponseText(response)
                        resultPane.caretPosition = 0
                        responseActionsPanel.isVisible = response.isNotBlank()
                    }
                }
            }
        }
    }

    private fun createAcceptAllButton() = JButton("Accept All").apply {
        addActionListener {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@addActionListener
            val document = editor.document
            lastRawResponse?.let {
                val codeToInsert = extractCodeFromResponse(it)
                if (codeToInsert.isNotBlank()) {
                    WriteCommandAction.runWriteCommandAction(project) {
                        val sortedSnippets = snippetManager.getSortedSnippets()
                        if (sortedSnippets.isNotEmpty()) {
                            val first = sortedSnippets.first()
                            val last = sortedSnippets.last()
                            val startOffset = document.getLineStartOffset(first.startLine - 1)
                            val endOffset = document.getLineEndOffset(last.endLine - 1)
                            document.replaceString(startOffset, endOffset, codeToInsert)
                        } else {
                            document.insertString(editor.caretModel.offset, codeToInsert)
                        }
                    }
                }
                cleanupAfterAction()
            }
        }
    }

    private fun createRejectAllButton() = JButton("Reject All").apply {
        addActionListener { cleanupAfterAction() }
    }

    private fun cleanupAfterAction() {
        resultPane.text = ""
        lastRawResponse = null
        responseActionsPanel.isVisible = false
        chatPanel.removeAll()
        chatPanel.revalidate()
        chatPanel.repaint()
        snippetManager.clear()
    }

    private fun handleCodePaste(code: String) {
        ApplicationManager.getApplication().runReadAction {
            val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@runReadAction
            val document = editor.document
            val file = FileDocumentManager.getInstance().getFile(document) ?: return@runReadAction

            val codeTrimmed = code.trim()
            val startIndex = document.text.indexOf(codeTrimmed)
            if (startIndex != -1) {
                val startLine = document.getLineNumber(startIndex) + 1
                val endLine = document.getLineNumber(startIndex + codeTrimmed.length) + 1
                val snippet = CodeSnippet(file.name, startLine, endLine, codeTrimmed)
                snippetManager.addSnippet(snippet)

                SwingUtilities.invokeLater {
                    addSnippetComponent(snippet)
                }
            }
        }
    }

    private fun addSnippetComponent(snippet: CodeSnippet) {
        val snippetComponent = createSnippetComponent(snippet) { component ->
            snippetManager.removeSnippet(snippet)
            chatPanel.remove(component)
            chatPanel.revalidate()
            chatPanel.repaint()
        }
        chatPanel.add(snippetComponent)
        chatPanel.revalidate()
        chatPanel.repaint()
    }

    private fun createSnippetComponent(snippet: CodeSnippet, onRemove: (JComponent) -> Unit): JComponent {
        val panel = JPanel(BorderLayout(5, 5)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, AppColors.BORDER),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
            )
            maximumSize = Dimension(Integer.MAX_VALUE, 150)
            alignmentX = Component.LEFT_ALIGNMENT
            background = AppColors.BACKGROUND
        }

        val topPanel = JPanel(BorderLayout()).apply {
            background = AppColors.BACKGROUND
            val fileLabel = JLabel("${snippet.fileName} (lines ${snippet.startLine}-${snippet.endLine})").apply {
                font = AppFonts.BOLD_SANS_SERIF
                foreground = AppColors.FOREGROUND
            }
            add(fileLabel, BorderLayout.CENTER)
            add(createRemoveButton(panel, onRemove), BorderLayout.EAST)
        }

        panel.add(topPanel, BorderLayout.NORTH)
        panel.add(JBScrollPane(createStyledEditorPane("text/html", formatCodeToHtml(snippet))), BorderLayout.CENTER)

        return panel
    }

    private fun createRemoveButton(parent: JComponent, onRemove: (JComponent) -> Unit) = JButton("x").apply {
        isContentAreaFilled = false
        isFocusPainted = false
        border = BorderFactory.createEmptyBorder(2, 5, 2, 5)
        foreground = AppColors.FOREGROUND
        addActionListener { onRemove(parent) }
    }

    private fun formatResponseText(raw: String): String {
        val builder = StringBuilder("<html><body style=\"font-family: sans-serif; font-size: 12px; background-color: #${Integer.toHexString(AppColors.BACKGROUND.rgb).substring(2)}; color: #${Integer.toHexString(AppColors.FOREGROUND.rgb).substring(2)}; padding: 10px;\">")
        var inCodeBlock = false
        var codeLanguage = ""
        for (line in raw.lines()) {
            when {
                line.trim().startsWith("```") -> {
                    if (inCodeBlock) {
                        builder.append("</pre>")
                        inCodeBlock = false
                    } else {
                        inCodeBlock = true
                        codeLanguage = line.trim().removePrefix("```").lowercase()
                        builder.append("<pre style=\"background-color:#${Integer.toHexString(AppColors.CODE_BACKGROUND.rgb).substring(2)}; color:#${Integer.toHexString(AppColors.FOREGROUND.rgb).substring(2)}; padding:10px; border: 1px solid #555;\">")
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
                line.trim().startsWith("###") -> builder.append("<h3>${line.removePrefix("###").trim()}</h3>")
                "**" in line -> builder.append("<p>${line.replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")}</p>")
                else -> builder.append("<p>${escapeHtml(line)}</p>")
            }
        }
        if (inCodeBlock) builder.append("</pre>")
        builder.append("</body></html>")
        return builder.toString()
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
}