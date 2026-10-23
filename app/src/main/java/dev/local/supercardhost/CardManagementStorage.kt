package dev.local.supercardhost

import android.content.Context
import android.provider.Settings

/** Existing secure-settings key and existing OEM first-install migration, without a new data store. */
internal class CardManagementStorage(context: Context) : CardManagementRepository.Storage {
    private val app = context.applicationContext
    override fun read(): String? = Settings.Secure.getString(app.contentResolver, KEY)
    override fun write(raw: String) = Settings.Secure.putString(app.contentResolver, KEY, raw)
    override fun canWrite() = RootAppSetup.canSave(app)
    override fun initialize() {
        // Only an absent configuration reaches here. Malformed existing data is never reset.
        val loader = app.classLoader
        val perf = loader.loadClass("com.vivo.card.CardPerfContext")
        for (method in listOf("setOriginContext", "setPerfContext"))
            perf.getMethod(method, Context::class.java).invoke(null, app)
        val type = loader.loadClass("com.vivo.card.data.CardDataInitializer")
        val initializer = type.getMethod("getInstance", Context::class.java).invoke(null, app)
        type.getMethod("init").invoke(initializer)
    }

    companion object { const val KEY = "card_setting_data" }
}
