package dev.local.supercardhost;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.UsingFieldData;

/** Resolves the native sidebar boundary without calling any native settings method. */
public final class SidebarDexResolver {
    private static final String SETTINGS = "com.oplus.smartsidebar.settings.";
    private static final String PREF = "androidx.preference.Preference";
    private static final String GROUP = "androidx.preference.PreferenceGroup";

    private SidebarDexResolver() { }

    /** Logical aliases never select the first overload. They refer to prevalidated Methods. */
    static final class Plan {
        final ClassLoader loader;
        final Map<String, Method> methods = new LinkedHashMap<>();
        final Map<String, Field> fields = new LinkedHashMap<>();
        Class<?> mainFragment, changeListener, clickListener;
        Plan(ClassLoader loader) { this.loader = loader; }
        Method method(Class<?> type, String alias) throws NoSuchMethodException {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                Method m = methods.get(c.getName() + "#" + alias);
                if (m != null) return m;
            }
            throw new NoSuchMethodException(type.getName() + "#" + alias);
        }
        Method method(String type, String alias) throws Exception { return method(loader.loadClass(type), alias); }
        Method optional(Class<?> type, String alias) {
            try { return method(type, alias); } catch (NoSuchMethodException ignored) { return null; }
        }
        void put(Class<?> owner, String alias, Method value) {
            value.setAccessible(true); methods.put(owner.getName() + "#" + alias, value);
        }
        void field(Class<?> owner, String alias, Field value) {
            value.setAccessible(true); fields.put(owner.getName() + "#" + alias, value);
        }
        Field field(Class<?> type, String alias) throws NoSuchFieldException {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                Field f = fields.get(c.getName() + "#" + alias); if (f != null) return f;
            }
            throw new NoSuchFieldException(type.getName() + "#" + alias);
        }
    }

    /** The runtime probe only reflects metadata. It does not install hooks or invoke business code. */
    public static Bundle probe(ClassLoader loader, boolean forceDex) {
        Bundle result = new Bundle();
        try (Session s = new Session(loader, forceDex)) {
            Plan p = s.ui();
            result.putString("status", "ok");
            result.putBoolean("sidebarEntriesReady", true);
            result.putString("mainFragment", p.mainFragment.getName());
            result.putInt("resolvedCount", p.methods.size() + p.fields.size());
        } catch (Throwable failure) {
            result.putString("status", "unavailable");
            result.putBoolean("sidebarEntriesReady", false);
            result.putString("error", failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
        return result;
    }

    static Plan resolveUi(ClassLoader loader) throws Exception {
        try (Session s = new Session(loader, false)) { return s.ui(); }
    }
    private static final class Session implements AutoCloseable {
        final ClassLoader loader;
        final boolean force;
        ObfuscationResolver dex;
        Session(ClassLoader loader, boolean force) { this.loader = loader; this.force = force; }
        ObfuscationResolver dex() throws Exception {
            if (dex == null) dex = ObfuscationResolver.open(loader);
            return dex;
        }
        Class<?> type(String name) throws ClassNotFoundException { return loader.loadClass(name); }

        Method resolve(Class<?> owner, String alias, Class<?> result, Class<?>[] args,
                MethodMatcher fallback) throws Exception {
            if (!force) try {
                Method m = exact(owner, alias, args);
                if (m.getReturnType() == result) return m;
            } catch (NoSuchMethodException ignored) { }
            if (fallback == null) return uniqueShape(owner, result, args, null);
            return dex().uniqueMethod(owner.getName() + "#" + alias, fallback,
                    m -> m.getDeclaringClass().isAssignableFrom(owner)
                            && !m.getDeclaringClass().isInterface()
                            && m.getReturnType() == result && Arrays.equals(args, m.getParameterTypes()));
        }
        Method bind(Plan p, Class<?> owner, String alias, Class<?> result, Class<?>[] args,
                MethodMatcher fallback) throws Exception {
            Method m = resolve(owner, alias, result, args, fallback); p.put(owner, alias, m); return m;
        }
        Method noarg(Plan p, Class<?> owner, String alias, MethodMatcher fallback) throws Exception {
            return bind(p, owner, alias, void.class, new Class<?>[0], fallback);
        }
        Method linked(Method source, java.util.function.Predicate<Method> check, String purpose) throws Exception {
            Map<String, Method> found = new LinkedHashMap<>();
            for (MethodData d : dex().bridge().getMethodData(source).getInvokes()) {
                // DEX invoke lists include constructors, which are not java.lang.reflect.Method.
                if (d.getDescriptor().contains("-><")) continue;
                Method m;
                try { m = d.getMethodInstance(loader); }
                catch (ReflectiveOperationException | LinkageError unavailable) { continue; }
                if (check.test(m)) { m.setAccessible(true); found.put(d.getDescriptor(), m); }
            }
            if (found.size() != 1) throw new NoSuchMethodException(purpose + " invoke candidates=" + found.size());
            return found.values().iterator().next();
        }
        Field readField(Method getter, Class<?> owner, Class<?> fieldType, String purpose) throws Exception {
            Map<String, Field> found = new LinkedHashMap<>();
            for (UsingFieldData d : dex().bridge().getMethodData(getter).getUsingFields()) {
                Field f = d.getField().getFieldInstance(loader);
                if (f.getDeclaringClass() == owner && f.getType() == fieldType
                        && !Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true); found.put(f.getName(), f);
                }
            }
            if (found.size() != 1) throw new NoSuchFieldException(purpose + " fields=" + found.size());
            return found.values().iterator().next();
        }
        Plan ui() throws Exception {
            Plan p = new Plan(loader);
            Class<?> pref = type(PREF), group = type(GROUP);
            Class<?> fragment = type("androidx.fragment.app.Fragment");
            if (!force) try { p.mainFragment = type(SETTINGS + "i"); }
            catch (ClassNotFoundException ignored) { }
            if (p.mainFragment == null || !fragment.isAssignableFrom(p.mainFragment)) {
                p.mainFragment = dex().uniqueClass("sidebar main fragment",
                        ClassMatcher.create().usingStrings("EdgePanelSettingsFragment", "key_root", "key_toggle"),
                        c -> fragment.isAssignableFrom(c));
            }
            bind(p, p.mainFragment, "X", void.class, new Class<?>[]{boolean.class},
                    mainUpdate().declaredClass(p.mainFragment));
            bind(p, group, "X0", pref, new Class<?>[]{CharSequence.class}, findPreference());
            Method add = bind(p, group, "W0", boolean.class, new Class<?>[]{pref}, addPreference());
            bind(p, pref, "u", Context.class, new Class<?>[0],
                    shape(pref, Context.class));
            Method summary = bind(p, pref, "L0", void.class, new Class<?>[]{CharSequence.class},
                    summary());
            Method title;
            if (!force) try { title = exact(pref, "O0", CharSequence.class); }
            catch (NoSuchMethodException missing) { title = null; }
            else title = null;
            if (title == null || title.getReturnType() != void.class) title = uniqueShape(pref, void.class,
                    new Class<?>[]{CharSequence.class}, m -> !m.equals(summary));
            p.put(pref, "O0", title);
            Method order;
            if (!force) try { order = exact(pref, "I0", int.class); }
            catch (NoSuchMethodException missing) { order = null; }
            else order = null;
            if (order == null || order.getReturnType() != void.class) order = linked(add,
                    m -> m.getDeclaringClass() == pref && signature(m, void.class, int.class), "preference order");
            p.put(pref, "I0", order);
            bind(p, pref, "P0", void.class, new Class<?>[]{boolean.class},
                    shape(pref, void.class, boolean.class).modifiers(Modifier.FINAL));
            bind(p, pref, "D0", void.class, new Class<?>[]{Intent.class}, shape(pref, void.class, Intent.class));
            p.changeListener = listener(pref, pref, Object.class);
            p.clickListener = listener(pref, pref);
            bind(p, pref, "G0", void.class, new Class<?>[]{p.changeListener}, shape(pref, void.class, p.changeListener));
            bind(p, pref, "H0", void.class, new Class<?>[]{p.clickListener}, shape(pref, void.class, p.clickListener));
            Class<?> toggle = type("com.coui.appcompat.preference.COUISwitchPreference");
            Method checked = null;
            if (!force) try { checked = exact(toggle, "W0", boolean.class); }
            catch (NoSuchMethodException ignored) { }
            if (checked == null || checked.getReturnType() != void.class) {
                Method clickToggle = dex().uniqueMethod("sidebar native toggle callback",
                        MethodMatcher.create().declaredClass(p.mainFragment).usingStrings("onClickToggle"), m -> true);
                checked = linked(clickToggle, m -> m.getDeclaringClass().isAssignableFrom(toggle)
                        && signature(m, void.class, boolean.class), "switch checked");
            }
            p.put(toggle, "W0", checked);
            Field key = null, persistent = null;
            if (!force) {
                try { key = pref.getDeclaredField("s"); if (key.getType() != String.class) key = null; }
                catch (NoSuchFieldException ignored) { }
                try { persistent = pref.getDeclaredField("y"); if (persistent.getType() != boolean.class) persistent = null; }
                catch (NoSuchFieldException ignored) { }
            }
            if (key == null) {
                Method getKey = linked(p.method(group, "X0"),
                        m -> m.getDeclaringClass() == pref && signature(m, String.class), "preference key getter");
                key = readField(getKey, pref, String.class, "preference key");
            }
            if (persistent == null) {
                Method persistBoolean = dex().uniqueMethod("preference persist boolean",
                        shape(pref, boolean.class, boolean.class).addInvoke(
                                "Landroid/content/SharedPreferences$Editor;->putBoolean(Ljava/lang/String;Z)Landroid/content/SharedPreferences$Editor;"),
                        m -> m.getDeclaringClass() == pref);
                Method shouldPersist = linked(persistBoolean,
                        m -> m.getDeclaringClass() == pref && signature(m, boolean.class), "preference shouldPersist");
                List<Field> candidates = new ArrayList<>();
                for (MethodData d : dex().bridge().getMethodData(shouldPersist).getInvokes()) {
                    if (!d.getDeclaredClassName().equals(PREF) || !d.getReturnTypeName().equals("boolean") || d.getParamCount() != 0) continue;
                    Method m = d.getMethodInstance(loader);
                    // hasKey calls TextUtils.isEmpty; the persistent getter is a pure field read.
                    if (d.getInvokes().isEmpty()) candidates.add(readField(m, pref, boolean.class, "persistent getter"));
                }
                if (candidates.size() != 1) throw new NoSuchFieldException("persistent candidates=" + candidates.size());
                persistent = candidates.get(0);
            }
            p.field(pref, "key", key); p.field(pref, "persistent", persistent);
            for (Class<?> f : new Class<?>[]{p.mainFragment, type(SETTINGS + "FloatBarSettingsFragment"),
                    type(SETTINGS + "FloatBarSettingsWithoutGestureFragment")}) {
                // PreferenceFragment's single CharSequence->Preference boundary may be inherited.
                bind(p, f, "a", pref, new Class<?>[]{CharSequence.class},
                        MethodMatcher.create().returnType(PREF).paramTypes("java.lang.CharSequence")
                                .declaredClass(ClassMatcher.create().className("androidx.preference", org.luckypray.dexkit.query.enums.StringMatchType.StartsWith)));
            }
            return p;
        }

        @Override public void close() { if (dex != null) dex.close(); }
    }

    private static Class<?> listener(Class<?> preference, Class<?>... args) throws Exception {
        Map<String, Class<?>> candidates = new LinkedHashMap<>();
        for (Field f : preference.getDeclaredFields()) {
            Class<?> c = f.getType(); if (!c.isInterface()) continue;
            Method[] methods = c.getDeclaredMethods();
            if (methods.length == 1 && signature(methods[0], boolean.class, args)) candidates.put(c.getName(), c);
        }
        if (candidates.size() != 1) throw new ClassNotFoundException("preference listener candidates=" + candidates.size());
        return candidates.values().iterator().next();
    }
    private static Method uniqueShape(Class<?> owner, Class<?> ret, Class<?>[] args,
            java.util.function.Predicate<Method> extra) throws Exception {
        List<Method> found = new ArrayList<>();
        for (Method m : owner.getDeclaredMethods()) if (signature(m, ret, args) && (extra == null || extra.test(m))) found.add(m);
        if (found.size() != 1) throw new NoSuchMethodException(owner.getName() + " signature candidates=" + found.size());
        Method m = found.get(0); m.setAccessible(true); return m;
    }
    static Method exact(Class<?> owner, String name, Class<?>... args) throws NoSuchMethodException {
        for (Class<?> c = owner; c != null; c = c.getSuperclass()) try {
            Method m = c.getDeclaredMethod(name, args); m.setAccessible(true); return m;
        } catch (NoSuchMethodException ignored) { }
        throw new NoSuchMethodException(owner.getName() + "#" + name);
    }
    static boolean signature(Method m, Class<?> ret, Class<?>... args) {
        return m.getReturnType() == ret && Arrays.equals(m.getParameterTypes(), args);
    }
    private static MethodMatcher shape(Class<?> owner, Class<?> ret, Class<?>... args) {
        return MethodMatcher.create().declaredClass(owner).returnType(ret).paramTypes(args);
    }
    private static MethodMatcher mainUpdate() { return MethodMatcher.create().returnType("void").paramTypes("boolean")
            .usingStrings("updatePreferences", "key_panel_category", "key_style_category"); }
    private static MethodMatcher findPreference() { return MethodMatcher.create().declaredClass(GROUP).returnType(PREF)
            .paramTypes("java.lang.CharSequence").usingStrings("Key cannot be null"); }
    private static MethodMatcher addPreference() { return MethodMatcher.create().declaredClass(GROUP).returnType("boolean")
            .paramTypes(PREF).usingStrings("Found duplicated key: \""); }
    private static MethodMatcher summary() { return MethodMatcher.create().declaredClass(PREF).returnType("void")
            .paramTypes("java.lang.CharSequence").usingStrings("Preference already has a SummaryProvider set."); }
    /** Host-side metadata probe: only reads the APK, prints method descriptors and ambiguity. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: SidebarDexResolver <SmartSideBar.apk>");
        try (ObfuscationResolver dex = ObfuscationResolver.openApk(args[0], null)) {
            Map<String, MethodMatcher> checks = new LinkedHashMap<>();
            checks.put("mainUpdate", mainUpdate()); checks.put("preferenceFind", findPreference());
            checks.put("preferenceAdd", addPreference()); checks.put("preferenceSummary", summary());
            for (Map.Entry<String, MethodMatcher> e : checks.entrySet()) {
                metadata(dex, e.getKey(), e.getValue());
            }
        }
    }
    private static MethodData metadata(ObfuscationResolver dex, String purpose, MethodMatcher matcher) throws Exception {
        Map<String, MethodData> found = new LinkedHashMap<>();
        for (MethodData d : dex.bridge().findMethod(FindMethod.create().matcher(matcher))) found.put(d.getDescriptor(), d);
        if (found.size() != 1) throw new NoSuchMethodException(purpose + " candidates=" + found.keySet());
        MethodData result = found.values().iterator().next();
        System.out.println(purpose + "=" + result.getDescriptor());
        return result;
    }
}
