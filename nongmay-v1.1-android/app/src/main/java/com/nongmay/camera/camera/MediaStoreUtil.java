package com.nongmay.camera.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Scoped-storage writes through MediaStore only (no storage permission). Items are created with
 * IS_PENDING = 1 and published after the last byte is written, so a crash never leaves a
 * half-written photo visible in the gallery.
 */
public final class MediaStoreUtil {
    public static final String PHOTO_DIR = Environment.DIRECTORY_PICTURES + "/NongMay";
    public static final String VIDEO_DIR = Environment.DIRECTORY_MOVIES + "/NongMay";
    public static final String STORY_PHOTO_DIR = PHOTO_DIR + "/Story";
    public static final String STORY_VIDEO_DIR = VIDEO_DIR + "/Story";
    public static final String REPORT_DIR = Environment.DIRECTORY_DOWNLOADS + "/NongMay";
    public static final long MIN_FREE_BYTES = 80L * 1024 * 1024;

    private MediaStoreUtil() {}

    public static String timestampName(String prefix) {
        return prefix + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date());
    }

    static Uri collectionFor(String mime) {
        if (mime.startsWith("image/")) return MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        if (mime.startsWith("video/")) return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        return MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
    }

    public static Uri insertPending(Context ctx, String displayName, String mime, String relPath) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        if (mime.startsWith("image/") || mime.startsWith("video/")) {
            v.put(MediaStore.MediaColumns.DATE_TAKEN, System.currentTimeMillis());
        }
        Uri uri = ctx.getContentResolver().insert(collectionFor(mime), v);
        if (uri == null) throw new IOException("MediaStore refused to create " + displayName);
        return uri;
    }

    public static void publish(Context ctx, Uri uri) {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        ctx.getContentResolver().update(uri, v, null, null);
    }

    public static void delete(Context ctx, Uri uri) {
        if (uri == null) return;
        try {
            ctx.getContentResolver().delete(uri, null, null);
        } catch (RuntimeException ignored) {
        }
    }

    public static Uri saveBytes(Context ctx, byte[] data, String displayName, String mime, String relPath) throws IOException {
        Uri uri = insertPending(ctx, displayName, mime, relPath);
        try (OutputStream os = ctx.getContentResolver().openOutputStream(uri, "w")) {
            if (os == null) throw new IOException("cannot open " + uri);
            os.write(data);
        } catch (IOException | RuntimeException e) {
            delete(ctx, uri);
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        }
        publish(ctx, uri);
        return uri;
    }

    /** Reports (Markdown/CSV/JSON) go to Download/NongMay so Pol can find and send them. */
    public static Uri saveText(Context ctx, String text, String displayName, String mime) throws IOException {
        return saveBytes(ctx, text.getBytes(StandardCharsets.UTF_8), displayName, mime, REPORT_DIR);
    }

    public static long freeBytes() {
        try {
            StatFs s = new StatFs(Environment.getExternalStorageDirectory().getPath());
            return s.getAvailableBytes();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    public static long sizeOf(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        } catch (RuntimeException ignored) {
        }
        return -1;
    }

    /** Publishes non-empty photos or deletes videos/reports that a crash left pending. */
    public static int cleanupOrphans(Context ctx) {
        int fixed = 0;
        String[] mimes = {"image/jpeg", "video/mp4", "text/markdown"};
        for (String mime : mimes) {
            Uri coll = collectionFor(mime);
            String[] proj = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED,
                    MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE};
            Cursor c = null;
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    Bundle args = new Bundle();
                    args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY);
                    c = ctx.getContentResolver().query(coll, proj, args, null);
                } else {
                    @SuppressWarnings("deprecation")
                    Uri pending = MediaStore.setIncludePending(coll);
                    c = ctx.getContentResolver().query(pending, proj, MediaStore.MediaColumns.IS_PENDING + "=1", null, null);
                }
                if (c == null) continue;
                long now = System.currentTimeMillis() / 1000;
                while (c.moveToNext()) {
                    String path = c.getString(3);
                    if (path == null || !path.contains("NongMay")) continue;
                    if (now - c.getLong(2) < 120) continue; // may still be in use by a running export
                    Uri item = Uri.withAppendedPath(coll, String.valueOf(c.getLong(0)));
                    String m = c.getString(4);
                    if (m != null && m.startsWith("image/") && c.getLong(1) > 0) publish(ctx, item);
                    else delete(ctx, item);
                    fixed++;
                }
            } catch (RuntimeException ignored) {
            } finally {
                if (c != null) c.close();
            }
        }
        return fixed;
    }

    public static String displayName(ContentResolver cr, Uri uri) {
        try (Cursor c = cr.query(uri, new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
        }
        String s = uri.getLastPathSegment();
        return s == null ? "media" : s;
    }
}
