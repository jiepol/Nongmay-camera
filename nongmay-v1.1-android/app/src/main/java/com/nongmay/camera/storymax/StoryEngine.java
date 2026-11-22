package com.nongmay.camera.storymax;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.util.Size;

import com.nongmay.camera.perf.PerfLog;
import com.nongmay.camera.storymax.quality.LabStore;

import java.io.IOException;

/** Shared by StoryMax and Quality Lab: preview loading and export dispatch + logging. */
public final class StoryEngine {
    private StoryEngine() {}

    /** Oriented, sRGB preview no larger than maxSide on its long edge. */
    public static Bitmap loadPreview(Context ctx, MediaInfo m, final int maxSide) throws IOException {
        if (m.video) {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            try {
                r.setDataSource(ctx, m.uri);
                long t = Math.min(Math.max(0, m.durationUs / 3), 3_000_000L);
                int w = m.orientedW(), h = m.orientedH();
                double s = Math.min(1.0, (double) maxSide / Math.max(w, h));
                Bitmap b = r.getScaledFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        Math.max(1, (int) (w * s)), Math.max(1, (int) (h * s)));
                if (b == null) b = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (b == null) throw new IOException("ดึงภาพตัวอย่างจากวิดีโอไม่ได้");
                // Some builds return frames without the container rotation: fix by aspect.
                boolean frameLandscape = b.getWidth() > b.getHeight();
                boolean wantLandscape = w > h;
                if (frameLandscape != wantLandscape && m.rotation % 180 != 0) {
                    Matrix mx = new Matrix();
                    mx.setRotate(m.rotation);
                    Bitmap rot = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), mx, true);
                    if (rot != b) b.recycle();
                    b = rot;
                }
                return b;
            } catch (RuntimeException e) {
                throw new IOException("อ่านวิดีโอไม่ได้: " + e.getMessage());
            } finally {
                try {
                    r.release();
                } catch (IOException | RuntimeException ignored) {
                }
            }
        }
        ImageDecoder.Source src = ImageDecoder.createSource(ctx.getContentResolver(), m.uri);
        return ImageDecoder.decodeBitmap(src, new ImageDecoder.OnHeaderDecodedListener() {
            @Override
            public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info, ImageDecoder.Source s) {
                Size sz = info.getSize();
                double scale = Math.min(1.0, (double) maxSide / Math.max(sz.getWidth(), sz.getHeight()));
                d.setTargetSize(Math.max(1, (int) (sz.getWidth() * scale)), Math.max(1, (int) (sz.getHeight() * scale)));
                d.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
                d.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            }
        });
    }

    public static void export(Context ctx, ExportJob job, ExportJob.Progress progress) {
        if (job.profile.status == StoryProfiles.NOT_IMPLEMENTED) {
            job.error = job.profile.code + " ยังไม่ได้สร้างใน v1.1: " + job.profile.note;
            LabStore.record(ctx, job);
            return;
        }
        if (job.source.video) VideoTranscoder.run(ctx, job, progress);
        else PhotoExporter.run(ctx, job, progress);
        if (job.ok()) {
            if (job.source.video && !job.profile.isDirect() && job.source.durationUs > 0) {
                PerfLog.add(PerfLog.EXPORT_VIDEO_RT, job.elapsedMs / (job.source.durationUs / 1e6));
            } else if (!job.source.video && !job.profile.isDirect()) {
                PerfLog.add(PerfLog.EXPORT_PHOTO, job.elapsedMs);
            }
        }
        LabStore.record(ctx, job);
    }
}
