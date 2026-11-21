package com.floatingclock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.TextClock;

public final class ClockService extends Service {
    static final String PREFS = "clock";
    static final String SECONDS = "seconds";
    static final String LARGE = "large";
    static final String LOCKED = "locked";
    private static final String POSITION_X = "position_x";
    private static final String POSITION_Y = "position_y";
    private static final String CHANNEL = "floating_clock";
    private static final String STOP = "com.floatingclock.app.STOP";

    private SharedPreferences preferences;
    private final SharedPreferences.OnSharedPreferenceChangeListener settingsChanged =
            (prefs, key) -> {
                if (SECONDS.equals(key) || LARGE.equals(key) || LOCKED.equals(key)) {
                    applySettings();
                }
            };
    private WindowManager windows;
    private WindowManager.LayoutParams params;
    private TextClock clock;
    private boolean added;
    private float touchX;
    private float touchY;
    private int startX;
    private int startY;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction()) || !Settings.canDrawOverlays(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (added) {
            return START_NOT_STICKY;
        }

        try {
            Notification notification = buildNotification();
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(1, notification);
            }
            showClock();
        } catch (WindowManager.BadTokenException | WindowManager.InvalidDisplayException
                 | SecurityException | IllegalStateException exception) {
            Log.w("FloatingClock", "Could not start the clock overlay", exception);
            android.widget.Toast.makeText(this, R.string.overlay_error,
                    android.widget.Toast.LENGTH_LONG).show();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification buildNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, getString(R.string.clock_channel), NotificationManager.IMPORTANCE_LOW));
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), pendingFlags);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, ClockService.class).setAction(STOP), pendingFlags);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(getString(R.string.clock_running))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        null, getString(R.string.stop_clock), stop).build())
                .setOngoing(true)
                .build();
    }

    private void showClock() {
        Context windowContext = this;
        if (Build.VERSION.SDK_INT >= 30) {
            Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) {
                throw new IllegalStateException("The default display is unavailable");
            }
            windowContext = createDisplayContext(display).createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        windows = windowContext.getSystemService(WindowManager.class);
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE);
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) {
            params.setFitInsetsTypes(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            params.setFitInsetsIgnoringVisibility(true);
        }

        clock = new TextClock(windowContext);
        clock.setTextColor(0xFFFFFFFF);
        clock.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        clock.setGravity(Gravity.CENTER);
        clock.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xEB111827);
        background.setCornerRadius(dp(12));
        clock.setBackground(background);
        clock.setOnTouchListener(this::dragClock);
        clock.setOnClickListener(view -> { });
        applySettings();
        windows.addView(clock, params);
        added = true;
        preferences.registerOnSharedPreferenceChangeListener(settingsChanged);
    }

    private void applySettings() {
        String format = preferences.getBoolean(SECONDS, true) ? "HH:mm:ss" : "HH:mm";
        clock.setFormat12Hour(format);
        clock.setFormat24Hour(format);
        clock.setTextSize(preferences.getBoolean(LARGE, false) ? 31 : 22);
        if (preferences.getBoolean(LOCKED, false)) {
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            // Android 12+ permits touches through a sufficiently translucent overlay.
            params.alpha = Build.VERSION.SDK_INT >= 31
                    ? getSystemService(InputManager.class).getMaximumObscuringOpacityForTouch()
                    : 1f;
        } else {
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            params.alpha = 1f;
        }
        restorePosition();
    }

    private void restorePosition() {
        Point range = movementRange();
        params.x = Math.round(range.x * preferences.getFloat(POSITION_X, 1f));
        params.y = Math.round(range.y * preferences.getFloat(POSITION_Y, 0.05f));
        updateWindow();
    }

    private boolean dragClock(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                touchX = event.getRawX();
                touchY = event.getRawY();
                startX = params.x;
                startY = params.y;
                return true;
            case MotionEvent.ACTION_MOVE:
                Point range = movementRange();
                params.x = clamp(startX + Math.round(event.getRawX() - touchX), range.x);
                params.y = clamp(startY + Math.round(event.getRawY() - touchY), range.y);
                updateWindow();
                return true;
            case MotionEvent.ACTION_UP:
                savePosition();
                if (params.x == startX && params.y == startY) {
                    view.performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                savePosition();
                return true;
            default:
                return true;
        }
    }

    private void savePosition() {
        Point range = movementRange();
        var edit = preferences.edit();
        if (range.x > 0) {
            edit.putFloat(POSITION_X, (float) params.x / range.x);
        }
        if (range.y > 0) {
            edit.putFloat(POSITION_Y, (float) params.y / range.y);
        }
        edit.apply();
    }

    private Point movementRange() {
        Point available = new Point();
        if (Build.VERSION.SDK_INT >= 30) {
            var metrics = windows.getCurrentWindowMetrics();
            Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            available.set(metrics.getBounds().width() - insets.left - insets.right,
                    metrics.getBounds().height() - insets.top - insets.bottom);
        } else {
            windows.getDefaultDisplay().getSize(available);
        }

        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        clock.measure(unspecified, unspecified);
        available.x = Math.max(0, available.x - clock.getMeasuredWidth() - dp(8));
        available.y = Math.max(0, available.y - clock.getMeasuredHeight() - dp(8));
        return available;
    }

    private int clamp(int value, int maximum) {
        return Math.max(0, Math.min(value, maximum));
    }

    private void updateWindow() {
        if (added) {
            windows.updateViewLayout(clock, params);
        }
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        if (added) {
            clock.post(() -> {
                if (added) {
                    restorePosition();
                }
            });
        }
    }

    @Override
    public void onDestroy() {
        if (preferences != null) {
            preferences.unregisterOnSharedPreferenceChangeListener(settingsChanged);
        }
        if (added) {
            added = false;
            windows.removeViewImmediate(clock);
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dp(int value) {
        return Math.round(value * clock.getResources().getDisplayMetrics().density);
    }
}
