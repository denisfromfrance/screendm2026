package com.domain.screenrecorder.services;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import com.domain.screenrecorder.R;
import com.domain.screenrecorder.states.Components;
import com.domain.screenrecorder.states.Constants;
import com.domain.screenrecorder.states.TransformedImage;
import com.domain.screenrecorder.threads.ImagePullThread;
import com.domain.screenrecorder.utils.DigitClassifier;
//import com.domain.screenrecorder.utils.DigitClassifier;
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
import org.opencv.core.Point;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class ScreenRecorderService extends Service {
    private MediaProjection mediaProjection;
    private MediaProjectionManager projectionManager;
    private MediaRecorder mediaRecorder;
    //private VirtualDisplay virtualDisplay;
    private DigitClassifier digitClassifier;

    private static int WIDTH = 1080;
    private static int HEIGHT = 1920;
    private static int DPI = 320;

    private long total = 0;

    private SurfaceTexture captureTexture;
    private Surface captureSurface;
    private VirtualDisplay captureVirtualDisplay;
    private ImageReader imageReader;

    Bitmap testBitmap;

    Handler handler;
    Runnable captureRunnable;

    ImagePullThread imagePullThread;

//    InputImage image;

    Socket socket;
    OutputStream outputStream;
    private BlockingQueue<Bitmap> imageQueue;
    int consecutiveFailures = 0;
//    private TextRecognizer textRecognition;

    int resultCode;
    Intent data;

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

//        saveImage(gray);

//        Imgproc.medianBlur(gray, gray, 3);

        Mat bw = Mat.zeros(gray.rows(), gray.cols(), CvType.CV_8UC1);
        Imgproc.threshold(gray, bw, 250, 255, Imgproc.THRESH_BINARY);
//        saveImage(bw);

        if (Components.getNoteApplication() == Constants.IARVEL) {
            org.opencv.core.Rect center = new org.opencv.core.Rect(
                    0,
                    0,
                    bw.cols(),
                    bw.rows());

            Mat centerMat = new Mat(bw, center);

            double meanVal = Core.mean(centerMat).val[0];
            if (meanVal > 127) {
                Core.bitwise_not(bw, bw);
            }

//            saveImage(bw);
        }

        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(10, 10));
        Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_CLOSE, kernel);
        Imgproc.morphologyEx(bw, bw, Imgproc.MORPH_OPEN, kernel);

        kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(50, 70));
        Imgproc.dilate(bw, bw, kernel);

//        saveImage(bw);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(bw, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        if (contours.size() > 1) {
            System.out.println("####### Found more than 1 contour");
            contours.sort(new Comparator<MatOfPoint>() {
                @Override
                public int compare(MatOfPoint o1, MatOfPoint o2) {
                    org.opencv.core.Rect rect1 = Imgproc.boundingRect(o1);
                    org.opencv.core.Rect rect2 = Imgproc.boundingRect(o2);
                    return Integer.compare(rect1.y, rect2.y);
                }
            });

            org.opencv.core.Rect canvasArea = Imgproc.boundingRect(contours.get(1));
            System.out.println("Canvas Area X: " + canvasArea.x);
            System.out.println("Canvas Area Y: " + canvasArea.y);
            System.out.println("Canvas Area width: " + canvasArea.width);
            System.out.println("Canvas Area height: " + canvasArea.height);
            canvasArea.y += 35;
            if (canvasArea.height > 60) {
                canvasArea.height -= 70;
            }

            System.out.println("Updated canvas Area X: " + canvasArea.x);
            System.out.println("Updated canvas Area Y: " + canvasArea.y);
            System.out.println("Updated canvas Area width: " + canvasArea.width);
            System.out.println("Updated canvas Area height: " + canvasArea.height);
            bw = new Mat(gray, canvasArea);

            System.out.println("Clearing contours...");
            contours.clear();
            Imgproc.findContours(bw, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
            double maxArea = 0;
            org.opencv.core.Rect whiteRect = null;
            for (MatOfPoint c: contours){
                double area = Imgproc.contourArea(c);
                if (area > maxArea){
                    maxArea = area;
                    whiteRect = Imgproc.boundingRect(c);
                }
            }

            if (whiteRect != null) {
                System.out.println("BW Width: " + bw.cols());
                System.out.println("BW Rows: " + bw.rows());

                bw = bw.submat(whiteRect);
                System.out.println("White Rect: " + whiteRect.x + " Y: " + whiteRect.y + " WIDTH: " + whiteRect.width + " Height: " + whiteRect.height);
            }
//            saveImage(bw);
//            Imgproc.cvtColor(bw, bw, Imgproc.COLOR_BGR2GRAY);
            Imgproc.threshold(bw, bw, 0, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
//            Mat testMat = Mat.zeros(bw.rows() + 100, bw.cols() + 100, bw.type());
//            Core.bitwise_not(testMat, testMat);
//            bw.copyTo(testMat.submat(new org.opencv.core.Rect(50, 50, bw.cols(), bw.rows())));
//            Core.bitwise_not(bw, bw);
//            saveImage(croppedArea);
//            saveImage(testMat);
        }else{
            Imgproc.threshold(gray, bw, 50, 255, Imgproc.THRESH_BINARY | Imgproc.THRESH_OTSU);
        }

        org.opencv.core.Rect center = new org.opencv.core.Rect(
                bw.cols() / 4,
                bw.rows() / 4,
                bw.cols() / 2,
                bw.rows() / 2);

        Mat centerMat = new Mat(bw, center);

        double meanVal = Core.mean(centerMat).val[0];
        if (meanVal > 127){
            Core.bitwise_not(bw, bw);
        }
//        saveImage(bw);
        return bw;
    }

    private List<MatOfPoint> getContours(Mat blackAndWhiteMat, boolean isChar){
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(isChar ? 8 : 50, 5));
        Mat dilated = Mat.zeros(blackAndWhiteMat.rows(), blackAndWhiteMat.cols(), blackAndWhiteMat.type());
        Imgproc.dilate(blackAndWhiteMat, dilated, kernel);

//        if(isChar) {
//            saveImage(blackAndWhiteMat);
//            saveImage(dilated);
//        }

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

//        Bitmap output = Bitmap.createBitmap(dilated.cols(), dilated.rows(), Bitmap.Config.ARGB_8888);
//        Utils.matToBitmap(dilated, output);
//        saveImage(dilated);
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

        Imgproc.threshold(newImage, newImage, 179.2D, 255.0D, Imgproc.THRESH_BINARY);

        Mat invertedImage = new Mat(new Size(imageWidth, imageHeight), newImage.type());
//        Core.bitwise_not(newImage, invertedImage);
//        saveImage(newImage);

        int classifiedDigit = digitClassifier.classify(newImage);
        System.out.println("Classified Digit: " + classifiedDigit);
        return classifiedDigit;
    }

    private String extractChar(Mat input, Mat original, org.opencv.core.Rect lineRect){
        Mat bw = input.clone();
//        saveImage(bw);
//        List<MatOfPoint> tempContours = getContours(input, true);
        List<MatOfPoint> contours = getContours(input, true);
        List<org.opencv.core.Rect> boundingBoxes = getBoundingBoxes(contours);

        Mat mat;
        Bitmap croppedPortion;
        Mat resized;
        Size size;
        int recommendedSize = 32;
        int height = 0;
        int width = 0;
        double aspectRatio = 0;
        Mat tempMat;

        boundingBoxes = boundingBoxes.stream().filter(rect -> rect.height > 5).collect(Collectors.toList());
        boundingBoxes = boundingBoxes.stream().sorted(new Comparator<org.opencv.core.Rect>() {
            @Override
            public int compare(org.opencv.core.Rect o1, org.opencv.core.Rect o2) {
                return Math.min(o1.x, o2.x);
            }
        }).collect(Collectors.toList());

        StringBuilder stringBuilder = new StringBuilder();
        System.out.println("Chars found: " + boundingBoxes.size());
        Map<Integer, String> numbersMap = new HashMap<>();
        for (org.opencv.core.Rect boundingBox : boundingBoxes) {
            if (boundingBox.height > 5) {
                mat = bw.submat(boundingBox);

                int originalX = lineRect.x + boundingBox.x;
                int originalY = lineRect.y + boundingBox.y;
                int originalWidth = boundingBox.width;
                int originalHeight = boundingBox.height;

                org.opencv.core.Rect rectFormCharsInOriginal = new org.opencv.core.Rect(originalX, originalY, originalWidth, originalHeight);
//                Imgproc.rectangle(original, rectFormCharsInOriginal, new Scalar(255), 2);
//                saveImage(original);

                width = mat.cols();
                height = mat.rows();

                aspectRatio = recommendedSize / (double) width;
                width = recommendedSize;
                height = (int) (height * aspectRatio);

                if (height > recommendedSize) {
                    aspectRatio = recommendedSize / (double) height;
                    height = recommendedSize;
                    width = (int) (width * aspectRatio);
                }

                tempMat = Mat.zeros(recommendedSize, recommendedSize, input.type());

                if (!mat.empty() && height > 0 && width > 0) {
                    resized = Mat.zeros(height, width, input.type());
                    size = new Size(width, height);
                    Imgproc.resize(mat, resized, size, 0, 0, Imgproc.INTER_LANCZOS4);

                    int posX = (recommendedSize / 2) - (width / 2);
                    int posY = (recommendedSize / 2) - (height / 2);

                    resized.copyTo(tempMat.submat(new org.opencv.core.Rect(0, 0, width, height)));
                }

//            Imgproc.resize(mat, resized, size, 0, 0, Imgproc.INTER_LANCZOS4);
                String type = digitClassifier.getType(tempMat);
//                System.out.println("Type: " + type);
                if (!type.equals("-")) {
                    int classifiedDigit = digitClassifier.classify(tempMat);
                    numbersMap.put(boundingBox.x, String.valueOf(classifiedDigit));
                    System.out.println("Classified Digit: " + classifiedDigit);
                    Imgproc.putText(tempMat, String.valueOf(classifiedDigit), new Point(10, 10), Imgproc.FONT_HERSHEY_PLAIN, 1.0D, new Scalar(255), 2);
                }

//                saveImage(tempMat);

//            int classifiedNumber = resizeToFixedSize(28, 28, resized);
            }
        }

        Map<Integer, String> sortedMap = new TreeMap<>(numbersMap);
        for (Map.Entry<Integer, String> entry : sortedMap.entrySet()){
            stringBuilder.append(entry.getValue());
        }

        saveImage(original);
        return stringBuilder.toString();
    }

    private Mat extractLines(Mat src){
//        Mat bw = convertToBlackAndWhite(src);
        Mat bw = src.clone();
//        saveImage(bw);
        List<MatOfPoint> contours = getContours(bw, false);
        List<org.opencv.core.Rect> boundingBoxes = getBoundingBoxes(contours);
        Collections.reverse(boundingBoxes);

        // remove last element when calculations needs to be done with detected numbers
        if (boundingBoxes.size() == 5) {
            boundingBoxes.remove(boundingBoxes.size() - 1);
        }
        System.out.println("Lines found: " + boundingBoxes.size());

//        saveImage(bw.submat(boundingBoxes.get(0)));


//        Mat mat = new Mat();
//        InputImage image;
        Bitmap croppedPortion;
        Mat resized;
        Size size;
        int targetHeight = 40;
        int newWidth = 0;
        double aspectRatio = 0;

        ArrayList<Integer> numberList = new ArrayList<>();
        long total = 0;
        for (org.opencv.core.Rect boundingBox : boundingBoxes){
//            if (boundingBox.width > 50) {
//                boundingBox.width -= 50;
//            }
//            boundingBox.x += 25;
            Mat mat = bw.submat(boundingBox);

//            Imgproc.rectangle(bw, boundingBox, new Scalar(255), 2);
//            saveImage(mat);

            String number = extractChar(mat, src, boundingBox);
            try {
                System.out.println("Extracted Number: " + number);
                if (!number.equals("")){
                    int n = Integer.parseInt(number);
                    switch (numberList.size()) {
                        case 0:
                            total = n;
                            break;
                        case 1:
                            total += n;
                            break;
                        case 2:
                            total -= n;
                            break;
                        case 3:
                            total *= n;
                            break;
                        default:
                            break;
                    }
                    numberList.add(n);
                }
            }catch(Exception exception){
                exception.printStackTrace();
            }

        }
//        saveImage(bw);

        Mat mat = Mat.zeros(32, src.cols(), CvType.CV_8UC1);
        int font = Imgproc.FONT_HERSHEY_SIMPLEX;
        double fontScale = 0.7;
        int thickness = 2;
        Size textSize = Imgproc.getTextSize(String.valueOf(total), font, fontScale, thickness, null);
        int x = (int)((src.cols() - textSize.width) / 2);
        if (x < 0){
            x = 0;
        }
        int y = 30;
        Imgproc.putText(mat, String.valueOf(total), new Point(x, y), font, fontScale, new Scalar(255), thickness);

        System.out.println("Numbers: " + Arrays.toString(numberList.toArray()));
        System.out.println("Total: " + total);
        this.total = total;
        return mat;
    }



    private Mat resizeMat(Mat inputMat, int newWidth, int newHeight){
        Mat resized = new Mat((int)newHeight, (int)newWidth, CvType.CV_8UC3);
        Size size = new Size(newWidth, newHeight);
        Imgproc.resize(inputMat, resized, size, 0, 0, Imgproc.INTER_LANCZOS4);
        return resized;
    }

    private Mat cleanMat(Mat src){
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size((int)(src.cols() / 4), 55));
        Mat dilated = new Mat();
        Imgproc.dilate(src, dilated, kernel);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        contours.sort(new Comparator<MatOfPoint>() {
            @Override
            public int compare(MatOfPoint o1, MatOfPoint o2) {
                org.opencv.core.Rect rect1 = Imgproc.boundingRect(o1);
                org.opencv.core.Rect rect2 = Imgproc.boundingRect(o2);
                return Integer.compare(rect1.y, rect2.y);
            }
        });
        int x = 0;
        int y = 0;
        int width = src.cols();
        int height = src.height();

        if (contours.size() > 2){
            org.opencv.core.Rect rect1 = Imgproc.boundingRect(contours.get(0));
            org.opencv.core.Rect rect2 = Imgproc.boundingRect(contours.get(contours.size() - 1));

//            rect2.x = 0;
//            rect2.width = src.cols();


            src.submat(rect1).setTo(new Scalar(0));
//            src.submat(rect2).setTo(new Scalar(0));

            y = rect1.y + rect1.height + 20;
            height -= rect2.height - 40;
        }
        return src;
    }

    public Mat smoothImage(Mat inputImage){
        Mat thick = new Mat();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(1, 1));
        Imgproc.dilate(inputImage, thick, kernel);

        int low = 20;
        int high = 200;
//        Mat mask = new Mat();
//        Core.inRange(thick, new Scalar(low), new Scalar(high), mask);
//        thick.setTo(new Scalar(255), mask);

//        kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(1, 3));
//        Imgproc.morphologyEx(thick, thick, Imgproc.MORPH_CLOSE, kernel);
//        Mat smooth = new Mat();
//        Imgproc.bilateralFilter(thick, smooth, 5, 75, 75);
        return thick;
    }

    public TransformedImage transformImageForDisplay(Mat croppedImage, int targetWidth, int targetHeight){
        double aspectRatio = targetWidth / (double)croppedImage.cols();
        double newWidth = targetWidth;
        double newHeight = croppedImage.rows() * aspectRatio;

        if (newHeight > targetHeight){
            aspectRatio = targetHeight / newHeight;
            newHeight = targetHeight;
            newWidth = newWidth * aspectRatio;
        }

        int newPosY = (int)((targetHeight / 2) - (newHeight / 2));
        int newPosX = (int)((targetWidth / 2) - (newWidth / 2));

        if (newPosX  < 0){
            newPosX = 0;
        }

        if (newPosY < 0){
            newPosY = 0;
        }

        Mat resized = Mat.zeros((int)newHeight, (int)newWidth, CvType.CV_8UC3);
        Size size = new Size(newWidth, newHeight);
        Imgproc.resize(croppedImage, resized, size, 0, 0, Imgproc.INTER_LANCZOS4);
        return new TransformedImage(resized, newPosX, newPosY, newWidth, newHeight, false);
    }

    private Bitmap prepareImageForDisplay(Bitmap original, int targetWidth, int targetHeight) {
        // 2. Resize to match your display (e.g., 96x64 or 50x50)
        Mat src = Mat.zeros(original.getHeight(), original.getWidth(), CvType.CV_8UC3);
        Utils.bitmapToMat(original, src);

        Mat bw = convertToBlackAndWhite(src);

//        saveImage(bw);

        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(150, 20));

        Mat dilated = Mat.zeros(bw.rows(), bw.cols(), bw.type());

        int subtractingAmount = 0;
        if (bw.rows() > 20){
            subtractingAmount = 10;
        }

        Mat bwSubmat = bw.submat(subtractingAmount, bw.rows() - subtractingAmount, 0, bw.cols());
        Mat dilatedSubmat = dilated.submat(subtractingAmount, dilated.rows() - subtractingAmount, 0, dilated.cols());

        System.out.println("Dilating the image...");
        Imgproc.dilate(bwSubmat, dilatedSubmat, kernel, new Point(75, 10), 1, Core.BORDER_CONSTANT, new Scalar(0));

//        saveImage(dilated);

        int subtractingAmountX = 150 / 2;
        int subtractingAmountY = 20 / 2;
        System.out.println("Saved dilated image!");

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        System.out.println("Contours Count: " + contours.size());

        contours.sort(new Comparator<MatOfPoint>() {
            @Override
            public int compare(MatOfPoint o1, MatOfPoint o2) {
                org.opencv.core.Rect rect1 = Imgproc.boundingRect(o1);
                org.opencv.core.Rect rect2 = Imgproc.boundingRect(o2);
                return Integer.compare(rect1.y, rect2.y);
            }
        });

//        if (contours.size() > 1){
//            contours.remove(0);
//            contours.remove(contours.size() - 1);
//        }

        Map<MatOfPoint, Integer[]> submats = new LinkedHashMap<>();

        int groupImageHeight = 0;
        int groupImageWidth = 0;

        int xStart = Integer.MAX_VALUE;
        int xEnd = 0;

        int yStart = Integer.MAX_VALUE;
        int yEnd = 0;

        for (MatOfPoint c : contours){
            org.opencv.core.Rect r = Imgproc.boundingRect(c);

//            r.x += subtractingAmountX;
//            r.y += subtractingAmountY;

//            r.width -= subtractingAmountX;
//            r.height -= subtractingAmountY;

            int imagePosX = r.x;
            int imagePosY = r.y;
            int imageWidth = r.width;
            int imageHeight = r.height;

            if (r.x < xStart){
                xStart = r.x;
            }

            if (xEnd == 0) {
                if (r.x + r.width > xEnd) {
                    xEnd += r.x + r.width;
                }
            }else {
                if (r.x > xEnd) {
                    imagePosX = xEnd;
                    xEnd += r.width;
                } else {
                    if (r.x + r.width > xEnd) {
                        xEnd += (r.x + r.width - xEnd);
                    }
                }
            }

            if (r.y < yStart){
                yStart = r.y;
            }

            if(yEnd == 0){
                if (r.y + r.height > yEnd){
                    yEnd += (r.y + r.height);
                }
            }else {
                if (r.y < yEnd) {
                    if (r.y + r.height > yEnd) {
                        yEnd += ((r.y + r.height) - yEnd);
                    }
                } else {
                    imagePosY = yEnd;
                    yEnd += r.height;
                }
            }

            if (imageWidth > subtractingAmountX){
                imageWidth -= subtractingAmountX;
            }

            if (imageHeight > subtractingAmountY){
                imageHeight -= subtractingAmountY;
            }

            submats.put(c, new Integer[]{(imagePosX - xStart) + subtractingAmountX, (imagePosY - yStart), imageWidth, imageHeight});
        }

        Bitmap bitmap;
        if (contours.size() > 0) {

            groupImageWidth = (xEnd - xStart);
            groupImageHeight = (yEnd - yStart);

//            Imgproc.rectangle(bw, new org.opencv.core.Rect(xStart, yStart, groupImageWidth, groupImageHeight), new Scalar(255), 2);
//            saveImage(bw);
            System.out.println("Group Image Width: " + groupImageWidth);
            System.out.println("Group Image Height: " + groupImageHeight);
            System.out.println("Original image size: " + bw.cols() + "x" + bw.rows());

            Mat cropped = Mat.zeros(groupImageHeight, groupImageWidth, bw.type());
            Mat tempBWSubmat;
            Mat croppedMat;

            for (Map.Entry<MatOfPoint, Integer[]> content : submats.entrySet()){
                org.opencv.core.Rect cropRoi = Imgproc.boundingRect(content.getKey());
                Integer[] imageData = content.getValue();

                cropRoi.x += subtractingAmountX;
                cropRoi.y += subtractingAmountY;
                cropRoi.width = imageData[2];
                cropRoi.height = imageData[3];

//                Imgproc.rectangle(bw, cropRoi, new Scalar(255), 2);

                System.out.println("CropX: " + cropRoi.x);
                System.out.println("CropY: " + cropRoi.y);
                System.out.println("Crop Width: " + cropRoi.width);
                System.out.println("Crop Height: " + cropRoi.height);

                System.out.println("Image Width: " + bw.cols());
                System.out.println("Image Height: " + bw.rows());

                // need to debug here

                if ((cropRoi.x + cropRoi.width <= bw.cols() && cropRoi.y + cropRoi.height <= bw.rows()) &&
                        (imageData[0] + imageData[2] <= cropped.cols() && imageData[1] + imageData[3] <= cropped.rows())
                ) {
                    croppedMat = new Mat(bw, cropRoi);

                    System.out.println("Image Data: " + Arrays.toString(imageData));

                    tempBWSubmat = cropped.submat(new org.opencv.core.Rect(imageData[0], imageData[1], imageData[2], imageData[3]));
                    croppedMat.copyTo(tempBWSubmat);
                }

//                Imgproc.rectangle(cropped, new org.opencv.core.Rect(imageData[0], imageData[1], imageData[2] - subtractingAmountX, imageData[3]), new Scalar(255), 2);
            }

//            saveImage(bw);

//            saveImage(cropped);
            System.out.println("Transforming image...");
            TransformedImage transformedImage = transformImageForDisplay(cropped, targetWidth, targetHeight);
            Mat resized = transformedImage.getImage();


            int newPosX = transformedImage.getNewPosX();
            int newPosY = transformedImage.getNewPosY();
            System.out.println("Transformed Image:");
            System.out.println(transformedImage);
//            saveImage(resized);

            Mat canvas = Mat.zeros(targetHeight, targetWidth, resized.type());
            if (Components.getOrientation() == 0){
                canvas = Mat.zeros(targetWidth, targetHeight, resized.type());
            }

            org.opencv.core.Rect roi = new org.opencv.core.Rect(newPosX, newPosY, resized.cols(), resized.rows());
            Mat targetArea = canvas.submat(roi);
            resized.copyTo(targetArea);

            canvas = smoothImage(canvas);
//            Imgproc.rectangle(canvas, roi, new Scalar(255), 2);

            if (Components.isDoCalculation()){
                Mat result = extractLines(canvas);
                if (newPosY > 0 && result.cols() <= resized.cols() && newPosY + resized.rows() + result.rows() < canvas.rows()){
                    org.opencv.core.Rect answerPositionRect = new org.opencv.core.Rect(0, newPosY + resized.rows(), result.cols(), result.rows());
                    result.copyTo(canvas.submat(answerPositionRect));
                }
            }

//            saveImage(canvas);

            if (Components.getOrientation() == 0){
                Mat rotated = new Mat();
                Core.rotate(canvas, rotated, Core.ROTATE_90_CLOCKWISE);
                bitmap = Bitmap.createBitmap(rotated.cols(), rotated.rows(), Bitmap.Config.ARGB_8888);
                Utils.matToBitmap(rotated, bitmap);
//                saveImage(rotated);
            }else{
                bitmap = Bitmap.createBitmap(canvas.cols(), canvas.rows(), Bitmap.Config.ARGB_8888);
                Utils.matToBitmap(canvas, bitmap);
            }
        }
        else{
            Mat canvas = Mat.zeros(targetHeight, targetWidth, CvType.CV_8UC3);
            bitmap = Bitmap.createBitmap(canvas.cols(), canvas.rows(), Bitmap.Config.ARGB_8888);
            Utils.matToBitmap(canvas, bitmap);
        }

        //Mat resizedMat = resizeMat(bw, (int)newWidth, (int)newHeight);

//        saveImage(resizedMat);

//        saveImage(canvas);
        //saveImageToPublicDirectory(getApplicationContext(), bitmap, "Updated.jpg");
        //Bitmap bitmap = Bitmap.createBitmap(resizedMat.cols(), resizedMat.rows(), Bitmap.Config.ARGB_8888);
        //Utils.matToBitmap(resizedMat, bitmap);

        return bitmap;
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
        String connectionHeader = "SCS: ";
        int connectionStatus = Components.getConnectionStatus();
        if (connectionStatus == 1){
            connectionHeader = connectionHeader.concat("S");
        }else if(connectionStatus == 0){
            connectionHeader = connectionHeader.concat("D");
        }else{
            connectionHeader = connectionHeader.concat("F");
        }

        connectionHeader = connectionHeader.concat("\n");

        String calculationResult = "TOTAL: " + this.total + "\n";

        System.out.println("Sending header and data!");
        System.out.println("Calculation Result: " + calculationResult);
        System.out.println("Total Chunks sending " + totalChunks + " of size " + chunkSize);
        if (outputStream != null){
            try {
                outputStream.write(connectionHeader.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();

                Components.setConnectionStatus(1);

                outputStream.write(header.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                outputStream.write(calculationResult.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();

                for (int i = 0; i < totalChunks; i++){
                    int start = i * chunkSize;
                    int length = Math.min(chunkSize, bytes.length - start);
                    outputStream.write(bytes, start, length);
                    //System.out.println(Arrays.toString(Arrays.copyOfRange(bytes, 0, 50)));
                    outputStream.flush();
                }

            }catch(IOException exception){
                exception.printStackTrace();
                try{
                    Components.setConnectionStatus(0);
                    outputStream.close();
                    connectToServer();

                    try {
                        Components.setConnectionStatus(1);
                        outputStream.write(header.getBytes(StandardCharsets.UTF_8));
                        outputStream.flush();

                        for (int i = 0; i < totalChunks; i++){
                            int start = i * chunkSize;
                            int length = Math.min(chunkSize, bytes.length - start);
                            outputStream.write(bytes, start, length);
//                            System.out.println(Arrays.toString(Arrays.copyOfRange(bytes, 0, 50)));
                            outputStream.flush();
                        }
                    }catch(IOException e){
                        e.printStackTrace();
                    }
                }catch(IOException e){
                    e.printStackTrace();
                }
            }
        }
    }

    private void prepareImageAndSend(Bitmap bitmap, int width, int height){
        Bitmap image = prepareImageForDisplay(bitmap, width, height);
//        saveImageToPublicDirectory(getApplicationContext(), image, String.format("Debugging Image %s.jpg", new Date().getTime()));
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
        mediaProjection = projectionManager.getMediaProjection(resultCode, data);

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
        /*virtualDisplay = mediaProjection.createVirtualDisplay(
                "Screen Record",
                WIDTH,
                HEIGHT,
                DPI,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                mediaRecorder.getSurface(),
                null, null
        );*/

        captureTexture = new SurfaceTexture(10);
        captureTexture.setDefaultBufferSize(WIDTH, HEIGHT);

        captureSurface = new Surface(captureTexture);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE){
            mediaProjection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    super.onStop();
                }

                @Override
                public void onCapturedContentResize(int width, int height) {
                    super.onCapturedContentResize(width, height);
                }

                @Override
                public void onCapturedContentVisibilityChanged(boolean isVisible) {
                    super.onCapturedContentVisibilityChanged(isVisible);
                }
            }, new Handler(Looper.getMainLooper()));
        }

        System.out.println("Display width: " + WIDTH);
        System.out.println("Display height: " + HEIGHT);
        System.out.println("Display DPI: " + DPI);

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
        ExecutorService executorService = Executors.newSingleThreadExecutor();

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

                                executorService.submit(() -> {
                                    prepareImageAndSend(testBitmap, 240, 320);
//                                    prepareImageAndSend(originalBitmap, 240, 320);
                                });
                            }
                        }catch (Exception exception){
                            exception.printStackTrace();
                        }
                    }else {
                        setupMediaRecorder();
                        System.out.println("Pixel copy failed!");
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                        1,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                );
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        1,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                );
            } else {
                // Older versions
                startForeground(1, notification);
            }
        }
    }

    public void connectToServer(){
        Thread networkThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    socket.connect(new InetSocketAddress("192.168.4.1", 5000), 5000);
                    //socket.connect(new InetSocketAddress("192.168.43.133", 5000), 5000);
                    outputStream = socket.getOutputStream();
                    Components.setConnectionStatus(1);
                    System.out.println("Connected to the server");
                } catch (IOException exception) {
                    exception.printStackTrace();
                }
            }
        });
        networkThread.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        resultCode = intent.getIntExtra("resultCode", Activity.RESULT_CANCELED);
        data = intent.getParcelableExtra("data");

        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager)getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);

        WIDTH = displayMetrics.widthPixels;
        HEIGHT = displayMetrics.heightPixels;
        DPI = displayMetrics.densityDpi;

        createNotification();

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        System.out.println("Setting up everything...");
        try {
            digitClassifier = new DigitClassifier(getApplicationContext());
        } catch (IOException e) {
            e.printStackTrace();
        }

        InputStream is = getApplicationContext().getResources().openRawResource(R.raw.newfourthtestimagefromdenis);
        testBitmap = BitmapFactory.decodeStream(is);

        socket = new Socket();

        if (!threadStarted){
            connectToServer();
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
        if (mediaRecorder != null){
            try {
                mediaRecorder.stop();
                mediaRecorder.reset();
            }catch(Exception exception){
                System.out.println("Media recorder stop failed!");
            }
        }

        /*if (virtualDisplay != null){
            virtualDisplay.release();
        }*/

        if (captureVirtualDisplay != null){
            captureVirtualDisplay.release();
        }

        if (handler != null){
            handler.removeCallbacks(captureRunnable);
        }

        if (mediaProjection != null){
            mediaProjection.stop();
        }

        if (outputStream != null){
            try{
                outputStream.close();
                Components.setConnectionStatus(0);
            }catch(IOException exception){
                exception.printStackTrace();
            }
        }

        if (socket != null){
            try{
                socket.close();
            }catch(IOException exception){
                exception.printStackTrace();
            }
        }

        //imagePullThread.terminateConnection();

        stopForeground(true);
    }
}