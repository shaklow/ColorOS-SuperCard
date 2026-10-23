package dev.local.supercardhost

/** The legacy editor component passes only the original whitelisted mCardType extra. */
class CardFunctionSettingsActivity : CardManagementActivity() {
    override val functionEditor = true
}
