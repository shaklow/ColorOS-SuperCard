package dev.local.supercardhost;

import android.os.Bundle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Main-thread session. Owns requests and timers, and retains no Activity, preference or View. */
public final class FloatSettingsSession {
    public interface Result { void complete(Bundle state, String error); }
    public interface Requests { void send(String operation, Bundle values, Result result); }
    public interface Scheduler {
        long now();
        long epoch();
        void execute(Runnable action);
        void after(Runnable action, long delay);
        void cancel(Runnable action);
    }
    public interface Listener { void changed(); void failed(String message, String error); }

    private final Requests requests;
    private final Scheduler scheduler;
    private Listener listener;
    private final String owner = UUID.randomUUID().toString();
    private final LinkedHashMap<String, Bundle> pending = new LinkedHashMap<>();
    private FloatSettingsState state = FloatSettingsState.defaults(), confirmed;
    private boolean loaded, connected, warned, active, closed, querying, saving, previewSending, previewAgain, previewEnded;
    private int tracking = -1;
    private long version, queryTicket, previewTicket, previewEpoch, lastPreview, failureSince = -1;
    private final Runnable saveTask = this::drain;
    private final Runnable previewTask = this::preview;
    private final Runnable reconnectTask = this::query;
    private final Runnable leaseTask = () -> { if (!loaded || !connected) query(); else preview(); lease(); };

    public FloatSettingsSession(Requests requests, Scheduler scheduler, Listener listener) {
        this.requests = requests; this.scheduler = scheduler; this.listener = listener;
    }
    FloatSettingsState state() { return state; }
    boolean loaded() { return loaded; }
    public Bundle snapshot() {
        Bundle value = state.snapshot(); value.putBoolean("loaded", loaded); value.putBoolean("connected", connected);
        value.putBoolean("saving", saving || !pending.isEmpty());
        value.putInt("position", state.progress(0)); value.putInt("length", state.progress(1)); value.putInt("opacity", state.progress(2));
        return value;
    }

    public void resume() {
        if (closed || active) return;
        active = true; previewEpoch = Math.max(previewEpoch + 1, scheduler.epoch()); previewEnded = false;
        query(); schedulePreview(); lease();
    }
    public void pause() {
        if (closed) return;
        active = false; tracking = -1; queryTicket++; querying = false;
        previewTicket++; previewSending = false; previewAgain = false;
        cancelTasks(); drain(); endPreview();
    }
    public void close() {
        if (closed) return;
        pause(); closed = true; listener = null;
    }
    private void cancelTasks() {
        scheduler.cancel(previewTask); scheduler.cancel(leaseTask);
        scheduler.cancel(saveTask); scheduler.cancel(reconnectTask);
    }
    void startTracking(int kind) { if (editable()) tracking = kind; }
    void stopTracking() { tracking = -1; drain(); }
    private boolean editable() { return active && loaded && !closed; }

    void range(int kind, int progress) {
        if (!editable()) return;
        state = state.range(kind, progress); version++;
        pending.put("range", state.range()); edited(600);
    }
    void range(float top, float bottom) {
        if (!editable()) return;
        state = state.range(top, bottom); version++; pending.put("range", state.range()); edited(600);
    }
    void opacity(float index) {
        if (!editable()) return;
        state = state.opacity(index); version++;
        Bundle value = new Bundle(); value.putFloat("value", state.opacity);
        pending.put("handleOpacity", value); edited(350);
    }
    void visible(boolean value) { toggle("handleVisible", value); }
    void autoHide(boolean value) { toggle("handleAutoHide", value); }
    void unlocked(boolean value) { toggle("unlocked", value); }
    void lockStyle(int value) {
        if (!editable() || value < 0 || value > 3) return;
        state = state.lockStyle(value); version++;
        Bundle values = new Bundle(); values.putInt("value", value); pending.put("lockStyle", values);
        changed(); schedulePreview(); drain();
    }
    private void toggle(String operation, boolean value) {
        if (!editable()) return;
        state = operation.equals("handleVisible") ? state.visible(value) : operation.equals("unlocked") ? state.unlocked(value) : state.autoHide(value); version++;
        Bundle values = new Bundle(); values.putBoolean("value", value); pending.put(operation, values);
        changed(); schedulePreview(); drain();
    }
    void reset() {
        if (!editable()) return;
        tracking = -1; state = state.reset(); version++;
        pending.put("range", state.range());
        Bundle value = new Bundle(); value.putFloat("value", state.opacity); pending.put("handleOpacity", value);
        changed(); schedulePreview(); drain();
    }
    private void edited(long delay) {
        changed(); schedulePreview(); scheduler.cancel(saveTask); scheduler.after(saveTask, delay);
    }

    public void query() {
        if (!active || querying || closed || saving || !pending.isEmpty()) return;
        querying = true; long ticket = ++queryTicket, revision = version;
        send("query", null, (value, error) -> {
            if (closed || ticket != queryTicket) return;
            querying = false;
            FloatSettingsState next = FloatSettingsState.read(value);
            if (error != null || next == null) { readFailure("暂时无法读取卡包浮标设置", error); reconnect(); return; }
            connected(); confirmed = next;
            if (revision == version && !saving && pending.isEmpty()) state = next;
            loaded = true; changed(); schedulePreview();
        });
    }
    private void drain() {
        scheduler.cancel(saveTask);
        if (!loaded || saving || pending.isEmpty()) return;
        Map.Entry<String, Bundle> entry = pending.entrySet().iterator().next();
        String operation = entry.getKey(); Bundle values = new Bundle(entry.getValue()); pending.remove(operation);
        saving = true; long revision = version;
        send(operation, values, (value, error) -> {
            saving = false; FloatSettingsState next = FloatSettingsState.read(value);
            if (error != null || next == null) {
                pending.clear(); if (confirmed != null) state = confirmed;
                changed(); schedulePreview(); failed("保存失败，已恢复已读取的设置", error); reconnect(); return;
            }
            confirmed = next;
            if (revision == version && pending.isEmpty() && tracking < 0) { state = next; changed(); schedulePreview(); }
            // Accepted edits finish even after pause/close; closed sessions cannot notify a UI.
            drain();
        });
    }
    private void schedulePreview() {
        if (!active || !loaded || closed) return;
        scheduler.cancel(previewTask); scheduler.after(previewTask, Math.max(0, 50 - (scheduler.now() - lastPreview)));
    }
    private Bundle ownership() {
        Bundle value = new Bundle(); value.putString("previewOwner", owner); value.putLong("previewEpoch", previewEpoch); return value;
    }
    private void preview() {
        if (!active || !loaded || closed) return;
        if (previewSending) { previewAgain = true; return; }
        Bundle value = state.preview(); value.putAll(ownership()); value.putLong("previewDeadline", scheduler.now() + 5000);
        previewSending = true; lastPreview = scheduler.now(); long ticket = ++previewTicket;
        send("preview", value, (result, error) -> {
            if (closed || !active || ticket != previewTicket) return;
            previewSending = false;
            if (error != null) { readFailure("浮标实时预览暂不可用", error); reconnect(); } else connected();
            if (previewAgain) { previewAgain = false; schedulePreview(); }
        });
    }
    private void endPreview() {
        if (previewEpoch == 0 || previewEnded) return;
        previewEnded = true;
        // Server-side ownership also rejects an old end or a preview arriving after this end.
        send("previewEnd", ownership(), (value, error) -> { });
    }
    private void reconnect() {
        if (!active || closed) return;
        scheduler.cancel(reconnectTask); scheduler.after(reconnectTask, 1000);
    }
    private void lease() {
        scheduler.cancel(leaseTask); if (active && !closed) scheduler.after(leaseTask, 3000);
    }
    private void connected() { connected = true; failureSince = -1; warned = false; scheduler.cancel(reconnectTask); }
    private void readFailure(String message, String error) {
        connected = false;
        if (failureSince < 0) failureSince = scheduler.now();
        if (!warned && scheduler.now() - failureSince >= 5000 && active && !closed) { failed(message, error); warned = true; }
    }
    private void changed() { if (active && !closed && listener != null) listener.changed(); }
    private void failed(String message, String error) { if (active && !closed && listener != null) listener.failed(message, error); }
    private void send(String operation, Bundle values, Result result) {
        AtomicBoolean completed = new AtomicBoolean();
        try {
            requests.send(operation, values == null ? null : new Bundle(values), (value, error) -> {
                if (!completed.compareAndSet(false, true)) return;
                Bundle snapshot = value == null ? null : new Bundle(value);
                scheduler.execute(() -> result.complete(snapshot, error));
            });
        } catch (RuntimeException | LinkageError error) {
            if (completed.compareAndSet(false, true)) scheduler.execute(() -> result.complete(null, error.toString()));
        }
    }
}
