package com.example.maxsignalcopy

import org.junit.Assert.*
import org.junit.Test

class ManualClaimGuardTest {
    @Test fun unusedNewCodeCanProceed() {
        assertNull(ManualClaimGuard.classify(false, false, false))
    }
    @Test fun previouslyAttemptedCodeIsBlockedEvenWhenNoOperationIsRunning() {
        assertEquals(ManualClaimBlock.ATTEMPTED, ManualClaimGuard.classify(false, false, true))
    }
    @Test fun activeAndPendingWorkTakePrecedenceOverDuplicateDiagnostic() {
        assertEquals(ManualClaimBlock.RUNNING, ManualClaimGuard.classify(true, true, true))
        assertEquals(ManualClaimBlock.PENDING, ManualClaimGuard.classify(false, true, true))
    }
    @Test fun reasonsTellUserWhatToDoWithoutSuggestingUnsafeRetry() {
        assertTrue(ManualClaimBlock.ATTEMPTED.explanation.contains("новый код"))
        assertTrue(ManualClaimBlock.RUNNING.explanation.contains("Дождитесь"))
        assertTrue(ManualClaimBlock.PENDING.explanation.contains("настройках"))
    }
}
