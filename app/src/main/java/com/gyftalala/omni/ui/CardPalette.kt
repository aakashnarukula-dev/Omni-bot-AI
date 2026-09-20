package com.gyftalala.omni.ui

import com.gyftalala.omni.data.Memory

/** Shared by the wallet and deterministic PNG export. Decorative finishes, not issuer artwork. */
data class CardPalette(val start: Int, val end: Int, val edge: Int) {
    companion object {
        private val finishes = listOf(
            CardPalette(0xFF42424B.toInt(), 0xFF22232A.toInt(), 0xFF686873.toInt()),
            CardPalette(0xFF334654.toInt(), 0xFF182B38.toInt(), 0xFF557487.toInt()),
            CardPalette(0xFF514335.toInt(), 0xFF29241F.toInt(), 0xFF887154.toInt()),
            CardPalette(0xFF304B45.toInt(), 0xFF192D29.toInt(), 0xFF557B6E.toInt()),
        )
        fun forMemory(memory: Memory): CardPalette {
            if (memory.palette.size == 3) return CardPalette(memory.palette[0], memory.palette[1], memory.palette[2])
            val key = memory.card?.issuer?.lowercase()?.takeIf { it.isNotBlank() } ?: memory.id
            return finishes[Math.floorMod(key.hashCode(), finishes.size)]
        }
    }
}
