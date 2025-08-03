package ir.dotin.AIPlugin.service

import ir.dotin.AIPlugin.data.CodeSnippet


class CodeSnippetManager {
    private val snippets = mutableListOf<CodeSnippet>()

    fun addSnippet(snippet: CodeSnippet) {
        snippets.add(snippet)
    }

    fun removeSnippet(snippet: CodeSnippet) {
        snippets.remove(snippet)
    }

    fun getAllSnippets(): List<CodeSnippet> = snippets.toList()

    fun getSnippetsForPrompt(): String {
        if (snippets.isEmpty()) return ""

        return buildString {
            append("\n\n--- Code Snippets ---\n")
            snippets.forEach { snippet ->
                append("\n// From ${snippet.fileName} (lines ${snippet.startLine}-${snippet.endLine})\n")
                append(snippet.code)
            }
        }
    }

    fun clear() {
        snippets.clear()
    }

    fun getSortedSnippets(): List<CodeSnippet> = snippets.sortedBy { it.startLine }
}