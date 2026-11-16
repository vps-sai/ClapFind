package com.findbyclap.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 10;

    private SharedPreferences prefs;
    private TextView status;
    private ProgressBar meter;
    private Button toggle;
    private Button ring;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        prefs = getSharedPreferences(ListenService.PREFS, MODE_PRIVATE);

        status = findViewById(R.id.status);
        meter = findViewById(R.id.meter);
        toggle = findViewById(R.id.toggle);
        ring = findViewById(R.id.ring);

        bindSwitch(R.id.swClap, "clap", true);
        bindSwitch(R.id.swWhistle, "whistle", true);
        bindSwitch(R.id.swWifi, "wifiOnly", true);

        SeekBar sens = findViewById(R.id.sens);
        sens.setProgress(prefs.getInt("sens", 60));
        sens.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                prefs.edit().putInt("sens", bar.getProgress()).apply();
                tellService(ListenService.ACTION_RELOAD);
            }
        });

        findViewById(R.id.help).setOnClickListener(v -> startActivity(new Intent(this, HelpActivity.class)));
        if (!prefs.getBoolean("helpShown", false)) {
            // first launch: show the instructions once
            prefs.edit().putBoolean("helpShown", true).apply();
            startActivity(new Intent(this, HelpActivity.class));
        }

        toggle.setOnClickListener(v -> {
            if (ListenService.running) {
                prefs.edit().putBoolean("enabled", false).apply();
                tellService(ListenService.ACTION_STOP);
            } else {
                turnOn();
            }
        });

        ring.setOnClickListener(v -> {
            if (!ListenService.running) {
                Toast.makeText(this, "Turn ClapFind on first", Toast.LENGTH_SHORT).show();
            } else if (ListenService.ringing) {
                tellService(ListenService.ACTION_SILENCE);
            } else {
                tellService(ListenService.ACTION_TEST);
            }
        });
    }

    private void bindSwitch(int id, String key, boolean def) {
        Switch s = findViewById(id);
        s.setChecked(prefs.getBoolean(key, def));
        s.setOnCheckedChangeListener((button, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            tellService(ListenService.ACTION_RELOAD);
        });
    }

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void turnOn() {
        List<String> need = new ArrayList<>();
        if (!hasMic()) {
            need.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
            return;
        }
        startListening();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMS) {
            return;
        }
        if (hasMic()) {
            startListening();
        } else {
            Toast.makeText(this, "ClapFind needs the microphone to hear your clap or whistle",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void startListening() {
        prefs.edit().putBoolean("enabled", true).apply();
        try {
            startForegroundService(new Intent(this, ListenService.class).setAction(ListenService.ACTION_START));
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(3);
        } catch (RuntimeException e) {
            Toast.makeText(this, "Could not start listening", Toast.LENGTH_LONG).show();
        }
    }

    private void tellService(String action) {
        if (!ListenService.running) {
            return;
        }
        try {
            startService(new Intent(this, ListenService.class).setAction(action));
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // After a phone restart, opening the app brings listening back.
        if (prefs.getBoolean("enabled", false) && !ListenService.running && hasMic()) {
            startListening();
        }
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void refresh() {
        boolean on = ListenService.running;
        String text;
        if (!on) {
            text = "Off";
        } else if (ListenService.ringing) {
            text = "Ringing!";
        } else if (ListenService.listeningNow) {
            text = "Listening…";
        } else {
            text = "Paused: waiting for Wi-Fi";
        }
        status.setText(text);
        meter.setProgress(on ? ListenService.level : 0);
        toggle.setText(on ? "Turn off" : "Turn on");
        ring.setText(ListenService.ringing ? "Stop ringing" : "Test ring");
    }
}
