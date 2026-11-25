/*
 * Adapted from Symphony (https://github.com/zyrouge/symphony), utils/Fuzzy.kt,
 * Copyright (C) Zyrouge, licensed under AGPL-3.0. RTQ NOW is AGPL-3.0; see LICENSE.
 *
 * The weighted multi-option scorer architecture is Symphony's. The string ratio
 * implementation below replaces the fuzzywuzzy dependency with a self-contained
 * Ratcliff-Obershelp scorer so RTQ NOW adds no new dependency.
 */
package io.github.aedev.flow.symphony.fuzzy

import io.github.aedev.flow.utils.foldForSearch
import kotlin.math.max
import kotlin.math.min

class FuzzySearchComparator(val input: String) {
    fun compareString(value: String) = Fuzzy.compare(input, value)

    fun compareCollection(values: Collection<String>): Int? {
        if (values.isEmpty()) return null
        var score = 0
        values.forEach { score = max(score, compareString(it)) }
        return score
    }
}

data class FuzzySearchOption<T>(
    val match: FuzzySearchComparator.(T) -> Int?,
    val weight: Int = 1,
)

data class FuzzyResultEntity<T>(
    val score: Int,
    val entity: T,
)

/** Symphony's weighted scorer: best weighted field score wins. */
class FuzzySearcher<T>(val options: List<FuzzySearchOption<T>>) {
    fun search(
        terms: String,
        entities: List<T>,
        maxLength: Int = -1,
    ): List<FuzzyResultEntity<T>> {
        val results = entities
            .map { compare(terms, it) }
            .sortedByDescending { it.score }
        return when {
            maxLength > -1 -> results.subList(0, min(maxLength, results.size))
            else -> results
        }
    }

    private fun compare(terms: String, entity: T): FuzzyResultEntity<T> {
        var score = 0
        val comparator = FuzzySearchComparator(terms)
        options.forEach { option ->
            option.match.invoke(comparator, entity)?.let {
                score = max(score, it * option.weight)
            }
        }
        return FuzzyResultEntity(score, entity)
    }
}

object Fuzzy {
    /** token_set partial ratio, as Symphony uses it, on normalized terms. */
    fun compare(input: String, against: String) = tokenSetPartialRatio(
        normalizeTerms(input),
        normalizeTerms(against),
    )

    private val symbolsRegex = Regex("""[~$&+,:;=?@#|'"<>.^*()\[\]%!\-_/\\]+""")
    private val whitespaceRegex = Regex("""\s+""")

    private fun normalizeTerms(terms: String) = terms.foldForSearch()
        .replace(symbolsRegex, "")
        .replace(whitespaceRegex, " ")
        .trim()

    private fun tokenSetPartialRatio(a: String, b: String): Int {
        val sortedA = a.split(whitespaceRegex).filter { it.isNotEmpty() }.sorted().joinToString(" ")
        val sortedB = b.split(whitespaceRegex).filter { it.isNotEmpty() }.sorted().joinToString(" ")
        if (sortedA.isEmpty() || sortedB.isEmpty()) return 0
        return partialRatio(sortedA, sortedB)
    }

    /** Best ratio of the shorter string against a sliding window of the longer. */
    private fun partialRatio(short: String, long: String): Int {
        val s = if (short.length <= long.length) short else long
        val l = if (short.length <= long.length) long else short
        var best = ratio(s, l)
        val window = s.length
        for (i in 0..(l.length - window)) {
            best = max(best, ratio(s, l.substring(i, i + window)))
            if (best == 100) break
        }
        return best
    }

    /** 0-100 similarity from Ratcliff-Obershelp matching characters: 2*M/T. */
    private fun ratio(a: String, b: String): Int {
        val total = a.length + b.length
        if (total == 0) return 100
        return (200 * matchingChars(a, b) / total)
    }

    private fun matchingChars(a: String, b: String): Int {
        var matches = 0
        val used = BooleanArray(b.length)
        fun longestMatch(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int) {
            var bestLen = 0
            var bestA = aStart
            var bestB = bStart
            for (i in aStart until aEnd) {
                var len = 0
                for (j in bStart until bEnd) {
                    if (used[j]) continue
                    if (i + len < aEnd && a[i + len] == b[j]) {
                        len++
                        if (len > bestLen) {
                            bestLen = len
                            bestA = i
                            bestB = j
                        }
                    } else {
                        len = 0
                    }
                }
            }
            if (bestLen == 0) return
            for (k in 0 until bestLen) used[bestB + k] = true
            matches += bestLen
            longestMatch(aStart, bestA, bStart, bestB)
            longestMatch(bestA + bestLen, aEnd, bestB + bestLen, bEnd)
        }
        longestMatch(0, a.length, 0, b.length)
        return matches
    }
}
