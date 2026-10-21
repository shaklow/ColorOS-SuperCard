package dev.local.supercardhost;

import android.graphics.Bitmap;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.Map;

final class DhuPayCodes {
    private DhuPayCodes() { }
    static Bitmap qr(String code) throws com.google.zxing.WriterException {
        return encode(code, BarcodeFormat.QR_CODE, 600, 600,
                Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 4));
    }
    static Bitmap barcode(String code) throws com.google.zxing.WriterException {
        return encode(code, BarcodeFormat.CODE_128, 900, 210, Map.of(EncodeHintType.MARGIN, 20));
    }
    private static Bitmap encode(String code, BarcodeFormat format, int width, int height,
            Map<EncodeHintType, ?> hints) throws com.google.zxing.WriterException {
        if (!DhuPayProtocol.validCode(code)) throw new IllegalArgumentException("Invalid payment payload");
        BitMatrix matrix = new MultiFormatWriter().encode(code, format, width, height, hints);
        int[] pixels = new int[matrix.getWidth() * matrix.getHeight()];
        for (int y = 0; y < matrix.getHeight(); y++) for (int x = 0; x < matrix.getWidth(); x++)
            pixels[y * matrix.getWidth() + x] = matrix.get(x, y) ? 0xff000000 : 0xffffffff;
        return Bitmap.createBitmap(pixels, matrix.getWidth(), matrix.getHeight(), Bitmap.Config.ARGB_8888);
    }
}
