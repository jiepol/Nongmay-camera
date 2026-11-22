package com.nongmay.camera.voice;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * App-private storage of trigger phrases (files/voice/*.nmvt). Only MFCC feature sequences are
 * stored — no audio — and "delete" removes the file immediately. No backup, no cloud sync
 * (android:allowBackup="false").
 */
public final class VoiceProfileStore {
    public static final String DEFAULT_ID = "default_nongmay";
    private final File dir;

    public VoiceProfileStore(Context ctx) {
        dir = new File(ctx.getFilesDir(), "voice");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    public static String newId() {
        return "p_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private File file(String id) {
        return new File(dir, id.replaceAll("[^A-Za-z0-9_\\-]", "_") + ".nmvt");
    }

    public List<VoiceTemplate> list() {
        List<VoiceTemplate> out = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.getName().endsWith(".nmvt")) continue;
                try {
                    out.add(VoiceTemplate.fromBytes(Files.readAllBytes(f.toPath())));
                } catch (IOException | RuntimeException ignored) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete(); // unreadable/old format: remove rather than crash
                }
            }
        }
        Collections.sort(out, new Comparator<VoiceTemplate>() {
            @Override
            public int compare(VoiceTemplate a, VoiceTemplate b) {
                if (a.id.equals(DEFAULT_ID)) return -1;
                if (b.id.equals(DEFAULT_ID)) return 1;
                return Long.compare(a.createdAt, b.createdAt);
            }
        });
        return out;
    }

    public VoiceTemplate get(String id) {
        if (id == null) return null;
        File f = file(id);
        if (!f.exists()) return null;
        try {
            return VoiceTemplate.fromBytes(Files.readAllBytes(f.toPath()));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public void save(VoiceTemplate t) throws IOException {
        File tmp = new File(dir, file(t.id).getName() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(t.toBytes());
            os.getFD().sync();
        }
        if (!tmp.renameTo(file(t.id))) throw new IOException("rename failed");
    }

    public boolean delete(String id) {
        return file(id).delete();
    }

    public int deleteAll() {
        int n = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) if (f.delete()) n++;
        return n;
    }
}
