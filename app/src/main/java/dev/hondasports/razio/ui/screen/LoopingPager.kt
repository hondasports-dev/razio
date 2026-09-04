package dev.hondasports.razio.ui.screen

/** Virtual page count so a small item set can keep scrolling past either end. */
internal const val LOOPING_PAGER_PAGE_COUNT = 10_000

internal fun loopingPagerStartPage(itemCount: Int, selectedIndex: Int): Int {
    require(itemCount > 0)
    val center = LOOPING_PAGER_PAGE_COUNT / 2
    return center - center.mod(itemCount) + selectedIndex.mod(itemCount)
}

internal fun loopingPagerItemIndex(page: Int, itemCount: Int): Int {
    require(itemCount > 0)
    return page.mod(itemCount)
}

internal fun nearestLoopingPagerPage(
    currentPage: Int,
    targetIndex: Int,
    itemCount: Int,
): Int {
    require(itemCount > 0)
    val currentIndex = loopingPagerItemIndex(currentPage, itemCount)
    val target = targetIndex.mod(itemCount)
    var delta = target - currentIndex
    val half = itemCount / 2
    if (delta > half) {
        delta -= itemCount
    }
    if (delta < -half) {
        delta += itemCount
    }
    return (currentPage + delta).coerceIn(0, LOOPING_PAGER_PAGE_COUNT - 1)
}
