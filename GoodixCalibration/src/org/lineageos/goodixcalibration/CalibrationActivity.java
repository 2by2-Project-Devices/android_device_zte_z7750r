/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.goodixcalibration;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemProperties;
import android.provider.Settings;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class CalibrationActivity extends Activity implements GoodixDaemon.Listener {
    private static final String TAG = "GoodixCalibration";

    private static final String PERSIST_FP = "/mnt/vendor/persist/fingerprint";
    private static final String PERSIST_FP_OVERLAY = "/data/vendor/goodix/persist_fp";
    private static final String LCD_HBM = "/proc/driver/lcd_hbm";
    private static final String BACKLIGHT = "/sys/class/backlight/panel0-backlight/brightness";
    private static final String BACKLIGHT_MAX =
            "/sys/class/backlight/panel0-backlight/max_brightness";

    private static final int MSG_TEST_CMD = 1001;
    private static final int TOKEN_ERROR_CODE = 100;
    private static final int TOKEN_METADATA = 1210;

    private static final int CMD_FT_CAPTURE_DARK_BASE = 1556;
    private static final int CMD_FT_CAPTURE_H_DARK = 1557;
    private static final int CMD_FT_CAPTURE_L_DARK = 1558;
    private static final int CMD_FT_CAPTURE_H_FLESH = 1559;
    private static final int CMD_FT_CAPTURE_L_FLESH = 1560;
    private static final int CMD_FT_CAPTURE_CHART = 1561;
    private static final int CMD_FT_CAPTURE_CHECKBOX = 1562;
    private static final int CMD_FT_EXPO_AUTO_CALIBRATION = 1565;
    private static final int CMD_FT_SPI_RST_INT = 1568;
    private static final int CMD_FT_SPI = 1569;
    private static final int CMD_FT_INIT = 1570;
    private static final int CMD_FT_EXIT = 1571;
    private static final int CMD_FT_MT_CHECK = 1573;

    // Panel backlight level ZTE uses for G3 sensors outside of HBM.
    private static final int G3_TEST_BRIGHTNESS = 178;
    private static final long STEP_DELAY_MS = 500;
    private static final long STEP_TIMEOUT_MS = 20000;

    private enum Prompt { NONE, FLESH, DARK, CHART }

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mTimeout = this::onStepTimeout;
    private final HandlerThread mWorkerThread = new HandlerThread(TAG);
    private Handler mWorker;

    private GoodixDaemon mDaemon;
    private TextView mTips;
    private TextView mLog;
    private Button mStartButton;
    private Button mNextButton;

    private Prompt mPrompt = Prompt.NONE;
    private int mPendingCmd;
    private boolean mRunning;
    private int mSavedBrightnessMode = -1;
    private String mSavedBacklight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(buildLayout());
        mDaemon = new GoodixDaemon(this);
        mWorkerThread.start();
        mWorker = new Handler(mWorkerThread.getLooper());

        if (!isOverlayMounted()) {
            mTips.setText(R.string.msg_overlay_missing);
            mStartButton.setEnabled(false);
        } else {
            mTips.setText(R.string.msg_intro);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            controller.hide(WindowInsets.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    protected void onDestroy() {
        abort();
        mWorkerThread.quitSafely();
        super.onDestroy();
    }

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addView(new SensorView(this), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        panel.setPadding(pad, dp(48), pad, pad);

        mTips = new TextView(this);
        mTips.setTextColor(Color.WHITE);
        mTips.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        panel.addView(mTips);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER_HORIZONTAL);
        mStartButton = new Button(this);
        mStartButton.setText(R.string.btn_start);
        mStartButton.setOnClickListener(v -> start());
        mNextButton = new Button(this);
        mNextButton.setText(R.string.btn_next);
        mNextButton.setEnabled(false);
        mNextButton.setOnClickListener(v -> next());
        Button close = new Button(this);
        close.setText(R.string.btn_close);
        close.setOnClickListener(v -> finish());
        buttons.addView(mStartButton);
        buttons.addView(mNextButton);
        buttons.addView(close);
        LinearLayout.LayoutParams buttonsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonsLp.topMargin = dp(16);
        panel.addView(buttons, buttonsLp);

        mLog = new TextView(this);
        mLog.setTextColor(Color.GRAY);
        mLog.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mLog.setMovementMethod(new ScrollingMovementMethod());
        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(220));
        logLp.topMargin = dp(16);
        panel.addView(mLog, logLp);

        root.addView(panel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP));
        return root;
    }

    private void start() {
        if (!isOverlayMounted()) {
            mTips.setText(R.string.msg_overlay_missing);
            return;
        }
        if (!mDaemon.connect()) {
            mTips.setText(R.string.msg_daemon_missing);
            return;
        }
        mLog.setText("");
        mStartButton.setEnabled(false);
        mNextButton.setEnabled(false);
        mRunning = true;
        mPrompt = Prompt.NONE;
        saveBrightness();
        send(CMD_FT_SPI, null);
    }

    private void next() {
        mNextButton.setEnabled(false);
        Prompt prompt = mPrompt;
        mPrompt = Prompt.NONE;
        switch (prompt) {
            case FLESH:
                setHbm(true);
                sendDelayed(CMD_FT_EXPO_AUTO_CALIBRATION);
                break;
            case DARK:
                setHbm(true);
                sendDelayed(CMD_FT_CAPTURE_H_DARK);
                break;
            case CHART:
                setHbm(true);
                sendDelayed(CMD_FT_CAPTURE_CHECKBOX);
                break;
            default:
                break;
        }
    }

    private void onStepSucceeded(int cmdId) {
        switch (cmdId) {
            case CMD_FT_SPI:
                send(CMD_FT_MT_CHECK, null);
                break;
            case CMD_FT_MT_CHECK:
                send(CMD_FT_SPI_RST_INT, null);
                break;
            case CMD_FT_SPI_RST_INT:
                send(CMD_FT_INIT, buildInitParam());
                break;
            case CMD_FT_INIT:
                prompt(Prompt.FLESH, R.string.msg_place_flesh);
                break;
            case CMD_FT_EXPO_AUTO_CALIBRATION:
                send(CMD_FT_CAPTURE_H_FLESH, null);
                break;
            case CMD_FT_CAPTURE_H_FLESH:
                setHbm(false);
                setBacklight(G3_TEST_BRIGHTNESS);
                sendDelayed(CMD_FT_CAPTURE_L_FLESH);
                break;
            case CMD_FT_CAPTURE_L_FLESH:
                prompt(Prompt.DARK, R.string.msg_place_dark);
                break;
            case CMD_FT_CAPTURE_H_DARK:
                setHbm(false);
                setBacklight(G3_TEST_BRIGHTNESS);
                sendDelayed(CMD_FT_CAPTURE_L_DARK);
                break;
            case CMD_FT_CAPTURE_L_DARK:
                setBacklight(0);
                sendDelayed(CMD_FT_CAPTURE_DARK_BASE);
                break;
            case CMD_FT_CAPTURE_DARK_BASE:
                setBacklight(G3_TEST_BRIGHTNESS);
                prompt(Prompt.CHART, R.string.msg_place_chart);
                break;
            case CMD_FT_CAPTURE_CHECKBOX:
                setHbm(true);
                sendDelayed(CMD_FT_CAPTURE_CHART);
                break;
            case CMD_FT_CAPTURE_CHART:
                finishRun(true);
                break;
            default:
                break;
        }
    }

    @Override
    public void onDaemonMessage(int msgId, int cmdId, byte[] data) {
        if (msgId != MSG_TEST_CMD) {
            return;
        }
        mHandler.post(() -> onTestResult(cmdId, data));
    }

    private void onTestResult(int cmdId, byte[] data) {
        if (!mRunning || cmdId != mPendingCmd) {
            log("ignored result for " + cmdId);
            return;
        }
        mHandler.removeCallbacks(mTimeout);
        int error = parseErrorCode(data);
        log(cmdName(cmdId) + " -> 0x" + Integer.toHexString(error));
        if (error != 0) {
            if (cmdId == CMD_FT_CAPTURE_CHECKBOX || cmdId == CMD_FT_CAPTURE_CHART) {
                // Keep the flesh/dark captures of this session and let the chart be re-seated.
                setHbm(false);
                setBacklight(G3_TEST_BRIGHTNESS);
                prompt(Prompt.CHART, R.string.msg_place_chart);
                mTips.append("\n" + getString(R.string.msg_failed, cmdName(cmdId),
                        Integer.toHexString(error)));
                return;
            }
            mTips.setText(getString(R.string.msg_failed, cmdName(cmdId),
                    Integer.toHexString(error)));
            finishRun(false);
            return;
        }
        onStepSucceeded(cmdId);
    }

    private void onStepTimeout() {
        if (!mRunning) {
            return;
        }
        log(cmdName(mPendingCmd) + " timeout");
        mTips.setText(getString(R.string.msg_timeout, cmdName(mPendingCmd)));
        finishRun(false);
    }

    private void prompt(Prompt prompt, int textRes) {
        mPrompt = prompt;
        mTips.setText(textRes);
        mNextButton.setEnabled(true);
    }

    private void sendDelayed(int cmdId) {
        mHandler.postDelayed(() -> send(cmdId, null), STEP_DELAY_MS);
    }

    private void send(int cmdId, byte[] param) {
        if (!mRunning) {
            return;
        }
        mPendingCmd = cmdId;
        mTips.setText(getString(R.string.msg_running, cmdName(cmdId)));
        log("send " + cmdName(cmdId));
        mHandler.postDelayed(mTimeout, STEP_TIMEOUT_MS);
        mWorker.post(() -> {
            int result = mDaemon.sendCommand(cmdId, param);
            if (result != 0) {
                mHandler.post(() -> onCommandRejected(cmdId, result));
            }
        });
    }

    private void onCommandRejected(int cmdId, int result) {
        if (!mRunning || cmdId != mPendingCmd) {
            return;
        }
        mHandler.removeCallbacks(mTimeout);
        log(cmdName(cmdId) + " rejected: " + result);
        mTips.setText(getString(R.string.msg_failed, cmdName(cmdId),
                Integer.toHexString(result)));
        finishRun(false);
    }

    private void finishRun(boolean success) {
        if (mRunning) {
            mRunning = false;
            mWorker.post(() -> mDaemon.sendCommand(CMD_FT_EXIT, null));
        }
        mHandler.removeCallbacksAndMessages(null);
        setHbm(false);
        restoreBrightness();
        mPrompt = Prompt.NONE;
        mNextButton.setEnabled(false);
        mStartButton.setEnabled(true);
        if (success) {
            mTips.setText(R.string.msg_done);
        }
    }

    private void abort() {
        if (mRunning) {
            finishRun(false);
        }
    }

    private static byte[] buildInitParam() {
        String path = "z7750r/" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date());
        byte[] value = path.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(8 + value.length).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(TOKEN_METADATA).putInt(value.length).put(value).array();
    }

    // Test results are key/value records; the error code is reported as an int32 record.
    private static int parseErrorCode(byte[] data) {
        if (data == null || data.length < 8) {
            return 0;
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset = 0; offset + 8 <= data.length; offset += 4) {
            if (buf.getInt(offset) == TOKEN_ERROR_CODE) {
                return buf.getInt(offset + 4);
            }
        }
        return 0;
    }

    private boolean isOverlayMounted() {
        try {
            StructStat persist = Os.stat(PERSIST_FP);
            StructStat overlay = Os.stat(PERSIST_FP_OVERLAY);
            return persist.st_dev == overlay.st_dev && persist.st_ino == overlay.st_ino;
        } catch (ErrnoException e) {
            Log.e(TAG, "stat failed", e);
            return false;
        }
    }

    private void saveBrightness() {
        try {
            mSavedBrightnessMode = Settings.System.getInt(getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE);
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        } catch (Settings.SettingNotFoundException e) {
            mSavedBrightnessMode = -1;
        }
        mSavedBacklight = readFile(BACKLIGHT);
    }

    private void restoreBrightness() {
        if (mSavedBacklight != null) {
            writeFile(BACKLIGHT, mSavedBacklight);
            mSavedBacklight = null;
        }
        if (mSavedBrightnessMode >= 0) {
            Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                    mSavedBrightnessMode);
            mSavedBrightnessMode = -1;
        }
    }

    // The panel HBM command alone does not raise the backlight on this kernel,
    // and the sensor needs full brightness to reach its exposure target.
    private void setHbm(boolean on) {
        writeFile(LCD_HBM, on ? "1" : "0");
        if (on) {
            String max = readFile(BACKLIGHT_MAX);
            setBacklight(max != null ? Integer.parseInt(max) : 255);
        }
    }

    private void setBacklight(int level) {
        writeFile(BACKLIGHT, String.valueOf(level));
    }

    private static String readFile(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                    StandardCharsets.US_ASCII).trim();
        } catch (IOException e) {
            Log.e(TAG, "read " + path + " failed", e);
            return null;
        }
    }

    private static void writeFile(String path, String value) {
        try (FileOutputStream out = new FileOutputStream(path)) {
            out.write(value.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            Log.e(TAG, "write " + path + " failed", e);
        }
    }

    private void log(String line) {
        Log.i(TAG, line);
        mLog.append(line + "\n");
    }

    private static String cmdName(int cmdId) {
        switch (cmdId) {
            case CMD_FT_SPI: return "SPI";
            case CMD_FT_MT_CHECK: return "MT_CHECK";
            case CMD_FT_SPI_RST_INT: return "SPI_RST_INT";
            case CMD_FT_INIT: return "FT_INIT";
            case CMD_FT_EXPO_AUTO_CALIBRATION: return "AUTO_EXPOSURE";
            case CMD_FT_CAPTURE_H_FLESH: return "H_FLESH";
            case CMD_FT_CAPTURE_L_FLESH: return "L_FLESH";
            case CMD_FT_CAPTURE_H_DARK: return "H_DARK";
            case CMD_FT_CAPTURE_L_DARK: return "L_DARK";
            case CMD_FT_CAPTURE_DARK_BASE: return "DARK_BASE";
            case CMD_FT_CAPTURE_CHECKBOX: return "CALIBRATE_CHART";
            case CMD_FT_CAPTURE_CHART: return "CAPTURE_CHART";
            case CMD_FT_EXIT: return "FT_EXIT";
            default: return String.valueOf(cmdId);
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    /** Draws a white disc over the under-display sensor, in physical screen coordinates. */
    private static final class SensorView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] mLocation = new int[2];
        private final float mX;
        private final float mY;
        private final float mR;

        SensorView(Context context) {
            super(context);
            mPaint.setColor(Color.WHITE);
            mX = SystemProperties.getInt("debug.goodixcal.center_x", SystemProperties.getInt(
                    "ro.vendor.feature.fingerprint_sensorui_position_center_x", 540));
            mY = SystemProperties.getInt("debug.goodixcal.center_y", SystemProperties.getInt(
                    "ro.vendor.feature.fingerprint_sensorui_position_center_y", 2068));
            mR = SystemProperties.getInt("debug.goodixcal.center_r", SystemProperties.getInt(
                    "ro.vendor.feature.fingerprint_sensorui_position_center_r", 94));
            Log.i(TAG, "sensor spot x=" + mX + " y=" + mY + " r=" + mR);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            getLocationOnScreen(mLocation);
            canvas.drawCircle(mX - mLocation[0], mY - mLocation[1], mR, mPaint);
        }
    }
}
