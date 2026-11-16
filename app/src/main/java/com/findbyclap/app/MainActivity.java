package com.findbyclap.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
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
    private static final int REQ_RINGTONE = 11;

    private SharedPreferences prefs;
    private TextView status;
    private ProgressBar meter;
    private Button toggle;
    private Button ring;
    private Button soundBtn;

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

        soundBtn = findViewById(R.id.sound);
        soundBtn.setOnClickListener(v -> chooseSound());
        showSoundName();

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

    // ------------------------------------------------------------ alarm sound

    private void chooseSound() {
        int builtIn = ToneSynth.NAMES.length;
        String[] items = new String[builtIn + 2];
        items[0] = "Phone's own alarm sound";
        System.arraycopy(ToneSynth.NAMES, 0, items, 1, builtIn);
        items[builtIn + 1] = "Choose from phone ringtones…";

        new AlertDialog.Builder(this)
                .setTitle("Alarm sound")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        saveSound("default", null);
                    } else if (which <= builtIn) {
                        saveSound(ToneSynth.KEYS[which - 1], null);
                    } else {
                        openRingtonePicker();
                    }
                })
                .show();
    }

    private void openRingtonePicker() {
        Intent pick = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        pick.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL);
        pick.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "ClapFind alarm sound");
        pick.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
        pick.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, false);
        String current = prefs.getString("sound", "default");
        if (current.startsWith("uri:")) {
            pick.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(current.substring(4)));
        }
        try {
            startActivityForResult(pick, REQ_RINGTONE);
        } catch (RuntimeException e) {
            Toast.makeText(this, "This phone has no ringtone chooser", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_RINGTONE || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        if (uri == null) {
            return;
        }
        String title = null;
        try {
            Ringtone r = RingtoneManager.getRingtone(this, uri);
            if (r != null) {
                title = r.getTitle(this);
            }
        } catch (RuntimeException ignored) {
        }
        saveSound("uri:" + uri, title == null ? "Phone ringtone" : title);
    }

    private void saveSound(String value, String title) {
        prefs.edit().putString("sound", value).putString("soundTitle", title).apply();
        showSoundName();
        Toast.makeText(this, "Tap Test ring to hear it", Toast.LENGTH_SHORT).show();
    }

    private void showSoundName() {
        String value = prefs.getString("sound", "default");
        String name = "Phone's own alarm sound";
        for (int i = 0; i < ToneSynth.KEYS.length; i++) {
            if (ToneSynth.KEYS[i].equals(value)) {
                name = ToneSynth.NAMES[i];
            }
        }
        if (value.startsWith("uri:")) {
            name = prefs.getString("soundTitle", "Phone ringtone");
        }
        soundBtn.setText("Alarm sound: " + name);
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
