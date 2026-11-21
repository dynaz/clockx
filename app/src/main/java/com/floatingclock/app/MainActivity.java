package com.floatingclock.app;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private Button start;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences preferences = getSharedPreferences(ClockService.PREFS, MODE_PRIVATE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = dp(24);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            // Include bars and cutouts on edge-to-edge devices and foldables.
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                var safe = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets.consumeSystemWindowInsets();
        });
        setContentView(scroll);

        TextView heading = new TextView(this);
        heading.setText(R.string.title);
        heading.setTextSize(34);
        heading.setTextColor(0xFF142343);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        content.addView(heading);

        TextView instructions = new TextView(this);
        instructions.setText(R.string.instructions);
        instructions.setTextSize(16);
        instructions.setTextColor(0xFF435573);
        instructions.setPadding(0, dp(10), 0, dp(24));
        content.addView(instructions);

        start = new Button(this);
        start.setTextColor(Color.WHITE);
        start.setBackgroundTintList(ColorStateList.valueOf(0xFF285BD9));
        start.setMinHeight(dp(52));
        start.setOnClickListener(view -> {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            try {
                startForegroundService(new Intent(this, ClockService.class));
            } catch (IllegalStateException | SecurityException exception) {
                Toast.makeText(this, R.string.overlay_error, Toast.LENGTH_LONG).show();
            }
        });
        content.addView(start);

        Button stop = new Button(this);
        stop.setText(R.string.stop_clock);
        stop.setMinHeight(dp(52));
        stop.setOnClickListener(view -> stopService(new Intent(this, ClockService.class)));
        content.addView(stop);

        addSwitch(content, preferences, ClockService.SECONDS, R.string.show_seconds, true);
        addSwitch(content, preferences, ClockService.LARGE, R.string.large_clock, false);
        addSwitch(content, preferences, ClockService.LOCKED, R.string.lock_clock, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        start.setText(Settings.canDrawOverlays(this) ? R.string.start_clock : R.string.allow_overlay);
    }

    private void addSwitch(LinearLayout content, SharedPreferences preferences, String key,
                           int label, boolean initialValue) {
        Switch setting = new Switch(this);
        setting.setText(label);
        setting.setTextSize(16);
        setting.setMinHeight(dp(52));
        setting.setChecked(preferences.getBoolean(key, initialValue));
        setting.setOnCheckedChangeListener((button, value) ->
                preferences.edit().putBoolean(key, value).apply());
        content.addView(setting, LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
