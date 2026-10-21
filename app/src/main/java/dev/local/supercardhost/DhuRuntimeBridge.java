package dev.local.supercardhost;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import io.github.libxposed.api.XposedModule;

/** Registers a separate card type while retaining the OEM container and lifecycle. */
public final class DhuRuntimeBridge {
    private static final Set<ClassLoader> INSTALLED = Collections.newSetFromMap(new WeakHashMap<>());
    private DhuRuntimeBridge() { }

    public static synchronized void install(XposedModule module, ClassLoader loader) throws Exception {
        if (INSTALLED.contains(loader)) return;
        Class<?> provider = loader.loadClass("com.vivo.card.manager.CardConfigProvider");
        for (String name : new String[]{"CARD_TYPE_MAPPING", "CARD_TYPE_MAPPING_REVERSE"}) {
            var field = provider.getDeclaredField(name); field.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String, String> mapping = (Map<String, String>) field.get(null);
            mapping.put(DhuCardConfiguration.TYPE, DhuCardConfiguration.TYPE);
        }
        Class<?> factory = loader.loadClass("com.vivo.card.manager.CardFactory");
        Class<?> shell = loader.loadClass("com.vivo.card.cards.base.view.DemoCardView");
        Class<?> base = loader.loadClass("com.vivo.card.cards.base.view.BaseCardView");
        Map<Object, WeakReference<DhuPayView>> views = Collections.synchronizedMap(new WeakHashMap<>());
        module.hook(method(factory, "createCard", String.class)).intercept(chain -> {
            if (!DhuCardConfiguration.TYPE.equals(chain.getArg(0))) return chain.proceed();
            Context context = (Context) DhuCardConfiguration.field(chain.getThisObject(), "context");
            // The bundled concrete shell implements the real OEM BaseCardView ABI, avoiding
            // duplicate compile-time stubs of OEM classes in the unified APK.
            ViewGroup card = (ViewGroup) shell.getConstructor(Context.class).newInstance(context);
            base.getMethod("setCardType", String.class).invoke(card, DhuCardConfiguration.TYPE);
            card.removeAllViews();
            DhuPayView content = new DhuPayView(context, false);
            card.addView(content, new FrameLayout.LayoutParams(-1, -1));
            views.put(card, new WeakReference<>(content));
            return card;
        });
        for (String event : new String[]{"onCardPackShown", "onCardPackHidden", "onCardPackHiding", "onCardPackDestroyed"}) {
            module.hook(method(base, event)).intercept(chain -> {
                Object result = chain.proceed();
                DhuPayView view = view(views, chain.getThisObject());
                if (view != null) view.setActive("onCardPackShown".equals(event));
                return result;
            });
        }
        module.hook(method(base, "onOuterPageScrollStateChanged", int.class, String.class)).intercept(chain -> {
            Object result = chain.proceed();
            DhuPayView view = view(views, chain.getThisObject());
            if (view != null) view.setActive(DhuCardConfiguration.TYPE.equals(chain.getArg(1)));
            return result;
        });
        module.hook(method(base, "notifyCardForegroundState", boolean.class)).intercept(chain -> {
            Object result = chain.proceed();
            DhuPayView view = view(views, chain.getThisObject());
            if (view != null) view.setActive((Boolean) chain.getArg(0));
            return result;
        });
        Class<?> initializer = loader.loadClass("com.vivo.card.data.CardDataInitializer");
        module.hook(method(initializer, "getOrLoadDefaultJson")).intercept(chain -> {
            String json = (String) chain.proceed();
            if (json == null || json.isEmpty()) return json;
            org.json.JSONObject config = new org.json.JSONObject(json);
            DhuCardConfiguration.defaults(config); return config.toString();
        });
        module.hook(method(initializer, "init")).intercept(chain ->
                DhuCardConfiguration.finishInitialization(chain.getThisObject(), chain.proceed()));
        Class<?> util = loader.loadClass("com.vivo.card.utils.CardUtil");
        module.hook(method(util, "getStringByResName", Context.class, String.class)).intercept(chain ->
                DhuCardConfiguration.TITLE_RESOURCE.equals(chain.getArg(1)) ? "东华校园卡" : chain.proceed());
        Class<?> adapter = loader.loadClass("com.vivo.card.adapter.CardManagerAdapter");
        Class<?> bean = loader.loadClass("com.vivo.card.model.CardSetBean$CardBean");
        module.hook(method(adapter, "bindImageCardView", ImageView.class, FrameLayout.class, bean)).intercept(chain ->
                DhuCardConfiguration.bindPreview((ImageView) chain.getArg(0), (FrameLayout) chain.getArg(1), chain.getArg(2))
                        ? null : chain.proceed());
        Class<?> holder = loader.loadClass("com.vivo.card.adapter.CardManagerAdapter$CardManagerHorizontalAdapter$CardListViewHolder");
        for (Method bind : holder.getDeclaredMethods()) if ("bind4UsingHolderItem".equals(bind.getName())) {
            bind.setAccessible(true);
            module.hook(bind).intercept(chain -> {
                Object result = chain.proceed(); DhuCardConfiguration.bindSettingsButton(chain.getThisObject(), chain.getArg(0)); return result;
            });
        }
        INSTALLED.add(loader);
    }

    private static Method method(Class<?> type, String name, Class<?>... args) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, args); method.setAccessible(true); return method;
    }

    private static DhuPayView view(Map<Object, WeakReference<DhuPayView>> views, Object card) {
        WeakReference<DhuPayView> reference = views.get(card);
        return reference == null ? null : reference.get();
    }
}
