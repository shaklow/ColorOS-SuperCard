package dev.local.supercardhost;

/** The existing v1 wire contract, shared by SystemUI and the root endpoint. */
final class RootDisplayProtocol {
    static final String ACTION = "dev.local.supercardhost.ROOT_DISPLAY_SERVICE";
    static final String DESCRIPTOR = "dev.local.supercardhost.RootDisplay.v1";
    static final int HELLO = 1;
    static final int CREATE = 2;
    static final int RELEASE = 3;
    static final int DISPLAY_FLAGS = 19917;

    private RootDisplayProtocol() { }
}
