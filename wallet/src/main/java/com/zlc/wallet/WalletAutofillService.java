package com.zlc.wallet;

import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.app.assist.AssistStructure;
import android.content.Intent;
import android.os.Build;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;

import org.json.JSONObject;

import java.util.List;

/**
 * System autofill service: when a login screen in another app (or a website in a browser that
 * supports autofill services) asks for suggestions, offer the matching 账号卡包 accounts.
 * If the 密码锁 is on, the suggestions only appear after the PIN is entered (UnlockActivity).
 */
@TargetApi(26)
public class WalletAutofillService extends AutofillService {
    @Override
    public void onFillRequest(FillRequest req, CancellationSignal cancel, FillCallback cb) {
        try {
            List<FillContext> ctx = req.getFillContexts();
            AssistStructure st = ctx.get(ctx.size() - 1).getStructure();
            Fill f = Fill.parse(st);
            if (getPackageName().equals(f.pkg) || !f.hasFields()) {
                cb.onSuccess(null);
                return;
            }
            JSONObject vault = Vault.load(this);
            if (vault == null) {
                cb.onSuccess(null);
                return;
            }
            List<JSONObject> apps = f.matches(vault);
            if (apps.isEmpty()) {
                cb.onSuccess(null);
                return;
            }
            JSONObject lock = vault.optJSONObject("lock");
            if (lock != null) {
                Intent i = new Intent(this, UnlockActivity.class);
                int flags = PendingIntent.FLAG_CANCEL_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                PendingIntent pi = PendingIntent.getActivity(this, 1, i, flags);
                FillResponse r = new FillResponse.Builder()
                        .setAuthentication(f.ids(), pi.getIntentSender(),
                                Fill.row(this, "账号卡包 · 输入密码锁后填充", apps.size() == 1 ? apps.get(0).optString("name") : "选择要填的账号"))
                        .build();
                cb.onSuccess(r);
                return;
            }
            cb.onSuccess(f.build(this, apps));
        } catch (Exception e) {
            cb.onSuccess(null);
        }
    }

    @Override
    public void onSaveRequest(SaveRequest req, SaveCallback cb) {
        cb.onSuccess();
    }
}
