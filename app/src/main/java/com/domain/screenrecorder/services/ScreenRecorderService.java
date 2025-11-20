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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

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

    Socket socket;
    OutputStream outputStream;
    private BlockingQueue<Bitmap> imageQueue;

    private TextRecognizer textRecognition;

    private boolean threadStarted = false;

    private Bitmap prepareImageForDisplay(Bitmap original, int targetWidth, int targetHeight) {

        // 2. Resize to match your display (e.g., 96x64 or 50x50)
        Bitmap resized = Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true);

        // 3. Convert to Black & White
        Bitmap bwBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);

        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {

                int color = resized.getPixel(x, y);

                int r = (color >> 16) & 0xFF;
                int g = (color >> 8) & 0xFF;
                int b = (color) & 0xFF;

                // Luminance formula
                int gray = (int) (0.299*r + 0.587*g + 0.114*b);

                // If you want pure black/white (threshold)
                int bw = (gray < 128) ? 0xFFFFFFFF : 0xFF000000;

                bwBitmap.setPixel(x, y, bw);
            }
        }

        return bwBitmap;
    }


    private byte[] bitmapTo1BitArray(Bitmap bmp) {
        int width = bmp.getWidth();
        int height = bmp.getHeight();

        byte[] bytes = new byte[width * height]; // 1 byte per pixel (0 or 1)

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {

                int pixel = bmp.getPixel(x, y);
                int r = (pixel >> 16) & 0xFF;

                // Assume black = 1, white = 0
                bytes[y * width + x] = (byte) (r < 128 ? 1 : 0);
            }
        }

        return bytes;
    }

    private void sendBytes(byte[] bytes){
        int chunkSize = 2048;
        int totalChunks = (int)Math.ceil(bytes.length / (double)chunkSize);
        String header = "IMG " + totalChunks + " " + bytes.length + '\n';
        System.out.println("Sending header and data!");
        System.out.println("Total Chunks sending " + totalChunks + " of size " + chunkSize);
        try {
            outputStream.write(header.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();

            for (int i = 0; i < totalChunks; i++){
                int start = i * chunkSize;
                int length = Math.min(chunkSize, bytes.length - start);
                outputStream.write(bytes, start, length);
                System.out.println(Arrays.toString(Arrays.copyOfRange(bytes, 0, 50)));
                outputStream.flush();
            }
        }catch(IOException exception){
            exception.printStackTrace();
        }
    }

    private void prepareImageAndSend(Bitmap bitmap, int width, int height){
        Bitmap image = prepareImageForDisplay(bitmap, width, height);
        System.out.println("Image received.");
        sendBytes(bitmapTo1BitArray(image));
    }

    public ScreenRecorderService() {
        textRecognition = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        imageQueue = new LinkedBlockingDeque<>();
//        imagePullThread = new ImagePullThread();
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
                            if (threadStarted) {
                                System.out.println("Sending image...");
                                Thread thread = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        prepareImageAndSend(originalBitmap, 128, 160);
                                    }
                                });
                                thread.start();
                                thread.join();
                            }
//                            imagePullThread.addImageToQueue(originalBitmap);
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

    public void connectToServer(){
        Thread networkThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress("192.168.4.1", 5000), 5000);
                    //socket.connect(new InetSocketAddress("192.168.43.133", 5000), 5000);
                    outputStream = socket.getOutputStream();

                    try {
                        outputStream.write("CONNECTED!\n".getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();
                    }catch(IOException exception){
                        exception.printStackTrace();
                    }

                } catch (IOException exception) {
                    exception.printStackTrace();
                }
            }
        });
        networkThread.start();
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
            connectToServer();
//            imagePullThread.start();
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