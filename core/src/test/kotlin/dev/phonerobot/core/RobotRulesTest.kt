package dev.phonerobot.core

import org.junit.Assert.*
import org.junit.Test

class RobotRulesTest {
    @Test fun `ordinary conversation containing stop is not an actuator command`() {
        assertEquals(Command.CHAT, CommandRouter.parse("Why do trains stop?"))
        assertEquals(Command.CHAT, CommandRouter.parse("don't look left"))
    }
    @Test fun `local commands tolerate casing punctuation and whitespace`() {
        assertEquals(Command.STOP, CommandRouter.parse(" STOP! "))
        assertEquals(Command.LEFT, CommandRouter.parse("Look   left."))
        assertEquals(Command.WAKE, CommandRouter.parse("wake up"))
    }
    @Test fun `blank and excessive inputs are rejected before routing`() {
        assertEquals(Command.EMPTY, CommandRouter.parse("   "))
        assertEquals(Command.INVALID, CommandRouter.parse("x".repeat(1001)))
    }
    @Test fun `cloud is optional and never handles local commands`() {
        assertFalse(CommandRouter.needsCloud(Command.LEFT, true, false))
        assertFalse(CommandRouter.needsCloud(Command.CHAT, false, false))
        assertFalse(CommandRouter.needsCloud(Command.CHAT, true, true))
        assertTrue(CommandRouter.needsCloud(Command.CHAT, true, false))
    }
    @Test fun `motion clamps valid targets and rejects nonfinite values`() {
        assertEquals(MotionTarget(1f, -1f), MotionTarget.bounded(9f, -3f))
        assertNull(MotionTarget.bounded(Float.NaN, 0f))
        assertNull(MotionTarget.bounded(0f, Float.POSITIVE_INFINITY))
    }
    @Test fun `stopped hardware gate rejects tracking until explicit resume`() {
        val active = MotionGate().resume()
        assertEquals(MotionTarget(0.2f, 0.4f), active.accept(0.2f, 0.4f))
        assertNull(active.stop().accept(0.2f, 0.4f))
        assertNull(MotionGate().accept(0f, 0f))
    }
}
