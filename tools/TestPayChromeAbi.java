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
                for (ClassDef type : dex.getClasses()) if (Set.of(CARD, ADAPTER, HOLDER).contains(type.getType())) {
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
            System.out.println("PAY appearance ABI: " + checks + " real bundled entries verified.");
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
