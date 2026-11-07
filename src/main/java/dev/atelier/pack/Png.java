package dev.atelier.pack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Размеры PNG и ширина глифа так, как её считает клиент. */
public final class Png {
    private Png() {}

    public record Info(int width, int height, int actualWidth) {}

    public static Info read(byte[] data) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
        if (img == null) throw new IOException("файл не является PNG-изображением");
        int w = img.getWidth(), h = img.getHeight();
        int actual = 0;
        outer:
        for (int x = w - 1; x >= 0; x--) {
            for (int y = 0; y < h; y++) {
                if ((img.getRGB(x, y) >>> 24) != 0) { actual = x + 1; break outer; }
            }
        }
        return new Info(w, h, actual);
    }

    /** Сдвиг курсора после bitmap-глифа: round(actualWidth · height/imageHeight) + 1 (как в BitmapProvider клиента). */
    public static int advance(Info info, int renderHeight) {
        float scale = (float) renderHeight / (float) info.height();
        return (int) (0.5 + (double) ((float) info.actualWidth() * scale)) + 1;
    }
}
