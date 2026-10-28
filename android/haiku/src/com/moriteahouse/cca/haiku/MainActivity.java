// CCA Haiku's only screen: the editor page in one WebView (bundled assets, the only page with the host bridge) and
// project previews in a second WebView that has no bridge, so code the model writes can never call the host.
package com.moriteahouse.cca.haiku;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.moriteahouse.cca.haiku.core.ModelClient;
import com.moriteahouse.cca.haiku.core.Projects;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String APP_HOST = "haiku.app";
    private static final String PREVIEW_HOST = "haiku.preview";
    private static final Map<String, String> TYPES = new HashMap<>();
    static {
        String[][] types = {{"html", "text/html"}, {"htm", "text/html"}, {"css", "text/css"},
                            {"js", "text/javascript"}, {"mjs", "text/javascript"}, {"json", "application/json"},
                            {"svg", "image/svg+xml"}, {"png", "image/png"}, {"jpg", "image/jpeg"},
                            {"jpeg", "image/jpeg"}, {"gif", "image/gif"}, {"webp", "image/webp"},
                            {"ico", "image/x-icon"}, {"txt", "text/plain"}, {"md", "text/plain"},
                            {"woff2", "font/woff2"}, {"mp3", "audio/mpeg"}, {"wav", "audio/wav"}};
        for (String[] t : types) TYPES.put(t[0], t[1]);
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private WebView app, preview;
    private LinearLayout previewPane;
    private TextView previewTitle;
    private Projects projects;
    private ModelClient client;
    private KeyVault vault;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        try {
            projects = new Projects(getFilesDir().toPath());
        } catch (Exception e) {
            throw new IllegalStateException("無法建立專案資料夾", e);
        }
        client = new ModelClient();
        vault = new KeyVault(this);
        final boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) ==
                              Configuration.UI_MODE_NIGHT_YES;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(night ? Color.rgb(20, 22, 24) : Color.rgb(247, 245, 240));
        app = new WebView(this);
        configure(app);
        app.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return asset(request.getUrl());
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !APP_HOST.equals(request.getUrl().getHost());  // the editor never leaves its own pages
            }
        });
        app.setWebChromeClient(new WebChromeClient());  // shows confirm() and prompt() as system dialogs
        app.addJavascriptInterface(new Bridge(), "HaikuHost");
        root.addView(app, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(buildPreview(night), new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                       ViewGroup.LayoutParams.MATCH_PARENT));
        // Android 15 draws apps edge to edge; keep content clear of the status bar, navigation bar and keyboard.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                             insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        setContentView(root);
        app.loadUrl("https://" + APP_HOST + "/index.html");
    }

    private void configure(WebView view) {
        WebSettings s = view.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    }

    private View buildPreview(boolean night) {
        previewPane = new LinearLayout(this);
        previewPane.setOrientation(LinearLayout.VERTICAL);
        previewPane.setBackgroundColor(night ? Color.rgb(20, 22, 24) : Color.WHITE);
        previewPane.setVisibility(View.GONE);
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(12, 6, 12, 6);
        Button back = new Button(this);
        back.setText("返回編輯");
        back.setOnClickListener(v -> closePreview());
        Button reload = new Button(this);
        reload.setText("重新整理");
        reload.setOnClickListener(v -> preview.reload());
        previewTitle = new TextView(this);
        previewTitle.setPadding(16, 0, 0, 0);
        previewTitle.setTextColor(night ? Color.rgb(230, 232, 235) : Color.rgb(31, 35, 40));
        bar.addView(back);
        bar.addView(reload);
        bar.addView(previewTitle);
        preview = new WebView(this);
        configure(preview);
        preview.setWebChromeClient(new WebChromeClient());
        preview.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return PREVIEW_HOST.equals(request.getUrl().getHost()) ? projectFile(request.getUrl()) : null;
            }
        });
        previewPane.addView(bar);
        previewPane.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return previewPane;
    }

    private void openPreview(String project) {
        previewTitle.setText(project);
        previewPane.setVisibility(View.VISIBLE);
        preview.clearCache(true);
        preview.loadUrl(new Uri.Builder().scheme("https").authority(PREVIEW_HOST).appendPath(project)
                            .appendPath("index.html").build().toString());
    }

    private void closePreview() {
        previewPane.setVisibility(View.GONE);
        preview.loadUrl("about:blank");
        app.evaluateJavascript("window.Haiku && window.Haiku.previewClosed && window.Haiku.previewClosed()", null);
    }

    @Override
    public void onBackPressed() {
        if (previewPane.getVisibility() == View.VISIBLE) closePreview();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }

    private WebResourceResponse asset(Uri url) {
        if (!APP_HOST.equals(url.getHost())) return null;
        String path = url.getPath() == null || url.getPath().equals("/") ? "index.html" : url.getPath().substring(1);
        if (path.contains("..") || !path.matches("[A-Za-z0-9_./-]+")) return notFound();
        try {
            InputStream in = getAssets().open("www/" + path);
            return new WebResourceResponse(type(path), "utf-8", in);
        } catch (Exception e) {
            return notFound();
        }
    }

    private WebResourceResponse projectFile(Uri url) {
        List<String> parts = url.getPathSegments();
        if (parts.isEmpty()) return notFound();
        StringBuilder relative = new StringBuilder();
        for (int i = 1; i < parts.size(); i++) relative.append(i > 1 ? "/" : "").append(parts.get(i));
        String path = relative.length() == 0 ? "index.html" : relative.toString();
        try {
            byte[] data = projects.readBytes(parts.get(0), path);
            return new WebResourceResponse(type(path), "utf-8", new ByteArrayInputStream(data));
        } catch (Exception e) {
            return notFound();
        }
    }

    private static String type(String path) {
        int dot = path.lastIndexOf('.');
        String t = dot < 0 ? null : TYPES.get(path.substring(dot + 1).toLowerCase());
        return t == null ? "application/octet-stream" : t;
    }

    private static WebResourceResponse notFound() {
        WebResourceResponse r = new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
        r.setStatusCodeAndReasonPhrase(404, "Not Found");
        return r;
    }

    /** Called from the editor page only. Work runs off the UI thread; replies go back through Haiku.receive. */
    private final class Bridge {
        @JavascriptInterface
        public void call(String message) {
            pool.execute(() -> {
                JSONObject reply = new JSONObject();
                try {
                    JSONObject m = new JSONObject(message);
                    reply.put("id", m.optString("id"));
                    reply.put("result", handle(m.optString("method"), m.optJSONObject("params") == null
                                                                       ? new JSONObject()
                                                                       : m.optJSONObject("params")));
                    reply.put("ok", true);
                } catch (Exception e) {
                    try {
                        reply.put("ok", false);
                        reply.put("error", e.getMessage() == null ? "操作失敗" : e.getMessage());
                    } catch (Exception ignored) {
                    }
                }
                // U+2028/2029 are valid in JSON but end a JavaScript string literal.
                String literal = JSONObject.quote(reply.toString()).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
                app.post(() -> app.evaluateJavascript("window.Haiku.receive(" + literal + ")", null));
            });
        }
    }

    private Object handle(String method, JSONObject p) throws Exception {
        String project = p.optString("project");
        switch (method) {
            case "projects.list": {
                JSONArray out = new JSONArray();
                for (Projects.ProjectInfo info : projects.list())
                    out.put(new JSONObject().put("name", info.name).put("files", info.files).put("updated", info.updated));
                return out;
            }
            case "projects.create": projects.create(p.getString("name")); return true;
            case "projects.delete": projects.delete(project); return true;
            case "files.list": {
                JSONArray out = new JSONArray();
                for (Projects.FileInfo f : projects.files(project))
                    out.put(new JSONObject().put("path", f.path).put("size", f.size));
                return out;
            }
            case "files.read": return projects.read(project, p.getString("path"));
            case "files.write": projects.write(project, p.getString("path"), p.getString("text")); return true;
            case "files.delete": projects.remove(project, p.getString("path")); return true;
            case "chat.read": return projects.readInternal(project, "chat.json");
            case "chat.write": projects.writeInternal(project, "chat.json", p.getString("text")); return true;
            case "checkpoint.take": return projects.checkpoint(project);
            case "checkpoint.restore": projects.restore(project, p.getString("checkpoint")); return true;
            case "keys.status": {
                JSONObject out = new JSONObject();
                for (String provider : new String[] {"anthropic", "openai"}) out.put(provider, vault.has(provider));
                return out;
            }
            case "keys.set": {
                String provider = p.getString("provider"), key = p.getString("key").trim();
                if (!ModelClient.knownProvider(provider) || key.length() < 8 || key.length() > 400)
                    throw new IllegalArgumentException("金鑰格式不正確");
                vault.put(provider, key);
                return true;
            }
            case "keys.clear": vault.clear(p.getString("provider")); return true;
            case "models.list": {
                String provider = p.getString("provider");
                ModelClient.Response r = client.models(provider, vault.get(provider));
                return new JSONObject().put("status", r.status).put("body", r.body);
            }
            case "model.send": {
                String provider = p.getString("provider");
                ModelClient.Response r = client.send(provider, vault.get(provider), p.getString("body"));
                return new JSONObject().put("status", r.status).put("body", r.body);
            }
            case "preview.open": {
                projects.files(project);  // fails for a missing project before the preview opens
                runOnUiThread(() -> openPreview(project));
                return true;
            }
            case "export.zip": {
                ContentValues values = new ContentValues();
                String file = project + "-" + System.currentTimeMillis() + ".zip";
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, file);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/CCA Haiku");
                ContentResolver resolver = getContentResolver();
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("無法在下載資料夾建立檔案");
                try (OutputStream out = resolver.openOutputStream(uri)) {
                    projects.exportZip(project, out);
                }
                return "Download/CCA Haiku/" + file;
            }
            default: throw new IllegalArgumentException("不支援的操作 " + method);
        }
    }
}
