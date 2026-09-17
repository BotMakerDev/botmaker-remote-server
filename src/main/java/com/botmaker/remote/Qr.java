package com.botmaker.remote;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.Map;

/**
 * The pairing URL as a QR code drawn in the terminal the server starts in, two modules per character.
 *
 * <p>Unicode half blocks: {@code █} both dark, {@code ▀}/{@code ▄} one of the two, a space neither. Dark on
 * light because that is how a phone camera expects a QR, so on a dark terminal the code is printed inside a
 * light box of its own quiet zone. Margin 4 is the spec's quiet zone; the pilot found a tighter one unreliable.
 */
public final class Qr {

    private Qr() {
    }

    /** Half blocks: two modules per row. Needs a terminal whose line height equals its glyph height. */
    public static String render(String text) {
        return render(text, false);
    }

    /**
     * @param big one module = {@code ██}, one row each: twice as tall, but square in any console — IDE run
     *            windows pad their lines and tear the half-block version into stripes
     */
    public static String render(String text, boolean big) {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.MARGIN, 4, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
        } catch (WriterException e) {
            return "(QR could not be drawn: " + e.getMessage() + ")";
        }
        int w = matrix.getWidth();
        int h = matrix.getHeight();
        StringBuilder out = new StringBuilder();
        // ANSI: white background, black foreground, so the quiet zone is light on any terminal theme.
        for (int y = 0; y < h; y += big ? 1 : 2) {
            out.append("[30;47m");
            for (int x = 0; x < w; x++) {
                boolean top = matrix.get(x, y);
                if (big) {
                    out.append(top ? "██" : "  ");
                    continue;
                }
                boolean bottom = y + 1 < h && matrix.get(x, y + 1);
                out.append(top ? (bottom ? '█' : '▀') : (bottom ? '▄' : ' '));
            }
            out.append("[0m\n");
        }
        return out.toString();
    }
}
