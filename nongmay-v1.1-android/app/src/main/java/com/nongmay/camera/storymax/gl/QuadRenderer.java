package com.nongmay.camera.storymax.gl;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;

import com.nongmay.camera.storymax.ToneMap;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Draws the decoder's external texture into the encoder surface: one textured quad whose vertices
 * place the (cropped / fitted) picture and whose texture coordinates undo the container rotation.
 * Two programs: plain samplerExternalOES (SDR, or HDR already tone-mapped by the decoder) and the
 * GL_EXT_YUV_target HDR→SDR shader from {@link ToneMap}.
 */
public final class QuadRenderer {
    private static final String VS2 = "uniform mat4 uTexMatrix;\n"
            + "attribute vec4 aPosition;\nattribute vec4 aTexCoord;\nvarying vec2 vTex;\n"
            + "void main(){ gl_Position = aPosition; vTex = (uTexMatrix * aTexCoord).xy; }\n";
    private static final String FS2 = "#extension GL_OES_EGL_image_external : require\n"
            + "precision mediump float;\nvarying vec2 vTex;\nuniform samplerExternalOES uTex;\n"
            + "void main(){ gl_FragColor = texture2D(uTex, vTex); }\n";
    private static final String VS3 = "#version 300 es\nuniform mat4 uTexMatrix;\n"
            + "in vec4 aPosition;\nin vec4 aTexCoord;\nout vec2 vTex;\n"
            + "void main(){ gl_Position = aPosition; vTex = (uTexMatrix * aTexCoord).xy; }\n";

    private final int program;
    private final int aPos, aTex, uTexMatrix, uTex, uTransfer, uFullRange, uPeakRel;
    private final int texture;
    private final boolean hdr;
    private final FloatBuffer pos = alloc(8), tex = alloc(8);

    /** Creates the external OES texture the decoder's SurfaceTexture will feed (needs a current GL context). */
    public static int createExternalTexture() {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, t[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        check("texture setup");
        return t[0];
    }

    public QuadRenderer(boolean hdrShader, int externalTexture) {
        hdr = hdrShader;
        program = hdrShader ? link(VS3, ToneMap.FRAGMENT_YUV_HDR) : link(VS2, FS2);
        aPos = GLES20.glGetAttribLocation(program, "aPosition");
        aTex = GLES20.glGetAttribLocation(program, "aTexCoord");
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix");
        uTex = GLES20.glGetUniformLocation(program, "uTex");
        uTransfer = hdrShader ? GLES20.glGetUniformLocation(program, "uTransfer") : -1;
        uFullRange = hdrShader ? GLES20.glGetUniformLocation(program, "uFullRange") : -1;
        uPeakRel = hdrShader ? GLES20.glGetUniformLocation(program, "uPeakRel") : -1;
        texture = externalTexture;
    }

    public int textureId() {
        return texture;
    }

    /**
     * @param ndc      8 floats: BL, BR, TL, TR vertex positions in normalised device coordinates
     * @param st       8 floats: matching texture coordinates in SurfaceTexture "s,t" space (t up)
     * @param stMatrix SurfaceTexture.getTransformMatrix
     */
    public void draw(float[] ndc, float[] st, float[] stMatrix, int viewportW, int viewportH, int transfer, boolean fullRange, float peakRel) {
        GLES20.glViewport(0, 0, viewportW, viewportH);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glUniform1i(uTex, 0);
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, stMatrix, 0);
        if (hdr) {
            GLES20.glUniform1i(uTransfer, transfer);
            GLES20.glUniform1i(uFullRange, fullRange ? 1 : 0);
            GLES20.glUniform1f(uPeakRel, peakRel);
        }
        pos.clear();
        pos.put(ndc).position(0);
        tex.clear();
        tex.put(st).position(0);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, pos);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, tex);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aTex);
        check("draw");
    }

    public void release() {
        GLES20.glDeleteProgram(program);
        GLES20.glDeleteTextures(1, new int[]{texture}, 0);
    }

    private static FloatBuffer alloc(int n) {
        return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new IllegalStateException("shader compile failed: " + log);
        }
        return s;
    }

    private static int link(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("program link failed: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static void check(String where) {
        int e = GLES20.glGetError();
        if (e != GLES20.GL_NO_ERROR) throw new IllegalStateException("GL error 0x" + Integer.toHexString(e) + " at " + where);
    }
}
