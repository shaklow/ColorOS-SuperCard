package dev.local.supercardhost

/** Optional native window owner; legacy card content keeps its existing callbacks. */
internal interface CardWindowOwner {
    fun hideCard()
    fun secureCard(secure: Boolean)
}
