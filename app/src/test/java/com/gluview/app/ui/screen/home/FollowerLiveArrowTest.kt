package com.gluview.app.ui.screen.home

import com.gluview.app.util.directionToArrow
import com.gluview.app.util.liveArrow
import org.junit.Assert.assertEquals
import org.junit.Test

/** L30: a seta do seguidor nas leituras ao vivo (NovaMedicao sem o campo "arrow"). */
class FollowerLiveArrowTest {

    @Test fun `sem arrow usa a direcao convertida`() {
        assertEquals("↘", liveArrow(null, "FortyFiveDown"))
        assertEquals("↑↑", liveArrow("", "DoubleUp"))
        assertEquals("→", liveArrow("  ", "Flat"))
    }

    @Test fun `arrow presente tem prioridade`() {
        assertEquals("↓", liveArrow("↓", "Flat"))
    }

    @Test fun `direcao que ja e seta passa igual`() {
        assertEquals("↗", liveArrow(null, "↗"))
    }

    @Test fun `direcao desconhecida nao vira texto no lugar da seta`() {
        assertEquals("", liveArrow(null, null))
        assertEquals("", liveArrow(null, "NOT COMPUTABLE"))
        assertEquals("", directionToArrow("RATE OUT OF RANGE"))
    }
}
