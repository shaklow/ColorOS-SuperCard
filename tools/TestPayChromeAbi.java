import java.io.BufferedInputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;
import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.ClassDef;
import org.jf.dexlib2.iface.Field;
import org.jf.dexlib2.iface.Method;

/** Checks the real bundled PAY ABI used by the appearance-only hook, without loading vendor code. */
public final class TestPayChromeAbi {
    private static final String CARD = "Lcom/vivo/card/cards/cardpay/PayCardView;";
    private static final String ADAPTER = "Lcom/vivo/card/cards/cardpay/adapter/PayCardAdapter;";
    private static final String HOLDER = "Lcom/vivo/card/cards/cardpay/adapter/PayCardAdapter$ViewHolder;";
    public static void main(String[] args) throws Exception {
        Map<String, ClassDef> classes = new HashMap<>();
        try (ZipFile zip = new ZipFile(Path.of(args[0]).toFile())) {
            for (var entry : Collections.list(zip.entries())) {
                if (!entry.getName().matches("classes[0-9]*\\.dex")) continue;
                var dex = DexBackedDexFile.fromInputStream(Opcodes.forApi(36), new BufferedInputStream(zip.getInputStream(entry)));
                for (ClassDef type : dex.getClasses()) if ((Set.of(CARD, ADAPTER, HOLDER, "Lcom/vivo/card/framework/virtual/VirtualViewManager;", "Lcom/vivo/card/cards/cardpay/virtual/PayVirtualViewManager;").contains(type.getType()) || type.getType().equals("Lcom/vivo/card/cards/cardcamera/handler/CameraCardEventHandler;") || type.getType().equals("Lcom/vivo/card/cards/base/view/BaseCardView;") || type.getType().equals("Lcom/vivo/card/model/CardSetBean$CardBean$FunctionBean;"))) {
                    if (classes.put(type.getType(), type) != null) throw new AssertionError("Duplicate PAY class");
                }
            }
            int checks = 0;
            for (String method : List.of("initViews", "setSkinDataToCard", "updateCurrentSkin", "onAttachedToWindow")) {
                requireMethod(classes.get(CARD), method, "()V"); checks++;
            }
            requireMethod(classes.get(ADAPTER), "onBindViewHolder", "(" + HOLDER + "I)V"); checks++;
            for (String field : List.of("cardFrame", "cardLogo", "cardSet")) {
                requireField(classes.get(CARD), field, "Landroid/widget/ImageView;"); checks++;
            }
            requireField(classes.get(CARD), "bigFingerTip", "Landroid/widget/TextView;"); checks++;
            requireField(classes.get(CARD), "shadowBgView", "Landroid/view/View;"); checks++;
            requireField(classes.get(CARD), "linearShadow", "Landroid/widget/LinearLayout;"); checks++;
            for (String getter : List.of("getImgBg", "getImgFunc")) {
                requireMethod(classes.get(HOLDER), getter, "()Landroid/widget/ImageView;"); checks++;
            }
            requireMethod(classes.get(HOLDER), "getRootView", "()Landroid/view/View;"); checks++;
            requireMethod(classes.get(HOLDER), "getTvFunc", "()Landroid/widget/TextView;"); checks++;
            ClassDef camera = classes.get("Lcom/vivo/card/cards/cardcamera/handler/CameraCardEventHandler;");
            ClassDef base = classes.get("Lcom/vivo/card/cards/base/view/BaseCardView;");
            String context = "Landroid/content/Context;", function = "Lcom/vivo/card/model/CardSetBean$CardBean$FunctionBean;";
            requireField(camera, "cardCallback", "Lcom/vivo/card/cards/cardcamera/callback/ICameraCardInnerCallback;"); checks++;
            requireMethod(camera, "isCardExpanded", "()Z"); checks++;
            requireMethod(camera, "requestUnlock", "(" + context + ")V"); checks++;
            requireMethod(camera, "handleFunctionClick", "(" + context + function + ")V"); checks++;
            requireMethod(camera, "handleSettingsClick", "(" + context + ")V"); checks++;
            requireMethod(camera, "resetPendingKeyguardRequest", "()V"); checks++;
            requireMethod(camera, "onKeyguardSuccess", "()V"); checks++;
            requireMethod(classes.get(function), "getType", "()Ljava/lang/String;"); checks++;
            for (String name : List.of("onCardPackHiding", "onCardPackHidden", "onCardPackDestroyed")) {
                requireMethod(base, name, "()V"); checks++;
            }
            requireMethod(base, "notifyCardForegroundState", "(Z)V"); checks++;
            requireMethod(base, "onOuterPageScrollStateChanged", "(ILjava/lang/String;)V"); checks++;
            ClassDef virtual = classes.get("Lcom/vivo/card/framework/virtual/VirtualViewManager;");
            requireMethod(virtual, "isVirtualDisplayActive", "()Z"); checks++;
            requireMethod(virtual, "getVirtualDisplayId", "()I"); checks++;
            requireMethod(virtual, "getTaskId", "()I"); checks++;
            requireMethod(virtual, "setTaskId", "(I)V"); checks++;
            requireMethod(virtual, "handleActivityResumed", "(Landroid/content/ComponentName;)V"); checks++;
            requireMethod(classes.get("Lcom/vivo/card/cards/cardpay/virtual/PayVirtualViewManager;"), "release", "()V"); checks++;
            requireMethod(classes.get("Lcom/vivo/card/cards/cardpay/virtual/PayVirtualViewManager;"), "launchPayment", "(Ljava/lang/String;[ILandroid/content/Intent;)V"); checks++;
            System.out.println("Floating card ABI: " + checks + " real bundled entries verified.");
        }
    }
    private static void requireMethod(ClassDef type, String name, String signature) {
        if (type == null) throw new AssertionError("PAY class missing");
        int found = 0;
        for (Method method : type.getMethods()) {
            String descriptor = "(" + method.getParameterTypes().stream().map(Object::toString).collect(Collectors.joining()) + ")" + method.getReturnType();
            if (name.equals(method.getName()) && signature.equals(descriptor) && (method.getAccessFlags() & 8) == 0) found++;
        }
        if (found != 1) throw new AssertionError("PAY method changed: " + type.getType() + "->" + name + signature);
    }
    private static void requireField(ClassDef type, String name, String descriptor) {
        if (type == null) throw new AssertionError("PAY class missing");
        int found = 0;
        for (Field field : type.getFields()) if (name.equals(field.getName()) && descriptor.equals(field.getType()) && (field.getAccessFlags() & 8) == 0) found++;
        if (found != 1) throw new AssertionError("PAY field changed: " + type.getType() + "->" + name);
    }
}
