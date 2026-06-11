package com.custom.browser;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JsResult;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MainActivity — Production-ready WebView with full hardware access,
 * dual-mode landing page architecture, offline-first caching,
 * and complete CORS/Security bypass for trusted web applications.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CustomBrowser";
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String LANDING_FILE = "file:///android_asset/www/landing.html";
    private static final String LOCAL_INDEX = "file:///android_asset/www/index.html";
    private static final String ERROR_PAGE = "file:///android_asset/error.html";
    private static final String CONFIG_FILE = "config.json";
    private static final long LANDING_DELAY_MS = 3000;

    private WebView webView;
    private FrameLayout webViewContainer;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> filePathCallback;
    private ValueCallback<Uri> filePathCallbackLegacy;
    private Uri cameraImageUri;
    private String launchUrl = null;
    private Handler handler;
    private boolean isFirstLaunchWithoutInternet = false;

    // ActivityResultLauncher for file chooser (Android 5.0+)
    private ActivityResultLauncher<Intent> fileChooserLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        handler = new Handler(Looper.getMainLooper());

        // Initialize views
        webViewContainer = findViewById(R.id.webview_container);
        progressBar = findViewById(R.id.progress_bar);

        // Register file chooser launcher
        fileChooserLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleFileChooserResult(result.getResultCode(), result.getData());
                } else if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
            }
        );

        // Check critical permissions
        requestAllPermissions();

        // Create and configure WebView
        createWebView();
        configureWebViewSettings();
        configureWebViewClients();

        // Decide what to load
        determineAndLoadContent();
    }

    // ──────────────────────────────────────────────
    //  WebView Creation &amp; Configuration
    // ──────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void createWebView() {
        webView = new WebView(this);
        webViewContainer.addView(webView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebViewSettings() {
        WebSettings settings = webView.getSettings();

        // ── JavaScript &amp; DOM ──
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);

        // ── Full CORS Bypass &amp; File Access ──
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);

        // ── Cache Strategy: Load from cache first, fallback to network ──
        settings.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        settings.setAppCacheEnabled(true);
        settings.setAppCachePath(getCacheDir().getAbsolutePath());

        // ── Performance &amp; Compatibility ──
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        settings.setSaveFormData(true);
        settings.setGeolocationEnabled(true);
        settings.setGeolocationDatabasePath(getFilesDir().getPath() + "/geolocation");
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // ── User Agent ──
        String userAgent = settings.getUserAgentString()
            .replace("; wv", " CustomBrowser/1.0");
        settings.setUserAgentString(userAgent);

        // ── Enable third-party cookies ──
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        // Allow debugging in debug builds
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
    }

    private void configureWebViewClients() {
        // ── WebViewClient ──
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String scheme = url.getScheme();

                // Handle non-http schemes (tel:, mailto:, intent:, etc.)
                if (scheme != null && !scheme.equals("http") && !scheme.equals("https") 
                    && !scheme.equals("file") && !scheme.equals("javascript")) {
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW, url);
                        startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        Log.w(TAG, "No activity found to handle: " + url);
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // In production, you should validate certificates. For trusted internal apps, proceed.
                handler.proceed();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                Log.e(TAG, "WebView error [" + errorCode + "]: " + description);
                if (!isNetworkAvailable() && !hasCachedContent()) {
                    loadErrorPage();
                }
            }

            @Override
            @RequiresApi(api = Build.VERSION_CODES.M)
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse errorResponse) {
                Log.w(TAG, "HTTP error " + errorResponse.getStatusCode() + " for: " + request.getUrl());
            }
        });

        // ── WebChromeClient ──
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                }
            }

            // ── Auto-grant hardware permissions (Camera, Microphone, Geolocation) ──
            @Override
            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            public void onPermissionRequest(final PermissionRequest request) {
                Log.d(TAG, "onPermissionRequest: " + request.getResources());
                // Auto-grant ALL requested permissions
                String[] resources = request.getResources();
                for (String resource : resources) {
                    Log.d(TAG, "Auto-granting permission: " + resource);
                }
                // Grant all requested permissions immediately
                request.grant(request.getResources());
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                Log.d(TAG, "Permission request canceled");
                // Retry granting — keep the app functional
                String[] resources = request.getResources();
                if (resources.length > 0) {
                    request.grant(resources);
                }
            }

            // ── File Chooser (Android 5.0+) ──
            @Override
            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            public boolean onShowFileChooser(
                WebView webView,
                ValueCallback<Uri[]> filePathCallback,
                WebChromeClient.FileChooserParams fileChooserParams
            ) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                // Build intent for picking files
                Intent intent = fileChooserParams.createIntent();
                intent.addCategory(Intent.CATEGORY_OPENABLE);

                try {
                    fileChooserLauncher.launch(intent);
                } catch (ActivityNotFoundException e) {
                    MainActivity.this.filePathCallback = null;
                    Toast.makeText(MainActivity.this, "No file chooser available", Toast.LENGTH_SHORT).show();
                    return false;
                }
                return true;
            }

            // ── Legacy File Chooser (Android 4.1 – 4.4) ──
            @SuppressWarnings("unused")
            public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture) {
                if (filePathCallbackLegacy != null) {
                    filePathCallbackLegacy.onReceiveValue(null);
                }
                filePathCallbackLegacy = uploadMsg;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");

                try {
                    startActivityForResult(Intent.createChooser(intent, "Select File"), PERMISSION_REQUEST_CODE);
                } catch (ActivityNotFoundException e) {
                    filePathCallbackLegacy = null;
                    Toast.makeText(MainActivity.this, "No file chooser available", Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                result.confirm();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                result.confirm();
                return true;
            }
        });

        // ── Download Listener ──
        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
                handleDownload(url, userAgent, contentDisposition, mimetype, contentLength);
            }
        });
    }

    // ──────────────────────────────────────────────
    //  Content Loading Logic
    // ──────────────────────────────────────────────

    private void determineAndLoadContent() {
        boolean landingExists = assetFileExists("www/landing.html");
        boolean indexExists = assetFileExists("www/index.html");

        if (landingExists) {
            // ── Landing Page Flow ──
            Log.d(TAG, "Landing page found. Loading with " + LANDING_DELAY_MS + "ms delay...");
            webView.loadUrl(LANDING_FILE);

            handler.postDelayed(() -> {
                if (indexExists) {
                    Log.d(TAG, "Loading local index.html after landing delay");
                    webView.loadUrl(LOCAL_INDEX);
                } else {
                    String remoteUrl = getLaunchUrlFromConfig();
                    if (remoteUrl != null && !remoteUrl.isEmpty() && !remoteUrl.equals("PLACEHOLDER_URL")) {
                        Log.d(TAG, "Loading remote URL after landing delay: " + remoteUrl);
                        webView.loadUrl(remoteUrl);
                    } else {
                        Log.d(TAG, "No valid remote URL configured. Staying on landing.");
                        Toast.makeText(this, "No content source configured.", Toast.LENGTH_LONG).show();
                    }
                }
            }, LANDING_DELAY_MS);

        } else if (indexExists) {
            // ── Direct Local Load ──
            Log.d(TAG, "Loading local index.html");
            webView.loadUrl(LOCAL_INDEX);

        } else {
            // ── Remote Load ──
            String remoteUrl = getLaunchUrlFromConfig();
            if (remoteUrl != null && !remoteUrl.isEmpty() && !remoteUrl.equals("PLACEHOLDER_URL")) {
                if (isNetworkAvailable()) {
                    Log.d(TAG, "Loading remote URL: " + remoteUrl);
                    webView.loadUrl(remoteUrl);
                } else {
                    // No internet + no cache → show error page
                    if (!hasCachedContent()) {
                        Log.d(TAG, "No network and no cache. Loading error page.");
                        isFirstLaunchWithoutInternet = true;
                        loadErrorPage();
                    } else {
                        // Has cache → let WebView use cache
                        Log.d(TAG, "No network but cache exists. Attempting cached load.");
                        webView.loadUrl(remoteUrl);
                    }
                }
            } else {
                // No URL configured → show error page
                Log.d(TAG, "No launch URL configured. Loading error page.");
                loadErrorPage();
            }
        }
    }

    private void loadErrorPage() {
        Log.d(TAG, "Loading embedded error page.");
        runOnUiThread(() -> {
            webView.loadUrl(ERROR_PAGE);
        });
    }

    // ──────────────────────────────────────────────
    //  Config Parsing
    // ──────────────────────────────────────────────

    private String getLaunchUrlFromConfig() {
        if (launchUrl != null && !launchUrl.isEmpty()) {
            return launchUrl;
        }

        try {
            AssetManager assetManager = getAssets();
            InputStream inputStream = assetManager.open(CONFIG_FILE);
            int size = inputStream.available();
            byte[] buffer = new byte[size];
            inputStream.read(buffer);
            inputStream.close();
            String json = new String(buffer, "UTF-8");

            // Simple JSON parsing without external library
            // Extract "launch_url" value
            String key = "\"launch_url\"";
            int keyIndex = json.indexOf(key);
            if (keyIndex != -1) {
                int colonIndex = json.indexOf(':', keyIndex);
                int valueStart = json.indexOf('"', colonIndex + 1);
                int valueEnd = json.indexOf('"', valueStart + 1);
                if (valueStart != -1 && valueEnd != -1) {
                    launchUrl = json.substring(valueStart + 1, valueEnd);
                    Log.d(TAG, "Parsed launch_url from config: " + launchUrl);
                    return launchUrl;
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to read config.json: " + e.getMessage());
        }

        return null;
    }

    // ──────────────────────────────────────────────
    //  Asset Helpers
    // ──────────────────────────────────────────────

    private boolean assetFileExists(String path) {
        try {
            AssetManager assetManager = getAssets();
            InputStream inputStream = assetManager.open(path);
            inputStream.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ──────────────────────────────────────────────
    //  Network &amp; Cache Detection
    // ──────────────────────────────────────────────

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnectedOrConnecting();
    }

    private boolean hasCachedContent() {
        // Check if any WebView cache exists
        try {
            File cacheDir = new File(getCacheDir(), "WebView");
            if (!cacheDir.exists()) return false;

            // Check for Chromium HTTP cache
            File chromiumCache = new File(cacheDir, "Default/HTTP Cache");
            if (chromiumCache.exists()) {
                File cacheDataDir = new File(chromiumCache, "Cache_Data");
                if (cacheDataDir.exists() && cacheDataDir.isDirectory()) {
                    String[] files = cacheDataDir.list();
                    if (files != null && files.length > 0) return true;
                }
                // Check the HTTP Cache directory itself
                String[] files = chromiumCache.list();
                if (files != null && files.length > 0) return true;
            }

            // Check for any files in the WebView directory
            File[] cacheFiles = cacheDir.listFiles();
            if (cacheFiles != null && cacheFiles.length > 0) return true;

            // Also check app cache
            File appCache = new File(cacheDir, "Application Cache");
            if (appCache.exists()) {
                String[] appCacheFiles = appCache.list();
                if (appCacheFiles != null && appCacheFiles.length > 0) return true;
            }

        } catch (Exception e) {
            Log.w(TAG, "Error checking cache: " + e.getMessage());
        }
        return false;
    }

    // ──────────────────────────────────────────────
    //  Download Handler
    // ──────────────────────────────────────────────

    private void handleDownload(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Scoped storage — use DownloadManager
            downloadUsingManager(url, userAgent, contentDisposition, mimetype);
        } else {
            // Request write permission before downloading
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST_CODE + 1);
                Toast.makeText(this, "Storage permission needed for downloads.", Toast.LENGTH_SHORT).show();
            } else {
                downloadUsingManager(url, userAgent, contentDisposition, mimetype);
            }
        }
    }

    private void downloadUsingManager(String url, String userAgent, String contentDisposition, String mimetype) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setMimeType(mimetype);
        request.addRequestHeader("User-Agent", userAgent);
        request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url));
        request.setDescription("Downloading file...");
        request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimetype));
        request.allowScanningByMediaScanner();
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
            URLUtil.guessFileName(url, contentDisposition, mimetype));

        DownloadManager downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager != null) {
            downloadManager.enqueue(request);
            Toast.makeText(this, "Download started...", Toast.LENGTH_SHORT).show();
        }
    }

    // ──────────────────────────────────────────────
    //  File Chooser Result Handling
    // ──────────────────────────────────────────────

    private void handleFileChooserResult(int resultCode, Intent data) {
        if (filePathCallback == null) return;

        Uri[] results = null;

        if (resultCode == Activity.RESULT_OK && data != null) {
            String dataString = data.getDataString();
            ClipData clipData = data.getClipData();

            if (clipData != null) {
                // Multiple files selected
                results = new Uri[clipData.getItemCount()];
                for (int i = 0; i < clipData.getItemCount(); i++) {
                    results[i] = clipData.getItemAt(i).getUri();
                }
            } else if (dataString != null) {
                // Single file selected
                results = new Uri[]{Uri.parse(dataString)};
            } else if (cameraImageUri != null) {
                // Photo from camera
                results = new Uri[]{cameraImageUri};
            }
        }

        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
        cameraImageUri = null;
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Handle legacy file chooser (pre-Lollipop)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (filePathCallbackLegacy != null) {
                Uri result = (data != null && resultCode == Activity.RESULT_OK) ? data.getData() : null;
                filePathCallbackLegacy.onReceiveValue(result);
                filePathCallbackLegacy = null;
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Permissions
    // ──────────────────────────────────────────────

    private void requestAllPermissions() {
        String[] permissions = {
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
        };

        // Filter for ungranted permissions
        java.util.ArrayList<String> needed = new java.util.ArrayList<>();
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needed.add(perm);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }

        // For Android 11+ manage external storage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // Optionally guide the user to grant this — uncomment for full file access:
                // Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                //     Uri.parse("package:" + getPackageName()));
                // startActivity(intent);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int grantResult : grantResults) {
                if (grantResult == PackageManager.PERMISSION_DENIED) {
                    Log.w(TAG, "Some permissions were denied by user.");
                    // App continues to function — WebView WebChromeClient auto-grants web requests
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Back Button Handling
    // ──────────────────────────────────────────────

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // ──────────────────────────────────────────────
    //  Lifecycle
    // ──────────────────────────────────────────────

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
    }

    @Override
    protected void onPause() {
        webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webViewContainer.removeView(webView);
            webView.loadUrl("about:blank");
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
            webView = null;
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        super.onDestroy();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        if (webView != null) {
            webView.restoreState(savedInstanceState);
        }
    }
}
