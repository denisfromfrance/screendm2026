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
import com.domain.screenrecorder.utils.DigitClassifier;
//import com.google.android.gms.common.util.Hex;
//import com.google.android.gms.tasks.OnFailureListener;
//import com.google.android.gms.tasks.OnSuccessListener;
//import com.google.mlkit.vision.common.InputImage;
//import com.google.mlkit.vision.text.Text;
//import com.google.mlkit.vision.text.TextRecognition;
//import com.google.mlkit.vision.text.TextRecognizer;
//import com.google.mlkit.vision.text.TextRecognizerOptionsInterface;
//import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.ml.KNearest;

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

    private DigitClassifier digitClassifier;

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

//    InputImage image;

    Socket socket;
    OutputStream outputStream;
    private BlockingQueue<Bitmap> imageQueue;

//    private TextRecognizer textRecognition;

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

    private Mat convertToBlackAndWhite(Mat src){
        Mat gray = new Mat();
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_BGR2GRAY);

        Mat bw = new Mat();
        Imgproc.threshold(gray, bw, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);

        org.opencv.core.Rect center = new org.opencv.core.Rect(
                src.width() / 4,
                src.height() / 4,
                src.width() / 2,
                src.height() / 2);

        Mat centerMat = new Mat(bw, center);

        double meanVal = Core.mean(centerMat).val[0];
        if (meanVal > 127){
            Core.bitwise_not(bw, bw);
        }
        return bw;
    }

    private List<MatOfPoint> getContours(Mat blackAndWhiteMat, boolean isChar){
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(isChar ? 10 : 50, 5));
        Mat dilated = new Mat();
        Imgproc.dilate(blackAndWhiteMat, dilated, kernel);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        Bitmap output = Bitmap.createBitmap(dilated.cols(), dilated.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(dilated, output);
//        saveImageToPublicDirectory(getApplicationContext(), output, String.format("Image processed using OpenCV%s.jpg", String.valueOf(new Date().getTime())));
        return contours;
    }

    public List<org.opencv.core.Rect> getBoundingBoxes(List<MatOfPoint> contours){
        List<org.opencv.core.Rect> boundingBoxes = new ArrayList<>();
        for (MatOfPoint contour : contours) {
            boundingBoxes.add(Imgproc.boundingRect(contour));
        }
        return boundingBoxes;
    }

    private void detectChar(Bitmap input){
//        image = InputImage.fromBitmap(input, 0);
//        textRecognition.process(image)
//                .addOnSuccessListener(visionText -> {
//                    String resultText = visionText.getText();
//                    System.out.println("MLKIT: Detected text: " + resultText);
//
////                    for (Text.TextBlock block : visionText.getTextBlocks()) {
////                        for (Text.Line line : block.getLines()) {
////                            String lineText = line.getText();
////                            System.out.println("MLKIT: Line: " + lineText);
////                        }
////                    }
//                })
//                .addOnFailureListener(e -> {
//                    e.printStackTrace();
//                });
    }

    private void saveImage(Mat mat){
        Bitmap croppedPortion = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(mat, croppedPortion);
        saveImageToPublicDirectory(getApplicationContext(), croppedPortion, String.format(
                "Cropped Digit-%s.jpg",
                String.valueOf(new Date().getTime())));
    }

    private int resizeToFixedSize(int width, int height, Mat inputMat){
        int imageWidth = inputMat.cols();
        int imageHeight = inputMat.rows();

//        System.out.println(String.format("Size before resizing: %dx%d", imageWidth, imageHeight));

        double aspectRatio = 0;

        if (imageWidth > width) {
            aspectRatio = width / (double) imageWidth;
            imageWidth = width;
            imageHeight = (int) (imageWidth * aspectRatio);
        }

        if (imageHeight > height) {
            aspectRatio = height / (double) imageHeight;
            imageWidth = (int) (imageWidth * aspectRatio);
        }

        Mat resized = new Mat((int) imageHeight, imageWidth, inputMat.type());
        Size size = new Size(imageWidth, imageHeight);
        Imgproc.resize(inputMat, resized, size, 0, 0, Imgproc.INTER_LANCZOS4);

//        System.out.println(String.format(
//                "Size after resizing: %dx%d = %dx%d",
//                imageWidth, imageHeight, resized.cols(), resized.rows()));

        Mat newImage = new Mat(new Size(width, height), inputMat.type());

        int x = 14 - (imageWidth / 2);
        int y = 14 - (imageHeight / 2);

//        System.out.println("Pos X: " + x);
//        System.out.println("Pos Y: " + y);

        org.opencv.core.Rect roi = new org.opencv.core.Rect(x, y, imageWidth, imageHeight);
        Mat destinationArea = newImage.submat(roi);
        resized.copyTo(destinationArea);

        Mat invertedImage = new Mat(new Size(imageWidth, imageHeight), newImage.type());
        Core.bitwise_not(newImage, invertedImage);
//        saveImage(invertedImage);

        int classifiedDigit = digitClassifier.classify(invertedImage);
        System.out.println("Classified Digit: " + classifiedDigit);
        return classifiedDigit;
    }

    private String extractChar(Mat input){
        List<MatOfPoint> contours = getContours(input, true);
        List<org.opencv.core.Rect> boundingBoxes = getBoundingBoxes(contours);

        Mat mat;
        Bitmap croppedPortion;
        Mat resized;
        Size size;
        int recommendedSize = 40;
        int height = 0;
        int width = 0;
        double aspectRatio = 0;

        StringBuilder stringBuilder = new StringBuilder();

        for (org.opencv.core.Rect boundingBox : boundingBoxes) {
            mat = input.submat(boundingBox);
            width = mat.cols();
            height = mat.rows();

            if (width <= recommendedSize) {
                aspectRatio = recommendedSize / (double) width;
                width = recommendedSize;
                height = (int) (height * aspectRatio);
            }

            if (height <= recommendedSize) {
                aspectRatio = recommendedSize / (double) height;
                width = (int) (width * aspectRatio);
            }

            croppedPortion = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            resized = new Mat((int) height, width, CvType.CV_8UC3);
            size = new Size(width, height);
            Imgproc.resize(mat, resized, size, 0, 0, Imgproc.INTER_LANCZOS4);
            Utils.matToBitmap(resized, croppedPortion);

            int classifiedNumber = resizeToFixedSize(28, 28, resized);
            stringBuilder.append(classifiedNumber);
        }
        return stringBuilder.toString();
    }

    private void extractLines(Bitmap bitmap){
        Mat src = new Mat();
        Utils.bitmapToMat(bitmap, src);

        Mat bw = convertToBlackAndWhite(src);
        List<MatOfPoint> contours = getContours(bw, false);
        List<org.opencv.core.Rect> boundingBoxes = getBoundingBoxes(contours);

        Mat mat = new Mat();
//        InputImage image;
        Bitmap croppedPortion;
        Mat resized;
        Size size;
        int targetHeight = 40;
        int newWidth = 0;
        double aspectRatio = 0;

        ArrayList<Integer> numberList = new ArrayList<>();
        int total = 0;
        for (org.opencv.core.Rect boundingBox : boundingBoxes){
            mat = bw.submat(boundingBox);
            String number = extractChar(mat);
            int n = Integer.parseInt(number);
            numberList.add(n);
            total += n;
        }

        System.out.println("Numbers: " + Arrays.toString(numberList.toArray()));
        System.out.println("Total: " + total);
    }

    private Bitmap processImageAndSave(Bitmap original, int targetWidth, int targetHeight){
        Mat src = new Mat();
        Utils.bitmapToMat(original, src);

//        int topCropOffset = 50;
//        int bottomCropOffset = 50;
//        org.opencv.core.Rect cropRect = new org.opencv.core.Rect(0, topCropOffset, src.cols(), src.rows() - topCropOffset - bottomCropOffset);
//        src = new Mat(src, cropRect);

        Mat bw = convertToBlackAndWhite(src);


//        Mat points = new Mat();
//        Core.findNonZero(bw, points);
//        org.opencv.core.Rect boundingBox = Imgproc.boundingRect(points);
//
//        Mat cropped = new Mat(bw, boundingBox);

        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(120, 85));
        Mat dilated = new Mat();
        Imgproc.dilate(bw, dilated, kernel);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        System.out.println("Contours Count: " + contours.size());

        MatOfPoint contour = contours.get(0);

        int contourIndex = 0;
        int centerY = dilated.height() / 2;
        int distance = 0;

        if (contours.size() == 3){
            contour = contours.get(1);
//            for (MatOfPoint point : contours) {
//                org.opencv.core.Rect boundingBox = Imgproc.boundingRect(point);
//                int distanceFromCenter = boundingBox.y;
//                if (boundingBox.y + boundingBox.height > centerY) {
//                    distanceFromCenter = dilated.height() - boundingBox.y + boundingBox.height;
//                }
//                if (distanceFromCenter > distance) {
//                    distance = distanceFromCenter;
//                    contour = point;
//                }
//            }
        }

        Mat output = Mat.zeros(bw.size(), CvType.CV_8UC3);
        Imgproc.drawContours(output, contours, -1, new Scalar(0, 255, 0), 2);

        org.opencv.core.Rect croppedText = Imgproc.boundingRect(contour);
        Mat cropped = new Mat(bw, croppedText);

        double aspectRatio = targetWidth / (double)cropped.cols();
        double newHeight = cropped.rows() * aspectRatio;

//        System.out.println("Aspect ratio: " + aspectRatio);
//        System.out.println("New Width: " + targetWidth);
//        System.out.println("New Height: " + newHeight);
//        System.out.println("Cropped Cols: " + cropped.cols());
//        System.out.println("Cropped Rows: " + cropped.rows());

        int newPosY = (int)((targetHeight / 2) - (newHeight / 2));

        Mat resized = new Mat((int)newHeight, targetWidth, CvType.CV_8UC3);
        Size size = new Size(targetWidth, newHeight);
        Imgproc.resize(cropped, resized, size, 0, 0, Imgproc.INTER_LANCZOS4);

        Mat canvas = Mat.zeros(targetHeight, targetWidth, resized.type());
        org.opencv.core.Rect roi = new org.opencv.core.Rect(0, newPosY, resized.cols(), resized.rows());
        Mat targetArea = canvas.submat(roi);
        resized.copyTo(targetArea);

        Bitmap bitmap = Bitmap.createBitmap(canvas.cols(), canvas.rows(), Bitmap.Config.ARGB_8888);
        Utils.matToBitmap(canvas, bitmap);

        extractLines(bitmap);
        return bitmap;
    }

    private Bitmap prepareImageForDisplay(Bitmap original, int targetWidth, int targetHeight) {
        // 2. Resize to match your display (e.g., 96x64 or 50x50)
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
//        textRecognition = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
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
                                        prepareImageAndSend(originalBitmap, 240, 320);
                                        //prepareImageAndSend(testBitmap, 240, 320);
//                                        prepareImageAndSend(testBitmap, 240, 320);
                                        //prepareImageAndSend(testBitmap, 240, 320);

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
        try {
            digitClassifier = new DigitClassifier(getApplicationContext());
        } catch (IOException e) {
            e.printStackTrace();
        }
        InputStream is = getApplicationContext().getResources().openRawResource(R.raw.examplenumbers);
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