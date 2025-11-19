package com.domain.screenrecorder.services;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.domain.screenrecorder.R;
import com.domain.screenrecorder.threads.ImagePullThread;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.IOException;

public class ScreenRecorderService extends Service {
    private MediaProjection mediaProjection;
    private MediaProjectionManager projectionManager;
    private MediaRecorder mediaRecorder;
    private VirtualDisplay virtualDisplay;

    private static final int WIDTH = 1080;
    private static final int HEIGHT = 1920;
    private static final int DPI = 320;

    private SurfaceTexture captureTexture;
    private Surface captureSurface;
    private VirtualDisplay captureVirtualDisplay;

    Handler handler;
    Runnable captureRunnable;

    ImagePullThread imagePullThread;

    private TextRecognizer textRecognition;

    private boolean threadStarted = false;

    public ScreenRecorderService() {
        textRecognition = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        imagePullThread = new ImagePullThread();
    }

    private void setupMediaRecorder(){
        System.out.println("Setting up media recorder...");
        try{
            File dir = getExternalFilesDir(null);
            String filePath = new File(dir, "/recordedVideo.mp4").getAbsolutePath();
            System.out.println(filePath);
            mediaRecorder = new MediaRecorder();
//            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(filePath);
            mediaRecorder.setVideoSize(WIDTH, HEIGHT);
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
//            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setVideoEncodingBitRate(512 * 1000);
            mediaRecorder.setVideoFrameRate(30);
            mediaRecorder.prepare();
        }catch(IOException e){
            e.printStackTrace();
        }
    }

    private void createVirtualDisplay(){
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "Screen Record",
                WIDTH,
                HEIGHT,
                DPI,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.getSurface(),
                null, null
        );

        captureTexture = new SurfaceTexture(10);
        captureTexture.setDefaultBufferSize(WIDTH, HEIGHT);
        captureSurface = new Surface(captureTexture);

        captureVirtualDisplay = mediaProjection.createVirtualDisplay(
                "Capture VDisplay",
                WIDTH,
                HEIGHT,
                DPI,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                captureSurface,
                null, null
        );
    }

    private void captureSurfacePeriodically(Surface surface){
        handler = new Handler(Looper.getMainLooper());
        captureRunnable = new Runnable() {
            @Override
            public void run() {
                final Bitmap[] bitmap = {Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)};
                PixelCopy.request(surface, bitmap[0], copyResult -> {
                    if (copyResult == PixelCopy.SUCCESS){
                        System.out.println("Bitmap loaded successfully!");
                        try{
                            Bitmap originalBitmap = bitmap[0];
                            imagePullThread.addImageToQueue(originalBitmap);
                        }catch (Exception exception){
                            exception.printStackTrace();
                        }



//                        bitmap[0] = Bitmap.createBitmap(originalBitmap, 0, 200, WIDTH, HEIGHT - 300);
//                        InputImage inputImage = InputImage.fromBitmap(bitmap[0], 0);
//                        textRecognition.process(inputImage)
//                                .addOnSuccessListener(new OnSuccessListener<Text>() {
//                                    @Override
//                                    public void onSuccess(Text text) {
//                                        imagePullThread.setTextData(text.getText());
//                                        System.out.println("Recognized Text: " + text.getText());
//                                        for (Text.TextBlock textBlock : text.getTextBlocks()){
//                                            System.out.println("TextBlock: " + textBlock.getText());
//                                            for (Text.Line textLine : textBlock.getLines()){
//                                                System.out.println("\t\t" + textLine.getText());
//                                            }
//                                        }
//                                    }
//                                })
//                                .addOnFailureListener(new OnFailureListener() {
//                                    @Override
//                                    public void onFailure(@NonNull Exception e) {
//                                        e.printStackTrace();
//                                    }
//                                });
                    }
                }, handler);

                handler.postDelayed(this, 2500);
            }
        };

        handler.post(captureRunnable);
    }

    private void createNotification(){
        String CHANNEL_ID = "Screen Record Channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            NotificationChannel notificationChannel = new NotificationChannel(CHANNEL_ID, "Screen Record", NotificationManager.IMPORTANCE_LOW);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(notificationChannel);

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("Recording in progress")
                    .setContentText("Your screen is being recorded")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .build();

            startForeground(1, notification);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotification();
        int resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        Intent data = intent.getParcelableExtra("data");
        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        mediaProjection = projectionManager.getMediaProjection(resultCode, data);
        System.out.println("Setting up everything...");
        if (!threadStarted){
            imagePullThread.start();
            threadStarted = true;
        }
        setupMediaRecorder();
        createVirtualDisplay();
        mediaRecorder.start();
        captureSurfacePeriodically(captureSurface);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (virtualDisplay != null){
            virtualDisplay.release();
        }

        if (captureVirtualDisplay != null){
            captureVirtualDisplay.release();
        }

        if (handler != null){
            handler.removeCallbacks(captureRunnable);
        }

        if (mediaRecorder != null){
            mediaRecorder.stop();
            mediaRecorder.reset();
        }

        if (mediaProjection != null){
            mediaProjection.stop();
        }

        imagePullThread.terminateConnection();

        stopForeground(true);
    }
}