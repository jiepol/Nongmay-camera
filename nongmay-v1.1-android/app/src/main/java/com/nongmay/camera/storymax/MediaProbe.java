package com.nongmay.camera.storymax;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.media.ExifInterface;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Size;

import com.nongmay.camera.util.JpegIcc;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Reads real metadata from a source or exported file (never guesses from the file name). */
public final class MediaProbe {
    private MediaProbe() {}

    public static MediaInfo probe(Context ctx, Uri uri) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        MediaInfo m = new MediaInfo();
        m.uri = uri;
        String type = cr.getType(uri);
        m.mime = type == null ? "" : type;
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                if (!c.isNull(0)) m.name = c.getString(0);
                if (!c.isNull(1)) m.sizeBytes = c.getLong(1);
            }
        } catch (RuntimeException ignored) {
        }
        if (m.sizeBytes < 0) {
            try (ParcelFileDescriptor pfd = cr.openFileDescriptor(uri, "r")) {
                if (pfd != null) m.sizeBytes = pfd.getStatSize();
            } catch (IOException | RuntimeException ignored) {
            }
        }
        if (m.mime.startsWith("video/") || (m.mime.isEmpty() && looksLikeVideo(m.name))) {
            m.video = true;
            probeVideo(ctx, uri, m);
        } else {
            probeImage(ctx, uri, m);
        }
        return m;
    }

    private static boolean looksLikeVideo(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".mp4") || n.endsWith(".mov") || n.endsWith(".3gp") || n.endsWith(".mkv") || n.endsWith(".webm");
    }

    private static void probeImage(Context ctx, Uri uri, final MediaInfo m) throws IOException {
        ContentResolver cr = ctx.getContentResolver();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in == null) throw new IOException("อ่านไฟล์ไม่ได้");
            BitmapFactory.decodeStream(in, null, o);
        }
        m.rawW = o.outWidth;
        m.rawH = o.outHeight;
        if (o.outMimeType != null) m.mime = o.outMimeType;
        try (InputStream in = cr.openInputStream(uri)) {
            if (in != null) {
                ExifInterface exif = new ExifInterface(in);
                m.exifOrientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
                if (m.exifOrientation < 1 || m.exifOrientation > 8) m.exifOrientation = 1;
                m.hasGps = exif.getLatLong(new float[2]) || exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE) != null;
                String make = exif.getAttribute(ExifInterface.TAG_MAKE), model = exif.getAttribute(ExifInterface.TAG_MODEL);
                if (make != null || model != null) m.camera = (make == null ? "" : make + " ") + (model == null ? "" : model);
            }
        } catch (IOException | RuntimeException ignored) {
        }
        if (m.rawW <= 0 || m.rawH <= 0) {
            // formats BitmapFactory cannot size (e.g. some DNG): ask ImageDecoder, which applies orientation itself
            try {
                ImageDecoder.Source src = ImageDecoder.createSource(cr, uri);
                ImageDecoder.decodeBitmap(src, new ImageDecoder.OnHeaderDecodedListener() {
                    @Override
                    public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info, ImageDecoder.Source s) {
                        Size sz = info.getSize();
                        m.rawW = sz.getWidth();
                        m.rawH = sz.getHeight();
                        m.exifOrientation = 1;
                        m.mime = info.getMimeType();
                        throw new AbortDecode();
                    }
                });
            } catch (AbortDecode ignored) {
            } catch (IOException | RuntimeException e) {
                throw new IOException("ไฟล์นี้เปิดไม่ได้: " + e.getMessage());
            }
        }
        if (m.rawW <= 0 || m.rawH <= 0) throw new IOException("ไม่รู้จักรูปแบบภาพนี้");
        try {
            ImageDecoder.Source src = ImageDecoder.createSource(cr, uri);
            ImageDecoder.decodeBitmap(src, new ImageDecoder.OnHeaderDecodedListener() {
                @Override
                public void onHeaderDecoded(ImageDecoder d, ImageDecoder.ImageInfo info, ImageDecoder.Source s) {
                    ColorSpace cs = info.getColorSpace();
                    if (cs != null) {
                        m.colorSpace = cs.getName();
                        m.wideGamut = !cs.equals(ColorSpace.get(ColorSpace.Named.SRGB))
                                && !cs.equals(ColorSpace.get(ColorSpace.Named.EXTENDED_SRGB))
                                && cs.getModel() == ColorSpace.Model.RGB;
                    }
                    throw new AbortDecode();
                }
            });
        } catch (AbortDecode ignored) {
        } catch (IOException | RuntimeException ignored) {
        }
        if ("image/jpeg".equals(m.mime) && m.sizeBytes > 0 && m.sizeBytes < 64L * 1024 * 1024) {
            try (InputStream in = cr.openInputStream(uri)) {
                if (in != null) {
                    byte[] head = new byte[(int) Math.min(m.sizeBytes, 256 * 1024)];
                    int n = 0, r;
                    while (n < head.length && (r = in.read(head, n, head.length - n)) > 0) n += r;
                    m.hasIcc = JpegIcc.hasIcc(head);
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                BitmapFactory.Options small = new BitmapFactory.Options();
                small.inSampleSize = Math.max(1, Integer.highestOneBit(Math.max(m.rawW, m.rawH) / 256));
                try (InputStream in = cr.openInputStream(uri)) {
                    android.graphics.Bitmap b = BitmapFactory.decodeStream(in, null, small);
                    if (b != null) {
                        m.hasGainmap = b.hasGainmap();
                        b.recycle();
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    private static final class AbortDecode extends RuntimeException {
        AbortDecode() {
            super(null, null, false, false);
        }
    }

    static int findTrack(MediaExtractor ex, String prefix) {
        for (int i = 0; i < ex.getTrackCount(); i++) {
            String mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    static int intOr(MediaFormat f, String key, int def) {
        try {
            return f.containsKey(key) ? f.getInteger(key) : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    static long longOr(MediaFormat f, String key, long def) {
        try {
            return f.containsKey(key) ? f.getLong(key) : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private static void probeVideo(Context ctx, Uri uri, MediaInfo m) throws IOException {
        MediaExtractor ex = new MediaExtractor();
        try {
            ex.setDataSource(ctx, uri, null);
            m.videoTrack = findTrack(ex, "video/");
            if (m.videoTrack < 0) throw new IOException("ไม่พบแทร็กวิดีโอในไฟล์นี้");
            MediaFormat vf = ex.getTrackFormat(m.videoTrack);
            m.videoMime = vf.getString(MediaFormat.KEY_MIME);
            m.videoW = intOr(vf, MediaFormat.KEY_WIDTH, 0);
            m.videoH = intOr(vf, MediaFormat.KEY_HEIGHT, 0);
            m.rotation = intOr(vf, MediaFormat.KEY_ROTATION, 0);
            m.durationUs = longOr(vf, MediaFormat.KEY_DURATION, -1);
            m.profile = intOr(vf, MediaFormat.KEY_PROFILE, -1);
            m.level = intOr(vf, MediaFormat.KEY_LEVEL, -1);
            m.colorStandard = intOr(vf, MediaFormat.KEY_COLOR_STANDARD, -1);
            m.colorTransfer = intOr(vf, MediaFormat.KEY_COLOR_TRANSFER, -1);
            m.colorRange = intOr(vf, MediaFormat.KEY_COLOR_RANGE, -1);
            m.hdr = m.colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084 || m.colorTransfer == MediaFormat.COLOR_TRANSFER_HLG
                    || "video/dolby-vision".equals(m.videoMime)
                    || m.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
                    || m.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10Plus;
            m.audioTrack = findTrack(ex, "audio/");
            if (m.audioTrack >= 0) {
                MediaFormat af = ex.getTrackFormat(m.audioTrack);
                m.hasAudio = true;
                m.audioMime = af.getString(MediaFormat.KEY_MIME);
                m.sampleRate = intOr(af, MediaFormat.KEY_SAMPLE_RATE, 0);
                m.channels = intOr(af, MediaFormat.KEY_CHANNEL_COUNT, 0);
                m.audioBitrate = intOr(af, MediaFormat.KEY_BIT_RATE, -1);
            }
            ex.selectTrack(m.videoTrack);
            List<Long> ts = new ArrayList<>();
            while (ts.size() < 240) {
                long t = ex.getSampleTime();
                if (t < 0) break;
                ts.add(t);
                if (!ex.advance()) break;
            }
            Collections.sort(ts);
            m.frames = ts.size();
            if (ts.size() >= 3) {
                List<Double> d = new ArrayList<>();
                for (int i = 1; i < ts.size(); i++) {
                    long dt = ts.get(i) - ts.get(i - 1);
                    if (dt > 0) d.add(1e6 / dt);
                }
                if (!d.isEmpty()) {
                    Collections.sort(d);
                    double median = d.get(d.size() / 2);
                    m.fps = median;
                    m.fpsMin = d.get((int) (d.size() * 0.05));
                    m.fpsMax = d.get((int) Math.min(d.size() - 1, d.size() * 0.95));
                    m.vfr = (m.fpsMax - m.fpsMin) / median > 0.12;
                    if (m.durationUs > 0 && ts.size() < 240) m.fps = (ts.size() - 1) * 1e6 / (ts.get(ts.size() - 1) - ts.get(0));
                }
            }
        } finally {
            ex.release();
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, uri);
            String br = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
            if (br != null) m.bitrate = Long.parseLong(br);
            if (m.durationUs <= 0) {
                String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (d != null) m.durationUs = Long.parseLong(d) * 1000;
            }
            String fc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT);
            if (fc != null) {
                m.frames = Integer.parseInt(fc);
                if (m.durationUs > 0) m.fps = m.frames * 1e6 / m.durationUs;
            }
            String loc = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION);
            m.hasGps = loc != null && !loc.isEmpty();
        } catch (RuntimeException ignored) {
        } finally {
            try {
                r.release();
            } catch (IOException | RuntimeException ignored) {
            }
        }
        if (m.bitrate <= 0 && m.durationUs > 0 && m.sizeBytes > 0) m.bitrate = m.sizeBytes * 8_000_000L / m.durationUs;
    }
}
