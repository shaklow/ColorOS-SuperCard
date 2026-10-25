package dev.local.supercardhost;

import android.app.Application;
import android.app.Instrumentation;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/** Loads the original card plugin inside the explicitly scoped SystemUI process. */
public final class VectorEntry extends XposedModule {
    static VectorEntry current;
    private static final String TAG = "SuperCardVector";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String MODULE_PACKAGE = "dev.local.supercardhost";
    private final AtomicBoolean hookInstalled = new AtomicBoolean();

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if ("com.tencent.mm".equals(param.getPackageName()) && param.isFirstPackage()) {
            WechatCodeHook.install(this, param.getClassLoader());
            return;
        }
        if ("com.finshell.wallet".equals(param.getPackageName()) && param.isFirstPackage()) {
            // The wallet's AppComponentFactory may replace the default loader.
            // Native account/ticket reflection needs the final application loader.
            try { WalletCouponBridge.install(this, param.getClassLoader()); }
            catch (Throwable error) { Log.e(TAG, "Unable to install wallet bridge", error); }
            return;
        }
        if ("com.coloros.smartsidebar".equals(param.getPackageName()) && param.isFirstPackage()) {
            try {
                SidebarIntegration.install(this, param.getClassLoader());
            }
            catch (Throwable error) { Log.e(TAG, "Unable to install sidebar settings", error); }
        }
    }

    @Override
    public void onPackageLoaded(PackageLoadedParam param) {
        try {
            if ("com.oplus.aimemory".equals(param.getPackageName()) && param.isFirstPackage()) {
                MemoryImportBridge.install(this, param.getDefaultClassLoader());
                return;
            }
            if (("com.vivo.card".equals(param.getPackageName())
                    || MODULE_PACKAGE.equals(param.getPackageName())) && param.isFirstPackage()) {
                OriginalAppCompat.install(this, param.getDefaultClassLoader(), param.getApplicationInfo());
                return;
            }
            if (!SYSTEM_UI.equals(param.getPackageName()) || !param.isFirstPackage()) return;
            current = this;
            RuntimeStatus.framework = getFrameworkName();
            RuntimeStatus.version = getFrameworkVersion();
            if (!hookInstalled.compareAndSet(false, true)) return;

            Method method = Instrumentation.class.getDeclaredMethod(
                    "callApplicationOnCreate", Application.class);
            hook(method).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    // Preserve the original Application lifecycle before scheduling our work.
                    Object result = chain.proceed();
                    try {
                        Object candidate = chain.getArg(0);
                        if (candidate instanceof Application) {
                            scheduleStartup((Application) candidate);
                        }
                    } catch (Throwable error) {
                        Log.e(TAG, "Unable to schedule card runtime", error);
                    }
                    return result;
                }
            });
            Log.i(TAG, "Installed SystemUI application bootstrap");
            RuntimeStatus.hooked = true;
        } catch (Throwable error) {
            hookInstalled.set(false);
            Log.e(TAG, "Unable to install SystemUI bootstrap", error);
        }
    }

    private void scheduleStartup(Application application) {
        RuntimeBootstrap.schedule(application);
    }
}
