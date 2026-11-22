package com.nongmay.camera.voice;

/** Iterative radix-2 complex FFT with precomputed twiddles. Pure Java; not thread-safe (reuses buffers). */
public final class Fft {
    private final int n;
    private final double[] cosT, sinT;
    private final int[] rev;
    private final double[] re, im;

    public Fft(int n) {
        if (n < 2 || Integer.bitCount(n) != 1) throw new IllegalArgumentException("n must be a power of two");
        this.n = n;
        int bits = Integer.numberOfTrailingZeros(n);
        cosT = new double[n / 2];
        sinT = new double[n / 2];
        for (int i = 0; i < n / 2; i++) {
            cosT[i] = Math.cos(-2 * Math.PI * i / n);
            sinT[i] = Math.sin(-2 * Math.PI * i / n);
        }
        rev = new int[n];
        for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        re = new double[n];
        im = new double[n];
    }

    public int size() {
        return n;
    }

    /** In-place forward transform. */
    public void transform(double[] re, double[] im) {
        for (int i = 0; i < n; i++) {
            int j = rev[i];
            if (j > i) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int size = 2; size <= n; size <<= 1) {
            int half = size >> 1;
            int step = n / size;
            for (int i = 0; i < n; i += size) {
                for (int j = 0, k = 0; j < half; j++, k += step) {
                    int a = i + j, b = a + half;
                    double tr = re[b] * cosT[k] - im[b] * sinT[k];
                    double ti = re[b] * sinT[k] + im[b] * cosT[k];
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                }
            }
        }
    }

    /** |X[k]|^2 for k = 0..n/2 of a real frame of length len (zero-padded to n). */
    public void power(double[] frame, int len, double[] out) {
        for (int i = 0; i < n; i++) {
            re[i] = i < len ? frame[i] : 0.0;
            im[i] = 0.0;
        }
        transform(re, im);
        for (int k = 0; k <= n / 2; k++) out[k] = re[k] * re[k] + im[k] * im[k];
    }
}
