package com.nongmay.camera.storymax.gl;

import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.view.Surface;

/** EGL context bound to a MediaCodec encoder input surface (EGL_RECORDABLE_ANDROID). */
public final class EglCore {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private final int version;

    public EglCore(Surface window, boolean wantEs3) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("no EGL display");
        int[] v = new int[2];
        if (!EGL14.eglInitialize(display, v, 0, v, 1)) throw new IllegalStateException("eglInitialize failed");
        EGLConfig config = null;
        int ver = 0;
        if (wantEs3) {
            config = choose(EGLExt.EGL_OPENGL_ES3_BIT_KHR);
            if (config != null) ver = 3;
        }
        if (config == null) {
            config = choose(EGL14.EGL_OPENGL_ES2_BIT);
            ver = 2;
        }
        if (config == null) throw new IllegalStateException("no recordable EGL config");
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, ver, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("eglCreateContext failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
        surface = EGL14.eglCreateWindowSurface(display, config, window, new int[]{EGL14.EGL_NONE}, 0);
        if (surface == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("eglCreateWindowSurface failed: 0x" + Integer.toHexString(EGL14.eglGetError()));
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw new IllegalStateException("eglMakeCurrent failed");
        version = ver;
    }

    private EGLConfig choose(int renderable) {
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, renderable, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) return null;
        return configs[0];
    }

    public int glVersion() {
        return version;
    }

    public boolean hasExtension(String name) {
        String ext = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        return ext != null && (" " + ext + " ").contains(" " + name + " ");
    }

    public void setPresentationTime(long nanos) {
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos);
    }

    public boolean swap() {
        return EGL14.eglSwapBuffers(display, surface);
    }

    public void release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
        surface = EGL14.EGL_NO_SURFACE;
    }
}
