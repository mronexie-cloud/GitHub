package com.zlc.wallet;

import android.annotation.TargetApi;
import android.app.assist.AssistStructure;
import android.content.Context;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.text.InputType;
import android.view.View;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.view.ViewStructure;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Finds the account / password boxes on a login screen and builds the 账号卡包 suggestions for them. */
@TargetApi(26)
final class Fill {
    AutofillId user, pass;
    String pkg = "", domain = "";

    private Fill() {
    }

    static Fill parse(AssistStructure st) {
        Fill f = new Fill();
        if (st.getActivityComponent() != null) f.pkg = st.getActivityComponent().getPackageName();
        for (int i = 0; i < st.getWindowNodeCount(); i++) f.walk(st.getWindowNodeAt(i).getRootViewNode());
        return f;
    }

    private static boolean has(String s, String... words) {
        if (s == null) return false;
        String l = s.toLowerCase(Locale.ROOT);
        for (String w : words) if (l.contains(w)) return true;
        return false;
    }

    private void walk(AssistStructure.ViewNode n) {
        if (n == null) return;
        if (n.getWebDomain() != null && !n.getWebDomain().isEmpty()) domain = n.getWebDomain();
        if (n.getAutofillId() != null && n.getAutofillType() == View.AUTOFILL_TYPE_TEXT && n.getVisibility() == View.VISIBLE) {
            int kind = classify(n);
            if (kind == 2 && pass == null) pass = n.getAutofillId();
            else if (kind == 1 && user == null) user = n.getAutofillId();
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChildAt(i));
    }

    /** 2 = password box, 1 = account box (user name / email / phone), 0 = something else. */
    private static int classify(AssistStructure.ViewNode n) {
        String[] hints = n.getAutofillHints();
        if (hints != null) for (String h : hints) {
            if (has(h, "password")) return 2;
            if (has(h, "username", "email", "phone", "user")) return 1;
        }
        int t = n.getInputType(), cls = t & InputType.TYPE_MASK_CLASS, v = t & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) return 2;
        if (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return 2;
        ViewStructure.HtmlInfo html = n.getHtmlInfo();
        String htmlType = "", htmlName = "";
        if (html != null && html.getAttributes() != null) {
            for (android.util.Pair<String, String> a : html.getAttributes()) {
                if ("type".equals(a.first)) htmlType = a.second;
                if ("name".equals(a.first) || "id".equals(a.first) || "autocomplete".equals(a.first)) htmlName += " " + a.second;
            }
        }
        if ("password".equalsIgnoreCase(htmlType)) return 2;
        String text = n.getIdEntry() + " " + n.getHint() + " " + htmlName;
        if (has(text, "password", "passwd", "pwd", "密码")) return 2;
        if (cls == InputType.TYPE_CLASS_PHONE || v == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || v == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) return 1;
        if ("email".equalsIgnoreCase(htmlType) || "tel".equalsIgnoreCase(htmlType)) return 1;
        if (has(text, "user", "account", "login", "email", "mail", "phone", "mobile", "tel",
                "账号", "帐号", "手机", "邮箱", "用户名", "会员名")) return 1;
        return 0;
    }

    boolean hasFields() {
        return user != null || pass != null;
    }

    AutofillId[] ids() {
        List<AutofillId> l = new ArrayList<>();
        if (user != null) l.add(user);
        if (pass != null) l.add(pass);
        return l.toArray(new AutofillId[0]);
    }

    private static boolean domainMatches(String host, String d) {
        host = host.toLowerCase(Locale.ROOT);
        d = d.toLowerCase(Locale.ROOT);
        return host.equals(d) || host.endsWith("." + d);
    }

    /** The saved apps that belong to this screen (same app package, or same website). */
    List<JSONObject> matches(JSONObject vault) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray apps = vault.optJSONArray("apps");
        if (apps == null) return out;
        for (int i = 0; i < apps.length(); i++) {
            JSONObject a = apps.optJSONObject(i);
            if (a == null) continue;
            boolean ok = false;
            JSONArray pk = a.optJSONArray("pkgs");
            if (pk != null) for (int j = 0; j < pk.length() && !ok; j++) ok = pkg.equals(pk.optString(j));
            JSONArray dm = a.optJSONArray("domains");
            if (!ok && !domain.isEmpty() && dm != null)
                for (int j = 0; j < dm.length() && !ok; j++) ok = domainMatches(domain, dm.optString(j));
            if (ok) out.add(a);
        }
        return out;
    }

    static RemoteViews row(Context c, String title, String sub) {
        RemoteViews r = new RemoteViews(c.getPackageName(), R.layout.af_item);
        r.setTextViewText(R.id.af_title, title);
        r.setTextViewText(R.id.af_sub, sub);
        return r;
    }

    /** One suggestion per saved account; tapping it fills both the account and the password. */
    FillResponse build(Context c, List<JSONObject> apps) {
        FillResponse.Builder b = new FillResponse.Builder();
        int n = 0;
        for (JSONObject a : apps) {
            JSONArray creds = a.optJSONArray("creds");
            if (creds == null) continue;
            for (int i = 0; i < creds.length() && n < 10; i++) {
                JSONObject cr = creds.optJSONObject(i);
                if (cr == null) continue;
                String u = cr.optString("user"), p = cr.optString("pass");
                if (user == null && p.isEmpty()) continue;
                Dataset.Builder d = new Dataset.Builder();
                RemoteViews rv = row(c, "账号卡包 · " + cr.optString("label"),
                        u + (p.isEmpty() ? "（未存密码）" : "  ·  账号+密码"));
                boolean any = false;
                if (user != null && !u.isEmpty()) {
                    d.setValue(user, AutofillValue.forText(u), rv);
                    any = true;
                }
                if (pass != null && !p.isEmpty()) {
                    d.setValue(pass, AutofillValue.forText(p), rv);
                    any = true;
                }
                if (any) {
                    b.addDataset(d.build());
                    n++;
                }
            }
        }
        return n == 0 ? null : b.build();
    }
}
