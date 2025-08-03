package ir.dotin.AIPlugin.data

data class CodeSnippet(
    val fileName: String,
    val startLine: Int,
    val endLine: Int,
    val code: String
)

// Used to structure the JSON request sent to the server
data class GenerationRequest(val prompt: String)

// Used to parse the JSON response from the server
data class GenerationResponse(val response: String)
