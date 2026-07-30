package com.ouktv.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

/**
 * O|U KTV — self-hosting Android app.
 *
 * This phone IS the server: HostService runs an embedded HTTP + WebSocket
 * server (KtvHttpServer) with its own local SQLite database, serving the
 * same O|U KTV web app that used to live on a remote VM. No internet
 * connection to any external domain is required for hosting, joining,
 * queueing, or playback control — only song search still calls out to
 * YouTube, same as the original app.
 *
 * Other phones/TVs on the same WiFi network join by opening
 * http://<this-phone's-LAN-IP>:8080/ktv/ in their own browser — see the
 * "Host Info" button.
 */
public class MainActivity extends AppCompatActivity {

    private static final int CAMERA_PERMISSION_REQUEST_CODE = 9001;
    private static final int NOTIF_PERMISSION_REQUEST_CODE = 9003;

    private WebView webView;
    private SwipeRefreshLayout swipeRefresh;
    private LinearLayout offlineBanner;
    private ProgressBar progressBar;

    private PermissionRequest pendingWebPermissionRequest;
    private ValueCallback<Uri[]> filePathCallback;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        offlineBanner = findViewById(R.id.offlineBanner);
        progressBar = findViewById(R.id.progressBar);
        Button hostInfoButton = findViewById(R.id.hostInfoButton);

        requestRuntimePermissions();

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                swipeRefresh.setRefreshing(false);
                offlineBanner.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                swipeRefresh.setRefreshing(false);
                offlineBanner.setVisibility(View.VISIBLE);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                progressBar.setVisibility(newProgress == 100 ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean needsCamera = false;
                    for (String resource : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) needsCamera = true;
                    }
                    if (needsCamera && ContextCompat.checkSelfPermission(MainActivity.this,
                            Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        pendingWebPermissionRequest = request;
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST_CODE);
                    } else {
                        request.grant(request.getResources());
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), 9002);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        swipeRefresh.setOnRefreshListener(this::loadApp);
        swipeRefresh.setColorSchemeColors(0xFFE91E8C);
        hostInfoButton.setOnClickListener(v -> showHostInfoDialog());

        // Start the local server (this phone hosting itself), then load it.
        Intent serviceIntent = new Intent(this, HostService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        // Give the embedded server a moment to bind its port before we load it.
        handler.postDelayed(this::loadApp, 600);
    }

    private void requestRuntimePermissions() {
        java.util.List<String> toRequest = new java.util.ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            toRequest.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            toRequest.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!toRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toArray(new String[0]), NOTIF_PERMISSION_REQUEST_CODE);
        }
    }

    private void loadApp() {
        String url = "http://127.0.0.1:" + HostService.PORT + "/ktv/";
        offlineBanner.setVisibility(View.GONE);
        webView.loadUrl(url);
    }

    private void showHostInfoDialog() {
        String ip = NetUtils.getLocalIpAddress();
        String url = "http://" + (ip != null ? ip : "<connect to WiFi first>") + ":" + HostService.PORT + "/ktv/";

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);

        TextView label = new TextView(this);
        label.setText("Other phones/TV on this same WiFi can join by scanning this code or opening:");
        label.setTextSize(14);
        layout.addView(label);

        TextView urlText = new TextView(this);
        urlText.setText(url);
        urlText.setTextIsSelectable(true);
        urlText.setTextSize(16);
        urlText.setPadding(0, pad / 2, 0, pad / 2);
        urlText.setTextColor(0xFFE91E8C);
        layout.addView(urlText);

        if (ip != null) {
            try {
                byte[] png = QrUtil.renderPng(url, 500);
                Bitmap bmp = BitmapFactory.decodeByteArray(png, 0, png.length);
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(bmp);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (int) (260 * getResources().getDisplayMetrics().density));
                iv.setLayoutParams(lp);
                layout.addView(iv);
            } catch (Exception ignored) {}
        } else {
            TextView warn = new TextView(this);
            warn.setText("This phone isn't on a WiFi network yet. Connect to WiFi so other devices can reach it.");
            warn.setTextColor(0xFFCC0000);
            layout.addView(warn);
        }

        new AlertDialog.Builder(this)
                .setTitle("Host Info")
                .setView(layout)
                .setPositiveButton("Close", null)
                .show();
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_PERMISSION_REQUEST_CODE && pendingWebPermissionRequest != null) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                pendingWebPermissionRequest.grant(pendingWebPermissionRequest.getResources());
            } else {
                pendingWebPermissionRequest.deny();
            }
            pendingWebPermissionRequest = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 9002) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }
}
