package dev.local.supercardhost;

import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

/** Publishes a live endpoint/nonce together and rejects work from an older connection. */
final class RootDisplayConnection {
    static final class Session {
        final IBinder endpoint;
        final String nonce;
        private IBinder.DeathRecipient death;
        private boolean dead;
        private Session(IBinder endpoint, String nonce) { this.endpoint = endpoint; this.nonce = nonce; }
    }

    private final IBinder lifetime = new Binder();
    private final Handler main;
    private final Runnable disconnected;
    private Session current;
    private long revision;

    RootDisplayConnection(Handler main, Runnable disconnected) {
        this.main = main; this.disconnected = disconnected;
    }

    synchronized Session snapshot() {
        return current != null && !current.dead && current.endpoint.isBinderAlive() ? current : null;
    }

    /** Main-thread announcements serialize publication with queued disconnect effects. */
    synchronized boolean connect(IBinder endpoint, String nonce) throws RemoteException {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("Connect root display endpoints on main");
        if (endpoint == null || nonce == null || !nonce.matches("[0-9a-f]{32}") || !endpoint.isBinderAlive())
            throw new IllegalArgumentException("A live endpoint and valid nonce are required");
        if (snapshot() != null && current.endpoint.equals(endpoint) && current.nonce.equals(nonce)) return false;
        RootDisplayTransactions.hello(endpoint, nonce, lifetime);
        Session next = new Session(endpoint, nonce);
        next.death = () -> died(next);
        endpoint.linkToDeath(next.death, 0);
        if (next.dead || !endpoint.isBinderAlive()) {
            unlink(next);
            throw new IllegalStateException("Root display endpoint died during connection");
        }
        Session previous = current;
        current = next; revision++;
        if (previous != null) unlink(previous);
        return true;
    }

    private void died(Session session) {
        final long lostRevision;
        synchronized (this) {
            session.dead = true;
            if (current != session) return;
            current = null; lostRevision = ++revision;
        }
        main.post(() -> {
            synchronized (RootDisplayConnection.this) {
                if (current != null || revision != lostRevision) return;
            }
            // connect() also runs on main, so no replacement can interleave here.
            // Effects run outside the connection lock: they may enter window/Binder code.
            disconnected.run();
        });
    }

    private static void unlink(Session session) {
        try { session.endpoint.unlinkToDeath(session.death, 0); }
        catch (java.util.NoSuchElementException ignored) { /* Death was already delivered. */ }
    }
}
