package dev.hondasports.razio.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class LoopingPagerTest {
    @Test
    fun startPageAlignsToSelectedIndexNearTheMiddle() {
        val start = loopingPagerStartPage(itemCount = 6, selectedIndex = 2)

        assertEquals(2, loopingPagerItemIndex(start, itemCount = 6))
        assertEquals(LOOPING_PAGER_PAGE_COUNT / 2 - (LOOPING_PAGER_PAGE_COUNT / 2).mod(6) + 2, start)
    }

    @Test
    fun itemIndexWrapsNegativeAndOverflowPages() {
        assertEquals(5, loopingPagerItemIndex(-1, itemCount = 6))
        assertEquals(0, loopingPagerItemIndex(6, itemCount = 6))
        assertEquals(1, loopingPagerItemIndex(7, itemCount = 6))
    }

    @Test
    fun nearestPageWalksForwardFromLastItemToFirst() {
        val last = loopingPagerStartPage(itemCount = 6, selectedIndex = 5)
        val wrapped = nearestLoopingPagerPage(
            currentPage = last,
            targetIndex = 0,
            itemCount = 6,
        )

        assertEquals(last + 1, wrapped)
        assertEquals(0, loopingPagerItemIndex(wrapped, itemCount = 6))
    }

    @Test
    fun nearestPageWalksBackwardFromFirstItemToLast() {
        val first = loopingPagerStartPage(itemCount = 6, selectedIndex = 0)
        val wrapped = nearestLoopingPagerPage(
            currentPage = first,
            targetIndex = 5,
            itemCount = 6,
        )

        assertEquals(first - 1, wrapped)
        assertEquals(5, loopingPagerItemIndex(wrapped, itemCount = 6))
    }

    @Test
    fun nearestPageStaysPutWhenAlreadyOnTarget() {
        val current = loopingPagerStartPage(itemCount = 6, selectedIndex = 3)

        assertEquals(
            current,
            nearestLoopingPagerPage(currentPage = current, targetIndex = 3, itemCount = 6),
        )
    }
}
