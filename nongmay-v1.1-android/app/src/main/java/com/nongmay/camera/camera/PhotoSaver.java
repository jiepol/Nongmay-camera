package com.nongmay.camera.camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Saves camera JPEGs. Native-aspect shots are written byte-for-byte as the camera produced them.
 * 1:1 / 3:2 crops and mirrored selfies have to be re-encoded (quality 95); their camera EXIF
 * (time, exposure, lens, orientation) is copied over and the file name stays the same.
 */
final class PhotoSaver {
    static final String[] EXIF_COPY = {
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_ISO_SPEED_RATINGS, ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_OFFSET_TIME_ORIGINAL};

    private PhotoSaver() {}

    static Uri saveJpeg(Context ctx, byte[] jpeg, String name, String relPath, int cropAspect, boolean mirror) throws IOException {
        byte[] out = (cropAspect == 0 && !mirror) ? jpeg : process(ctx, jpeg, cropAspect, mirror);
        return MediaStoreUtil.saveBytes(ctx, out, name, "image/jpeg", relPath);
    }

    static Rect cropRect(int w, int h, int cropAspect) {
        if (cropAspect == 1) {
            int s = Math.min(w, h);
            return new Rect((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
        }
        if (cropAspect == 2) {
            int cw = w, ch = h;
            if (w >= h) {
                ch = Math.round(w * 2f / 3f);
                if (ch > h) { ch = h; cw = Math.round(h * 1.5f); }
            } else {
                cw = Math.round(h * 2f / 3f);
                if (cw > w) { cw = w; ch = Math.round(w * 1.5f); }
            }
            return new Rect((w - cw) / 2, (h - ch) / 2, (w + cw) / 2, (h + ch) / 2);
        }
        return new Rect(0, 0, w, h);
    }

    @SuppressWarnings("deprecation")
    static byte[] process(Context ctx, byte[] jpeg, int cropAspect, boolean mirror) throws IOException {
        ExifInterface src = new ExifInterface(new ByteArrayInputStream(jpeg));
        int orientation = src.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        Rect crop = cropRect(bounds.outWidth, bounds.outHeight, cropAspect);
        BitmapRegionDecoder dec = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.length, false);
        Bitmap bmp;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            bmp = dec.decodeRegion(crop, o);
        } finally {
            dec.recycle();
        }
        if (bmp == null) throw new IOException("decode failed");
        if (mirror) {
            boolean sideways = orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270;
            Matrix m = new Matrix();
            if (sideways) m.setScale(1, -1); else m.setScale(-1, 1);
            Bitmap flipped = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, false);
            if (flipped != bmp) bmp.recycle();
            bmp = flipped;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream(jpeg.length);
        bmp.compress(Bitmap.CompressFormat.JPEG, 95, bos);
        bmp.recycle();
        File tmp = File.createTempFile("nm_proc", ".jpg", ctx.getCacheDir());
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(bos.toByteArray());
            }
            ExifInterface dst = new ExifInterface(tmp.getAbsolutePath());
            for (String tag : EXIF_COPY) {
                String v = src.getAttribute(tag);
                if (v != null) dst.setAttribute(tag, v);
            }
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(orientation));
            dst.setAttribute(ExifInterface.TAG_SOFTWARE, "NongMay Camera 1.1");
            dst.saveAttributes();
            return Files.readAllBytes(tmp.toPath());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
