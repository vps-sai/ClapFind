package com.findbyclap.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;

import java.util.HashSet;
import java.util.Set;

public class ListenService extends Service {
    public static final String PREFS = "clapfind";
    public static final String ACTION_START = "com.findbyclap.app.START";
    public static final String ACTION_STOP = "com.findbyclap.app.STOP";
    public static final String ACTION_SILENCE = "com.findbyclap.app.SILENCE";
    public static final String ACTION_TEST = "com.findbyclap.app.TEST";
    public static final String ACTION_RELOAD = "com.findbyclap.app.RELOAD";

    private static final String CH_STATUS = "status";
    private static final String CH_ALARM = "alarm";
    private static final int ID_STATUS = 1;
    private static final int ID_ALARM = 2;
    private static final int ID_REMINDER = 3;
    private static final long RING_MS = 45_000;

    // read by the screen
    public static volatile boolean running;
    public static volatile boolean ringing;
    public static volatile boolean listeningNow;
    public static volatile int level;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final SoundDetector detector = new SoundDetector();
    private final Set<Network> wifiNetworks = new HashSet<>();

    private volatile boolean alive;
    private volatile boolean wifiOnly = true;
    private volatile long resumeAt;
    private Thread audioThread;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager.NetworkCallback wifiCallback;
    private BroadcastReceiver unlockReceiver;

    private MediaPlayer player;
    private AudioTrack synthTrack;
    private int savedAlarmVolume = -1;
    private String torchId;
    private boolean torchOn;
    private boolean shownListening;

    private final Runnable autoStop = this::stopRinging;
    private final Runnable torchBlink = new Runnable() {
        @Override
        public void run() {
            if (!ringing) {
                return;
            }
            setTorch(!torchOn);
            main.postDelayed(this, 400);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (action == null) {
            action = ACTION_START;
        }

        if (ACTION_START.equals(action)) {
            if (!goForeground()) {
                // Android refused (for example after the system restarted us in the background).
                postResumeReminder(this);
                stopSelf();
                return START_NOT_STICKY;
            }
            if (!running) {
                running = true;
                startWork();
            }
            loadSettings();
            return START_STICKY;
        }

        if (!running) {
            stopSelf();
            return START_NOT_STICKY;
        }
        switch (action) {
            case ACTION_STOP:
                stopRinging();
                stopForeground(true);
                stopSelf();
                break;
            case ACTION_SILENCE:
                stopRinging();
                break;
            case ACTION_TEST:
                startRinging();
                break;
            case ACTION_RELOAD:
                loadSettings();
                break;
            default:
                break;
        }
        return START_STICKY;
    }

    private boolean goForeground() {
        try {
            ensureChannels(this);
            Notification n = statusNotification(listeningNow);
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(ID_STATUS, n);
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        detector.configure(p.getBoolean("clap", true), p.getBoolean("whistle", true), p.getInt("sens", 60));
        wifiOnly = p.getBoolean("wifiOnly", true);
    }

    private void startWork() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clapfind:listen");
        wakeLock.setReferenceCounted(false);

        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                synchronized (wifiNetworks) {
                    wifiNetworks.add(network);
                }
            }

            @Override
            public void onLost(Network network) {
                synchronized (wifiNetworks) {
                    wifiNetworks.remove(network);
                }
            }
        };
        try {
            cm.registerNetworkCallback(
                    new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    wifiCallback);
        } catch (RuntimeException e) {
            wifiCallback = null;
        }

        // Unlocking the phone means it has been found: stop the noise.
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                stopRinging();
            }
        };
        IntentFilter unlocked = new IntentFilter(Intent.ACTION_USER_PRESENT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(unlockReceiver, unlocked, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(unlockReceiver, unlocked);
        }

        alive = true;
        audioThread = new Thread(this::audioLoop, "clapfind-audio");
        audioThread.start();
    }

    private boolean onWifi() {
        synchronized (wifiNetworks) {
            return !wifiNetworks.isEmpty();
        }
    }

    private boolean shouldListen() {
        return !ringing
                && SystemClock.elapsedRealtime() >= resumeAt
                && (!wifiOnly || wifiCallback == null || onWifi());
    }

    // ------------------------------------------------------------------ listening

    private void audioLoop() {
        short[] buf = new short[SoundDetector.FRAME];
        AudioRecord rec = null;
        while (alive) {
            if (!shouldListen()) {
                if (rec != null) {
                    release(rec);
                    rec = null;
                }
                setListening(false);
                pause(400);
                continue;
            }
            if (rec == null) {
                rec = openRecorder();
                if (rec == null) {
                    setListening(false);
                    pause(3000);
                    continue;
                }
                detector.reset();
                try {
                    wakeLock.acquire();
                } catch (RuntimeException ignored) {
                }
            }
            setListening(true);

            int got = 0;
            while (got < buf.length) {
                int n = rec.read(buf, got, buf.length - got);
                if (n <= 0) {
                    got = -1;
                    break;
                }
                got += n;
            }
            if (got < 0) {
                release(rec);
                rec = null;
                pause(1000);
                continue;
            }

            int event = detector.process(buf);
            level = detector.level();
            if (event != SoundDetector.NONE) {
                main.post(this::startRinging);
                pause(300);
            }
        }
        if (rec != null) {
            release(rec);
        }
        setListening(false);
    }

    private AudioRecord openRecorder() {
        int min = AudioRecord.getMinBufferSize(SoundDetector.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) {
            return null;
        }
        int size = Math.max(min, SoundDetector.FRAME * 2 * 4);
        int[] sources = {MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        for (int source : sources) {
            AudioRecord r = null;
            try {
                r = new AudioRecord(source, SoundDetector.SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
                if (r.getState() == AudioRecord.STATE_INITIALIZED) {
                    r.startRecording();
                    if (r.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        return r;
                    }
                }
            } catch (RuntimeException ignored) {
                // permission missing or microphone busy; try the next option
            }
            if (r != null) {
                try {
                    r.release();
                } catch (RuntimeException ignored) {
                }
            }
        }
        return null;
    }

    private void release(AudioRecord rec) {
        try {
            rec.stop();
        } catch (RuntimeException ignored) {
        }
        try {
            rec.release();
        } catch (RuntimeException ignored) {
        }
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (RuntimeException ignored) {
        }
    }

    private void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    private void setListening(boolean now) {
        listeningNow = now;
        if (!now) {
            level = 0;
        }
        if (now != shownListening && alive) {
            shownListening = now;
            try {
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                        .notify(ID_STATUS, statusNotification(now));
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ ringing

    private void startRinging() {
        if (ringing || !running) {
            return;
        }
        ringing = true;

        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        try {
            savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM,
                    am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0);
        } catch (RuntimeException ignored) {
        }

        AudioAttributes alarmAttrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        String choice = getSharedPreferences(PREFS, MODE_PRIVATE).getString("sound", "default");

        if (ToneSynth.isBuiltIn(choice)) {
            try {
                short[] pcm = ToneSynth.make(choice);
                AudioTrack t = new AudioTrack.Builder()
                        .setAudioAttributes(alarmAttrs)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(ToneSynth.RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .setBufferSizeInBytes(pcm.length * 2)
                        .build();
                t.write(pcm, 0, pcm.length);
                t.setLoopPoints(0, pcm.length, -1);
                t.play();
                synthTrack = t;
            } catch (RuntimeException e) {
                synthTrack = null;
            }
        }

        if (synthTrack == null) {
            Uri chosen = null;
            if (choice.startsWith("uri:")) {
                try {
                    chosen = Uri.parse(choice.substring(4));
                } catch (RuntimeException ignored) {
                }
            }
            Uri[] sounds = {
                    chosen,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            };
            for (Uri sound : sounds) {
                if (sound == null) {
                    continue;
                }
                MediaPlayer mp = new MediaPlayer();
                try {
                    mp.setAudioAttributes(alarmAttrs);
                    mp.setDataSource(this, sound);
                    mp.setLooping(true);
                    mp.prepare();
                    mp.start();
                    player = mp;
                    break;
                } catch (Exception e) {
                    mp.release();
                }
            }
        }

        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) {
                v.vibrate(VibrationEffect.createWaveform(new long[]{0, 500, 300}, 0));
            }
        } catch (RuntimeException ignored) {
        }

        torchId = findTorch();
        main.post(torchBlink);

        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(ID_ALARM, alarmNotification());
        } catch (RuntimeException ignored) {
        }
        main.postDelayed(autoStop, RING_MS);
    }

    private void stopRinging() {
        if (!ringing) {
            return;
        }
        ringing = false;
        main.removeCallbacks(autoStop);
        main.removeCallbacks(torchBlink);
        setTorch(false);

        if (synthTrack != null) {
            try {
                synthTrack.stop();
            } catch (RuntimeException ignored) {
            }
            synthTrack.release();
            synthTrack = null;
        }
        if (player != null) {
            try {
                player.stop();
            } catch (RuntimeException ignored) {
            }
            player.release();
            player = null;
        }
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) {
                v.cancel();
            }
        } catch (RuntimeException ignored) {
        }
        if (savedAlarmVolume >= 0) {
            try {
                ((AudioManager) getSystemService(AUDIO_SERVICE))
                        .setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0);
            } catch (RuntimeException ignored) {
            }
            savedAlarmVolume = -1;
        }
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(ID_ALARM);
        } catch (RuntimeException ignored) {
        }
        // give the room a moment to go quiet before listening again
        resumeAt = SystemClock.elapsedRealtime() + 2500;
    }

    private String findTorch() {
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void setTorch(boolean on) {
        torchOn = on;
        if (torchId == null) {
            return;
        }
        try {
            ((CameraManager) getSystemService(CAMERA_SERVICE)).setTorchMode(torchId, on);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ notifications

    static void ensureChannels(Context c) {
        NotificationManager nm = (NotificationManager) c.getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel status = new NotificationChannel(CH_STATUS, "Listening status",
                NotificationManager.IMPORTANCE_LOW);
        status.setShowBadge(false);
        nm.createNotificationChannel(status);
        NotificationChannel alarm = new NotificationChannel(CH_ALARM, "Phone found alert",
                NotificationManager.IMPORTANCE_HIGH);
        alarm.setSound(null, null);
        nm.createNotificationChannel(alarm);
    }

    private static PendingIntent openApp(Context c) {
        return PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent serviceAction(String action, int code) {
        return PendingIntent.getService(this, code,
                new Intent(this, ListenService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification statusNotification(boolean listening) {
        return new Notification.Builder(this, CH_STATUS)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(listening ? "ClapFind is listening" : "ClapFind is paused")
                .setContentText(listening ? "Clap 3 times or whistle to find this phone"
                        : "Waiting for Wi-Fi")
                .setContentIntent(openApp(this))
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Turn off",
                        serviceAction(ACTION_STOP, 1)).build())
                .build();
    }

    private Notification alarmNotification() {
        return new Notification.Builder(this, CH_ALARM)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Here I am!")
                .setContentText("Tap to stop ringing")
                .setContentIntent(serviceAction(ACTION_SILENCE, 2))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_ALARM)
                .setAutoCancel(true)
                .addAction(new Notification.Action.Builder(null, "Stop ringing",
                        serviceAction(ACTION_SILENCE, 2)).build())
                .build();
    }

    static void postResumeReminder(Context c) {
        try {
            ensureChannels(c);
            Notification n = new Notification.Builder(c, CH_STATUS)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("ClapFind is off")
                    .setContentText("Tap to turn listening back on")
                    .setContentIntent(openApp(c))
                    .setAutoCancel(true)
                    .build();
            ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).notify(ID_REMINDER, n);
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void onDestroy() {
        stopRinging();
        alive = false;
        running = false;
        if (audioThread != null) {
            audioThread.interrupt();
            audioThread = null;
        }
        if (wifiCallback != null) {
            try {
                ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE))
                        .unregisterNetworkCallback(wifiCallback);
            } catch (RuntimeException ignored) {
            }
            wifiCallback = null;
        }
        if (unlockReceiver != null) {
            try {
                unregisterReceiver(unlockReceiver);
            } catch (RuntimeException ignored) {
            }
            unlockReceiver = null;
        }
        listeningNow = false;
        level = 0;
        super.onDestroy();
    }
}
