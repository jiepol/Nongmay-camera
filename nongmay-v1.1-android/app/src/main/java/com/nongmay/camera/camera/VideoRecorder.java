package com.nongmay.camera.camera;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.os.Build;
import android.view.Surface;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;

/**
 * MediaRecorder helpers. A persistent input surface is created once per video session, so
 * starting/stopping a clip only swaps the recorder, never the camera session (fast record button).
 */
final class VideoRecorder {
    private VideoRecorder() {}

    static MediaRecorder newRecorder(Context ctx) {
        if (Build.VERSION.SDK_INT >= 31) return new MediaRecorder(ctx);
        //noinspection deprecation
        return new MediaRecorder();
    }

    /** Persistent surface whose buffer size/format are fixed by a throw-away prepare() (Camera2Video pattern). */
    static Surface createPersistentSurface(Context ctx, CameraController.SessionSpec spec) throws IOException {
        Surface surface = MediaCodec.createPersistentInputSurface();
        MediaRecorder r = newRecorder(ctx);
        File tmp = new File(ctx.getCacheDir(), "recorder_prepare.mp4");
        try {
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setOutputFile(tmp.getAbsolutePath());
            applyVideo(r, spec);
            r.setInputSurface(surface);
            r.prepare();
        } catch (IOException | RuntimeException e) {
            surface.release();
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        } finally {
            r.release();
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
        return surface;
    }

    static void applyVideo(MediaRecorder r, CameraController.SessionSpec spec) {
        r.setVideoEncodingBitRate(spec.bitrate);
        r.setVideoFrameRate(spec.fps);
        r.setVideoSize(spec.video.getWidth(), spec.video.getHeight());
        r.setVideoEncoder("video/hevc".equals(spec.codec) ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
    }

    static MediaRecorder start(Context ctx, Surface persistent, CameraController.SessionSpec spec, FileDescriptor fd,
                               boolean audio, int orientationHint, long maxBytes) throws IOException {
        MediaRecorder r = newRecorder(ctx);
        try {
            if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setOutputFile(fd);
            applyVideo(r, spec);
            if (audio) {
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                r.setAudioEncodingBitRate(192_000);
                r.setAudioSamplingRate(48_000);
                r.setAudioChannels(2);
            }
            r.setOrientationHint(orientationHint);
            if (maxBytes > 0) r.setMaxFileSize(maxBytes);
            r.setInputSurface(persistent);
            r.prepare();
            return r;
        } catch (IOException | RuntimeException e) {
            r.release();
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }
    }

    /** Sensible default bitrate (H.264 bits per pixel per frame ≈ 0.11; HEVC ≈ 0.07). */
    static int autoBitrate(int w, int h, int fps, boolean hevc) {
        double bpp = hevc ? 0.07 : 0.11;
        long br = Math.round((double) w * h * fps * bpp);
        return (int) Math.max(4_000_000, Math.min(br, 120_000_000));
    }
}
