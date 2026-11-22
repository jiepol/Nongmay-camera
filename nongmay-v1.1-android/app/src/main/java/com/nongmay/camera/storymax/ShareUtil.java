package com.nongmay.camera.storymax;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

/**
 * Android-authorised sharing only. The app never logs in, posts, or uses a private Instagram API:
 * it hands the file to the system share sheet or to Instagram's normal share target, and Pol posts.
 */
public final class ShareUtil {
    public static final String INSTAGRAM = "com.instagram.android";

    private ShareUtil() {}

    public static Intent chooser(Uri uri, String mime, String title) {
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType(mime);
        s.putExtra(Intent.EXTRA_STREAM, uri);
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent c = Intent.createChooser(s, title);
        c.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return c;
    }

    public static boolean instagramInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(INSTAGRAM, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Opens Instagram's own share target (it then offers Story / Feed / Reels / Direct). */
    public static Intent instagram(Uri uri, String mime) {
        Intent s = new Intent(Intent.ACTION_SEND);
        s.setType(mime);
        s.putExtra(Intent.EXTRA_STREAM, uri);
        s.setPackage(INSTAGRAM);
        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return s;
    }

    public static Intent view(Uri uri, String mime) {
        Intent v = new Intent(Intent.ACTION_VIEW);
        v.setDataAndType(uri, mime);
        v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return v;
    }
}
