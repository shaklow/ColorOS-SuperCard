package dev.local.supercardhost;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Shared by the standalone settings DEX bridge and the SystemUI framework adapter. */
public final class DhuCardConfiguration {
    public static final String TYPE = "dhu";
    public static final String TITLE_RESOURCE = "dhu_campus_card";
    private DhuCardConfiguration() { }

    static void defaults(JSONObject config) throws Exception {
        JSONArray cards = config.optJSONArray("cards");
        if (cards == null) return;
        for (int i = 0; i < cards.length(); i++) if (TYPE.equals(cards.getJSONObject(i).optString("type"))) return;
        cards.put(new JSONObject().put("type", TYPE).put("typeName", "东华校园卡")
                .put("typeNameResId", TITLE_RESOURCE).put("typeIconResId", "pay_card")
                .put("enable", true).put("skinName", "").put("functions", new JSONArray()));
    }

    static Object finishInitialization(Object initializer, Object config) throws Exception {
        if (config == null) return null;
        List<?> cards = (List<?>) config.getClass().getMethod("getCards").invoke(config);
        if (cards == null) return config;
        for (Object card : cards)
            if (TYPE.equals(card.getClass().getMethod("getType").invoke(card))) return config;
        Class<?> bean = initializer.getClass().getClassLoader().loadClass("com.vivo.card.model.CardSetBean$CardBean");
        Object campus = bean.getConstructor().newInstance();
        bean.getMethod("setType", String.class).invoke(campus, TYPE);
        bean.getMethod("setTypeName", String.class).invoke(campus, "东华校园卡");
        bean.getMethod("setTypeNameResId", String.class).invoke(campus, TITLE_RESOURCE);
        bean.getMethod("setTypeIconResId", String.class).invoke(campus, "pay_card");
        bean.getMethod("setEnable", boolean.class).invoke(campus, true);
        bean.getMethod("setFunctions", List.class).invoke(campus, new ArrayList<>());
        ArrayList<Object> updated = new ArrayList<>(cards); updated.add(campus);
        config.getClass().getMethod("setCards", List.class).invoke(config, updated);
        Object manager = field(initializer, "settingsManager");
        if (!Boolean.TRUE.equals(manager.getClass().getMethod("saveCardSetBean", config.getClass()).invoke(manager, config)))
            throw new IllegalStateException("Cannot save campus card registration");
        return config;
    }

    static boolean bindPreview(ImageView image, FrameLayout container, Object bean) throws Exception {
        if (!TYPE.equals(bean.getClass().getMethod("getType").invoke(bean))) return false;
        int width = Math.round(280 * image.getResources().getDisplayMetrics().density);
        int height = Math.round(450 * image.getResources().getDisplayMetrics().density);
        DhuPayView preview = new DhuPayView(image.getContext(), true);
        preview.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        preview.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        preview.draw(new Canvas(bitmap)); image.setImageBitmap(bitmap);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setVisibility(View.VISIBLE);
        container.removeAllViews(); container.setVisibility(View.GONE);
        image.setContentDescription("东华校园卡预览，设置授权链接后获取付款码");
        return true;
    }

    static void bindSettingsButton(Object holder, Object item) throws Exception {
        Object card = item.getClass().getMethod("getCardBean").invoke(item);
        if (card == null || !TYPE.equals(card.getClass().getMethod("getType").invoke(card))) return;
        View button = (View) field(holder, "buttonSet");
        button.setVisibility(View.VISIBLE);
        button.setOnClickListener(v -> button.getContext().startActivity(DhuPayView.settingsIntent(button.getContext())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
    }

    static Object field(Object target, String name) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
}
