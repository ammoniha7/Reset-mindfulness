package com.example.urgeprocessor.util

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.example.urgeprocessor.data.StandardJournalEntry
import com.example.urgeprocessor.data.UrgeEntry

data class SearchResult(
    val timestamp: Long,
    val type: String, // "Journal" or "Urge"
    val snippet: AnnotatedString,
    val relevance: Int,
    val originalEntry: Any // The StandardJournalEntry or UrgeEntry object
)

fun performSearch(query: String, journals: List<StandardJournalEntry>, urges: List<UrgeEntry>): List<SearchResult> {
    val results = mutableListOf<SearchResult>()
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()

    val terms = q.split(" ").filter { it.isNotEmpty() }

    // Search Journals
    journals.forEach { entry ->
        val content = entry.content.lowercase()
        var relevance = 0
        if (content.contains(q)) {
            relevance = 100 // Exact phrase match
        } else if (terms.all { content.contains(it) }) {
            relevance = 50 // All words present
        } else if (terms.any { content.contains(it) }) {
            relevance = 10 // Partial match
        }

        if (relevance > 0) {
            results.add(SearchResult(
                timestamp = entry.timestamp,
                type = "Journal",
                snippet = createSearchSnippet(entry.content, q),
                relevance = relevance,
                originalEntry = entry
            ))
        }
    }

    // Search Urge Entries
    urges.forEach { entry ->
        val fields = listOf(entry.category, entry.specificEmotion, entry.feltLoved, entry.stressReason, entry.excitementWeek)
        val fullText = fields.joinToString(" ").lowercase()
        
        var relevance = 0
        if (fullText.contains(q)) {
            relevance = 100
        } else if (terms.all { fullText.contains(it) }) {
            relevance = 50
        } else if (terms.any { fullText.contains(it) }) {
            relevance = 10
        }

        if (relevance > 0) {
            val displaySnippet = fields.filter { it.lowercase().contains(terms.first()) }.joinToString(" | ")
            results.add(SearchResult(
                timestamp = entry.timestamp,
                type = "Urge Flow",
                snippet = createSearchSnippet(displaySnippet.ifEmpty { entry.category + ": " + entry.specificEmotion }, q),
                relevance = relevance,
                originalEntry = entry
            ))
        }
    }

    return results.sortedWith(compareByDescending<SearchResult> { it.relevance }.thenByDescending { it.timestamp })
}

fun createSearchSnippet(text: String, query: String): AnnotatedString {
    val index = text.lowercase().indexOf(query.lowercase().split(" ").first())
    val start = (index - 30).coerceAtLeast(0)
    val end = (index + query.length + 30).coerceAtMost(text.length)
    
    val snippetBase = (if (start > 0) "..." else "") + text.substring(start, end) + (if (end < text.length) "..." else "")
    
    return getHighlightedText(snippetBase, query)
}

fun getHighlightedText(text: String, query: String): AnnotatedString {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return buildAnnotatedString { append(text) }
    
    val terms = q.split(" ").filter { it.isNotEmpty() }
    
    return buildAnnotatedString {
        val lowerText = text.lowercase()
        val matches = mutableListOf<IntRange>()
        
        terms.forEach { term ->
            var idx = lowerText.indexOf(term)
            while (idx != -1) {
                matches.add(idx until (idx + term.length))
                idx = lowerText.indexOf(term, idx + term.length)
            }
        }
        
        val sortedMatches = matches.sortedBy { it.first }
        val mergedMatches = mutableListOf<IntRange>()
        if (sortedMatches.isNotEmpty()) {
            var current = sortedMatches[0]
            for (i in 1 until sortedMatches.size) {
                val next = sortedMatches[i]
                if (next.first <= current.last + 1) { // +1 to merge adjacent
                    current = current.first .. maxOf(current.last, next.last)
                } else {
                    mergedMatches.add(current)
                    current = next
                }
            }
            mergedMatches.add(current)
        }
        
        var lastIndex = 0
        mergedMatches.forEach { range ->
            if (range.first > lastIndex) {
                append(text.substring(lastIndex, range.first))
            }
            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, background = Color.Yellow.copy(alpha = 0.3f))) {
                append(text.substring(range.first, range.last + 1))
            }
            lastIndex = range.last + 1
        }
        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}
