package com.findbyclap.app;

/**
 * Decides whether the microphone is hearing three claps or a long whistle.
 * Works on 32 ms slices of sound held in memory only. Nothing is stored.
 */
public final class SoundDetector {
    public static final int SAMPLE_RATE = 16000;
    public static final int FRAME = 512;
    public static final int NONE = 0, CLAP = 1, WHISTLE = 2;

    private static final int CLAPS_NEEDED = 3;

    private volatile boolean clapEnabled = true;
    private volatile boolean whistleEnabled = true;
    private volatile double sens = 0.5; // 0 = least sensitive, 1 = most

    private final double[] re = new double[FRAME];
    private final double[] im = new double[FRAME];
    private final double[] window = new double[FRAME];

    private long frameNo;
    private double ambient = 200;
    private double prevRms;
    private double lastRms;

    // whistle state
    private int whistleRun, whistleMiss;
    private double lastWhistleHz;

    // clap state
    private long onsetFrame = -1;
    private double onsetPeak;
    private long lastClapFrame = -1000;
    private int clapCount;

    // results of the most recent spectrum analysis
    private double tonalRatio, highShare, peakHz;

    public SoundDetector() {
        for (int i = 0; i < FRAME; i++) {
            window[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FRAME - 1));
        }
    }

    public void configure(boolean clap, boolean whistle, int sensitivityPercent) {
        clapEnabled = clap;
        whistleEnabled = whistle;
        sens = Math.max(0, Math.min(100, sensitivityPercent)) / 100.0;
    }

    public void reset() {
        frameNo = 0;
        ambient = 200;
        prevRms = 0;
        whistleRun = 0;
        whistleMiss = 0;
        onsetFrame = -1;
        lastClapFrame = -1000;
        clapCount = 0;
    }

    /** Loudness of the last slice, 0..100, for the on-screen meter. */
    public int level() {
        double db = 20 * Math.log10(lastRms + 1);
        return (int) Math.max(0, Math.min(100, (db - 25) * 100 / 60));
    }

    /** Feed exactly FRAME samples. Returns NONE, CLAP or WHISTLE. */
    public int process(short[] buf) {
        frameNo++;
        double sum = 0;
        for (int i = 0; i < FRAME; i++) {
            sum += (double) buf[i] * buf[i];
        }
        double rms = Math.sqrt(sum / FRAME);
        lastRms = rms;

        double minLevel = 1500 * Math.pow(0.08, sens); // how loud a clap must be
        double whistleMin = minLevel * 0.4;
        boolean haveSpectrum = false;
        int result = NONE;

        // ---- whistle: one steady, pure tone held for about a second ----
        if (whistleEnabled) {
            boolean tonal = false;
            if (rms > whistleMin) {
                analyse(buf);
                haveSpectrum = true;
                tonal = tonalRatio > 0.7 && peakHz >= 800 && peakHz <= 4500
                        && (whistleRun == 0 || Math.abs(peakHz - lastWhistleHz) < 400);
            }
            if (tonal) {
                whistleRun++;
                whistleMiss = 0;
                lastWhistleHz = peakHz;
            } else if (whistleRun > 0 && ++whistleMiss > 2) {
                whistleRun = 0;
                whistleMiss = 0;
            }
            int framesNeeded = (int) Math.round(30 - 14 * sens); // ~1.0 s down to ~0.5 s
            if (whistleRun >= framesNeeded) {
                whistleRun = 0;
                result = WHISTLE;
            }
        }

        // ---- clap: sudden, broadband bang that dies away quickly, three in a row ----
        if (clapEnabled && result == NONE) {
            if (frameNo - lastClapFrame > 34) {
                clapCount = 0; // too long since the last clap, start over
            }
            if (onsetFrame < 0) {
                if (rms > Math.max(ambient * 5, minLevel) && prevRms < rms * 0.5) {
                    if (!haveSpectrum) {
                        analyse(buf);
                    }
                    if (tonalRatio < 0.5 && highShare > 0.2) {
                        onsetFrame = frameNo;
                        onsetPeak = rms;
                    }
                }
            } else {
                long n = frameNo - onsetFrame;
                if (n <= 2) {
                    onsetPeak = Math.max(onsetPeak, rms);
                } else if (n >= 5) {
                    if (rms < 0.3 * onsetPeak) {
                        long gap = onsetFrame - lastClapFrame;
                        clapCount = (gap >= 4 && gap <= 30) ? clapCount + 1 : 1;
                        lastClapFrame = onsetFrame;
                    }
                    onsetFrame = -1;
                    if (clapCount >= CLAPS_NEEDED) {
                        clapCount = 0;
                        result = CLAP;
                    }
                }
            }
        }

        // slowly track the room's background noise
        if (onsetFrame < 0 && rms < ambient * 3) {
            ambient = 0.98 * ambient + 0.02 * rms;
        } else {
            ambient = 0.999 * ambient + 0.001 * rms;
        }
        if (ambient < 50) {
            ambient = 50;
        }
        prevRms = rms;
        return result;
    }

    private void analyse(short[] buf) {
        for (int i = 0; i < FRAME; i++) {
            re[i] = buf[i] * window[i];
            im[i] = 0;
        }
        fft(re, im);
        double binHz = (double) SAMPLE_RATE / FRAME;
        int first = (int) Math.ceil(300 / binHz);
        int half = FRAME / 2;
        double total = 0, high = 0, best = 0;
        int bestBin = first;
        for (int k = first; k < half; k++) {
            double p = re[k] * re[k] + im[k] * im[k];
            re[k] = p; // reuse as power
            total += p;
            if (k * binHz > 1500) {
                high += p;
            }
            if (p > best) {
                best = p;
                bestBin = k;
            }
        }
        double around = 0;
        for (int k = Math.max(first, bestBin - 2); k <= Math.min(half - 1, bestBin + 2); k++) {
            around += re[k];
        }
        if (total <= 0) {
            tonalRatio = 0;
            highShare = 0;
        } else {
            tonalRatio = around / total;
            highShare = high / total;
        }
        peakHz = bestBin * binHz;
    }

    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }
}
