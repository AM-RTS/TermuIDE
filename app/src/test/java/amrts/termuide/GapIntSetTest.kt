package amrts.termuide

import cn.rbc.codeeditor.util.GapIntSet
import org.junit.Assert.*
import org.junit.Test

class GapIntSetTest {
    @Test fun insertionsAndDeletionsShiftAndMergeMarks() {
        val cases = listOf(
            Triple(7, 2, intArrayOf(6, 11, 12)),
            Triple(7, -1, intArrayOf(6, 8, 9)),
            Triple(7, -3, intArrayOf(6, 7)),
            Triple(10, 1, intArrayOf(6, 9, 11)),
            Triple(10, -1, intArrayOf(6, 9)),
            Triple(11, -1, intArrayOf(6, 9, 10)),
            Triple(10, -5, intArrayOf(5))
        )
        for ((pivot, offset, expected) in cases) {
            val marks = GapIntSet().apply { setData(intArrayOf(6, 9, 10)) }
            marks.shift(pivot, offset)
            val label = "shift($pivot, $offset)"
            assertEquals(label, expected.size, marks.size())
            assertEquals(label, expected.toList(), (0 until marks.size()).map { marks.get(it) })
            assertArrayEquals(label, expected, marks.getData())
        }
    }

    @Test fun deletingMarkedLinesPreservesAnEarlierZeroMark() {
        val marks = GapIntSet().apply { setData(intArrayOf(0, 6, 9, 10)) }
        marks.shift(10, -5)
        assertEquals(0, marks.get(0))
        assertEquals(5, marks.get(1))
        assertArrayEquals(intArrayOf(0, 5), marks.getData())
    }

    @Test fun marksStayUniqueAndFindableThroughToggleShiftAndClear() {
        val marks = GapIntSet(1)
        marks.set(9)
        marks.set(3)
        assertEquals(1, marks.set(9))
        marks.toggle(3)
        marks.toggle(6)
        marks.shift(7, 2)
        assertEquals(2, marks.size())
        assertEquals(1, marks.find(11))
        assertEquals(6, marks.get(0))
        assertEquals(11, marks.get(1))
        marks.clear()
        assertEquals(0, marks.size())
        marks.set(0)
        assertArrayEquals(intArrayOf(0), marks.getData())
    }
}
