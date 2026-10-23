package dev.local.supercardhost;

/** SystemUI-side ownership fence, independent of Android UI objects and persistent settings. */
final class FloatPreviewLease {
    private String owner;
    private int uid;
    private long epoch;
    private boolean ended = true;

    boolean preview(String nextOwner, long nextEpoch, int sender, long nowNanos, long now, long deadline) {
        validate(nextOwner, nextEpoch, nowNanos);
        if (deadline <= now || deadline - now > 5000) throw new IllegalArgumentException("Gesture preview expired");
        if (nextEpoch < epoch || (nextEpoch == epoch && (ended || sender != uid || !nextOwner.equals(owner)))) return false;
        owner = nextOwner; uid = sender; epoch = nextEpoch; ended = false;
        return true;
    }
    boolean end(String nextOwner, long nextEpoch, int sender, long nowNanos) {
        validate(nextOwner, nextEpoch, nowNanos);
        if (nextEpoch < epoch || (nextEpoch == epoch && (ended || sender != uid || !nextOwner.equals(owner)))) return false;
        owner = nextOwner; uid = sender; epoch = nextEpoch; ended = true;
        return true;
    }
    private static void validate(String owner, long epoch, long nowNanos) {
        if (owner == null || owner.isEmpty() || owner.length() > 128 || epoch <= 0 || epoch > nowNanos)
            throw new IllegalArgumentException("Invalid gesture preview owner");
    }
}
