package com.smart.hawala.desk;

import java.io.File;
import javax.imageio.ImageIO;

/** يولّد صور الأيقونة بكل المقاسات لملف AppIcon.icns (يُستدعى من سكربت التحزيم). */
public final class IconGen {
    private IconGen() {}

    public static void main(String[] a) throws Exception {
        File dir = new File(a[0]);
        dir.mkdirs();
        for (int s : new int[]{16, 24, 32, 48, 64, 128, 256, 512, 1024}) ImageIO.write(Main.icon(s), "png", new File(dir, "icon_" + s + ".png"));
    }
}
