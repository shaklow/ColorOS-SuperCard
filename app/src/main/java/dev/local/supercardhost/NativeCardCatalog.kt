package dev.local.supercardhost

internal object NativeCardCatalog {
    private val types = setOf("pay", "staging", "camera", "dhu")
    fun cards(raw: String?): List<String> = if (raw.isNullOrBlank()) listOf("pay", "staging", "camera", "dhu") else
        CardManagementState.parse(raw).cards.filter { it.enabled && it.type in types }.map { it.type }
    fun complete(raw: String?, payReady: Boolean): Boolean {
        if (raw.isNullOrBlank()) return false
        val enabled = CardManagementState.parse(raw).cards.filter { it.enabled }
        return enabled.isNotEmpty() && enabled.all { it.type in types &&
            (it.type != "pay" || payReady && NativePayConfiguration.supported(raw)) }
    }
}
