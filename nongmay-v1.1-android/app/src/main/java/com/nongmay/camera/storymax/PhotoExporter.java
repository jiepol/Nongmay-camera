package com.nongmay.camera.storymax;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import com.nongmay.camera.R;
import com.nongmay.camera.camera.MediaStoreUtil;
import com.nongmay.camera.util.JpegIcc;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;

/**
 * Colour-managed 9:16 still export. Decodes only the needed region at a bounded size (never a
 * full 200 MP bitmap), converts to sRGB, applies EXIF orientation, downsamples in ≤2× steps,
 * encodes JPEG and embeds an sRGB ICC profile. The original file is only ever read.
 */
public final class PhotoExporter {
    private static final long MAX_DECODE_PIXELS = 24_000_000L;

    private PhotoExporter() {}

    public static void run(Context ctx, ExportJob job, ExportJob.Progress progress) {
        long t0 = SystemClock.uptimeMillis();
        try {
            if (job.profile.isDirect()) {
                directCopy(ctx, job);
            } else {
                render(ctx, job, progress);
            }
            if (job.output != null) job.actual = MediaProbe.probe(ctx, job.output);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            job.error = e instanceof OutOfMemoryError ? "หน่วยความจำไม่พอสำหรับภาพนี้" : e.getMessage();
            MediaStoreUtil.delete(ctx, job.output);
            job.output = null;
        }
        job.elapsedMs = SystemClock.uptimeMillis() - t0;
    }

    private static void render(Context ctx, ExportJob job, ExportJob.Progress progress) throws IOException {
        MediaInfo src = job.source;
        int outW = job.profile.width, outH = job.profile.height;
        int ow = src.orientedW(), oh = src.orientedH();
        progress.onProgress(0.05f, "ถอดรหัสภาพ");
        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(0xFF000000);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (job.mode == CropMath.FIT) {
            double[] fit = CropMath.fitRect(ow, oh, outW, outH);
            Bitmap whole = decodeOriented(ctx, src, new double[]{0, 0, ow, oh}, (int) Math.ceil(fit[2] - fit[0]),
                    (int) Math.ceil(fit[3] - fit[1]), job);
            if (job.blurBackground) {
                double[] cover = CropMath.coverRect(whole.getWidth(), whole.getHeight(), (double) outW / outH, 1, 0.5, 0.5);
                Rect cr = new Rect((int) cover[0], (int) cover[1], (int) cover[2], (int) cover[3]);
                Bitmap tiny = Bitmap.createBitmap(Math.max(1, outW / 24), Math.max(1, outH / 24), Bitmap.Config.ARGB_8888);
                new Canvas(tiny).drawBitmap(whole, cr, new Rect(0, 0, tiny.getWidth(), tiny.getHeight()), paint);
                Paint dim = new Paint(Paint.FILTER_BITMAP_FLAG);
                ColorMatrix cm = new ColorMatrix();
                cm.setScale(0.6f, 0.6f, 0.6f, 1f);
                dim.setColorFilter(new ColorMatrixColorFilter(cm));
                canvas.drawBitmap(tiny, null, new Rect(0, 0, outW, outH), dim);
                tiny.recycle();
                job.notes.add("พื้นหลังเบลอสร้างจากภาพเดียวกัน (ไม่ใช่เนื้อภาพจริง)");
            } else {
                job.notes.add("Fit: ขอบดำเพิ่มเพื่อให้ได้ 9:16 โดยไม่ครอปภาพ");
            }
            drawDownscaled(canvas, whole, new RectF((float) fit[0], (float) fit[1], (float) fit[2], (float) fit[3]), paint);
            whole.recycle();
        } else {
            double[] r = job.cropOriented != null ? job.cropOriented : CropMath.coverRect(ow, oh, CropMath.STORY_ASPECT, 1, 0.5, 0.5);
            Bitmap region = decodeOriented(ctx, src, r, outW, outH, job);
            drawDownscaled(canvas, region, new RectF(0, 0, outW, outH), paint);
            region.recycle();
            double kept = CropMath.keptFraction(r, ow, oh);
            if (kept < 0.999) job.notes.add(String.format(java.util.Locale.ROOT, "ครอปเหลือ %.0f%% ของภาพต้นฉบับ", kept * 100));
            double srcPx = (r[2] - r[0]);
            if (srcPx < outW * 0.98) job.notes.add(String.format(java.util.Locale.ROOT,
                    "พื้นที่ที่เลือกกว้าง %.0f px จึงถูกขยายขึ้นเป็น %d px (ไม่ได้เพิ่มรายละเอียดจริง)", srcPx, outW));
        }
        progress.onProgress(0.7f, "เข้ารหัส JPEG");
        ByteArrayOutputStream bos = new ByteArrayOutputStream(outW * outH / 2);
        out.compress(Bitmap.CompressFormat.JPEG, job.profile.jpegQuality, bos);
        out.recycle();
        byte[] jpeg = bos.toByteArray();
        byte[] icc = readRaw(ctx, R.raw.srgb_v2);
        jpeg = JpegIcc.insertIcc(jpeg, icc);
        if (src.wideGamut) job.notes.add("แปลงสี " + src.colorSpace + " → sRGB (สีที่อยู่นอก sRGB ถูกตัด)");
        if (src.hasGainmap) job.notes.add("ใช้ภาพ SDR พื้นฐานของ Ultra HDR · ไม่เก็บ gain map ในไฟล์ Story");
        if (src.hasGps) job.notes.add("ไฟล์ส่งออกไม่มี EXIF/GPS");
        progress.onProgress(0.85f, "บันทึก");
        job.outputName = MediaStoreUtil.timestampName("NM_STORY_") + "_" + job.profile.code + (job.blindCode != null ? "_" + job.blindCode : "") + ".jpg";
        job.output = MediaStoreUtil.saveBytes(ctx, jpeg, job.outputName, "image/jpeg", MediaStoreUtil.STORY_PHOTO_DIR);
        progress.onProgress(1f, "เสร็จ");
    }

    /** Oriented crop rect -> sRGB bitmap at least (minW × minH) where possible, bounded memory. */
    static Bitmap decodeOriented(Context ctx, MediaInfo src, double[] rect, int minW, int minH, ExportJob job) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        int o = src.exifOrientation;
        double rw = rect[2] - rect[0], rh = rect[3] - rect[1];
        int sample = CropMath.sampleSize(rw, rh, minW, minH);
        while ((rw / sample) * (rh / sample) > MAX_DECODE_PIXELS) sample *= 2;
        double[] raw = CropMath.rectToRaw(rect, o, src.rawW, src.rawH);
        Rect rr = new Rect((int) Math.floor(raw[0]), (int) Math.floor(raw[1]), (int) Math.ceil(raw[2]), (int) Math.ceil(raw[3]));
        rr.intersect(0, 0, src.rawW, src.rawH);
        Bitmap region = null;
        try (ParcelFileDescriptor pfd = cr.openFileDescriptor(src.uri, "r")) {
            if (pfd != null) {
                @SuppressWarnings("deprecation")
                BitmapRegionDecoder dec = BitmapRegionDecoder.newInstance(pfd.getFileDescriptor(), false);
                if (dec != null) {
                    try {
                        BitmapFactory.Options opt = new BitmapFactory.Options();
                        opt.inSampleSize = sample;
                        opt.inPreferredConfig = Bitmap.Config.ARGB_8888;
                        opt.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
                        region = dec.decodeRegion(rr, opt);
                    } finally {
                        dec.recycle();
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            region = null; // fall through to ImageDecoder (HEIC/DNG on some builds)
        }
        if (region != null) {
            Bitmap oriented = applyOrientation(region, o);
            if (oriented != region) region.recycle();
            return oriented;
        }
        job.notes.add("ใช้ ImageDecoder (ถอดรหัสทั้งภาพแบบย่อ) เพราะรูปแบบนี้ไม่รองรับการถอดเฉพาะส่วน");
        final int fs = Math.max(sample, fullSample(src));
        ImageDecoder.Source s = ImageDecoder.createSource(cr, src.uri);
        Bitmap full = ImageDecoder.decodeBitmap(s, new ImageDecoder.OnHeaderDecodedListener() {
            @Override
            public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info, ImageDecoder.Source source) {
                d.setTargetSampleSize(fs);
                d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
                d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            }
        });
        // ImageDecoder already applied EXIF orientation, so crop in oriented coordinates.
        double sx = (double) full.getWidth() / src.orientedW(), sy = (double) full.getHeight() / src.orientedH();
        Rect c = new Rect((int) Math.floor(rect[0] * sx), (int) Math.floor(rect[1] * sy), (int) Math.ceil(rect[2] * sx), (int) Math.ceil(rect[3] * sy));
        c.intersect(0, 0, full.getWidth(), full.getHeight());
        Bitmap crop = Bitmap.createBitmap(full, c.left, c.top, Math.max(1, c.width()), Math.max(1, c.height()));
        if (crop != full) full.recycle();
        return crop;
    }

    private static int fullSample(MediaInfo src) {
        int s = 1;
        while ((double) src.rawW / s * src.rawH / s > MAX_DECODE_PIXELS) s *= 2;
        return s;
    }

    static Bitmap applyOrientation(Bitmap b, int o) {
        Matrix m = new Matrix();
        switch (o) {
            case 2: m.setScale(-1, 1); break;
            case 3: m.setRotate(180); break;
            case 4: m.setScale(1, -1); break;
            case 5: m.setScale(-1, 1); m.postRotate(270); break;
            case 6: m.setRotate(90); break;
            case 7: m.setScale(-1, 1); m.postRotate(90); break;
            case 8: m.setRotate(270); break;
            default: return b;
        }
        return Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
    }

    /** Area-preserving downscale in ≤2× bilinear steps (avoids the aliasing of one big jump). */
    static void drawDownscaled(Canvas canvas, Bitmap src, RectF dst, Paint paint) {
        Bitmap cur = src;
        while (cur.getWidth() > dst.width() * 2.01f && cur.getHeight() > dst.height() * 2.01f) {
            Bitmap half = Bitmap.createScaledBitmap(cur, Math.max(1, cur.getWidth() / 2), Math.max(1, cur.getHeight() / 2), true);
            if (cur != src) cur.recycle();
            cur = half;
        }
        canvas.drawBitmap(cur, null, dst, paint);
        if (cur != src) cur.recycle();
    }

    static byte[] readRaw(Context ctx, int id) throws IOException {
        try (InputStream in = ctx.getResources().openRawResource(id)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    /** DIRECT_ORIGINAL for photos: byte copy; for JPEG the GPS tags are removed without re-encoding. */
    private static void directCopy(Context ctx, ExportJob job) throws IOException {
        MediaInfo src = job.source;
        ContentResolver cr = ctx.getContentResolver();
        byte[] data;
        try (InputStream in = cr.openInputStream(src.uri)) {
            if (in == null) throw new IOException("อ่านไฟล์ต้นฉบับไม่ได้");
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(1024, src.sizeBytes));
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            data = bos.toByteArray();
        }
        String ext = "image/jpeg".equals(src.mime) ? ".jpg" : "image/png".equals(src.mime) ? ".png"
                : src.mime.contains("heic") || src.mime.contains("heif") ? ".heic" : ".img";
        if ("image/jpeg".equals(src.mime) && src.hasGps) {
            File tmp = File.createTempFile("nm_direct", ".jpg", ctx.getCacheDir());
            try {
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    fos.write(data);
                }
                ExifInterface e = new ExifInterface(tmp.getAbsolutePath());
                String[] gps = {ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
                        ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
                        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
                        ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_IMG_DIRECTION};
                for (String t : gps) e.setAttribute(t, null);
                e.saveAttributes();
                data = Files.readAllBytes(tmp.toPath());
                job.notes.add("คัดลอกไฟล์เดิม (ไม่เข้ารหัสใหม่) และลบ GPS ออกจาก EXIF");
            } finally {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } else if (src.hasGps) {
            job.notes.add("⚠ คัดลอกไฟล์เดิมทั้งไฟล์ — ลบ GPS อัตโนมัติได้เฉพาะ JPEG ไฟล์นี้อาจยังมีตำแหน่งอยู่");
        } else {
            job.notes.add("คัดลอกไฟล์เดิม (ไม่เข้ารหัสใหม่)");
        }
        job.outputName = MediaStoreUtil.timestampName("NM_STORY_") + "_ORIGINAL" + (job.blindCode != null ? "_" + job.blindCode : "") + ext;
        String mime = src.mime.isEmpty() ? "image/jpeg" : src.mime;
        job.output = MediaStoreUtil.saveBytes(ctx, data, job.outputName, mime, MediaStoreUtil.STORY_PHOTO_DIR);
    }
}
