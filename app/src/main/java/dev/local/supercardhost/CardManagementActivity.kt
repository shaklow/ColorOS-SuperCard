package dev.local.supercardhost

import android.content.Intent
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.function.Consumer

/** Shared original-configuration editor; runtime cards and launch metadata keep their ownership. */
open class CardManagementActivity : M3eSettingsActivity() {
    protected open val functionEditor = false
    private var cardType: String? = null
    private var payMaximum = 6
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var repository: CardManagementRepository
    private var root: View? = null
    private var confirmed: CardManagementState.Snapshot? = null
    private var projected: CardManagementState.Snapshot? = null
    private var backgrounds: List<Bundle>? = null
    private var busy = false
    private var refreshPending = false
    private var observing = false
    private var closed = false
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { refreshIfChanged() }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (functionEditor) {
            cardType = intent.getStringExtra("mCardType")
            if (cardType !in listOf("pay", "camera")) { finish(); return }
            if (cardType == "pay") try {
                val util = classLoader.loadClass("com.vivo.card.utils.CardUtil")
                if (util.getDeclaredMethod("isBigFingerProjects").apply { isAccessible = true }.invoke(null) == true) payMaximum = 4
            } catch (_: ReflectiveOperationException) { /* Standard ColorOS layout: six functions. */ }
        }
        title = when (cardType) { "pay" -> "支付卡设置"; "camera" -> "LENS 功能设置"; else -> "卡片管理" }
        repository = CardManagementRepository(CardManagementStorage(this))
        try {
            root = cardType?.let { DhuMaterialUi.cardFunctions(this, it) } ?: DhuMaterialUi.cardManagement(this)
            setContentView(root)
            DhuMaterialUi.bindCardManagement(root, Consumer(::action))
            render("正在读取卡片…")
        } catch (error: Exception) { root = null; unavailable(error) }
        catch (error: LinkageError) { root = null; unavailable(error) }
    }

    override fun onStart() {
        super.onStart()
        if (root == null) return
        contentResolver.registerContentObserver(Settings.Secure.getUriFor(CardManagementStorage.KEY), false, observer)
        observing = true
    }

    override fun onResume() {
        super.onResume()
        root?.let { DhuMaterialUi.bindCardManagement(it, Consumer(::action)); refresh() }
    }
    override fun onStop() {
        if (observing) { contentResolver.unregisterContentObserver(observer); observing = false }
        super.onStop()
    }

    override fun onDestroy() {
        closed = true
        main.removeCallbacksAndMessages(null)
        worker.shutdown() // A user's accepted write finishes; its result cannot touch a closed page.
        super.onDestroy()
    }

    private fun refresh() {
        if (closed || root == null) return
        if (busy) { refreshPending = true; return }
        submit(null)
    }

    private fun refreshIfChanged() {
        if (closed || root == null) return
        if (busy) { refreshPending = true; return }
        val base = confirmed ?: return refresh()
        // Provider notifications can arrive after a confirmed write. An unchanged
        // notification must not disable a button while its long press is pending.
        worker.execute {
            val unchanged = try { repository.isCurrent(base) } catch (_: Exception) { false }
            main.post {
                if (!closed && alive() && !unchanged && confirmed?.raw == base.raw) refresh()
            }
        }
    }

    private fun submit(edit: ((CardManagementState.Snapshot) -> String)?) {
        if (busy || closed) return
        val base = confirmed
        if (edit != null && base == null) return
        try { projected = if (edit == null) null else validate(CardManagementState.parse(edit(checkNotNull(base)))) }
        catch (error: Exception) {
            Toast.makeText(this, error.message ?: "无法修改功能", Toast.LENGTH_SHORT).show()
            render("更改会自动保存"); return
        }
        busy = true
        render(if (base == null) "正在读取卡片…" else "更改会自动保存")
        worker.execute {
            var result: CardManagementState.Snapshot? = null
            var message: String? = null
            try { result = validate(if (edit == null) repository.load() else repository.change(checkNotNull(base), edit)) }
            catch (error: Exception) {
                message = error.cause?.message ?: error.message ?: "卡片配置暂不可用，请重试"
                // A failed/stale write displays the actual provider value, never an optimistic switch state.
                if (edit != null) try { result = validate(repository.load()) } catch (_: Exception) {}
            }
            val catalog = if (backgrounds == null && cardType != null) try { CardBackgrounds.load(this, cardType!!) }
                catch (error: Exception) { android.util.Log.w("CardBackgrounds", "Cannot read background assets", error); emptyList() }
                catch (error: LinkageError) { android.util.Log.w("CardBackgrounds", "Cannot read background assets", error); emptyList() } else backgrounds
            val snapshot = result
            val failure = message
            main.post {
                if (closed || !alive()) return@post
                busy = false; confirmed = snapshot; projected = null
                backgrounds = catalog
                if (edit != null && failure != null) Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
                render(failure ?: "更改会自动保存")
                if (refreshPending) { refreshPending = false; refresh() }
            }
        }
    }

    private fun action(event: Bundle) {
        when (event.getString("action")) {
            "back" -> finish()
            "retry" -> refresh()
            "runtime" -> open(Intent(this, HostToolsActivity::class.java))
            "gestures" -> open(Intent(this, GestureAreaActivity::class.java))
            "management" -> open(Intent(this, CardManagementActivity::class.java))
            "background" -> if (!busy && cardType != null) {
                val name = event.getString("name") ?: return
                val strategy = event.getInt("strategy")
                if (backgrounds.orEmpty().none { it.getString("name") == name && it.getInt("strategy") == strategy }) return
                submit { snapshot -> CardFunctionState.background(snapshot, cardType!!, name, strategy) }
            }
            "toggle" -> if (!busy) {
                val type = event.getString("type") ?: return
                submit { snapshot -> cardType?.let { CardFunctionState.enabled(snapshot, it, type, event.getBoolean("value"), payMaximum) }
                    ?: CardManagementState.enabled(snapshot, type, event.getBoolean("value")) }
            }
            "move" -> if (!busy) {
                val type = event.getString("type") ?: return
                val target = event.getString("target") ?: return
                submit { snapshot -> cardType?.let { CardFunctionState.move(snapshot, it, type, target) }
                    ?: CardManagementState.move(snapshot, type, target) }
            }
            "settings" -> if (!busy) when (val type = event.getString("type")) {
                "pay", "camera" -> open(Intent(this, CardFunctionSettingsActivity::class.java)
                    .putExtra("mCardType", type))
                "dhu" -> open(Intent(this, DhuPaySettingsActivity::class.java))
                "staging" -> {
                    val intent = WalletCouponClient.managementIntent(this)
                    if (intent != null) open(intent)
                    else Toast.makeText(this, "钱包券管理入口暂不可用", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun open(intent: Intent) {
        try { startActivity(intent) }
        catch (_: RuntimeException) { Toast.makeText(this, "页面暂不可用，请确认对应应用已安装", Toast.LENGTH_SHORT).show() }
    }

    private fun render(message: String) {
        val view = root ?: return
        val authorized = RootAppSetup.canSave(this)
        val display = projected ?: confirmed
        val functions = cardType?.let { type -> display?.let { CardFunctionState.parse(it, type, payMaximum) } }
        val values = Bundle().apply {
            putBoolean("busy", busy && display == null)
            putBoolean("saving", busy && display != null)
            putBoolean("authorized", authorized)
            putBoolean("writable", display != null && authorized)
            putString("message", message)
            putInt("minimum", functions?.minimum ?: if (cardType == "camera") 4 else 1)
            putInt("maximum", functions?.maximum ?: if (cardType == null) Int.MAX_VALUE else if (cardType == "camera") 6 else payMaximum)
            if (cardType != null) {
                putParcelableArrayList("backgrounds", ArrayList(backgrounds.orEmpty()))
                val selected = display?.let { CardFunctionState.background(it, cardType!!) }
                putString("backgroundName", selected?.first); putInt("backgroundStrategy", selected?.second ?: 1)
            }
            putParcelableArrayList("cards", if (cardType != null) ArrayList(functions?.functions.orEmpty().map { function -> Bundle().apply {
                putString("type", function.type); putString("title", function.title); putBoolean("enabled", function.enabled)
            } }) else ArrayList(display?.cards.orEmpty().map { card -> Bundle().apply {
                putString("type", card.type); putString("title", card.title); putBoolean("enabled", card.enabled)
            } }))
        }
        DhuMaterialUi.renderCardManagement(view, values)
    }

    private fun validate(snapshot: CardManagementState.Snapshot): CardManagementState.Snapshot {
        cardType?.let { CardFunctionState.parse(snapshot, it, payMaximum) }
        return snapshot
    }
}
