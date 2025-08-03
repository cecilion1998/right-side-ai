package ir.dotin.AIPlugin.ui

import ir.dotin.AIPlugin.data.CodeSnippet
import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JEditorPane

object AppColors {
    val BACKGROUND = Color(43, 43, 43)
    val FOREGROUND = Color(169, 183, 198)
    val BORDER = Color.DARK_GRAY
    val CODE_BACKGROUND = Color(60, 63, 65)
    val KEYWORD = Color(204, 120, 50)
    val STRING = Color(106, 135, 89)
    val COMMENT = Color(128, 128, 128)
    val VARIABLE = Color(152, 118, 170)
}

object AppFonts {
    val MONO = Font("Monospaced", Font.PLAIN, 12)
    val SANS_SERIF = Font("sans-serif", Font.PLAIN, 12)
    val BOLD_SANS_SERIF = SANS_SERIF.deriveFont(Font.BOLD)
}

fun createStyledEditorPane(contentType: String, text: String): JEditorPane {
    return JEditorPane(contentType, text).apply {
        isEditable = false
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        background = AppColors.BACKGROUND
    }
}

fun escapeHtml(text: String): String {
    return text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}

fun highlightJava(code: String): String {
    val keywords = listOf(
        "public", "class", "static", "void", "int", "long", "if", "else",
        "return", "new", "private", "protected", "boolean", "String",
        "package", "import", "override", "fun", "val", "var"
    )

    var escaped = escapeHtml(code)

    escaped = escaped.replace(Regex("\"(.*?)\"")) {
        "<span style='color:#${Integer.toHexString(AppColors.STRING.rgb).substring(2)}'>&quot;${it.groupValues[1]}&quot;</span>"
    }
    escaped = escaped.replace(Regex("(//.*)$")) {
        "<span style='color:#${Integer.toHexString(AppColors.COMMENT.rgb).substring(2)}'>${it.groupValues[1]}</span>"
    }
    for (kw in keywords) {
        escaped = escaped.replace(Regex("\\b$kw\\b")) {
            "<span style='color:#${Integer.toHexString(AppColors.KEYWORD.rgb).substring(2)}'>${it.value}</span>"
        }
    }
    return escaped
}

fun highlightBash(code: String): String {
    val keywords = listOf("echo", "cd", "ls", "pwd", "rm", "mkdir", "touch", "cat", "sudo", "chmod", "chown", "git", "export")
    val variables = Regex("\\$[A-Za-z_][A-Za-z0-9_]*")

    var escaped = escapeHtml(code)

    escaped = escaped.replace(Regex("#.*")) {
        "<span style='color:#${Integer.toHexString(AppColors.COMMENT.rgb).substring(2)}'>${it.value}</span>"
    }
    escaped = escaped.replace(Regex("\"(.*?)\"")) {
        "<span style='color:#${Integer.toHexString(AppColors.STRING.rgb).substring(2)}'>&quot;${it.groupValues[1]}&quot;</span>"
    }
    escaped = escaped.replace(variables) {
        "<span style='color:#${Integer.toHexString(AppColors.VARIABLE.rgb).substring(2)}'>${it.value}</span>"
    }
    for (kw in keywords) {
        escaped = escaped.replace(Regex("\\b$kw\\b")) {
            "<span style='color:#${Integer.toHexString(AppColors.KEYWORD.rgb).substring(2)}'>${it.value}</span>"
        }
    }
    return escaped
}

fun formatCodeToHtml(snippet: CodeSnippet): String {
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
            <body style="font-family: ${AppFonts.MONO.family}; font-size: ${AppFonts.MONO.size}px; background-color: #${Integer.toHexString(AppColors.BACKGROUND.rgb).substring(2)}; color: #${Integer.toHexString(AppColors.FOREGROUND.rgb).substring(2)};">
                <pre style="margin: 0; padding: 5px;">$highlightedCode</pre>
            </body>
        </html>
    """.trimIndent()
}