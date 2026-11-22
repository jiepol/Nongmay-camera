package com.nongmay.camera.storymax;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.Surface;

import com.nongmay.camera.camera.EncoderCaps;
import com.nongmay.camera.camera.MediaStoreUtil;
import com.nongmay.camera.storymax.gl.EglCore;
import com.nongmay.camera.storymax.gl.QuadRenderer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Story video export, streaming end to end (no frames kept in memory):
 * MediaExtractor -> hardware decoder -> SurfaceTexture -> GL (crop / fit / rotation,
 * optional HDR->SDR) -> hardware H.264 encoder (BT.709 SDR) -> MediaMuxer, with the AAC track
 * copied untouched. DIRECT_ORIGINAL is a stream copy into a new MP4 (no re-encode).
 */
public final class VideoTranscoder {
    private static final long TIMEOUT_US = 10_000;

    public static final class Cancelled extends RuntimeException {
        Cancelled() {
            super("cancelled");
        }
    }

    private VideoTranscoder() {}

    public static void run(Context ctx, ExportJob job, ExportJob.Progress progress) {
        long t0 = SystemClock.uptimeMillis();
        try {
            if (job.profile.isDirect()) remux(ctx, job, progress);
            else transcode(ctx, job, progress);
            if (job.output != null) {
                job.actual = MediaProbe.probe(ctx, job.output);
                validate(job);
            }
        } catch (Cancelled c) {
            job.error = "ยกเลิกแล้ว";
            MediaStoreUtil.delete(ctx, job.output);
            job.output = null;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            MediaStoreUtil.delete(ctx, job.output);
            job.output = null;
        }
        job.elapsedMs = SystemClock.uptimeMillis() - t0;
    }

    // ===================================================================================

    private static void transcode(Context ctx, ExportJob job, ExportJob.Progress progress) throws IOException {
        MediaInfo src = job.source;
        StoryProfiles.Profile p = job.profile;
        if (src.videoTrack < 0) throw new IOException("ไม่พบแทร็กวิดีโอ");
        int outW = p.width, outH = p.height;
        int fps = (int) Math.round(Double.isNaN(src.fps) ? 30 : Math.min(src.fps, p.maxFps));
        fps = Math.max(1, Math.min(p.maxFps, fps));
        MediaCodecInfo encInfo = EncoderCaps.find("video/avc", outW, outH, fps);
        if (encInfo == null) throw new IOException("เครื่องนี้ไม่มี H.264 encoder สำหรับ " + outW + "×" + outH + "@" + fps);
        boolean landscape = !EncoderCaps.exactSize(encInfo, "video/avc", outW, outH, fps);
        int encW = landscape ? outH : outW, encH = landscape ? outW : outH;
        if (landscape) job.notes.add("encoder ไม่รับขนาดแนวตั้งตรง ๆ จึงเข้ารหัส " + encW + "×" + encH + " + ธงหมุน 90° ใน MP4");
        if (src.vfr) job.notes.add("ต้นฉบับเป็น VFR: ใช้เวลาของแต่ละเฟรมตามจริง เพดาน " + p.maxFps + " fps (ไม่สร้างเฟรมเพิ่ม)");
        else if (!Double.isNaN(src.fps) && src.fps > p.maxFps + 1) {
            job.notes.add(String.format(Locale.ROOT, "%.0f → %d fps โดยตัดเฟรม (ไม่ interpolate)", src.fps, p.maxFps));
        }

        MediaCodec encoder = null, decoder = null;
        EglCore egl = null;
        QuadRenderer renderer = null;
        SurfaceTexture st = null;
        Surface encSurface = null, decSurface = null;
        MediaExtractor vex = null, aex = null;
        MediaMuxer muxer = null;
        ParcelFileDescriptor pfd = null;
        HandlerThread cbThread = new HandlerThread("nm-export-st");
        boolean muxerStarted = false;
        try {
            encoder = MediaCodec.createByCodecName(encInfo.getName());
            MediaFormat ef = encoderFormat(encInfo, encW, encH, fps, p.bitrate, true);
            try {
                encoder.configure(ef, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (RuntimeException first) {
                encoder.release();
                encoder = MediaCodec.createByCodecName(encInfo.getName());
                encoder.configure(encoderFormat(encInfo, encW, encH, fps, p.bitrate, false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                job.notes.add("encoder ไม่รับ High profile/level ที่ขอ จึงใช้ค่าเริ่มต้นของ encoder");
            }
            encSurface = encoder.createInputSurface();
            encoder.start();

            egl = new EglCore(encSurface, true);
            int tex = QuadRenderer.createExternalTexture();
            st = new SurfaceTexture(tex);
            cbThread.start();
            final Object frameLock = new Object();
            final boolean[] frameReady = {false};
            st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture s) {
                    synchronized (frameLock) {
                        frameReady[0] = true;
                        frameLock.notifyAll();
                    }
                }
            }, new Handler(cbThread.getLooper()));
            decSurface = new Surface(st);

            vex = new MediaExtractor();
            vex.setDataSource(ctx, src.uri, null);
            vex.selectTrack(src.videoTrack);
            MediaFormat df = vex.getTrackFormat(src.videoTrack);
            if (df.containsKey(MediaFormat.KEY_ROTATION)) df.setInteger(MediaFormat.KEY_ROTATION, 0); // rotation is applied in GL
            String dmime = df.getString(MediaFormat.KEY_MIME);
            boolean hdr = src.hdr;
            if (hdr && Build.VERSION.SDK_INT >= 31) df.setInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
            try {
                decoder = MediaCodec.createDecoderByType(dmime);
                decoder.configure(df, decSurface, null, 0);
            } catch (IOException | RuntimeException e) {
                throw new IOException("ถอดรหัส " + dmime + " ไม่ได้บนเครื่องนี้ (" + e.getMessage() + ") — ลองโปรไฟล์ DIRECT_ORIGINAL");
            }
            boolean decoderToneMap = false;
            if (hdr && Build.VERSION.SDK_INT >= 31) {
                MediaFormat in = decoder.getInputFormat();
                decoderToneMap = in.containsKey(MediaFormat.KEY_COLOR_TRANSFER_REQUEST)
                        && in.getInteger(MediaFormat.KEY_COLOR_TRANSFER_REQUEST) == MediaFormat.COLOR_TRANSFER_SDR_VIDEO;
            }
            boolean glToneMap = hdr && !decoderToneMap;
            if (glToneMap && (egl.glVersion() < 3 || !egl.hasExtension("GL_EXT_YUV_target"))) {
                throw new IOException("ต้นฉบับเป็น HDR แต่เครื่องนี้ไม่มีทั้ง decoder tone-mapping และ GL_EXT_YUV_target "
                        + "จึงแปลงเป็น SDR อย่างถูกต้องไม่ได้ (ไม่แปะป้าย SDR หลอก) — ใช้ DIRECT_ORIGINAL แทน");
            }
            if (decoderToneMap) job.notes.add("HDR → SDR ด้วย tone-mapping ของ decoder ในเครื่อง (MediaCodec)");
            if (glToneMap) job.notes.add("HDR → SDR ด้วย shader ของแอป (knee แบบ BT.2408/2390, BT.2020→709) · EXPERIMENTAL");
            renderer = new QuadRenderer(glToneMap, tex);
            decoder.start();

            boolean audioCopy = false;
            int aTrackOut = -1;
            MediaFormat af = null;
            if (src.hasAudio) {
                if ("audio/mp4a-latm".equals(src.audioMime)) {
                    aex = new MediaExtractor();
                    aex.setDataSource(ctx, src.uri, null);
                    aex.selectTrack(src.audioTrack);
                    af = aex.getTrackFormat(src.audioTrack);
                    audioCopy = true;
                    job.notes.add("เสียง AAC คัดลอกตรง (ไม่เข้ารหัสใหม่)");
                } else {
                    job.notes.add("⚠ เสียงต้นฉบับเป็น " + src.audioMime + " — v1.1 คัดลอกได้เฉพาะ AAC จึงไม่มีเสียงในไฟล์นี้");
                }
            }

            job.outputName = MediaStoreUtil.timestampName("NM_STORY_") + "_" + p.code + (job.blindCode != null ? "_" + job.blindCode : "") + ".mp4";
            job.output = MediaStoreUtil.insertPending(ctx, job.outputName, "video/mp4", MediaStoreUtil.STORY_VIDEO_DIR);
            pfd = ctx.getContentResolver().openFileDescriptor(job.output, "rw");
            if (pfd == null) throw new IOException("เปิดไฟล์ปลายทางไม่ได้");
            muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            if (landscape) muxer.setOrientationHint(90);

            int ow = src.orientedW(), oh = src.orientedH();
            double[] dst, srcNorm;
            if (job.mode == CropMath.FIT) {
                dst = CropMath.fitRect(ow, oh, outW, outH);
                srcNorm = new double[]{0, 0, 1, 1};
                job.notes.add("Fit: แถบดำเพิ่มเพื่อให้ได้ 9:16 โดยไม่ครอปภาพ");
            } else {
                double[] r = job.cropOriented != null ? job.cropOriented : CropMath.coverRect(ow, oh, CropMath.STORY_ASPECT, 1, 0.5, 0.5);
                dst = new double[]{0, 0, outW, outH};
                srcNorm = new double[]{r[0] / ow, r[1] / oh, r[2] / ow, r[3] / oh};
                double kept = CropMath.keptFraction(r, ow, oh);
                if (kept < 0.999) job.notes.add(String.format(Locale.ROOT, "ครอปเหลือ %.0f%% ของภาพ", kept * 100));
                if (r[2] - r[0] < outW * 0.98) job.notes.add(String.format(Locale.ROOT, "พื้นที่ที่เลือกกว้าง %.0f px ถูกขยายเป็น %d px", r[2] - r[0], outW));
            }
            float[][] quad = QuadMath.quad(outW, outH, dst, srcNorm, src.orientation(), landscape);
            float[] stMatrix = new float[16];
            int transfer = src.colorTransfer;
            boolean fullRange = src.colorRange == MediaFormat.COLOR_RANGE_FULL;
            float peakRel = (float) (ToneMap.peakNits(transfer, 0) / ToneMap.REF_WHITE_NITS);

            FrameRatePolicy policy = new FrameRatePolicy(p.maxFps);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            ByteBuffer audioBuf = ByteBuffer.allocateDirect(512 * 1024);
            MediaCodec.BufferInfo ainfo = new MediaCodec.BufferInfo();
            boolean inputDone = false, decoderDone = false, encoderDone = false, audioDone = !audioCopy;
            int videoTrackOut = -1;
            long duration = Math.max(1, src.durationUs);
            long lastProgress = SystemClock.uptimeMillis();
            long lastVideoPts = 0;
            while (!encoderDone) {
                if (job.cancelled) throw new Cancelled();
                if (SystemClock.uptimeMillis() - lastProgress > 15_000) throw new IOException("การส่งออกค้าง (ไม่มีเฟรมใหม่ 15 วินาที)");
                if (!inputDone) {
                    int ib = decoder.dequeueInputBuffer(TIMEOUT_US);
                    if (ib >= 0) {
                        ByteBuffer buf = decoder.getInputBuffer(ib);
                        int size = buf == null ? -1 : vex.readSampleData(buf, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(ib, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            decoder.queueInputBuffer(ib, 0, size, vex.getSampleTime(), 0);
                            vex.advance();
                        }
                    }
                }
                if (!decoderDone) {
                    int ob = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
                    if (ob >= 0) {
                        boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        boolean render = info.size > 0 && policy.accept(info.presentationTimeUs);
                        decoder.releaseOutputBuffer(ob, render);
                        if (render) {
                            synchronized (frameLock) {
                                long end = SystemClock.uptimeMillis() + 3000;
                                while (!frameReady[0]) {
                                    long left = end - SystemClock.uptimeMillis();
                                    if (left <= 0) throw new IOException("รอเฟรมจาก decoder เกินเวลา");
                                    try {
                                        frameLock.wait(left);
                                    } catch (InterruptedException ie) {
                                        throw new Cancelled();
                                    }
                                }
                                frameReady[0] = false;
                            }
                            st.updateTexImage();
                            st.getTransformMatrix(stMatrix);
                            renderer.draw(quad[0], quad[1], stMatrix, encW, encH, transfer, fullRange, peakRel);
                            egl.setPresentationTime(info.presentationTimeUs * 1000L);
                            egl.swap();
                            lastProgress = SystemClock.uptimeMillis();
                            progress.onProgress(Math.min(0.98f, (float) info.presentationTimeUs / duration), "เข้ารหัสวิดีโอ");
                        }
                        if (eos) {
                            encoder.signalEndOfInputStream();
                            decoderDone = true;
                        }
                    }
                }
                while (true) {
                    int eb = encoder.dequeueOutputBuffer(info, decoderDone ? TIMEOUT_US : 0);
                    if (eb == MediaCodec.INFO_TRY_AGAIN_LATER) break;
                    if (eb == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxerStarted) throw new IOException("encoder เปลี่ยนรูปแบบกลางทาง");
                        videoTrackOut = muxer.addTrack(encoder.getOutputFormat());
                        if (audioCopy) aTrackOut = muxer.addTrack(af);
                        muxer.start();
                        muxerStarted = true;
                        continue;
                    }
                    if (eb < 0) continue;
                    ByteBuffer out = encoder.getOutputBuffer(eb);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && out != null) {
                        if (!muxerStarted) throw new IOException("encoder ส่งข้อมูลก่อนรูปแบบไฟล์");
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        muxer.writeSampleData(videoTrackOut, out, info);
                        lastVideoPts = info.presentationTimeUs;
                        lastProgress = SystemClock.uptimeMillis();
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(eb, false);
                    if (!audioDone && muxerStarted) audioDone = pumpAudio(aex, muxer, aTrackOut, audioBuf, ainfo, lastVideoPts + 500_000);
                    if (eos) {
                        encoderDone = true;
                        break;
                    }
                }
            }
            if (!muxerStarted) throw new IOException("ไม่มีเฟรมวิดีโอถูกเข้ารหัส");
            while (!audioDone) audioDone = pumpAudio(aex, muxer, aTrackOut, audioBuf, ainfo, Long.MAX_VALUE);
            muxer.stop();
            muxer.release();
            muxer = null;
            pfd.close();
            pfd = null;
            MediaStoreUtil.publish(ctx, job.output);
            job.notes.add(String.format(Locale.ROOT, "เฟรมที่เข้ารหัส %d · ตัดทิ้ง %d", policy.accepted(), policy.dropped()));
            progress.onProgress(1f, "เสร็จ");
        } finally {
            if (muxer != null) {
                try {
                    if (muxerStarted) muxer.stop();
                } catch (RuntimeException ignored) {
                }
                muxer.release();
            }
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
            }
            if (decoder != null) {
                try {
                    decoder.stop();
                } catch (RuntimeException ignored) {
                }
                decoder.release();
            }
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (RuntimeException ignored) {
                }
                encoder.release();
            }
            if (renderer != null) renderer.release();
            if (decSurface != null) decSurface.release();
            if (st != null) st.release();
            if (egl != null) egl.release();
            if (encSurface != null) encSurface.release();
            if (vex != null) vex.release();
            if (aex != null) aex.release();
            cbThread.quitSafely();
        }
    }

    private static MediaFormat encoderFormat(MediaCodecInfo info, int w, int h, int fps, int bitrate, boolean profile) {
        MediaFormat f = MediaFormat.createVideoFormat("video/avc", w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        if (EncoderCaps.vbrSupported(info, "video/avc")) {
            f.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
        }
        if (profile && EncoderCaps.profileSupported(info, "video/avc", MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)) {
            f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
            f.setInteger(MediaFormat.KEY_LEVEL, QuadMath.avcLevelFor(w, h, fps));
        }
        f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709);
        f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
        f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
        return f;
    }

    /** Copies AAC samples up to {@code untilUs}. Returns true when the audio track is exhausted. */
    private static boolean pumpAudio(MediaExtractor aex, MediaMuxer muxer, int track, ByteBuffer buf, MediaCodec.BufferInfo info, long untilUs) {
        while (true) {
            long t = aex.getSampleTime();
            if (t < 0) return true;
            if (t > untilUs) return false;
            buf.clear();
            int size = aex.readSampleData(buf, 0);
            if (size < 0) return true;
            info.set(0, size, t, (aex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            muxer.writeSampleData(track, buf, info);
            aex.advance();
        }
    }

    // ===================================================================================

    /** DIRECT_ORIGINAL: copy compressed samples into a fresh MP4 (no re-encode, no location/metadata). */
    private static void remux(Context ctx, ExportJob job, ExportJob.Progress progress) throws IOException {
        MediaInfo src = job.source;
        MediaExtractor ex = new MediaExtractor();
        MediaMuxer muxer = null;
        ParcelFileDescriptor pfd = null;
        boolean started = false;
        try {
            ex.setDataSource(ctx, src.uri, null);
            int n = ex.getTrackCount();
            int[] map = new int[n];
            java.util.Arrays.fill(map, -1);
            job.outputName = MediaStoreUtil.timestampName("NM_STORY_") + "_ORIGINAL" + (job.blindCode != null ? "_" + job.blindCode : "") + ".mp4";
            job.output = MediaStoreUtil.insertPending(ctx, job.outputName, "video/mp4", MediaStoreUtil.STORY_VIDEO_DIR);
            pfd = ctx.getContentResolver().openFileDescriptor(job.output, "rw");
            if (pfd == null) throw new IOException("เปิดไฟล์ปลายทางไม่ได้");
            muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int maxIn = 4 * 1024 * 1024;
            for (int i = 0; i < n; i++) {
                MediaFormat f = ex.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime == null || !(mime.startsWith("video/") || mime.startsWith("audio/"))) continue;
                try {
                    map[i] = muxer.addTrack(f);
                } catch (RuntimeException e) {
                    throw new IOException("MP4 รับแทร็ก " + mime + " ตรง ๆ ไม่ได้ (" + e.getMessage() + ")");
                }
                ex.selectTrack(i);
                if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxIn = Math.max(maxIn, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE));
            }
            if (src.rotation != 0) muxer.setOrientationHint(src.rotation);
            muxer.start();
            started = true;
            ByteBuffer buf = ByteBuffer.allocateDirect(Math.min(maxIn, 64 * 1024 * 1024));
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long duration = Math.max(1, src.durationUs);
            while (true) {
                if (job.cancelled) throw new Cancelled();
                int ti = ex.getSampleTrackIndex();
                if (ti < 0) break;
                buf.clear();
                int size = ex.readSampleData(buf, 0);
                if (size < 0) break;
                if (map[ti] >= 0) {
                    info.set(0, size, ex.getSampleTime(),
                            (ex.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                    muxer.writeSampleData(map[ti], buf, info);
                }
                progress.onProgress(Math.min(0.98f, (float) ex.getSampleTime() / duration), "คัดลอกสตรีม");
                ex.advance();
            }
            muxer.stop();
            started = false;
            muxer.release();
            muxer = null;
            pfd.close();
            pfd = null;
            MediaStoreUtil.publish(ctx, job.output);
            job.notes.add("คัดลอกสตรีมเดิม (ไม่เข้ารหัสใหม่) ลง MP4 ใหม่ · ไม่มี GPS/metadata เดิม");
            if (src.hdr) job.notes.add("⚠ ข้อมูล HDR ระดับคอนเทนเนอร์ (เช่น Dolby Vision box) อาจไม่ถูกคัดลอกโดย MediaMuxer");
            if (Math.abs(src.orientedW() * 16 - src.orientedH() * 9) > src.orientedH() / 20) {
                job.notes.add("⚠ ต้นฉบับไม่ใช่ 9:16 — Instagram จะครอป/ย่อเอง");
            }
            progress.onProgress(1f, "เสร็จ");
        } finally {
            if (muxer != null) {
                try {
                    if (started) muxer.stop();
                } catch (RuntimeException ignored) {
                }
                muxer.release();
            }
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
            }
            ex.release();
        }
    }

    /** Compare measured output with what was requested; mismatches are reported, not hidden. */
    static void validate(ExportJob job) {
        MediaInfo a = job.actual;
        StoryProfiles.Profile p = job.profile;
        if (a == null || p.isDirect()) return;
        boolean dims = a.orientedW() == p.width && a.orientedH() == p.height;
        job.notes.add((dims ? "✓ " : "≠ ") + "ขนาดจริง " + a.orientedW() + "×" + a.orientedH());
        job.notes.add(("video/avc".equals(a.videoMime) ? "✓ " : "≠ ") + "codec " + a.videoMime);
        if (!Double.isNaN(a.fps)) job.notes.add((a.fps <= p.maxFps + 0.5 ? "✓ " : "≠ ") + String.format(Locale.ROOT, "%.2f fps", a.fps));
        job.notes.add((a.colorStandard == MediaFormat.COLOR_STANDARD_BT709 ? "✓ " : "? ") + "colour tag " + MediaInfo.standardName(a.colorStandard)
                + " / " + MediaInfo.transferName(a.colorTransfer));
        if (a.bitrate > 0) job.notes.add(String.format(Locale.ROOT, "bitrate จริง %.1f Mbps (ขอ %.0f)", a.bitrate / 1e6, p.bitrate / 1e6));
        if (job.source.hasAudio && "audio/mp4a-latm".equals(job.source.audioMime)) {
            job.notes.add((a.hasAudio ? "✓ " : "≠ ") + "เสียง " + (a.hasAudio ? a.audioMime + " " + a.sampleRate + " Hz" : "หายไป"));
        }
        if (job.source.durationUs > 0 && a.durationUs > 0) {
            double d = Math.abs(a.durationUs - job.source.durationUs) / 1e6;
            job.notes.add((d < 0.25 ? "✓ " : "≠ ") + String.format(Locale.ROOT, "ความยาว %.2f s (ต้นฉบับ %.2f s)", a.durationUs / 1e6, job.source.durationUs / 1e6));
        }
    }
}
