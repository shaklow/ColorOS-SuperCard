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

/** Management presentation only. The original editors and runtime cards keep their ownership. */
class CardManagementActivity : M3eSettingsActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var repository: CardManagementRepository
    private var root: View? = null
    private var confirmed: CardManagementState.Snapshot? = null
    private var busy = false
    private var refreshPending = false
    private var observing = false
    private var closed = false
    private val observer = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) { refresh() }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = "卡片管理"
        repository = CardManagementRepository(CardManagementStorage(this))
        try {
            root = DhuMaterialUi.cardManagement(this)
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

    private fun submit(edit: ((CardManagementState.Snapshot) -> String)?) {
        if (busy || closed) return
        val base = confirmed
        if (edit != null && base == null) return
        busy = true
        render(if (edit == null) "正在读取卡片…" else "正在保存…")
        worker.execute {
            var result: CardManagementState.Snapshot? = null
            var message: String? = null
            try { result = if (edit == null) repository.load() else repository.change(checkNotNull(base), edit) }
            catch (error: Exception) {
                message = error.cause?.message ?: error.message ?: "卡片配置暂不可用，请重试"
                // A failed/stale write displays the actual provider value, never an optimistic switch state.
                if (edit != null) try { result = repository.load() } catch (_: Exception) {}
            }
            val snapshot = result
            val failure = message
            main.post {
                if (closed || !alive()) return@post
                busy = false; confirmed = snapshot
                if (edit != null && failure != null) Toast.makeText(this, failure, Toast.LENGTH_LONG).show()
                render(failure ?: if (edit != null) "已保存，卡片将按新设置显示" else "拖动卡片或使用上移、下移调整顺序")
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
            "toggle" -> if (!busy) {
                val type = event.getString("type") ?: return
                submit { CardManagementState.enabled(it, type, event.getBoolean("value")) }
            }
            "move" -> if (!busy) {
                val type = event.getString("type") ?: return
                val target = event.getString("target") ?: return
                submit { CardManagementState.move(it, type, target) }
            }
            "settings" -> if (!busy) when (val type = event.getString("type")) {
                "pay", "camera" -> open(Intent().setClassName(packageName, "com.vivo.card.setting.CardEditActivity")
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
        val values = Bundle().apply {
            putBoolean("busy", busy)
            putBoolean("authorized", authorized)
            putBoolean("writable", confirmed != null && authorized)
            putString("message", message)
            putParcelableArrayList("cards", ArrayList(confirmed?.cards.orEmpty().map { card -> Bundle().apply {
                putString("type", card.type); putString("title", card.title); putBoolean("enabled", card.enabled)
            } }))
        }
        DhuMaterialUi.renderCardManagement(view, values)
    }
}
