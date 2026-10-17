package com.sidhbaba.quotations;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.graphics.Insets;
import android.os.Environment;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.ValueCallback;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_ALL_FILES = 4101;
    private static final int REQ_INSTALL_UNKNOWN = 4102;
    private static final int REQ_SELECT_BACKUP = 4103;
    private WebView webView;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private String pendingQuoteJson;
    private String pendingExportMode;
    private File pendingInstallFile;
    private boolean settingsForStorage;
    private boolean settingsForInstall;
    private String pendingBackupJson;
    private ValueCallback<Uri[]> filePathCallback;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(0x00000000);
        getWindow().setNavigationBarColor(0x00000000);
        if (Build.VERSION.SDK_INT >= 29) {
            getWindow().setStatusBarContrastEnforced(false);
            getWindow().setNavigationBarContrastEnforced(false);
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        webView = new WebView(this);
        webView.setBackgroundColor(0xFFF4F6FA);
        webView.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("application/json");
                try { startActivityForResult(pick, REQ_SELECT_BACKUP); return true; }
                catch (ActivityNotFoundException e) { filePathCallback = null; callback.onReceiveValue(null); return false; }
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                try { view.evaluateJavascript("window.onNativeConfig(" + getNativeConfig() + ")", null); } catch (Exception ignored) { }
            }
        });
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        setContentView(webView);
        try (InputStream asset = getAssets().open("index.html"); ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            byte[] data = new byte[8192]; int count;
            while ((count = asset.read(data)) != -1) buffer.write(data, 0, count);
            String html = buffer.toString(StandardCharsets.UTF_8.name());
            webView.loadDataWithBaseURL("https://sbe.local/", html, "text/html", "UTF-8", null);
        } catch (Exception e) {
            Toast.makeText(this, "Could not load quotation screen: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        checkAutomaticUpdate();
    }

    @Override protected void onResume() {
        super.onResume();
        if (pendingQuoteJson != null && hasAllFilesAccess()) {
            String q = pendingQuoteJson, mode = pendingExportMode;
            pendingQuoteJson = null; pendingExportMode = null;
            generatePdfNow(q, mode);
        }
        if (pendingBackupJson != null && hasAllFilesAccess()) {
            String backup = pendingBackupJson; pendingBackupJson = null;
            saveBackupNow(backup);
        }
        if (pendingInstallFile != null && canInstallPackages() && settingsForInstall) {
            settingsForInstall = false;
            File apk = pendingInstallFile;
            pendingInstallFile = null;
            launchPackageInstaller(apk);
        }
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (webView != null) { webView.removeJavascriptInterface("AndroidBridge"); webView.destroy(); }
        io.shutdownNow();
        super.onDestroy();
    }

    private boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    private boolean canInstallPackages() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    private void promptStorageAccess(String quoteJson, String mode) {
        pendingQuoteJson = quoteJson;
        pendingExportMode = mode;
        new AlertDialog.Builder(this)
            .setTitle("Allow quotation folder access")
            .setMessage("To save PDFs in the phone's main storage folder named 'Quotitions Form', Android requires one-time storage access approval. This app uses it only for quotation PDFs and backup files.")
            .setPositiveButton("Open Settings", (d, w) -> openAllFilesSettings())
            .setNegativeButton("Cancel", (d, w) -> {
                pendingQuoteJson = null; pendingExportMode = null;
                notifyWeb(false, "Storage access was not granted. PDF was not saved.", null, null);
            }).show();
    }

    private void openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < 30) return;
        settingsForStorage = true;
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivityForResult(i, REQ_ALL_FILES);
        } catch (ActivityNotFoundException e) {
            try { startActivityForResult(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION), REQ_ALL_FILES); }
            catch (Exception ignored) { Toast.makeText(this, "Open app settings and enable All files access.", Toast.LENGTH_LONG).show(); }
        }
    }

    private File quotationFolder() throws Exception {
        File root = Environment.getExternalStorageDirectory();
        File folder = new File(root, "Quotitions Form");
        if (!folder.exists() && !folder.mkdirs()) throw new Exception("Could not create /Quotitions Form. Check storage access in Settings.");
        if (!folder.isDirectory() || !folder.canWrite()) throw new Exception("Quotation folder is not writable. Check storage access in Settings.");
        return folder;
    }

    private void generatePdfNow(String quoteJson, String mode) {
        io.execute(() -> {
            File out = null;
            try {
                JSONObject q = new JSONObject(quoteJson);
                String rawName = q.optString("quoteNo", "Quotation").replaceAll("[^A-Za-z0-9._-]", "_");
                if (rawName.isEmpty()) rawName = "Quotation";
                out = new File(quotationFolder(), rawName + ".pdf");
                QuotePdfRenderer.create(this, q, out);
                File finalOut = out;
                runOnUiThread(() -> {
                    if ("share".equalsIgnoreCase(mode)) sharePdf(finalOut);
                    notifyWeb(true, "PDF saved: /Quotitions Form/" + finalOut.getName(), finalOut.getAbsolutePath(), finalOut.getName());
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Could not generate PDF." : e.getMessage();
                runOnUiThread(() -> notifyWeb(false, message, null, null));
            }
        });
    }

    private void sharePdf(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/pdf");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "Quotation - " + file.getName().replace(".pdf", ""));
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Share quotation PDF"));
        } catch (Exception e) {
            Toast.makeText(this, "PDF saved, but sharing could not be opened: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void notifyWeb(boolean ok, String message, String path, String name) {
        if (webView == null) return;
        try {
            JSONObject result = new JSONObject();
            result.put("ok", ok); result.put("message", message == null ? "" : message);
            result.put("path", path == null ? "" : path); result.put("name", name == null ? "" : name);
            webView.evaluateJavascript("window.onNativePdfResult && window.onNativePdfResult(" + JSONObject.quote(result.toString()) + ")", null);
        } catch (Exception ignored) { }
    }

    private String getNativeConfig() {
        JSONObject j = new JSONObject();
        try {
            j.put("versionName", BuildConfig.VERSION_NAME);
            j.put("versionCode", BuildConfig.VERSION_CODE);
            j.put("githubOwner", BuildConfig.GITHUB_OWNER);
            j.put("githubRepo", BuildConfig.GITHUB_REPO);
            j.put("storageGranted", hasAllFilesAccess());
            j.put("androidVersion", Build.VERSION.RELEASE);
        } catch (Exception ignored) { }
        return j.toString();
    }

    private String getAppInfo() {
        return "SBE Quotations v" + BuildConfig.VERSION_NAME + " · Android " + Build.VERSION.RELEASE + " · Storage access: " + (hasAllFilesAccess() ? "enabled" : "not yet enabled");
    }

    private class AndroidBridge {
        @JavascriptInterface public void generatePdf(String quoteJson, String mode) {
            runOnUiThread(() -> {
                if (!hasAllFilesAccess()) { promptStorageAccess(quoteJson, mode); return; }
                generatePdfNow(quoteJson, mode);
            });
        }
        @JavascriptInterface public void requestStoragePermission() {
            runOnUiThread(() -> {
                if (hasAllFilesAccess()) { Toast.makeText(MainActivity.this, "Quotation folder access is already enabled.", Toast.LENGTH_SHORT).show(); return; }
                new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Quotation folder access")
                    .setMessage("Enable All files access to create /Quotitions Form in phone internal storage.")
                    .setPositiveButton("Open Settings", (d, w) -> openAllFilesSettings())
                    .setNegativeButton("Cancel", null).show();
            });
        }
        @JavascriptInterface public String getNativeConfig() { return MainActivity.this.getNativeConfig(); }
        @JavascriptInterface public String getAppInfo() { return MainActivity.this.getAppInfo(); }
        @JavascriptInterface public void checkForUpdate(String owner, String repo, boolean manual) { checkForUpdateInternal(owner, repo, manual); }
        @JavascriptInterface public void saveBackup(String json) {
            runOnUiThread(() -> {
                if (!hasAllFilesAccess()) {
                    pendingBackupJson = json;
                    new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Allow quotation folder access")
                        .setMessage("To save a compact JSON backup in /Quotitions Form, enable storage access once.")
                        .setPositiveButton("Open Settings", (d, w) -> openAllFilesSettings())
                        .setNegativeButton("Cancel", (d, w) -> pendingBackupJson = null).show();
                    return;
                }
                saveBackupNow(json);
            });
        }
    }

    private void saveBackupNow(String json) {
        io.execute(() -> {
            try {
                File folder = quotationFolder();
                String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(new java.util.Date());
                File f = new File(folder, "SBE-Quotations-Backup-" + stamp + ".json");
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Backup saved: " + f.getName(), Toast.LENGTH_LONG).show());
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(MainActivity.this, "Backup failed: " + e.getMessage(), Toast.LENGTH_LONG).show()); }
        });
    }

    private void checkAutomaticUpdate() {
        String owner = BuildConfig.GITHUB_OWNER, repo = BuildConfig.GITHUB_REPO;
        if (owner == null || owner.isEmpty() || owner.equals("YOUR_GITHUB_USERNAME") || repo == null || repo.isEmpty()) return;
        long last = getPreferences(MODE_PRIVATE).getLong("last_update_check", 0L);
        if (System.currentTimeMillis() - last < 12L * 60L * 60L * 1000L) return;
        checkForUpdateInternal(owner, repo, false);
    }

    private void checkForUpdateInternal(String owner, String repo, boolean manual) {
        owner = owner == null ? "" : owner.trim(); repo = repo == null ? "" : repo.trim();
        if (owner.isEmpty() || repo.isEmpty() || owner.equals("YOUR_GITHUB_USERNAME")) {
            if (manual) notifyUpdate("Enter the public GitHub owner and repository name in Settings.", "error");
            return;
        }
        final String finalOwner = owner, finalRepo = repo;
        io.execute(() -> {
            // Throttle both successful and unsuccessful automatic checks to avoid checking on every launch.
            getPreferences(MODE_PRIVATE).edit().putLong("last_update_check", System.currentTimeMillis()).apply();
            HttpURLConnection connection = null;
            try {
                URL url = new URL("https://api.github.com/repos/" + finalOwner + "/" + finalRepo + "/releases/latest");
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET"); connection.setConnectTimeout(10000); connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setRequestProperty("User-Agent", "SBE-Quotations-Android");
                int status = connection.getResponseCode();
                if (status == 404) throw new Exception("No public GitHub release found. Publish a release with an APK first.");
                if (status < 200 || status >= 300) throw new Exception("GitHub returned HTTP " + status + ".");
                String body;
                try (InputStream in = connection.getInputStream()) { body = new String(readAll(in), StandardCharsets.UTF_8); }
                JSONObject release = new JSONObject(body);
                String tag = release.optString("tag_name", "").replaceFirst("^[vV]", "");
                String current = BuildConfig.VERSION_NAME.replaceFirst("^[vV]", "");
                JSONArray assets = release.optJSONArray("assets");
                String apkUrl = null, apkName = "SBE-Quotations.apk";
                if (assets != null) for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String name = asset.optString("name", "");
                    if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) { apkUrl = asset.optString("browser_download_url", null); apkName = name; break; }
                }
                if (tag.isEmpty() || compareVersions(tag, current) <= 0) {
                    if (manual) notifyUpdate("You're using the latest version (v" + BuildConfig.VERSION_NAME + ").", "ok");
                    return;
                }
                if (apkUrl == null || apkUrl.isEmpty()) throw new Exception("Release v" + tag + " has no APK asset.");
                String finalApkUrl = apkUrl, finalApkName = apkName;
                runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Update available: v" + tag)
                    .setMessage("Installed version: " + BuildConfig.VERSION_NAME + "\nNew version: " + tag + "\n\nDownload and open Android installer now?")
                    .setPositiveButton("Download & Install", (d, w) -> downloadAndInstall(finalApkUrl, finalApkName))
                    .setNegativeButton("Later", null).show());
            } catch (Exception e) {
                if (manual) notifyUpdate(e.getMessage() == null ? "Update check failed." : e.getMessage(), "error");
            } finally { if (connection != null) connection.disconnect(); }
        });
    }

    private void downloadAndInstall(String apkUrl, String apkName) {
        notifyUpdate("Downloading update APK…", "info");
        io.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(apkUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
                connection.setRequestProperty("User-Agent", "SBE-Quotations-Android");
                if (connection.getResponseCode() < 200 || connection.getResponseCode() >= 300) throw new Exception("APK download failed.");
                File out = new File(getCacheDir(), apkName.replaceAll("[^A-Za-z0-9._-]", "_"));
                try (InputStream in = new BufferedInputStream(connection.getInputStream()); FileOutputStream stream = new FileOutputStream(out)) {
                    byte[] buffer = new byte[8192]; int n;
                    while ((n = in.read(buffer)) != -1) stream.write(buffer, 0, n);
                }
                pendingInstallFile = out;
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Update downloaded.", Toast.LENGTH_SHORT).show();
                    if (canInstallPackages()) { File f = pendingInstallFile; pendingInstallFile = null; launchPackageInstaller(f); }
                    else {
                        settingsForInstall = true;
                        try { startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())), REQ_INSTALL_UNKNOWN); }
                        catch (Exception e) { new AlertDialog.Builder(MainActivity.this).setMessage("Allow this app to install updates in Android settings, then return here.").setPositiveButton("OK", null).show(); }
                    }
                });
            } catch (Exception e) { notifyUpdate(e.getMessage() == null ? "Update download failed." : e.getMessage(), "error"); }
            finally { if (connection != null) connection.disconnect(); }
        });
    }

    private void launchPackageInstaller(File apk) {
        if (apk == null || !apk.exists()) { notifyUpdate("Downloaded update APK is missing.", "error"); return; }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apk);
            Intent install = new Intent(Intent.ACTION_VIEW);
            install.setDataAndType(uri, "application/vnd.android.package-archive");
            install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(install);
        } catch (Exception e) { notifyUpdate("Could not open Android installer: " + e.getMessage(), "error"); }
    }

    private void notifyUpdate(String message, String kind) {
        runOnUiThread(() -> {
            if (webView != null) {
                String js = "window.onNativeUpdateStatus && window.onNativeUpdateStatus(" + JSONObject.quote(message) + "," + JSONObject.quote(kind) + ")";
                webView.evaluateJavascript(js, null);
            } else Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
        });
    }

    private static byte[] readAll(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private int compareVersions(String a, String b) {
        String[] aa = a.split("[.-]"), bb = b.split("[.-]");
        int n = Math.max(aa.length, bb.length);
        for (int i = 0; i < n; i++) {
            String x = i < aa.length ? aa[i] : "0", y = i < bb.length ? bb[i] : "0";
            int xi, yi;
            try { xi = Integer.parseInt(x); } catch (NumberFormatException e) { xi = 0; }
            try { yi = Integer.parseInt(y); } catch (NumberFormatException e) { yi = 0; }
            if (xi != yi) return Integer.compare(xi, yi);
            if (xi == 0 && yi == 0) { int cmp = x.compareToIgnoreCase(y); if (cmp != 0 && !x.equals("0") && !y.equals("0")) return cmp; }
        }
        return 0;
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_SELECT_BACKUP) {
            if (filePathCallback != null) {
                Uri result = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
                filePathCallback.onReceiveValue(result == null ? null : new Uri[]{result});
                filePathCallback = null;
            }
        } else if (requestCode == REQ_ALL_FILES) {
            settingsForStorage = false;
            if (hasAllFilesAccess()) {
                if (pendingBackupJson != null) { String b = pendingBackupJson; pendingBackupJson = null; saveBackupNow(b); }
                else if (pendingQuoteJson != null) { String q = pendingQuoteJson, m = pendingExportMode; pendingQuoteJson = null; pendingExportMode = null; generatePdfNow(q, m); }
            } else if (pendingBackupJson != null) {
                pendingBackupJson = null;
                Toast.makeText(this, "Backup not saved because storage access was not enabled.", Toast.LENGTH_LONG).show();
            } else if (pendingQuoteJson != null) {
                pendingQuoteJson = null; pendingExportMode = null;
                notifyWeb(false, "Storage access was not enabled. PDF was not saved.", null, null);
            }
        } else if (requestCode == REQ_INSTALL_UNKNOWN) {
            if (canInstallPackages() && pendingInstallFile != null) { File f = pendingInstallFile; pendingInstallFile = null; settingsForInstall = false; launchPackageInstaller(f); }
            else notifyUpdate("Allow 'Install unknown apps' for SBE Quotations, then check for updates again.", "error");
        }
    }
}
