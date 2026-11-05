package com.ee.downloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Foreground best-effort downloader for unencrypted MPEG-TS HLS VOD. No DRM or login bypass. */
public final class HlsDownloadService extends Service {
    public static final String ACTION_START = "com.ee.downloader.HLS_START";
    public static final String ACTION_CANCEL = "com.ee.downloader.HLS_CANCEL";
    public static final String EXTRA_URL = "url";
    public static final String EXTRA_COOKIE = "cookie";
    public static final String EXTRA_REFERER = "referer";
    public static final String EXTRA_USER_AGENT = "useragent";
    private static final int NOTIFICATION_ID = 2640;
    private static final String CHANNEL = "hls_downloads";
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private final AtomicBoolean canceled=new AtomicBoolean(false);
    private final AtomicBoolean running=new AtomicBoolean(false);
    private NotificationManager manager;

    public static String status(android.content.Context ctx) {
        return ctx.getSharedPreferences("ee_hls", MODE_PRIVATE).getString("status", "لا يوجد تنزيل HLS نشط");
    }
    private void status(String value) {
        getSharedPreferences("ee_hls",MODE_PRIVATE).edit().putString("status",value).apply();
    }
    @Override public void onCreate() {
        super.onCreate();
        manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        manager.createNotificationChannel(new NotificationChannel(CHANNEL,"تنزيلات HLS",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent==null) return START_NOT_STICKY;
        if (ACTION_CANCEL.equals(intent.getAction())) {
            canceled.set(true);
            status("جارٍ إيقاف تنزيل HLS...");
            if (!running.get()) stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;
        String url=intent.getStringExtra(EXTRA_URL);
        if(url==null || !HlsPlaylist.isHls(url)) { stopSelf();return START_NOT_STICKY; }
        if (!running.compareAndSet(false,true)) return START_NOT_STICKY;
        canceled.set(false);
        startForeground(NOTIFICATION_ID,notification("جارٍ فحص قائمة HLS",0));
        String cookie=intent.getStringExtra(EXTRA_COOKIE);
        String referer=intent.getStringExtra(EXTRA_REFERER);
        String agent=intent.getStringExtra(EXTRA_USER_AGENT);
        executor.execute(()->download(url,cookie,referer,agent));
        return START_NOT_STICKY;
    }
    private Notification notification(String message,int progress) {
        Intent cancel=new Intent(this,HlsDownloadService.class).setAction(ACTION_CANCEL);
        PendingIntent cancelIntent=PendingIntent.getService(this,1,cancel,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent openIntent=PendingIntent.getActivity(this,2,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("EE Tube • HLS")
            .setContentText(message)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(new Notification.Action.Builder(null,"إلغاء",cancelIntent).build());
        if(progress>0 && progress<100) builder.setProgress(100,progress,false);
        else if(progress<=0) builder.setProgress(100,0,true);
        return builder.build();
    }
    private HttpURLConnection connection(String url,String cookie,String referer,String agent) throws Exception {
        URL address=new URL(url);
        if(!"https".equalsIgnoreCase(address.getProtocol()) && !"http".equalsIgnoreCase(address.getProtocol())) throw new IllegalArgumentException("scheme");
        HttpURLConnection c=(HttpURLConnection)address.openConnection();
        c.setConnectTimeout(15000);c.setReadTimeout(20000);
        c.setInstanceFollowRedirects(true);
        if(cookie!=null && !cookie.isEmpty()) c.setRequestProperty("Cookie",cookie);
        if(referer!=null && !referer.isEmpty()) c.setRequestProperty("Referer",referer);
        if(agent!=null && !agent.isEmpty()) c.setRequestProperty("User-Agent",agent);
        return c;
    }
    private String readManifest(String url,String cookie,String referer,String agent) throws Exception {
        HttpURLConnection c=connection(url,cookie,referer,agent);
        try {
            if(c.getResponseCode()!=200) throw new IllegalStateException("HTTP "+c.getResponseCode());
            try(InputStream in=c.getInputStream();java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()) {
                byte[] bytes=new byte[4096];int len;
                while((len=in.read(bytes))!=-1) {
                    if(canceled.get()) throw new InterruptedException("canceled");
                    if(out.size()+len>2_000_000) throw new IllegalStateException("Manifest too large");
                    out.write(bytes,0,len);
                }
                return out.toString("UTF-8");
            }
        } finally {c.disconnect();}
    }
    private void download(String url,String cookie,String referer,String agent) {
        Uri output=null;
        try {
            if(canceled.get()) throw new InterruptedException("canceled");
            String manifest=readManifest(url,cookie,referer,agent);
            List<HlsPlaylist.Variant> variants=HlsPlaylist.variants(url,manifest);
            if(!variants.isEmpty()) { url=variants.get(0).url; manifest=readManifest(url,cookie,referer,agent); }
            HlsPlaylist.Media media=HlsPlaylist.media(url,manifest);
            if(!media.supported()) throw new UnsupportedOperationException(media.error);
            ContentValues values=new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME,"EE_Tube_"+System.currentTimeMillis()+".ts");
            values.put(MediaStore.Downloads.MIME_TYPE,"video/mp2t");
            values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/EE Tube");
            values.put(MediaStore.Downloads.IS_PENDING,1);
            output=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
            if(output==null) throw new IllegalStateException("Storage unavailable");
            final int total=media.segments.size();
            status("HLS: جارٍ تنزيل "+total+" مقطع");
            try(OutputStream out=getContentResolver().openOutputStream(output,"w")) {
                if(out==null) throw new IllegalStateException("Output unavailable");
                byte[] buffer=new byte[65536];long lastNotice=0;
                for(int i=0;i<total;i++) {
                    if(canceled.get()) throw new InterruptedException("canceled");
                    HttpURLConnection c=connection(media.segments.get(i),cookie,referer,agent);
                    try {
                        if(c.getResponseCode()!=200) throw new IllegalStateException("Segment HTTP "+c.getResponseCode());
                        try(InputStream in=c.getInputStream()) {
                            int len;
                            while((len=in.read(buffer))!=-1) {
                                if(canceled.get()) throw new InterruptedException("canceled");
                                out.write(buffer,0,len);
                            }
                        }
                    } finally {c.disconnect();}
                    int pct=(i+1)*100/total;
                    status("HLS: "+pct+"% • "+(i+1)+"/"+total);
                    if(System.currentTimeMillis()-lastNotice>1200) {
                        manager.notify(NOTIFICATION_ID,notification("تم تنزيل "+(i+1)+" من "+total+" مقطع",pct));
                        lastNotice=System.currentTimeMillis();
                    }
                }
            }
            ContentValues complete=new ContentValues();complete.put(MediaStore.Downloads.IS_PENDING,0);
            getContentResolver().update(output,complete,null,null); output=null;
            status("اكتمل تنزيل HLS بنجاح — الملف داخل Downloads/EE Tube");
        } catch (InterruptedException ex) {
            status("تم إلغاء تنزيل HLS");
        } catch (Exception ex) {
            status("تعذر تنزيل HLS: "+ex.getMessage());
        } finally {
            if(output!=null) try{getContentResolver().delete(output,null,null);}catch(Exception ignored){}
            running.set(false);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }
    @Override public IBinder onBind(Intent intent) {return null;}
    @Override public void onDestroy() {canceled.set(true);executor.shutdownNow();super.onDestroy();}
}
