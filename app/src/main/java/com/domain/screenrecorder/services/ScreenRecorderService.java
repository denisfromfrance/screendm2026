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
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.WindowManager;

import androidx.core.app.NotificationCompat;

import com.domain.screenrecorder.R;
import com.domain.screenrecorder.states.Components;
import com.domain.screenrecorder.states.Constants;
import com.domain.screenrecorder.states.TransformedImage;
import com.domain.screenrecorder.utils.DigitClassifier;

import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class ScreenRecorderService extends Service {
    private MediaProjection mediaProjection;
    private MediaProjectionManager projectionManager;
    private MediaRecorder mediaRecorder;
    private DigitClassifier digitClassifier;

    private static int WIDTH = 1080;
    private static int HEIGHT = 1920;
    private static int DPI = 320;

    private long latestSeconds = SystemClock.elapsedRealtime();
    private long total = 0;

    private SurfaceTexture captureTexture;
    private Surface captureSurface;
    private VirtualDisplay captureVirtualDisplay;

    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    private final AtomicReference<Bitmap> latestFrame = new AtomicReference<>();

    Image image;
    Image.Plane plane;
    ByteBuffer byteBuffer;
    Bitmap bitmap;
    Bitmap cropped;
    Bitmap original;
    Bitmap finalCropped;

    Mat charExtractingKernel;
    Mat lineExtractingKernel;
    Mat canvas;
    Mat rgba;
    Mat digitMat;
    Mat calculationResult;
    Mat mainDilatedMat;
    Mat dilatedOriginalMat;
    Mat originalImageCleaningKernel;
    Mat writingAreaFilterKernel;

    Mat testImageMat;


    Bitmap outputBitmap;

    HandlerThread imageThread;

    Bitmap testBitmap;

    Handler handler;
    Runnable captureRunnable;

    ImageReader imageReader;

    Socket socket;
    OutputStream outputStream;
    private BlockingQueue<Bitmap> imageQueue;

    int resultCode;
    Intent data;

    private boolean threadStarted = false;


    private Mat convertToBlackAndWhite(Mat mat){
        Mat src = mat.clone();
//        Imgproc.cvtColor(src, src, Imgproc.COLOR_BGR2GRAY);

//        saveImage(src);
//        Imgproc.medianBlur(gray, gray, 3);

        if (dilatedOriginalMat == null || dilatedOriginalMat.cols() != src.cols() || dilatedOriginalMat.rows() != src.rows()){
            dilatedOriginalMat = Mat.zeros(src.rows(), src.cols(), CvType.CV_8UC1);
        }

        Imgproc.threshold(src, src, 250, 255, Imgproc.THRESH_BINARY);
//        saveImage(src);

//        if (Components.getNoteApplication() == Constants.IARVEL) {
//            org.opencv.core.Rect center = new org.opencv.core.Rect(
//                    0,
//                    0,
//                    src.cols(),
//                    src.rows());
//
//            Mat centerMat = new Mat(src, center);
//
//            double meanVal = Core.mean(centerMat).val[0];
//            if (meanVal > 127) {
//                Core.bitwise_not(src, src);
//            }
//        }

//        Imgproc.morphologyEx(src, src, Imgproc.MORPH_CLOSE, originalImageCleaningKernel);
//        saveImage(src);
//
//        Imgproc.morphologyEx(src, src, Imgproc.MORPH_OPEN, originalImageCleaningKernel);
//        saveImage(src);

        dilatedOriginalMat.setTo(new Scalar(0));

        Imgproc.dilate(src, dilatedOriginalMat, writingAreaFilterKernel);
//        saveImage(src);
//        saveImage(dilatedOriginalMat);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(dilatedOriginalMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        if (contours.size() > 0) {
            System.out.println("####### Found more than 1 contour");
            contours.sort(new Comparator<MatOfPoint>() {
                @Override
                public int compare(MatOfPoint o1, MatOfPoint o2) {
                    org.opencv.core.Rect rect1 = Imgproc.boundingRect(o1);
                    org.opencv.core.Rect rect2 = Imgproc.boundingRect(o2);
                    return Integer.compare(rect1.y, rect2.y);
                }
            });

            org.opencv.core.Rect canvasArea = Imgproc.boundingRect(contours.get(0));
            if (contours.size() > 1 && Components.getNoteApplication() == Constants.HUIONNOTE){
                canvasArea = Imgproc.boundingRect(contours.get(1));
                src = mat.submat(canvasArea);
            }

            if (Components.getNoteApplication() == Constants.IARVEL){
                int minX = Integer.MAX_VALUE;
                int minY = Integer.MAX_VALUE;
                int maxX = 0;
                int maxY = 0;
                for (Mat contour : contours){
                    org.opencv.core.Rect contourBoundingBox = Imgproc.boundingRect(contour);
                    if(contourBoundingBox.x < minX){
                        minX = contourBoundingBox.x;
                    }

                    if (contourBoundingBox.y < minY){
                        minY = contourBoundingBox.y;
                    }

                    if (contourBoundingBox.x + contourBoundingBox.width > maxX){
                        maxX = contourBoundingBox.x + contourBoundingBox.width;
                    }

                    if (contourBoundingBox.y + contourBoundingBox.height > maxY){
                        maxY = contourBoundingBox.y + contourBoundingBox.height;
                    }
                }
                canvasArea = new org.opencv.core.Rect(minX, minY, maxX - minX, maxY - minY);
                src = mat.submat(canvasArea);
            }

            System.out.println("Canvas Area X: " + canvasArea.x);
            System.out.println("Canvas Area Y: " + canvasArea.y);
            System.out.println("Canvas Area width: " + canvasArea.width);
            System.out.println("Canvas Area height: " + canvasArea.height);
//            canvasArea.y += 35;
            if (canvasArea.height > 60) {
                canvasArea.height -= 35;
            }

            System.out.println("Updated canvas Area X: " + canvasArea.x);
            System.out.println("Updated canvas Area Y: " + canvasArea.y);
            System.out.println("Updated canvas Area width: " + canvasArea.width);
            System.out.println("Updated canvas Area height: " + canvasArea.height);
            System.out.println("Clearing contours...");

//            if (Components.getNoteApplication() == Constants.HUIONNOTE) {
//                contours.clear();
//                Imgproc.findContours(dilatedOriginalMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
//
//                double maxArea = 0;
//                org.opencv.core.Rect whiteRect = null;
//                for (MatOfPoint c : contours) {
//                    double area = Imgproc.contourArea(c);
//                    if (area > maxArea) {
//                        maxArea = area;
//                        whiteRect = Imgproc.boundingRect(c);
//                    }
//                }
//
//                if (whiteRect != null) {
//                    System.out.println("BW Width: " + src.cols());
//                    System.out.println("BW Rows: " + src.rows());
//
//                    System.out.println("White Rect: " + whiteRect.x + " Y: " + whiteRect.y + " WIDTH: " + whiteRect.width + " Height: " + whiteRect.height);
//                    src = mat.submat(whiteRect);
//                }
//            }
//            saveImage(src);
        }

        Imgproc.threshold(src, src, 150, 255, Imgproc.THRESH_BINARY);
//        saveImage(src);

//        org.opencv.core.Rect center = new org.opencv.core.Rect(
//                src.cols() / 4,
//                src.rows() / 4,
//                src.cols() / 2,
//                src.rows() / 2);

//        Mat centerMat = new Mat(bw, center);

        double meanVal = Core.mean(src).val[0];
        if (meanVal > 127){
            Core.bitwise_not(src, src);
        }

        return src;
    }

    private List<MatOfPoint> getContours(Mat blackAndWhiteMat, boolean isChar){
//        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(isChar ? 4 : 80, 3));
        Mat dilated = Mat.zeros(blackAndWhiteMat.rows(), blackAndWhiteMat.cols(), blackAndWhiteMat.type());

        if (isChar) {
            Imgproc.dilate(blackAndWhiteMat, dilated, charExtractingKernel);
        }else{
            Imgproc.dilate(blackAndWhiteMat, dilated, lineExtractingKernel);
        }

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(dilated, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        if (!isChar){
//            saveImage(dilated);
            System.out.println("Contour count when detecting lines: " + contours.size());
        }

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

    public void saveBitmap(Bitmap bitmap){
        saveImageToPublicDirectory(getApplicationContext(), bitmap, String.format(
                "Cropped Digit-%s.jpg",
                String.valueOf(new Date().getTime())));
    }

    private String extractChar(Mat input, org.opencv.core.Rect lineRect){
        Mat bw = input.clone();
//        saveImage(bw);
//        List<MatOfPoint> tempContours = getContours(input, true);
        List<MatOfPoint> contours = getContours(bw, true);
        List<org.opencv.core.Rect> boundingBoxes = getBoundingBoxes(contours);

        Mat mat;
        Mat resized;
        Size size;
        int recommendedSize = 32;
        int height;
        int width;
        double aspectRatio;

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


                if (!mat.empty() && height > 0 && width > 0) {
                    resized = Mat.zeros(height, width, input.type());
                    size = new Size(width, height);
                    Imgproc.resize(mat, resized, size, 0, 0, Imgproc.INTER_LANCZOS4);
                    digitMat.setTo(new Scalar(0));
                    resized.copyTo(digitMat.submat(new org.opencv.core.Rect(0, 0, width, height)));
                }

//            Imgproc.resize(mat, resized, size, 0, 0, Imgproc.INTER_LANCZOS4);
                //saveImage(tempMat);
                String type = digitClassifier.getType(digitMat);
//                System.out.println("Type: " + type);
                if (!type.equals("-")) {
                    int classifiedDigit = digitClassifier.classify(digitMat);
                    numbersMap.put(boundingBox.x, String.valueOf(classifiedDigit));
                    System.out.println("Classified Digit: " + classifiedDigit);
//                    Imgproc.putText(digitMat, String.valueOf(classifiedDigit), new Point(10, 10), Imgproc.FONT_HERSHEY_PLAIN, 1.0D, new Scalar(255), 2);
                }

//                saveImage(tempMat);

//            int classifiedNumber = resizeToFixedSize(28, 28, resized);
            }
        }

        Map<Integer, String> sortedMap = new TreeMap<>(numbersMap);
        for (Map.Entry<Integer, String> entry : sortedMap.entrySet()){
            stringBuilder.append(entry.getValue());
        }

        //saveImage(original);
        return stringBuilder.toString();
    }

    private Mat extractLines(Mat bw){
//        Mat bw = convertToBlackAndWhite(src);
//        Mat bw = src.clone();
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

            String number = extractChar(mat, boundingBox);
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
        calculationResult.setTo(new Scalar(0));
        int font = Imgproc.FONT_HERSHEY_SIMPLEX;
        double fontScale = 0.7;
        int thickness = 2;
        Size textSize = Imgproc.getTextSize(String.valueOf(total), font, fontScale, thickness, null);
        int x = (int)((bw.cols() - textSize.width) / 2);
        if (x < 0){
            x = 0;
        }
        int y = 30;
        Imgproc.putText(calculationResult, String.valueOf(total), new Point(x, y), font, fontScale, new Scalar(255), thickness);

        System.out.println("Numbers: " + Arrays.toString(numberList.toArray()));
        System.out.println("Total: " + total);
        this.total = total;
        return calculationResult;
    }


    public Mat smoothImage(Mat inputImage){
        Mat thick = inputImage.clone();
        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, new Size(5, 5));
        //Imgproc.dilate(inputImage, thick, kernel);
        Imgproc.morphologyEx(thick, thick, Imgproc.MORPH_CLOSE, kernel);

        return thick;
    }

    public Mat removeNoise(Mat mat){
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();

        int numComponents = Imgproc.connectedComponentsWithStats(mat, labels, stats, centroids);

        int MINAREA = 9;
        Mat cleaned = Mat.zeros(mat.size(), mat.type());
        for (int i = 1; i < numComponents; i++){
            int area = (int)stats.get(i, Imgproc.CC_STAT_AREA)[0];
            if (area >= MINAREA){
                Mat mask = new Mat();
                Core.compare(labels, new Scalar(i), mask, Core.CMP_EQ);
                cleaned.setTo(new Scalar(255), mask);
            }
        }

        return cleaned;
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

        Mat resized = Mat.zeros((int)newHeight, (int)newWidth, CvType.CV_8UC1);
        resized.setTo(new Scalar(0));
        Size size = new Size(newWidth, newHeight);
        Imgproc.resize(croppedImage, resized, size, 0, 0, Imgproc.INTER_LANCZOS4);
        return new TransformedImage(resized, newPosX, newPosY, newWidth, newHeight, false);
    }

    private Bitmap prepareImageForDisplay(Mat original, int targetWidth, int targetHeight) {
        // 2. Resize to match your display (e.g., 96x64 or 50x50)
//        Mat src = Mat.zeros(original.getHeight(), original.getWidth(), CvType.CV_8UC3);
//        Utils.bitmapToMat(original, src);

        Mat bw = convertToBlackAndWhite(original);

//        bw = bw.submat(50, bw.rows() - 100, 50, bw.cols() - 100).clone();

        bw = removeNoise(bw);
//        saveImage(bw);

        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(180, 20));
        if (mainDilatedMat == null){
            mainDilatedMat = Mat.zeros(bw.rows(), bw.cols(), bw.type());
        }else{
            mainDilatedMat.setTo(new Scalar(0));
        }

        int subtractingAmount = 0;
        if (bw.rows() > 20){
            subtractingAmount = 10;
        }

        Mat dilatedSubmat;
        Mat bwSubmat;

        if (Components.getNoteApplication() == Constants.HUIONNOTE) {
            dilatedSubmat = mainDilatedMat.submat(subtractingAmount, mainDilatedMat.rows() - subtractingAmount, 0, mainDilatedMat.cols());
            bwSubmat = bw.submat(subtractingAmount, bw.rows() - subtractingAmount, 0, bw.cols());
            Imgproc.dilate(bwSubmat, dilatedSubmat, kernel, new Point(90, 10), 1, Core.BORDER_CONSTANT, new Scalar(0));
        }else{
            dilatedSubmat = mainDilatedMat.submat(0, mainDilatedMat.rows(), 5, mainDilatedMat.cols() - 5);
            bwSubmat = bw.submat(0, bw.rows(), 5, bw.cols() - 5);
            bw = bwSubmat.clone();
            Imgproc.dilate(bw, dilatedSubmat, kernel, new Point(90, 10), 1, Core.BORDER_CONSTANT, new Scalar(0));
        }

//        saveImage(dilatedSubmat);

        int subtractingAmountX = 180 / 4;
        int subtractingAmountY = 20 / 4;
        System.out.println("Saved dilated image!");

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(dilatedSubmat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);
        System.out.println("Contours Count: " + contours.size());

        contours.sort(new Comparator<MatOfPoint>() {
            @Override
            public int compare(MatOfPoint o1, MatOfPoint o2) {
                org.opencv.core.Rect rect1 = Imgproc.boundingRect(o1);
                org.opencv.core.Rect rect2 = Imgproc.boundingRect(o2);
                return Integer.compare(rect1.y, rect2.y);
            }
        });

        Map<MatOfPoint, Integer[]> submats = new LinkedHashMap<>();

        int groupImageHeight = 0;
        int groupImageWidth = 0;

        int xStart = Integer.MAX_VALUE;
        int xEnd = 0;

        int yStart = Integer.MAX_VALUE;
        int yEnd = 0;

        for (MatOfPoint c : contours){
            org.opencv.core.Rect r = Imgproc.boundingRect(c);

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

            if (imageWidth > subtractingAmountX * 2){
                imageWidth -= subtractingAmountX * 2;
            }

            if (imageHeight > subtractingAmountY){
                imageHeight -= subtractingAmountY;
            }

            if (Components.getNoteApplication() == Constants.IARVEL) {
                submats.put(c, new Integer[]{(imagePosX) + subtractingAmountX, (imagePosY - yStart), imageWidth, imageHeight});
            }else {
                submats.put(c, new Integer[]{(imagePosX) + subtractingAmountX, (imagePosY - yStart), imageWidth, imageHeight});
            }
        }

        if (Components.getOrientation() == 0){
            if (outputBitmap.getWidth() == targetWidth && outputBitmap.getHeight() == targetHeight){
                outputBitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
            }

            if (canvas.cols() == targetWidth && canvas.rows() == targetHeight) {
                canvas = Mat.zeros(targetWidth, targetHeight, CvType.CV_8UC1);
            }

            if (rgba.cols() == targetWidth && rgba.rows() == targetHeight) {
                rgba = Mat.zeros(targetWidth, targetHeight, CvType.CV_8UC4);
            }

        }else{
            if (outputBitmap.getWidth() == targetHeight && outputBitmap.getHeight() == targetWidth){
                outputBitmap = Bitmap.createBitmap(240, 320, Bitmap.Config.ARGB_8888);
            }

            if (canvas.cols() == targetHeight && canvas.rows() == targetWidth) {
                canvas = Mat.zeros(targetHeight, targetWidth, CvType.CV_8UC1);
            }

            if (rgba.cols() == targetHeight && rgba.rows() == targetWidth) {
                rgba = Mat.zeros(targetHeight, targetWidth, CvType.CV_8UC4);
            }
        }

        canvas.setTo(new Scalar(0));
        rgba.setTo(new Scalar(0, 0, 0, 0));

        if (contours.size() > 0) {

            groupImageWidth = (xEnd - xStart);
            if (groupImageWidth > subtractingAmountX * 2){
                groupImageWidth -= (subtractingAmountX * 2);
            }
            groupImageHeight = (yEnd - yStart) + (subtractingAmountY * 2);

//            Imgproc.rectangle(bw, new org.opencv.core.Rect(xStart, yStart, groupImageWidth, groupImageHeight), new Scalar(255), 2);
//            saveImage(bw);

            System.out.println("Start X: " + xStart);
            System.out.println("End X: " + xEnd);
            System.out.println("Start Y: " + yStart);
            System.out.println("End Y: " + yEnd);
            System.out.println("Group Image Width: " + groupImageWidth);
            System.out.println("Group Image Height: " + groupImageHeight);
            System.out.println("Original image size: " + bw.cols() + "x" + bw.rows());

            Mat cropped = Mat.zeros(groupImageHeight, groupImageWidth, bw.type());
            cropped.setTo(new Scalar(0));
            Mat tempBWSubmat;
            Mat croppedMat;

            for (Map.Entry<MatOfPoint, Integer[]> content : submats.entrySet()){
                org.opencv.core.Rect cropRoi = Imgproc.boundingRect(content.getKey());
                Integer[] imageData = content.getValue();

//                cropRoi.x += (subtractingAmountX);
//                cropRoi.x += 15;
                cropRoi.y += (subtractingAmountY);
                cropRoi.width = imageData[2];
                cropRoi.height = imageData[3];

//                Imgproc.rectangle(bw, cropRoi, new Scalar(255), 2);

                System.out.println("CropX: " + cropRoi.x);
                System.out.println("CropY: " + cropRoi.y);
                System.out.println("Crop Width: " + cropRoi.width);
                System.out.println("Crop Height: " + cropRoi.height);

                System.out.println("Image Width: " + bw.cols());
                System.out.println("Image Height: " + bw.rows());
                System.out.println("Image Data: " + Arrays.toString(imageData));

                // need to debug here
                if (Components.getNoteApplication() == Constants.HUIONNOTE) {
                    if ((cropRoi.x + cropRoi.width <= bw.cols() && imageData[1] + cropRoi.height <= bw.rows()) &&
                            (imageData[2] <= cropped.cols() && imageData[3] <= cropped.rows()) &&
                            (imageData[3] >= 10 && imageData[2] >= 10)
                    ) {
                        croppedMat = new Mat(bw, cropRoi);

                        System.out.println("Image Data: " + Arrays.toString(imageData));

                        int newPosX = cropRoi.x - xStart;
                        if (newPosX < 0) {
                            newPosX = 0;
                        }

//                        Imgproc.rectangle(cropped, new org.opencv.core.Rect(newPosX, imageData[1], imageData[2], imageData[3]), new Scalar(255), 2);

                        tempBWSubmat = cropped.submat(new org.opencv.core.Rect(newPosX, imageData[1], imageData[2], imageData[3]));
                        croppedMat.copyTo(tempBWSubmat);
                    }
                }else {
                    if ((cropRoi.x + cropRoi.width <= bw.cols() && imageData[1] + cropRoi.height <= bw.rows()) &&
                            (cropRoi.x + imageData[2] <= cropped.cols() && imageData[1] + imageData[3] <= cropped.rows()) &&
                            (imageData[3] >= 10 && imageData[2] >= 10)
                    ) {
                        croppedMat = new Mat(bw, cropRoi);

                        System.out.println("Image Data: " + Arrays.toString(imageData));

                        int newPosX = cropRoi.x - xStart;
                        if (newPosX < 0) {
                            newPosX = 0;
                        }

                        tempBWSubmat = cropped.submat(new org.opencv.core.Rect(newPosX, imageData[1], imageData[2], imageData[3]));
                        croppedMat.copyTo(tempBWSubmat);
                    }
                }
            }

//            saveImage(bw);
//            saveImage(cropped);
            System.out.println("Transforming image...");
            TransformedImage transformedImage;
            if (Components.getOrientation() == 0){
                transformedImage = transformImageForDisplay(cropped, 320, 240);
            }else{
                transformedImage = transformImageForDisplay(cropped, targetWidth, targetHeight);
            }


            int newPosX = transformedImage.getNewPosX();
            int newPosY = transformedImage.getNewPosY();
            System.out.println("Transformed Image:");
            System.out.println(transformedImage);

            Mat resized = transformedImage.getImage();
//            saveImage(resized);
            org.opencv.core.Rect roi = new org.opencv.core.Rect(newPosX, newPosY, resized.cols(), resized.rows());
            Mat targetArea = canvas.submat(roi);
            resized.copyTo(targetArea);

            //canvas = smoothImage(canvas);

            if (Components.isDoCalculation()){
                Mat result = extractLines(canvas);
                if (newPosY > 0 && result.cols() <= resized.cols() && newPosY + resized.rows() + result.rows() < canvas.rows()){
                    org.opencv.core.Rect answerPositionRect = new org.opencv.core.Rect(0, newPosY + resized.rows(), result.cols(), result.rows());
                    result.copyTo(canvas.submat(answerPositionRect));
                }
            }

            Imgproc.cvtColor(canvas, rgba, Imgproc.COLOR_GRAY2RGBA);
//            saveImage(canvas);
//            saveImage(rgba);

            if (Components.getOrientation() == 0){
                Mat rotated = new Mat();
                Core.rotate(rgba, rotated, Core.ROTATE_90_CLOCKWISE);
                Utils.matToBitmap(rotated, outputBitmap);
//                saveImage(rotated);
            }else{
                Utils.matToBitmap(rgba, outputBitmap);
            }
        }
        else{
            Utils.matToBitmap(canvas, outputBitmap);
        }

        return outputBitmap;
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

//    private void prepareImageAndSend(Bitmap bitmap, int width, int height){
//        Bitmap image = prepareImageForDisplay(bitmap, width, height);
//        System.out.println("Image received.");
//        sendBytes(bitmapTo1BitArray(image));
//    }

    private void prepareImageAndSend(Mat bitmap, int width, int height){
        Bitmap image = prepareImageForDisplay(bitmap, width, height);
        System.out.println("Image received.");
        sendBytes(bitmapTo1BitArray(image));
    }

    public ScreenRecorderService() {
//        textRecognition = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        imageQueue = new LinkedBlockingDeque<>();
    }

    private void setupMediaRecorder(){
        System.out.println("Setting up media recorder...");
        //mediaProjection = projectionManager.getMediaProjection(resultCode, data);

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

    private Mat yuvToGray(Image image) {
        Image.Plane yPlane = image.getPlanes()[0];
        ByteBuffer buffer = yPlane.getBuffer();

        int width = image.getWidth();
        int height = image.getHeight();
        int rowStride = yPlane.getRowStride();
        int pixelStride = yPlane.getPixelStride(); // usually 1

        Mat gray = new Mat(height, width, CvType.CV_8UC1);

        byte[] rowData = new byte[rowStride];
        int matRow = 0;

        buffer.rewind();

        for (int row = 0; row < height; row++) {
            buffer.position(row * rowStride);
            buffer.get(rowData, 0, rowStride);

            if (pixelStride == 1) {
                gray.put(matRow, 0, rowData, 0, width);
            } else {
                // Rare case (some devices)
                byte[] compact = new byte[width];
                for (int col = 0; col < width; col++) {
                    compact[col] = rowData[col * pixelStride];
                }
                gray.put(matRow, 0, compact);
            }
            matRow++;
        }

        return gray;
    }

    private void createVirtualDisplay() throws IOException {
        imageThread = new HandlerThread("ImageReaderThread");
        imageThread.start();
        handler = new Handler(imageThread.getLooper());

        captureTexture = new SurfaceTexture(10);
        captureTexture.setDefaultBufferSize(WIDTH, HEIGHT);

        captureSurface = new Surface(captureTexture);
        //captureSurface = mediaRecorder.getSurface();

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

        ExecutorService executorService = Executors.newSingleThreadExecutor();

        testImageMat = new Mat(testBitmap.getHeight(), testBitmap.getWidth(), CvType.CV_8UC3);

        imageReader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            image = reader.acquireLatestImage();
            if (image == null) return;

//            long now = SystemClock.elapsedRealtime();
//            if (now - latestSeconds < 1000){
//                image.close();
//                return;
//            }

            if (!isProcessing.compareAndSet(false, true)){
                image.close();
                return;
            }

//            Mat gray = yuvToGray(image);
//            saveImage(gray);

            plane = image.getPlanes()[0];
            byteBuffer = plane.getBuffer();
//            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
//            int rowPadding = rowStride - pixelStride * WIDTH;

            Mat rgbaImage = new Mat(HEIGHT, WIDTH, CvType.CV_8UC4);
            byte[] row = new byte[rowStride];

            byteBuffer.rewind();

            for (int y = 0; y < HEIGHT; y++) {
                byteBuffer.position(y * rowStride);
                byteBuffer.get(row);
                rgbaImage.put(y, 0, row, 0, WIDTH * 4);
            }

            image.close();

            Mat gray = new Mat();
            Imgproc.cvtColor(rgbaImage, gray, Imgproc.COLOR_RGBA2GRAY);
            if (gray.cols() > 150){
                gray = gray.submat(0, gray.rows(), 50, gray.cols() - 50).clone();
            }
            rgbaImage.release();
//            saveImage(gray);

//            System.out.println("Pixel Stride: " + pixelStride);
            System.out.println("Row Stride: " + rowStride);
//            System.out.println("Row Padding: " + rowPadding);
//            System.out.println("Expected: " + WIDTH * pixelStride);

//            bitmap = Bitmap.createBitmap(WIDTH + rowPadding / pixelStride, HEIGHT, Bitmap.Config.ARGB_8888);
//            bitmap.copyPixelsFromBuffer(byteBuffer);

//            int STRIPE = 50;

//            cropped = Bitmap.createBitmap(bitmap, 0, 0, WIDTH, HEIGHT);
//            if (Components.getNoteApplication() == Constants.IARVEL){
//                cropped = Bitmap.createBitmap(bitmap, STRIPE, 0, WIDTH - (STRIPE * 2), HEIGHT);
//            }

//            original = cropped.copy(Bitmap.Config.ARGB_8888, false);
//            latestFrame.set(original);
//            saveBitmap(cropped);


            if (threadStarted){
//                latestSeconds = now;

                Mat finalGray = gray;
                executorService.execute(() -> {
                    try {
                        Utils.bitmapToMat(testBitmap, testImageMat);
                        Imgproc.cvtColor(testImageMat, testImageMat, Imgproc.COLOR_BGR2GRAY);
                        prepareImageAndSend(testImageMat, 240, 320);
//                        prepareImageAndSend(finalGray, 240, 320);
                    }finally {
//                        cropped.recycle();
                        isProcessing.set(false);
                    }
                });
            }else{
                bitmap.recycle();
                isProcessing.set(false);
            }

        }, handler);

        captureVirtualDisplay = mediaProjection.createVirtualDisplay(
                "Capture VDisplay",
                WIDTH,
                HEIGHT,
                DPI,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), // captureSurface,
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

//                                executorService.submit(() -> {
//                                    prepareImageAndSend(testBitmap, 240, 320);
//                                    prepareImageAndSend(originalBitmap, 240, 320);
//                                });
                            }
                        }catch (Exception exception){
                            exception.printStackTrace();
                        }
                    }else {
                        //setupMediaRecorder();
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
//                    socket.connect(new InetSocketAddress("192.168.4.1", 5000), 5000);
                    socket.connect(new InetSocketAddress("192.168.43.133", 5000), 5000);
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

        originalImageCleaningKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(10, 10));
        writingAreaFilterKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(50, 70));
        charExtractingKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(4, 3));
        lineExtractingKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(80, 3));

        canvas = Mat.zeros(320, 240, CvType.CV_8UC1);
        rgba = Mat.zeros(canvas.rows(), canvas.cols(), CvType.CV_8UC4);
        outputBitmap = Bitmap.createBitmap(canvas.cols(), canvas.rows(), Bitmap.Config.ARGB_8888);
        digitMat = Mat.zeros(32, 240, CvType.CV_8UC1);
        calculationResult = Mat.zeros(32, 240, CvType.CV_8UC1);


        InputStream is = getApplicationContext().getResources().openRawResource(R.raw.numberswithgoodspaceing2);
//        InputStream is = getApplicationContext().getResources().openRawResource(R.raw.correctnumberrepresentation);
//        InputStream is = getApplicationContext().getResources().openRawResource(R.raw.iarvel3);
        testBitmap = BitmapFactory.decodeStream(is);

        socket = new Socket();

        if (!threadStarted){
            connectToServer();
            threadStarted = true;
        }

        mediaProjection = projectionManager.getMediaProjection(resultCode, data);

        //setupMediaRecorder();
        try {
            createVirtualDisplay();
        }catch (IOException exception){
            exception.printStackTrace();
        }
        //mediaRecorder.start();
//        captureSurfacePeriodically(captureSurface);
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

        stopForeground(true);
    }
}