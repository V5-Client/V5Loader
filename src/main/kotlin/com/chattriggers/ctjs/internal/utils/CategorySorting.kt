package com.chattriggers.ctjs.internal.utils

import gg.essential.vigilance.data.Category
import gg.essential.vigilance.data.SortingBehavior

internal object CategorySorting : SortingBehavior() {
    private val order = mapOf("General" to 0, "Console" to 1)

    override fun getCategoryComparator(): Comparator<in Category> =
        Comparator { o1, o2 ->
            val first = order[o1.name]
            val second = order[o2.name]
            if (first == null || second == null) {
                throw IllegalArgumentException("All categories must be in the list of categories")
            }

            first - second
        }
}
