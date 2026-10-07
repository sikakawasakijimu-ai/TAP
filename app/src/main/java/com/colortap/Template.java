package com.colortap;

import android.content.Context;
import android.graphics.Bitmap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;

/**
 * An image to look for, stored as RGBA at capture resolution (1/SCALE of the screen).
 * Matching compares a fixed set of sample points rather than every pixel.
 */
final class Template {
    private static final int MAX_SAMPLES = 64;

    final File file;
    final int w, h;
    final byte[] rgba;
    /** Sample points (offsets inside the template) and their colors. */
    final int[] sx, sy, sr, sg, sb;

    private Template(File file, int w, int h, byte[] rgba) {
        this.file = file;
        this.w = w;
        this.h = h;
        this.rgba = rgba;

        // Evenly spaced grid, visited in a fixed shuffled order so early exits see the whole image.
        int side = (int) Math.ceil(Math.sqrt(MAX_SAMPLES));
        int nx = Math.min(w, side), ny = Math.min(h, side);
        int n = nx * ny;
        sx = new int[n];
        sy = new int[n];
        sr = new int[n];
        sg = new int[n];
        sb = new int[n];
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Random rnd = new Random(1);
        for (int i = n - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1), t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        for (int i = 0; i < n; i++) {
            int gx = order[i] % nx, gy = order[i] / nx;
            int x = nx == 1 ? w / 2 : gx * (w - 1) / (nx - 1);
            int y = ny == 1 ? h / 2 : gy * (h - 1) / (ny - 1);
            int p = (y * w + x) * 4;
            sx[i] = x;
            sy[i] = y;
            sr[i] = rgba[p] & 0xFF;
            sg[i] = rgba[p + 1] & 0xFF;
            sb[i] = rgba[p + 2] & 0xFF;
        }
    }

    Bitmap toBitmap() {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.copyPixelsFromBuffer(ByteBuffer.wrap(rgba)); // ARGB_8888 is laid out as RGBA in memory
        return b;
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "templates");
        d.mkdirs();
        return d;
    }

    /**
     * Crops a w x h region at (x, y) out of a tightly packed RGBA frame of width frameW,
     * saves it and returns the file name.
     */
    static String save(Context c, byte[] frame, int frameW, int x, int y, int w, int h) throws IOException {
        byte[] out = new byte[w * h * 4];
        for (int row = 0; row < h; row++) {
            System.arraycopy(frame, ((y + row) * frameW + x) * 4, out, row * w * 4, w * 4);
        }
        File f = new File(dir(c), "t" + System.currentTimeMillis() + ".bin");
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(f))) {
            o.writeInt(w);
            o.writeInt(h);
            o.write(out);
        }
        return f.getName();
    }

    /** Loads a saved image by file name; returns null if it is missing or unreadable. */
    static Template load(Context c, String name) {
        File f = new File(dir(c), name);
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            int w = in.readInt(), h = in.readInt();
            byte[] rgba = new byte[w * h * 4];
            in.readFully(rgba);
            return new Template(f, w, h, rgba);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
