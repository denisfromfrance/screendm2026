package com.domain.screenrecorder.services;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.PixelCopy;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.domain.screenrecorder.R;
import com.domain.screenrecorder.states.Components;
import com.domain.screenrecorder.threads.ImagePullThread;
import com.google.android.gms.common.util.Hex;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.sql.SQLOutput;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
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

    Bitmap testBitmap;

    Handler handler;
    Runnable captureRunnable;

    ImagePullThread imagePullThread;

    Socket socket;
    OutputStream outputStream;
    private BlockingQueue<Bitmap> imageQueue;

    private TextRecognizer textRecognition;

    private boolean threadStarted = false;

    public Bitmap zoomFromTopCenterFixedSize(Bitmap original, float scale) {

        int width = original.getWidth();
        int height = original.getHeight();

        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale, width / 2f, 0f);

        // Scaled bitmap (larger)
        Bitmap scaled = Bitmap.createBitmap(original, 0, 0, width, height, matrix, true);

        // Crop center top area of scaled bitmap to original size
        int newW = scaled.getWidth();
        int newH = scaled.getHeight();

        int x = (newW - width) / 2;
        int y = 0;   // keep top fixed

        Bitmap finalBmp = Bitmap.createBitmap(scaled, x, y, width, height);

        return finalBmp;
    }

    private Bitmap scaleToFitWidth(int width, Bitmap image){
        float scaleRatio = (float)image.getHeight() / (float)image.getWidth();
        int newHeight = (int)(width * scaleRatio);
        return scaleSmooth(image, width, newHeight);
    }

    private Bitmap collapseBlankLines(Bitmap originalImage, int targetWidth, int targetHeight){
        Bitmap collapsedImage = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        int currentHeight = originalImage.getHeight();
        boolean shouldProccess = currentHeight > targetHeight;
        int newImagePixelPosY = 0;

        int mainColorToCheck = -1;

        int centerX = originalImage.getWidth() / 2;
        int inspectLimitRange = 50;
        int startX = centerX - inspectLimitRange;
        int endX = centerX + inspectLimitRange;


        if (shouldProccess){
            int[] pixels = new int[targetWidth];
            for (int y = 0; y < originalImage.getHeight(); y++){
                boolean foundBlack = false;
                boolean foundWhite = false;

                for (int x = 0; x < originalImage.getWidth(); x++){
                    int pixel = originalImage.getPixel(x, y);

                    if (pixel == 0xFFFFFFFF){
                        foundWhite = true;
                    }else if(pixel == 0xFF000000){
                        foundBlack = true;
                    }

                    if (foundWhite && foundBlack) {
                        break;
                    }
                }

                if (newImagePixelPosY < targetHeight && (foundWhite && foundBlack)){
                    originalImage.getPixels(pixels, 0, targetWidth, 0, y, targetWidth, 1);
                    collapsedImage.setPixels(pixels, 0, targetWidth, 0, newImagePixelPosY, targetWidth, 1);
                    System.out.println(Integer.toHexString(mainColorToCheck));
                    if (mainColorToCheck == -1){
                        mainColorToCheck = pixels[0];
                    }else{
                        for (int i = 0; i < centerX - inspectLimitRange; i++){
                            int leftPixel = pixels[i];
                            int rightPixel = pixels[(centerX + inspectLimitRange) + i];

                            if (leftPixel != mainColorToCheck) {
                                if (i < startX) {
                                    startX = i;
                                }
                            }

                            if (rightPixel != mainColorToCheck){
                                if (endX < (centerX + inspectLimitRange) + i){
                                    endX = (centerX + inspectLimitRange) + i;
                                }
                            }
                        }
                    }

                    if (mainColorToCheck == 0xFF000000){
                        System.out.println("Main color is black");
                    }else{
                        System.out.println("Main color is white");
                    }

                    newImagePixelPosY++;
                }
            }
        }

        int posY = (targetHeight / 2) - (newImagePixelPosY / 2);
        int contentWidth = endX - startX;
        int posX = (contentWidth / 2) - (targetWidth / 2);
        Bitmap croppedArea = Bitmap.createBitmap(collapsedImage, startX, 0, endX - startX, newImagePixelPosY);
        croppedArea = scaleToFitWidth(targetWidth, croppedArea);

        System.out.println("New Y position of the image to place in the center: " + posY);
        System.out.println("Height of the cropped image portion: " + croppedArea.getHeight());

        Canvas originalCanvas = new Canvas(collapsedImage);
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        originalCanvas.drawRect(new Rect(0, 0, targetWidth, targetHeight), paint);
        originalCanvas.drawBitmap(croppedArea, 0, (int)(targetHeight / 2) - ((int)(croppedArea.getHeight() / 2)), null);
        paint.setColor(Color.GREEN);
//        originalCanvas.drawLine(startX, 0, startX, collapsedImage.getHeight(), paint);
//        originalCanvas.drawLine(endX, 0, endX, collapsedImage.getHeight(), paint);
        return collapsedImage;
    }
    
    private Bitmap scaleSmooth(Bitmap original, int newWidth, int newHeight){
        Bitmap scaled = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(scaled);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setFilterBitmap(true);
        paint.setDither(true);
        paint.setSubpixelText(true);

        Rect src = new Rect(0, 0, original.getWidth(), original.getHeight());
        Rect dst = new Rect(0, 0, newWidth, newHeight);

        canvas.drawBitmap(original, src, dst, paint);
        return scaled;
    }

    private Bitmap getBlackAndWhiteImage(Bitmap original){
        int width = original.getWidth();
        int height = original.getHeight();

        Bitmap newBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {

                int color = original.getPixel(x, y);

                int r = (color >> 16) & 0xFF;
                int g = (color >> 8) & 0xFF;
                int b = (color) & 0xFF;

                // Luminance formula
                int gray = (int) (0.299*r + 0.587*g + 0.114*b);

                int grayPixel = 0xFF000000 | (gray << 16) | (gray << 8) | (gray);

                // If you want pure black/white (threshold)
//                int bw = (gray < 128) ? 0xFF000000 : 0xFFFFFFFF;
                int bw = (gray < 128) ? 0xFF000000 : grayPixel;

                newBitmap.setPixel(x, y, bw);
            }
        }
        return newBitmap;
    }

    private Bitmap processImageAndSave(Bitmap original, int targetWidth, int targetHeight){
        Mat src = new Mat();
        Utils.bitmapToMat(original, src);

//        int topCropOffset = 50;
//        int bottomCropOffset = 50;
//        org.opencv.core.Rect cropRect = new org.opencv.core.Rect(0, topCropOffset, src.cols(), src.rows() - topCropOffset - bottomCropOffset);
//        src = new Mat(src, cropRect);

        Mat gray = new Mat();
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);

        Mat bw = new Mat();
        Imgproc.threshold(gray, bw, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);

//        double meanVal = Core.mean(bw).val[0];
//        if (meanVal > 127){
//            Core.bitwise_not(bw, bw);
//        }
//
//        Mat points = new Mat();
//        Core.findNonZero(bw, points);
//        org.opencv.core.Rect boundingBox = Imgproc.boundingRect(points);
//
//        Mat cropped = new Mat(bw, boundingBox);

        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(50, 50));
        Mat dilated = new Mat();
        Imgproc.dilate(bw, dilated, kernel);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        System.out.println("Contours Count: " + contours.size());

        if (contours.size() == 3){
            contours.remove(2);
            contours.remove(0);
        }

        Mat output = Mat.zeros(bw.size(), CvType.CV_8UC3);
        Imgproc.drawContours(output, contours, -1, new Scalar(0, 255, 0), 2);


        org.opencv.core.Rect croppedText = Imgproc.boundingRect(contours.get(0));
        Mat cropped = new Mat(bw, croppedText);

        Mat resized = new Mat();
        Size size = new Size(targetWidth, targetHeight);
        Imgproc.resize(cropped, resized, size);

        Bitmap bitmap = Bitmap.createBitmap(resized.width(), resized.height(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(resized, bitmap);
//        saveImageToPublicDirectory(getApplicationContext(), bitmap, String.format("Image processed using OpenCV%s.jpg", String.valueOf(new Date().getTime())));
        return bitmap;
    }

    private Bitmap prepareImageForDisplay(Bitmap original, int targetWidth, int targetHeight) {
        // 2. Resize to match your display (e.g., 96x64 or 50x50)
        int originalWidth = original.getWidth();
        int originalHeight = original.getHeight();

        //        float scaleRatio = originalHeight / originalWidth;
//
//        int newWidth = targetWidth;
//        int newHeight = (int)(targetWidth * scaleRatio);
//
//        System.out.println("Height when fit to display width: " + newHeight);
////        System.out.println("##################################");
////        Bitmap blackAndWhiteHDImage = getBlackAndWhiteImage(original);
////        blackAndWhiteHDImage = collapseBlankLines(blackAndWhiteHDImage, blackAndWhiteHDImage.getWidth(), blackAndWhiteHDImage.getHeight());
////        saveImageToPublicDirectory(getApplicationContext(), blackAndWhiteHDImage, String.format("New Output Image From the App-%s.jpg", String.valueOf(new Date().getTime())));
////        System.out.println("##################################");
//
////        Bitmap resized = Bitmap.createScaledBitmap(original, newWidth, newHeight, true);
//        Bitmap resized = scaleSmooth(original, newWidth, newHeight);
//
//        int startY = 100;
//        //if (extraHeight > 0){
//            //startY = (int)(extraHeight / 2);
//            //newHeight = targetHeight;
//        //}
//
//        System.out.println("Height after removing a portion from the top: " + (newHeight - startY));
//        //Bitmap resized = Bitmap.createScaledBitmap(original, newWidth, newHeight, true);
//        resized = Bitmap.createBitmap(resized, 0, startY, newWidth, newHeight - startY);
//        System.out.println("Height for confirmation: " + resized.getHeight());
//        //resized = zoomFromTopCenterFixedSize(resized, 1.5f);
//
//        // 3. Convert to Black & White
//        newHeight -= 50;
//        Bitmap bwBitmap = Bitmap.createBitmap(newWidth, newHeight - startY, Bitmap.Config.ARGB_8888);
//
//        for (int y = 0; y < newHeight - startY; y++) {
//            for (int x = 0; x < newWidth; x++) {
//
//                int color = resized.getPixel(x, y);
//
//                int r = (color >> 16) & 0xFF;
//                int g = (color >> 8) & 0xFF;
//                int b = (color) & 0xFF;
//
//                // Luminance formula
//                int gray = (int) (0.299*r + 0.587*g + 0.114*b);
//
//                int grayPixel = 0xFF000000 | (gray << 16) | (gray << 8) | (gray);
//
//                // If you want pure black/white (threshold)
//                int bw = (gray < 128) ? 0xFF000000 : 0xFFFFFFFF;
////                int bw = (gray < 128) ? 0xFF000000 : grayPixel;
//                bwBitmap.setPixel(x, y, bw);
//            }
//        }
//
//        bwBitmap = collapseBlankLines(bwBitmap, targetWidth, targetHeight);
//
////        saveImageToPublicDirectory(getApplicationContext(), bwBitmap, String.format("Output Image From the App-%s.jpg", String.valueOf(new Date().getTime())));
        return processImageAndSave(original, targetWidth, targetHeight);
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
        if (outputStream != null){
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
                try{
                    outputStream.close();
                }catch(IOException e){
                    e.printStackTrace();
                }
            }
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

    public static Uri saveImageToPublicDirectory(Context context, Bitmap bitmap, String fileName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MyAppImages"); // Your folder name

        Uri uri = null;
        OutputStream outputStream = null;

        try {
            // Insert into MediaStore
            uri = context.getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            if (uri == null) {
                return null;
            }

            // Open output stream
            outputStream = context.getContentResolver().openOutputStream(uri);

            // Write the bitmap into the stream
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);

            return uri; // returns the image URI (can be shared)

        } catch (Exception e) {
            e.printStackTrace();
            return null;

        } finally {
            if (outputStream != null) {
                try { outputStream.close(); } catch (Exception ignored) {}
            }
        }
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
                                        //prepareImageAndSend(originalBitmap, 240, 320);
                                        //prepareImageAndSend(testBitmap, 240, 320);
                                        prepareImageAndSend(testBitmap, 240, 320);

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
//                    socket.connect(new InetSocketAddress("192.168.4.1", 5000), 5000);
                    socket.connect(new InetSocketAddress("192.168.43.133", 5000), 5000);
                    outputStream = socket.getOutputStream();

                    Components.setConnectionStatus(1);

//                    try {
//                        outputStream.write("CONNECTED!\n".getBytes(StandardCharsets.UTF_8));
//                        outputStream.flush();
//                    }catch(IOException exception){
//                        exception.printStackTrace();
//                    }
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
        InputStream is = getApplicationContext().getResources().openRawResource(R.raw.testdrawing4lines);
        testBitmap = BitmapFactory.decodeStream(is);
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

        if (outputStream != null){
            try{
                outputStream.close();
            }catch(IOException exception){
                exception.printStackTrace();
            }
        }

        //imagePullThread.terminateConnection();

        stopForeground(true);
    }
}