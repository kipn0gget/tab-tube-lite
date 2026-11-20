package com.example.data.remote

import com.example.data.model.VideoItem

/**
 * Geeft voorkeur aan YouTube Kids-achtige peutercontent (ca. 1-4 jaar)
 * bij zoekopdrachten en feeds, zonder een trage externe API-laag toe te voegen.
 */
object KidsContentFilter {

    val QUICK_TODDLER_TOPICS = listOf(
        "Nijntje",
        "Juf Roos",
        "Bumba",
        "Sesamstraat",
        "Zappelin",
        "Peuterliedjes",
        "Dieren",
        "Slaapliedjes"
    )

    private val ALREADY_KIDS_KEYWORDS = listOf(
        "peuter", "dreumes", "kleuter", "kinder", "kids", "toddler", "preschool", "baby",
        "nijntje", "miffy", "bumba", "juf roos", "sesamstraat", "zappelin", "woezel",
        "peppa", "bluey", "cocomelon", "super simple", "bing", "pieter konijn",
        "kinderlied", "slaaplied", "liedje", "tekenfilm", "lullaby", "nursery"
    )

    private val TODDLER_BOOST_KEYWORDS = listOf(
        "nijntje", "miffy", "bumba", "juf roos", "sesamstraat", "zappelin", "woezel",
        "peppa", "bluey", "cocomelon", "super simple", "hey bear", "bing", "teletubbies",
        "brandweerman sam", "bob de bouwer", "thomas de stoomlocomotief", "pieter konijn",
        "maya de bij", "kinderlied", "peuter", "dreumes", "kleuter", "slaaplied",
        "liedjes", "kleuren", "cijfers", "tellen", "dieren", "abc", "verhaaltje",
        "tekenfilm", "toddler", "preschool", "nursery", "lullaby", "kids", "babies"
    )

    private val BLOCKED_WORD_REGEXES = listOf(
        "horror", "scary", "creepypasta", "jumpscare", "gore", "bloody",
        "18\\+", "nsfw", "violent", "violence", "shooting", "murder", "true crime",
        "prank", "roast", "exposed", "drama", "politics", "politiek", "journaal",
        "nieuws", "podcast", "gta", "call of duty", "fortnite", "valorant",
        "counter-strike", "killing", "weapon", "gambling", "alcohol", "drugs",
        "nightmare", "demon", "zombie", "slasher"
    ).map { pattern -> Regex("\\b$pattern\\b", RegexOption.IGNORE_CASE) }

    fun enrichSearchQuery(rawQuery: String, toddlerMode: Boolean): String {
        val trimmed = rawQuery.trim()
        if (!toddlerMode || trimmed.isBlank()) return trimmed
        if (PipedStreamResolver.extractVideoId(trimmed) != null) return trimmed

        val lower = trimmed.lowercase()
        val alreadyKidsScoped = ALREADY_KIDS_KEYWORDS.any { lower.contains(it) }
        return if (alreadyKidsScoped) {
            trimmed
        } else {
            "$trimmed voor peuters kids"
        }
    }

    fun isToddlerAppropriate(video: VideoItem): Boolean {
        val combined = "${video.title} ${video.channelTitle}".lowercase()
        return BLOCKED_WORD_REGEXES.none { it.containsMatchIn(combined) }
    }

    fun toddlerPreferenceScore(video: VideoItem): Int {
        val text = "${video.title} ${video.channelTitle} ${video.category}".lowercase()
        var score = 0
        for (kw in TODDLER_BOOST_KEYWORDS) {
            if (text.contains(kw)) score += 3
        }
        if (video.category.equals("Peuter NL", ignoreCase = true) ||
            video.category.equals("Liedjes", ignoreCase = true) ||
            video.category.equals("Verhalen", ignoreCase = true)
        ) {
            score += 2
        }
        return score
    }

    fun filterAndRankResults(
        items: List<VideoItem>,
        toddlerMode: Boolean,
        isSearch: Boolean
    ): List<VideoItem> {
        if (!toddlerMode || items.isEmpty()) return items
        val safe = items.filter(::isToddlerAppropriate)
        val pool = if (safe.isNotEmpty()) safe else items
        if (!isSearch) return pool

        // Behoud relatieve zoekvolgorde voor gelijke scores, maar trek peutercontent naar voren
        return pool.withIndex()
            .sortedWith(compareByDescending<IndexedValue<VideoItem>> { toddlerPreferenceScore(it.value) }
                .thenBy { it.index })
            .map { it.value }
    }
}
