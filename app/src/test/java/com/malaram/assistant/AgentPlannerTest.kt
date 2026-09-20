package com.malaram.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPlannerTest {
    @Test fun directClickIsPlanned() {
        val plan = AgentPlanner.plan("क्लिक भेजें")
        assertEquals(1, plan.size)
        assertEquals("click", plan[0].action)
        assertEquals("भेजें", plan[0].argument)
    }

    @Test fun compoundPlanIsExpanded() {
        val plan = AgentPlanner.plan("क्लिक खोजो और फिर लिखो बारिश")
        assertEquals(2, plan.size)
        assertEquals("click", plan[0].action)
        assertEquals("type", plan[1].action)
        assertEquals("बारिश", plan[1].argument)
    }

    @Test fun unknownCommandDoesNotInventActions() {
        assertTrue(AgentPlanner.plan("मुझे कुछ कर दो").isEmpty())
    }
}
