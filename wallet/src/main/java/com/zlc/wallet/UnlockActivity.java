package com.zlc.wallet;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.assist.AssistStructure;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.Editable;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.autofill.AutofillManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.List;

/** Small PIN screen shown before autofill when the 密码锁 is on. */
@TargetApi(26)
public class UnlockActivity extends Activity {
    private int tries = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        final AssistStructure st = getIntent().getParcelableExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE);
        final JSONObject vault = Vault.load(this);
        if (st == null || vault == null) {
            finish();
            return;
        }
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = (int) (24 * dp);
        box.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1c1c1e"));
        bg.setCornerRadius(20 * dp);
        box.setBackground(bg);

        TextView t = new TextView(this);
        t.setText("账号卡包");
        t.setTextColor(Color.WHITE);
        t.setTextSize(20);
        t.setGravity(Gravity.CENTER);
        box.addView(t);
        final TextView sub = new TextView(this);
        sub.setText("输入 6 位密码锁，自动填充账号和密码");
        sub.setTextColor(Color.parseColor("#99ebebf5"));
        sub.setTextSize(14);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, (int) (6 * dp), 0, (int) (16 * dp));
        box.addView(sub);

        final EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        pin.setTextColor(Color.WHITE);
        pin.setTextSize(26);
        pin.setLetterSpacing(0.5f);
        pin.setGravity(Gravity.CENTER);
        pin.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        box.addView(pin, new LinearLayout.LayoutParams((int) (220 * dp), LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(box);
        setFinishOnTouchOutside(true);

        pin.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int c, int d) {
            }

            public void onTextChanged(CharSequence s, int a, int c, int d) {
            }

            public void afterTextChanged(Editable s) {
                if (s.length() != 6) return;
                final String p = s.toString();
                sub.setText("验证中…");
                new Thread(() -> {
                    boolean ok = Vault.checkPin(vault.optJSONObject("lock"), p);
                    runOnUiThread(() -> {
                        if (ok) finishWith(st, vault);
                        else {
                            tries++;
                            sub.setText(tries >= 5 ? "错太多次了" : "密码不对，再试一次");
                            pin.setText("");
                            if (tries >= 5) finish();
                        }
                    });
                }).start();
            }
        });
        pin.requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private void finishWith(AssistStructure st, JSONObject vault) {
        Fill f = Fill.parse(st);
        List<JSONObject> apps = f.matches(vault);
        Intent r = new Intent();
        r.putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, f.build(this, apps));
        setResult(RESULT_OK, r);
        finish();
    }
}
