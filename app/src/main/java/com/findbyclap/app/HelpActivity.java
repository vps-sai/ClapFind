package com.findbyclap.app;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Plain instructions for anyone who installs the app. */
public class HelpActivity extends Activity {

    // heading, text, heading, text, ...
    private static final String[] HELP = {
            "Getting started",
            "1. Tap Turn on.\n"
                    + "2. Allow the microphone and notifications when asked. Both are needed: the microphone "
                    + "hears your clap or whistle, and the notification lets you stop the ringing.\n"
                    + "3. Tap Test ring once to hear what the alarm sounds like.\n"
                    + "4. You can now close the app. It keeps listening in the background.",

            "Finding your phone",
            "Clap: three firm claps in a row, about three claps per second.\n\n"
                    + "Whistle: one steady note held for about one second.\n\n"
                    + "The phone rings at full alarm volume, vibrates and flashes its torch, "
                    + "even if it is set to silent.",

            "Stopping the ringing",
            "Unlock the phone, or tap Stop ringing in the notification. "
                    + "If you do nothing, it stops by itself after 45 seconds and starts listening again.",

            "Choosing the alarm sound",
            "Tap Alarm sound on the main screen. You can keep your phone's own alarm sound, pick one of "
                    + "the built-in sounds (bird chirping, cuckoo, siren, loud beeps), or choose any ringtone "
                    + "already on your phone. Tap Test ring to hear your choice. For a phone that may be "
                    + "under cushions or in another room, Siren and Loud beeps carry the furthest.",

            "After restarting your phone",
            "Android does not allow any app to switch the microphone on by itself. After a restart, "
                    + "ClapFind shows a reminder. Tap it, or open the app once, and listening comes back on.",

            "Listen only while on Wi-Fi",
            "With this switch on, ClapFind listens only while the phone is connected to a Wi-Fi network, "
                    + "such as at home, and pauses when you are out on mobile data. This saves battery. "
                    + "It listens on any Wi-Fi, including at work. Switch it off if you want ClapFind "
                    + "to listen everywhere.",

            "If it does not respond",
            "• Move the Sensitivity slider to the right and try again.\n"
                    + "• Watch the bar under the status while you clap. If it does not move, the microphone "
                    + "is blocked or the permission was not given.\n"
                    + "• Check that the status says Listening. If it says Paused, the phone is not on Wi-Fi.\n"
                    + "• Clap with a steady rhythm. One or two claps, or very slow claps, are ignored on purpose.\n"
                    + "• ClapFind cannot hear you during a phone call or while another app is using the microphone.\n"
                    + "• A phone inside a bag, drawer or under cushions hears much less. Whistling carries better than clapping.",

            "If it rings when it should not",
            "Move the Sensitivity slider to the left. Loud music, television or rhythmic knocking can "
                    + "sometimes sound like claps or a whistle. You can also switch off claps or whistles "
                    + "and keep only the one that works best in your home.",

            "If it stops listening after some hours",
            "Some phones close background apps to save battery. Open your phone's Settings, go to "
                    + "Apps, ClapFind, Battery, and choose Unrestricted (on some phones: Don't optimise, "
                    + "or Allow background activity). Also avoid swiping ClapFind away with Clear all "
                    + "on phones that close apps when you do that.",

            "The green dot and the notification",
            "While ClapFind is listening, Android shows a green dot or microphone icon at the top of "
                    + "the screen, and a ClapFind notification stays in the list. Both are required by Android "
                    + "so that you always know the microphone is in use. They cannot be hidden.",

            "Battery",
            "Listening all the time uses some battery. To use less, keep Listen only while on Wi-Fi "
                    + "switched on, or tap Turn off when you do not need it.",

            "Your privacy",
            "ClapFind has no internet permission, so nothing can leave your phone. Sound is checked "
                    + "in memory for loudness and pitch only, then discarded. Nothing is recorded, saved "
                    + "or shared. There are no ads, no sign-in and no tracking."
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.help);
        LinearLayout box = findViewById(R.id.helpContent);
        float dp = getResources().getDisplayMetrics().density;

        for (int i = 0; i + 1 < HELP.length; i += 2) {
            TextView heading = new TextView(this);
            heading.setText(HELP[i]);
            heading.setTextSize(18);
            heading.setTypeface(Typeface.DEFAULT_BOLD);
            heading.setTextColor(0xFF212121);
            heading.setPadding(0, (int) (22 * dp), 0, (int) (6 * dp));
            box.addView(heading);

            TextView body = new TextView(this);
            body.setText(HELP[i + 1]);
            body.setTextSize(15);
            body.setTextColor(0xFF424242);
            body.setLineSpacing(3 * dp, 1f);
            box.addView(body);
        }

        Button done = new Button(this);
        done.setText("Got it");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (56 * dp));
        lp.topMargin = (int) (28 * dp);
        done.setLayoutParams(lp);
        done.setOnClickListener(v -> finish());
        box.addView(done);
    }
}
