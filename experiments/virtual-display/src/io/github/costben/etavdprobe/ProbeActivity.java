package io.github.costben.etavdprobe;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;

/** Synthetic content only. Each task has its own counter, text and frame clock. */
public final class ProbeActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView state;
    private EditText input;
    private int counter;
    private int ticks;
    private boolean resumed;
    private final Runnable ticker = new Runnable() {
        public void run() {
            ticks++;
            publish("tick");
            handler.postDelayed(this, 1000);
        }
    };

    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(padding, padding, padding, padding);
        TextView title = new TextView(this);
        title.setText("Eta virtual display probe");
        title.setTextSize(22);
        root.addView(title);
        Button tap = new Button(this);
        tap.setText("Increment counter");
        tap.setContentDescription("eta-vd-tap");
        tap.setOnClickListener(v -> { counter++; publish("tap"); });
        root.addView(tap);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Synthetic test input");
        input.setContentDescription("eta-vd-input");
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { publish("text"); }
            public void afterTextChanged(Editable e) {}
        });
        root.addView(input);
        state = new TextView(this);
        state.setTextSize(16);
        state.setContentDescription("eta-vd-state");
        root.addView(state);
        setContentView(root);
        handler.post(ticker);
    }

    protected void onResume() { super.onResume(); resumed = true; publish("resume"); }
    protected void onPause() { resumed = false; publish("pause"); super.onPause(); }
    protected void onDestroy() { handler.removeCallbacks(ticker); super.onDestroy(); }

    private void publish(String event) {
        if (state == null || input == null) return;
        try {
            KeyguardManager kg = getSystemService(KeyguardManager.class);
            JSONObject value = new JSONObject()
                .put("event", event).put("display", getDisplay().getDisplayId())
                .put("task", getTaskId()).put("counter", counter).put("ticks", ticks)
                .put("text", input.getText().toString()).put("resumed", resumed)
                .put("deviceLocked", kg.isDeviceLocked()).put("deviceSecure", kg.isDeviceSecure())
                .put("keyguardLocked", kg.isKeyguardLocked());
            state.setText(value.toString());
            Log.i("EtaVDProbeApp", value.toString());
        } catch (Exception e) { Log.e("EtaVDProbeApp", "publish failed", e); }
    }
}
