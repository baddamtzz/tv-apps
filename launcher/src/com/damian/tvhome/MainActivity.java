package com.damian.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    static final int BRIDGE = 2;
    private static final String ALLOWED_HOST = "baddamtzz.github.io";
    private static final String UI_URL = "https://baddamtzz.github.io/tv-apps/launcher/index.html";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean uiFromFile, uiReady, uiPending;
    private long created, lastFetch;
    private WebView web;
    private WebView over;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("tvhome", MODE_PRIVATE);
        created = System.currentTimeMillis();
        if (getIntent().getBooleanExtra("debug", false)) WebView.setWebContentsDebuggingEnabled(true);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.addJavascriptInterface(new Bridge(), "TV");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                recreate();
                return true;
            }
        });
        loadUi();
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));

        over = new WebView(this);
        over.setBackgroundColor(Color.BLACK);
        WebSettings o = over.getSettings();
        o.setJavaScriptEnabled(true);
        o.setDomStorageEnabled(true);
        over.setWebViewClient(new WebViewClient() {
            @Override
            public boolean onRenderProcessGone(WebView v, android.webkit.RenderProcessGoneDetail d) {
                closeOverlay();
                toast("Pagina s-a oprit. Încearcă din nou");
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !ALLOWED_HOST.equals(r.getUrl().getHost());
            }

            @Override
            public void onReceivedError(WebView v, int code, String d, String url) {
                if (url != null && url.contains(ALLOWED_HOST)) {
                    v.loadData("<body style='background:#0b0d14;color:#cdd;font:32px sans-serif;display:flex;align-items:center;justify-content:center;height:90vh'>Nu m-am putut conecta la internet.</body>", "text/html", "utf-8");
                }
            }
        });
        over.setVisibility(View.GONE);
        root.addView(over, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        web.requestFocus();
        fetchUi(true);
        startWatch();
    }

    private void startWatch() {
        try {
            Intent i = new Intent(this, WatchService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception ignored) {
        }
    }

    private File uiFile() {
        return new File(getFilesDir(), "ui.html");
    }

    private String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        in.close();
        return o.toString("UTF-8");
    }

    private String readUi() {
        try {
            File f = uiFile();
            return f.exists() ? readAll(new java.io.FileInputStream(f)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean uiCompatible(String h) {
        Matcher m = Pattern.compile("name=\"tvhome-bridge\" content=\"(\\d+)\"").matcher(h);
        return m.find() && Integer.parseInt(m.group(1)) <= BRIDGE;
    }

    private void loadUi() {
        String h = readUi();
        uiReady = false;
        if (h != null && uiCompatible(h)) {
            uiFromFile = true;
            web.loadDataWithBaseURL("file:///android_asset/", h, "text/html", "utf-8", null);
            ui.postDelayed(new Runnable() {
                public void run() {
                    if (!uiReady && uiFromFile) {
                        uiFile().delete();
                        uiFromFile = false;
                        web.loadUrl("file:///android_asset/index.html");
                    }
                }
            }, 9000);
        } else {
            uiFromFile = false;
            web.loadUrl("file:///android_asset/index.html");
        }
    }

    private void fetchUi(final boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastFetch < 30 * 60 * 1000L) return;
        lastFetch = now;
        new Thread(new Runnable() {
            public void run() {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(UI_URL + "?t=" + System.currentTimeMillis()).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(15000);
                    if (c.getResponseCode() != 200) return;
                    String body = readAll(c.getInputStream());
                    if (body.length() < 2000 || !body.contains("</html>") || !uiCompatible(body)) return;
                    String old = readUi();
                    if (old == null) old = readAll(getAssets().open("index.html"));
                    if (body.equals(old)) return;
                    File tmp = new File(getFilesDir(), "ui.tmp");
                    FileOutputStream o = new FileOutputStream(tmp);
                    o.write(body.getBytes("UTF-8"));
                    o.close();
                    tmp.renameTo(uiFile());
                    ui.post(new Runnable() {
                        public void run() {
                            if (System.currentTimeMillis() - created < 20000 && over.getVisibility() != View.VISIBLE) loadUi();
                            else uiPending = true;
                        }
                    });
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_NOTIFICATION) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                try {
                    Intent si = new Intent(android.provider.Settings.ACTION_SETTINGS);
                    si.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(si);
                } catch (Exception ex) {
                    toast("Nu pot deschide setările");
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_BACK) {
            if (over.getVisibility() == View.VISIBLE) {
                over.evaluateJavascript("(window.onBack&&window.onBack())===true", new ValueCallback<String>() {
                    public void onReceiveValue(String v) {
                        if (!"true".equals(v)) closeOverlay();
                    }
                });
            } else {
                web.evaluateJavascript("window.onBack&&window.onBack()", null);
            }
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    public void onBackPressed() {
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (over.getVisibility() == View.VISIBLE) closeOverlay();
        web.evaluateJavascript("window.onHome&&window.onHome()", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        prefs.edit().putString("gmode", "0").apply();
        if (uiPending && over.getVisibility() != View.VISIBLE) {
            uiPending = false;
            loadUi();
        } else {
            fetchUi(false);
        }
        web.evaluateJavascript("window.onShown&&window.onShown()", null);
        if (over.getVisibility() == View.VISIBLE) over.requestFocus(); else web.requestFocus();
    }

    private void closeOverlay() {
        over.setVisibility(View.GONE);
        over.loadUrl("about:blank");
        web.requestFocus();
    }

    private void toast(String t) {
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show();
    }

    public class Bridge {
        @JavascriptInterface
        public String apps() {
            try {
                PackageManager pm = getPackageManager();
                Map<String, JSONObject> m = new LinkedHashMap<>();
                Intent lb = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER");
                Intent la = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
                for (ResolveInfo ri : pm.queryIntentActivities(lb, 0)) {
                    String p = ri.activityInfo.packageName;
                    if (p.equals(getPackageName()) || m.containsKey(p)) continue;
                    m.put(p, new JSONObject().put("p", p).put("l", ri.loadLabel(pm).toString()).put("t", 1));
                }
                for (ResolveInfo ri : pm.queryIntentActivities(la, 0)) {
                    String p = ri.activityInfo.packageName;
                    if (p.equals(getPackageName()) || m.containsKey(p)) continue;
                    m.put(p, new JSONObject().put("p", p).put("l", ri.loadLabel(pm).toString()).put("t", 0));
                }
                return new JSONArray(m.values()).toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public String icon(String pkg) {
            try {
                PackageManager pm = getPackageManager();
                Drawable d = null;
                boolean banner = false;
                Intent lb = new Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.LEANBACK_LAUNCHER").setPackage(pkg);
                List<ResolveInfo> l = pm.queryIntentActivities(lb, 0);
                if (!l.isEmpty()) d = l.get(0).activityInfo.loadBanner(pm);
                if (d == null) d = pm.getApplicationBanner(pkg);
                if (d != null) banner = true; else d = pm.getApplicationIcon(pkg);
                int w = banner ? 320 : 160, h = banner ? 180 : 160;
                Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bm);
                d.setBounds(0, 0, w, h);
                d.draw(c);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bm.compress(banner ? Bitmap.CompressFormat.JPEG : Bitmap.CompressFormat.PNG, 88, out);
                return (banner ? "B|data:image/jpeg;base64," : "I|data:image/png;base64,")
                        + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            } catch (Throwable t) {
                return "";
            }
        }

        @JavascriptInterface
        public void launch(final String pkg) {
            runOnUiThread(new Runnable() {
                public void run() {
                    PackageManager pm = getPackageManager();
                    Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
                    if (i == null) i = pm.getLaunchIntentForPackage(pkg);
                    if (i == null) {
                        toast("Nu pot deschide aplicația");
                        return;
                    }
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        startActivity(i);
                    } catch (Exception e) {
                        toast("Nu pot deschide aplicația");
                    }
                }
            });
        }

        @JavascriptInterface
        public void openTv() {
            runOnUiThread(new Runnable() {
                public void run() {
                    if (!LiveTv.open(MainActivity.this)) toast("Nu găsesc aplicația TV");
                }
            });
        }

        @JavascriptInterface
        public void ready() {
            uiReady = true;
        }

        @JavascriptInterface
        public int version() {
            return BRIDGE;
        }

        @JavascriptInterface
        public void refreshUi() {
            runOnUiThread(new Runnable() {
                public void run() {
                    uiPending = false;
                    created = System.currentTimeMillis();
                    fetchUi(true);
                }
            });
        }

        @JavascriptInterface
        public void googleHome() {
            prefs.edit().putString("gmode", "1").apply();
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_MAIN);
                        i.addCategory(Intent.CATEGORY_HOME);
                        i.setClassName("com.google.android.apps.tv.launcherx", "com.google.android.apps.tv.launcherx.home.HomeActivity");
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        toast("Nu pot deschide Google TV");
                    }
                }
            });
        }

        @JavascriptInterface
        public String inputs() {
            JSONArray a = new JSONArray();
            try {
                TvInputManager tim = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
                for (TvInputInfo info : tim.getTvInputList()) {
                    CharSequence cl = info.loadCustomLabel(MainActivity.this);
                    CharSequence ll = info.loadLabel(MainActivity.this);
                    a.put(new JSONObject()
                            .put("id", info.getId())
                            .put("label", cl != null ? cl.toString() : String.valueOf(ll))
                            .put("pass", info.isPassthroughInput())
                            .put("type", info.getType()));
                }
            } catch (Throwable t) {
                try {
                    a.put(new JSONObject().put("error", String.valueOf(t)));
                } catch (Exception ignored) {
                }
            }
            return a.toString();
        }

        @JavascriptInterface
        public void openInput(final String id) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(id));
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception e) {
                        toast("Nu pot deschide sursa");
                    }
                }
            });
        }

        @JavascriptInterface
        public void settings(final String action) {
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        Intent i = new Intent(action);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (ActivityNotFoundException e) {
                        toast("Nu pot deschide setările");
                    }
                }
            });
        }

        @JavascriptInterface
        public void openUrl(final String url) {
            runOnUiThread(new Runnable() {
                public void run() {
                    Uri u = Uri.parse(url);
                    if (!"https".equals(u.getScheme()) || !ALLOWED_HOST.equals(u.getHost())) return;
                    over.clearCache(true);
                    over.setVisibility(View.VISIBLE);
                    over.loadUrl(url);
                    over.requestFocus();
                }
            });
        }

        @JavascriptInterface
        public boolean online() {
            try {
                android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                return cm.getActiveNetwork() != null;
            } catch (Exception e) {
                return true;
            }
        }

        @JavascriptInterface
        public String get(String k) {
            return prefs.getString(k, "");
        }

        @JavascriptInterface
        public void set(String k, String v) {
            prefs.edit().putString(k, v).apply();
        }
    }
}
