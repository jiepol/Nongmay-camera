package com.nongmay.camera.storymax;

import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/** One Story export request + its measured outcome. */
public final class ExportJob {
    public interface Progress {
        void onProgress(float fraction, String stage);
    }

    // request
    public MediaInfo source;
    public StoryProfiles.Profile profile;
    public int mode = CropMath.FILL;      // FILL/REFRAME (crop) or FIT (letterbox)
    public boolean blurBackground;
    public double[] cropOriented;         // l,t,r,b in oriented source pixels (FILL/REFRAME)
    public boolean keepCameraExif;
    public String experimentId;
    public String blindCode;
    public volatile boolean cancelled;

    // result
    public Uri output;
    public String outputName;
    public MediaInfo actual;
    public long elapsedMs;
    public String error;
    public final List<String> notes = new ArrayList<>();

    public boolean ok() {
        return error == null && output != null;
    }

    public String notesText() {
        StringBuilder sb = new StringBuilder();
        for (String n : notes) sb.append("• ").append(n).append('\n');
        return sb.toString().trim();
    }
}
