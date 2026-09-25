package org.libsdl.app

/**
 * #209 - jump-to-letter for the carousel's "Sort" picker (ported from the
 * Windows launcher's version).
 *
 * The game list reads A to Z ignoring case (#202), so every letter's games
 * sit together and a game can be reached directly by the first letter of its
 * name. Everything that is not A to Z (a digit, a symbol, or a non-English
 * letter) shares one entry, "#". This only says where to scroll to; it never
 * filters or reorders the list.
 */
const val DIGITS_ENTRY = "#"

/** The picker's entries, in order: "#", then "A" to "Z". */
val LETTER_ENTRIES: List<String> = listOf(DIGITS_ENTRY) + ('A'..'Z').map { it.toString() }

/** Which entry a game's name belongs to: its first letter, upper-cased, or "#". */
fun letterEntryFor(name: String): String {
    val first = name.firstOrNull()?.uppercaseChar() ?: return DIGITS_ENTRY
    return if (first in 'A'..'Z') first.toString() else DIGITS_ENTRY
}

/**
 * For each entry that has at least one game, the index of the first game in
 * [games] that belongs to it. An entry with no games is absent, which is what
 * the picker uses to dim it.
 */
fun letterJumpTargets(games: List<Game>): Map<String, Int> {
    val targets = LinkedHashMap<String, Int>()
    games.forEachIndexed { index, game -> targets.putIfAbsent(letterEntryFor(game.name), index) }
    return targets
}
