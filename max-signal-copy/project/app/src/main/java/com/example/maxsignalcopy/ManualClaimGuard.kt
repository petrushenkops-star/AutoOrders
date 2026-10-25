package com.example.maxsignalcopy

/** Explain why a manual code was rejected without weakening duplicate protection. */
enum class ManualClaimBlock(val explanation: String) {
    INVALID("Неверный формат кода. Проверьте количество цифр."),
    RUNNING("Предыдущая операция ещё выполняется. Дождитесь её завершения."),
    PENDING("Есть ожидающий сигнал. Проверьте или отклоните его в настройках перед новым заказом."),
    ATTEMPTED("Этот код уже запускался сегодня. Повторная отправка запрещена; для нового заказа нужен новый код."),
    SAVE_FAILED("Не удалось безопасно сохранить попытку. Заказ не отправлен.");
}

object ManualClaimGuard {
    fun classify(
        anotherOrderRunning: Boolean,
        awaitingSignal: Boolean,
        codeAlreadyAttemptedToday: Boolean,
    ): ManualClaimBlock? = when {
        anotherOrderRunning -> ManualClaimBlock.RUNNING
        awaitingSignal -> ManualClaimBlock.PENDING
        codeAlreadyAttemptedToday -> ManualClaimBlock.ATTEMPTED
        else -> null
    }
}
