package com.dsh.android.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PetWindowGeometryTest {
    @Test
    fun `moving the same character does not resize its surface every pixel`() {
        val crops = (0..20).map { step ->
            PetWindowGeometry.crop(10f + step / 20f, 30f + step / 20f, 78.4f, 93.1f, 2.75f, 1080, 2240, false)!!
        }
        assertEquals("moving must not alternate between two surface widths", 1, crops.map { it.width }.distinct().size)
        assertEquals("moving must not alternate between two surface heights", 1, crops.map { it.height }.distinct().size)
    }

    @Test
    fun `keeps the in-app pet size and rounds outward`() {
        val crop = PetWindowGeometry.crop(10.1f, 30.2f, 78f, 93f, 2.75f, 1080, 2240, false)!!
        assertEquals(27, crop.x)
        assertEquals(83, crop.y)
        assertEquals(216, crop.width)
        assertEquals(257, crop.height)
        assertEquals(crop.x, crop.contentX)
        assertEquals(crop.y, crop.contentY)
    }

    @Test
    fun `a throw outside the screen keeps the cropped character visible`() {
        val crop = PetWindowGeometry.crop(-80f, 900f, 78f, 93f, 2.75f, 1080, 2240, false)!!
        assertEquals(0, crop.x)
        assertEquals(1983, crop.y)
        assertEquals(-220, crop.contentX)
        assertEquals(2475, crop.contentY)
    }

    @Test
    fun `menus use the full window so nested submenus have room`() {
        assertEquals(
            PetWindowGeometry.Crop(0, 0, 1080, 2240, 0, 0),
            PetWindowGeometry.crop(20f, 30f, 78f, 93f, 2.75f, 1080, 2240, true),
        )
    }

    @Test
    fun `a page modal uses the whole window`() {
        assertEquals(
            PetWindowGeometry.Crop(0, 0, 1080, 2240, 0, 0),
            PetWindowGeometry.crop(20f, 30f, 78f, 93f, 2.75f, 1080, 2240, true, true),
        )
    }

    @Test
    fun `a pet pinned to the edge falls back to the full window`() {
        // Only 35px remain to the right, far less than the 160dp the menu needs.
        assertEquals(
            PetWindowGeometry.Crop(0, 0, 1080, 2240, 0, 0),
            PetWindowGeometry.crop(380f, 30f, 78f, 93f, 2.75f, 1080, 2240, true),
        )
    }

    @Test
    fun `zero size hides in a tiny window and invalid JS values are ignored`() {
        assertEquals(1, PetWindowGeometry.crop(0f, 0f, 0f, 0f, 2.75f, 1080, 2240, false)!!.width)
        assertNull(PetWindowGeometry.crop(Float.NaN, 0f, 78f, 93f, 2.75f, 1080, 2240, false))
    }
}
