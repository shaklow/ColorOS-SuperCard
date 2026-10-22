package dev.local.supercardhost

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.view.Display
import android.view.LayoutInflater
import android.view.View
import dalvik.system.DelegateLastClassLoader
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.function.IntConsumer

/** Shared private UI boundary. Cache code/assets, never a host, Activity, theme or View. */
internal object MaterialUiArchive {
    private const val PACKAGE = "dev.local.supercardui"

    enum class Operation(val entry: String, val result: Class<*>, vararg val parameters: Class<*>) {
        CARD("create", View::class.java, Context::class.java),
        EXPANDED("createExpanded", View::class.java, Context::class.java),
        LENS("createLens", View::class.java, Context::class.java),
        SETTINGS("createSettings", View::class.java, Context::class.java, String::class.java),
        STYLE_PAY("stylePay", Void.TYPE, Context::class.java, String::class.java, Array<View>::class.java),
        RENDER_LENS("renderLens", Void.TYPE, View::class.java, Bundle::class.java),
        RENDER_SETTINGS("renderSettings", Void.TYPE, View::class.java, Bundle::class.java),
        CHOOSE_SETTINGS("chooseSettings", Void.TYPE, View::class.java, String::class.java,
            Array<String>::class.java, Int::class.javaPrimitiveType!!, IntConsumer::class.java),
        CONFIRM_SETTINGS("confirmSettings", Void.TYPE, View::class.java, String::class.java, String::class.java, Runnable::class.java),
        INFLATER("inflater", LayoutInflater::class.java, Context::class.java)
    }

    private class Loaded(val assets: AssetManager, val loader: ClassLoader, val methods: Map<Operation, Method>)
    @Volatile private var loaded: Loaded? = null

    @Synchronized private fun load(host: Context): Loaded {
        loaded?.let { return it }
        val module = host.createPackageContext("dev.local.supercardhost", Context.CONTEXT_IGNORE_SECURITY)
        val archive = MemoryArchiveResources.copyAsset(module, "dhu-ui.apk", File(host.codeCacheDir, "dhu_material_ui"))
        val info = host.packageManager.getPackageArchiveInfo(archive.absolutePath, 0)
            ?: throw IllegalStateException("M3E archive manifest missing")
        require(info.packageName == PACKAGE) { "M3E archive manifest mismatch" }
        val application = requireNotNull(info.applicationInfo)
        application.sourceDir = archive.absolutePath; application.publicSourceDir = archive.absolutePath
        application.splitSourceDirs = null; application.splitPublicSourceDirs = null
        val assets = host.packageManager.getResourcesForApplication(application).assets
        val loader = DelegateLastClassLoader(archive.absolutePath, MaterialUiArchive::class.java.classLoader)
        val factory = loader.loadClass("$PACKAGE.DhuCardSurface")
        // Validate the entire framework-only contract before publishing any shared state.
        val methods = Operation.entries.associateWith { operation ->
            factory.getMethod(operation.entry, *operation.parameters).apply {
                require(Modifier.isStatic(modifiers) && operation.result.isAssignableFrom(returnType)) {
                    "M3E archive API changed: ${operation.entry}"
                }
            }
        }
        return Loaded(assets, loader, methods).also { loaded = it }
    }

    @JvmStatic fun prepare(host: Context) { ArchiveContext(host, load(host)) }

    @JvmStatic fun create(host: Context, operation: Operation, vararg arguments: Any): Any? {
        require(operation.parameters.first() == Context::class.java)
        val archive = load(host)
        return archive.methods.getValue(operation).invoke(null, ArchiveContext(host, archive), *arguments)
    }

    @JvmStatic fun update(root: View, operation: Operation, vararg arguments: Any): Any? {
        require(operation.parameters.first() == View::class.java)
        return load(root.context).methods.getValue(operation).invoke(null, root, *arguments)
    }

    private class ArchiveContext(host: Context, private val archive: Loaded) : ContextWrapper(host) {
        private val archiveResources = Resources(archive.assets, host.resources.displayMetrics, host.resources.configuration)
        private val archiveTheme = archiveResources.newTheme().apply {
            val style = archiveResources.getIdentifier("Theme.DhuUi", "style", PACKAGE)
            if (style == 0) throw Resources.NotFoundException("Missing M3E theme")
            applyStyle(style, true)
        }
        override fun getResources() = archiveResources
        override fun getAssets() = archive.assets
        override fun getClassLoader() = archive.loader
        override fun getTheme() = archiveTheme
        override fun setTheme(id: Int) { archiveTheme.applyStyle(id, true) }
        override fun createConfigurationContext(configuration: Configuration): Context =
            ArchiveContext(baseContext.createConfigurationContext(configuration), archive)
        override fun createDisplayContext(display: Display): Context =
            ArchiveContext(baseContext.createDisplayContext(display), archive)
        override fun getSystemService(name: String): Any? = if (name == LAYOUT_INFLATER_SERVICE)
            archive.methods.getValue(Operation.INFLATER).invoke(null, this) else super.getSystemService(name)
    }
}
