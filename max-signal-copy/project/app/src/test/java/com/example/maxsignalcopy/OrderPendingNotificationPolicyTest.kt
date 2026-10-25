package com.example.maxsignalcopy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderPendingNotificationPolicyTest {
    @Test fun notifyOnlyAfterVerifiedPendingOrder() {
        assertTrue(OrderPendingNotificationPolicy.shouldNotify("В ОЖИДАНИИ"))
        for (state in listOf("ОШИБКА", "ОСТАНОВЛЕНО", "НЕИЗВЕСТНО", "УСПЕШНО", "Заказ создан", "")) {
            assertFalse("Not a confirmed creation: $state", OrderPendingNotificationPolicy.shouldNotify(state))
        }
    }
}
