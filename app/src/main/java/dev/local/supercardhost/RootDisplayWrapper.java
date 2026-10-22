package dev.local.supercardhost;

import android.hardware.display.VirtualDisplay;
import android.hardware.display.VirtualDisplayConfig;
import android.os.IBinder;
import android.view.Surface;
import java.lang.reflect.Method;

/** Adapts the root-owned callback token to the original SDK's framework wrapper. */
final class RootDisplayWrapper {
    private RootDisplayWrapper() { }

    static VirtualDisplay wrap(String name, int width, int height, int density, Surface surface,
            int flags, RootDisplayTransactions.DisplayToken created) throws Exception {
        if (created.id <= 0 || created.callback == null)
            throw new IllegalStateException("Root display was not created on a secondary display");
        Class<?> globalType = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Class<?> callbackType = Class.forName("android.hardware.display.IVirtualDisplayCallback");
        Class<?> stubType = Class.forName("android.hardware.display.IVirtualDisplayCallback$Stub");
        Object token = stubType.getMethod("asInterface", IBinder.class).invoke(null, created.callback);
        Method instance = globalType.getDeclaredMethod("getInstance"); instance.setAccessible(true);
        Object global = instance.invoke(null);
        VirtualDisplayConfig config = new VirtualDisplayConfig.Builder(name, width, height, density)
                .setSurface(surface).setFlags(flags).build();
        Method wrap = globalType.getDeclaredMethod("createVirtualDisplayWrapper",
                VirtualDisplayConfig.class, callbackType, int.class);
        wrap.setAccessible(true);
        VirtualDisplay result = (VirtualDisplay) wrap.invoke(global, config, token, created.id);
        if (result == null) throw new IllegalStateException("Cannot wrap root display " + created.id);
        return result;
    }
}
