package dev.local.supercardhost;

import android.content.Context;
import android.app.KeyguardManager;
import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Connection decisions run with real framework Bundle/JSON; vendor I/O is scripted. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36, manifest = Config.NONE)
public class WalletCouponClientTest {
    private final Context context = RuntimeEnvironment.getApplication();

    private class Peer extends WalletCouponClient.Connection {
        int pings, queries, warmups, closes;
        int readyAfter = 1;
        long clock;
        Bundle response = ok(), hello = status("connected");
        String warmupFailure;
        Exception queryFailure;
        boolean interrupt;
        boolean lockDuringQuery;
        Peer() { super(context); }
        @Override long now() { return clock; }
        @Override void pause() throws InterruptedException {
            if (interrupt) throw new InterruptedException("cancelled");
            clock += 200;
        }
        @Override void warmUp() { warmups++; bootstrapFailure = warmupFailure; }
        @Override Bundle exchange(String operation, long timeout) throws Exception {
            if ("ping".equals(operation)) {
                pings++;
                return pings >= readyAfter ? hello : new Bundle();
            }
            assertEquals("query", operation);
            queries++;
            if (lockDuringQuery) Shadows.shadowOf(context.getSystemService(KeyguardManager.class))
                    .setIsDeviceLocked(true);
            if (queryFailure != null) throw queryFailure;
            return response;
        }
        @Override public void close() { closes++; }
    }

    @Test public void runningWalletDoesNotRequireSeedlingProvider() throws Exception {
        Peer peer = new Peer();
        assertEquals("ok", query(peer).getString("status"));
        assertEquals(0, peer.warmups);
        assertEquals(1, peer.queries);
        assertEquals(1, peer.closes);
    }

    @Test public void coldStartWaitsForReceiverBeforeAnyNativeRead() throws Exception {
        Peer peer = new Peer(); peer.readyAfter = 4;
        query(peer);
        assertEquals(1, peer.warmups);
        assertEquals(4, peer.pings);
        assertEquals(1, peer.queries);
        assertEquals(1, peer.closes);
    }

    @Test public void deniedOptionalWarmupDoesNotRejectAReadyWallet() throws Exception {
        Peer peer = new Peer(); peer.readyAfter = 2; peer.warmupFailure = "bootstrap_denied";
        assertEquals("ok", query(peer).getString("status"));
        assertEquals(1, peer.queries);
    }

    @Test public void missingHookTimesOutWithoutQueryingAccount() throws Exception {
        Peer peer = new Peer(); peer.readyAfter = Integer.MAX_VALUE;
        assertEquals("bridge_unavailable", failure(peer).reason);
        assertEquals(0, peer.queries);
        assertTrue(peer.clock >= 8000 && peer.clock <= 8200);
        assertEquals(1, peer.closes);
    }

    @Test public void missingProviderAndMissingHookAreReportedTogether() throws Exception {
        Peer peer = new Peer(); peer.readyAfter = Integer.MAX_VALUE;
        peer.warmupFailure = "bootstrap_unavailable";
        assertEquals("bootstrap_unavailable", failure(peer).reason);
        assertEquals(0, peer.queries);
    }

    @Test public void oldWalletProcessCanAnswerAfterModuleUpdate() throws Exception {
        Peer peer = new Peer(); peer.hello = status("unavailable");
        peer.hello.putString("reason", "unsupported_operation");
        assertEquals("ok", query(peer).getString("status"));
        assertEquals(0, peer.warmups);
    }

    @Test public void nativeFailuresKeepTheirReasonAndNeverBecomeAnEmptyWallet() throws Exception {
        for (String reason : new String[]{"not_logged_in", "account_changed", "service_disabled",
                "account_unavailable", "native_unavailable", "busy", "timeout"}) {
            Peer peer = new Peer(); peer.response = status("unavailable");
            peer.response.putString("reason", reason);
            peer.response.putString("data", "{\"films\":[],\"performances\":[],\"coupons\":[]}");
            assertEquals(reason, failure(peer).reason);
            assertEquals(1, peer.queries);
            assertEquals(1, peer.closes);
        }
    }

    @Test public void nativeTimeoutCannotStartAnotherQuery() throws Exception {
        Peer peer = new Peer();
        peer.queryFailure = new WalletCouponClient.QueryFailure("transport_timeout", "private data");
        assertEquals("transport_timeout", failure(peer).reason);
        assertEquals(1, peer.queries);
        assertEquals(1, peer.closes);
    }

    @Test public void disappearingReceiverIsNotAnEmptyWallet() throws Exception {
        Peer peer = new Peer(); peer.response = null;
        assertEquals("bridge_unavailable", failure(peer).reason);
    }

    @Test public void malformedAndIncompletePayloadsAreRejected() throws Exception {
        for (String raw : new String[]{"broken", "{}", "{\"films\":[],\"performances\":[],\"coupons\":{}}"}) {
            Peer peer = new Peer(); peer.response.putString("data", raw);
            assertEquals("invalid_response", failure(peer).reason);
            assertEquals(1, peer.closes);
        }
    }

    @Test public void interruptionClosesConnectionAndNeverReadsTickets() throws Exception {
        Peer peer = new Peer(); peer.readyAfter = Integer.MAX_VALUE; peer.interrupt = true;
        assertThrows(InterruptedException.class, () -> query(peer));
        assertEquals(0, peer.queries);
        assertEquals(1, peer.closes);
    }

    @Test public void lockedDeviceCannotContactWallet() throws Exception {
        var keyguard = Shadows.shadowOf(context.getSystemService(KeyguardManager.class));
        keyguard.setIsDeviceLocked(true);
        try {
            Peer peer = new Peer();
            assertEquals("locked", failure(peer).reason);
            assertEquals(0, peer.pings);
            assertEquals(0, peer.queries);
        } finally { keyguard.setIsDeviceLocked(false); }
    }

    @Test public void lockingDuringQueryPreventsReturningTicketData() throws Exception {
        Peer peer = new Peer(); peer.lockDuringQuery = true;
        try {
            assertEquals("locked", failure(peer).reason);
            assertEquals(1, peer.queries);
            assertEquals(1, peer.closes);
        } finally {
            Shadows.shadowOf(context.getSystemService(KeyguardManager.class)).setIsDeviceLocked(false);
        }
    }

    @Test public void uiThreadCannotIssueWalletIpc() {
        Peer peer = new Peer();
        assertThrows(IllegalStateException.class, () -> WalletCouponClient.querySnapshot(context, peer));
        assertEquals(0, peer.pings);
        assertEquals(0, peer.queries);
    }

    @Test @Config(sdk = 31) public void oldAndroidFailsBeforeCallingNewIdentityApi() throws Exception {
        Peer peer = new Peer();
        assertEquals("unsupported_android", failure(peer).reason);
        assertEquals(0, peer.pings);
    }

    private Bundle query(Peer peer) throws Exception {
        return worker(() -> WalletCouponClient.querySnapshot(context, peer));
    }
    private WalletCouponClient.QueryFailure failure(Peer peer) {
        return assertThrows(WalletCouponClient.QueryFailure.class, () -> query(peer));
    }
    private static <T> T worker(Callable<T> work) throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try { return pool.submit(work).get(5, TimeUnit.SECONDS); }
        catch (ExecutionException wrapped) {
            if (wrapped.getCause() instanceof Exception) throw (Exception) wrapped.getCause();
            throw new AssertionError(wrapped.getCause());
        } finally { pool.shutdownNow(); }
    }
    private static Bundle status(String status) {
        Bundle result = new Bundle(); result.putString("status", status); return result;
    }
    private static Bundle ok() {
        Bundle result = status("ok");
        result.putString("data", "{\"films\":[],\"performances\":[],\"coupons\":[]}");
        result.putString("accountKey", "a".repeat(64));
        return result;
    }
}
