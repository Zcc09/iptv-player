package com.zcc09.iptvplayer.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resume is easy to get wrong in a way nobody reports: rewinding two seconds of
 * viewing is annoying, and "resuming" a title that was already finished traps the
 * viewer in a loop of the last minute. Pin both edges.
 */
class ResumeRuleTest {

    @Test
    fun `a few seconds of viewing does not resume`() {
        assertFalse(ResumeRule.shouldResume(0, 3_600_000))
        assertFalse(ResumeRule.shouldResume(29_999, 3_600_000))
    }

    @Test
    fun `the minimum is inclusive`() {
        assertTrue(ResumeRule.shouldResume(ResumeRule.MIN_RESUME_MS, 3_600_000))
    }

    @Test
    fun `a position in the middle of a title resumes`() {
        assertTrue(ResumeRule.shouldResume(10 * 60_000, 3_600_000))
    }

    @Test
    fun `an effectively finished title does not resume`() {
        // 97% of the way through
        assertFalse(ResumeRule.shouldResume(3_492_000, 3_600_000))
    }

    @Test
    fun `the finished boundary is 95 percent`() {
        assertFalse(ResumeRule.shouldResume(3_420_000, 3_600_000)) // exactly 95%
        assertTrue(ResumeRule.shouldResume(3_419_999, 3_600_000))
    }

    @Test
    fun `an unknown duration still resumes past the minimum`() {
        assertTrue(ResumeRule.shouldResume(60_000, 0))
        assertFalse(ResumeRule.shouldResume(1_000, 0))
    }
}
