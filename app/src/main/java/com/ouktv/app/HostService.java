package com.ouktv.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

public class HostService extends Service {

    public static final int PORT = 8080;
    private static final String CHANNEL_ID = "ouktv_host";
    private static final int NOTIF_ID = 1;

    private KtvHttpServer server;
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends android.os.Binder {
        HostService getService() { return HostService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIF_ID, buildNotification());
        startServer();
    }

    private void startServer() {
        if (server != null) return;
        try {
            server = new KtvHttpServer(this, PORT);
            server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        } catch (Exception e) {
            // If the port is taken (rare), the WebView will show its offline banner
            // and the user can restart the app / free the port.
        }
    }

    public boolean isRunning() {
        return server != null && server.isAlive();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
            server = null;
        }
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "O|U KTV Host", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps your karaoke room running for guests on your WiFi");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("O|U KTV is hosting")
                .setContentText("Your karaoke room is live on this WiFi network")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
