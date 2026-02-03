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
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.view.PixelCopy;
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
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

public class ScreenRecorderService extends Service {
    private MediaProjection mediaProjection;
    private DigitClassifier digitClassifier;

    private static int WIDTH = 1080;
    private static int HEIGHT = 1920;
    private static int DPI = 320;

    private int latestDisplayedNumber = 0;
    int resultCode;

    private long total = 0;

    private SurfaceTexture captureTexture;
    private VirtualDisplay captureVirtualDisplay;

    ArrayList<Integer> numberList = new ArrayList<>();

    private final AtomicBoolean isProcessing = new AtomicBoolean(false);

    HandlerThread networkConnectionHandlerThread;
    HandlerThread imageThread;

    Handler networkConnectionHandler;
    Handler handler;

    Runnable captureRunnable;

    boolean connectedToServer = false;
    private boolean threadStarted = false;
    private boolean startedScreenRecording = false;

    Mat charExtractingKernel;
    Mat lineExtractingKernel;
    Mat canvas;
    Mat rgba;
    Mat digitMat;
    Mat calculationResult;
    Mat detectedNumbers;
    Mat mainDilatedMat;
    Mat dilatedOriginalMat;
    Mat originalImageCleaningKernel;
    Mat writingAreaFilterKernel;
    Mat yuanMorphKernel;
    Mat testImageMat;
    Mat erosionKernel;
    Mat rotated;
    Mat whiteRect;
    Mat kernel;
    Mat lineAligningKernel;
    Mat huionBlackAndWhiteMask;
    Mat tempHuionImage;

    Bitmap outputBitmap;
    Bitmap testBitmap;

    ImageReader imageReader;

    Socket socket;
    OutputStream outputStream;
    InputStream inputStream;

    Intent data;

    private String currentDisplayingNumber = "";
    private ArrayList<String> numbersRecorded;

    private int imagePosX = 0;
    private int imagePosY = 0;
    private int numberOfLines = 0;


    private boolean isBlack(Mat input){
        double meanVal = Core.mean(input).val[0];
        return meanVal < 127;
    }

    private boolean isPureBlack(Mat input){
        double meanVal = Core.mean(input).val[0];
        return meanVal < 2;
    }


    private int[] extractCenteredNonBlackRegion(Mat mat){
        int height = mat.rows();
        int width = mat.cols();
        int centerY = height / 2;

        int startY = 0;
        int endY = 0;

        int[] boundary = new int[2];

        for (int y = centerY; y > 0; y--){
            boolean isStartBlack = isPureBlack(mat.row(y));
            boolean isEndBlack = isPureBlack(mat.row(height - y));

            if (isStartBlack && startY == 0){
                startY = y;
            }

            if (isEndBlack && endY == 0){
                endY = height - y;
                if (endY > 1){
                    endY -= 1;
                }
            }
        }

        boundary[0] = startY;
        boundary[1] = endY;
        return boundary;
    }


    private Mat convertToBlackAndWhite(Mat mat){
        Mat src = mat.clone();
//        Imgproc.cvtColor(src, src, Imgproc.COLOR_BGR2GRAY);

//        saveImage(src);
//        Imgproc.medianBlur(gray, gray, 3);

        if (dilatedOriginalMat == null || dilatedOriginalMat.cols() != src.cols() || dilatedOriginalMat.rows() != src.rows()){
            dilatedOriginalMat = Mat.zeros(src.rows(), src.cols(), CvType.CV_8UC1);
        }

        if (whiteRect == null){
            whiteRect = Mat.ones(src.rows(), src.cols(), CvType.CV_8UC1);
            whiteRect.setTo(new Scalar(255));
        }


        if (Components.getNoteApplication() == Constants.YUAN){
            Imgproc.threshold(src, src, 200, 255, Imgproc.THRESH_BINARY);
//            Core.bitwise_and(src, whiteRect, src);
//            Imgproc.rectangle(src, new Rect(0, 0, src.cols(), 50), Scalar.all(255.0D), -1);
            Imgproc.rectangle(src, new Rect(0, src.rows() - 55, src.cols(), 50), Scalar.all(0), -1);
//            Imgproc.threshold(src, src, 5, 255, Imgproc.THRESH_BINARY);
//            saveImage(src);

            int imageHeight = src.rows();

            int midY = src.rows() / 2;
            int startY = 0;
            int endY = 0;
            for (int i = midY; i > 0; i--){
                boolean isStartBlack = isPureBlack(src.row(i));
                if (isStartBlack && startY == 0){
                    startY = i;
                }

                boolean isEndBlack = isPureBlack(src.row(imageHeight - i));
                if (isEndBlack && endY == 0){
                    endY = imageHeight - i;
                }
            }

            // add 2 pixels to start Y coordinate to miss the black line
            startY += 2;

            // remove page number
            if (endY - 50 < startY){
                endY = startY + 50;
            }else {
                endY -= 60;
            }

            System.out.println("Extracting the column range");
            int imageWidth = src.cols();
            int midX = imageWidth / 2;
            int startX = 0;
            int endX = 0;
            for (int x = 0; x < midX; x++){
                boolean isStartBlack = isPureBlack(src.col(x));
                boolean isEndBlack = isPureBlack(src.col((imageWidth - 1) - x));

                if (!isStartBlack && startX == 0){
                    startX = x;
                    startX += 2;
                }

                if (!isEndBlack && endX == 0){
                    endX = imageWidth - x;
                    endX -= 2;
                }
            }

            // prevent end x getting 0
            if (endX == 0){
                endX = startX + 50;
            }

            System.out.println("Start X: " + startX);
            System.out.println("Start Y: " + startY);
            System.out.println("End X: " + endX);
            System.out.println("End Y: " + endY);

            src = mat.submat(new Rect(startX, startY, endX - startX, endY - startY)).clone();
            //src = mat.submat(new Rect(10, startY, mat.cols() - 10, endY - startY)).clone();
//            Imgproc.threshold(src, src, 200, 255, Imgproc.THRESH_BINARY);
//            Imgproc.morphologyEx(src, src, Imgproc.MORPH_CLOSE, yuanMorphKernel);

//            saveImage(src);

            if (!isBlack(src)){
                Core.bitwise_not(src, src);
            }

        }else if(Components.getNoteApplication() == Constants.IARVEL){
            Imgproc.threshold(src, src, 250, 255, Imgproc.THRESH_BINARY);
//            Imgproc.morphologyEx(src, src, Imgproc.MORPH_OPEN, erosionKernel);
        }else if(Components.getNoteApplication() == Constants.HUIONNOTE){
//            Core.inRange(src, Scalar.all(0), Scalar.all(0), huionBlackAndWhiteMask);
//            Mat huionCleanImage = new Mat(src.rows(), src.cols(), src.type(), new Scalar(255));
//            src.copyTo(huionCleanImage, huionBlackAndWhiteMask);
            if (tempHuionImage != null){
                tempHuionImage.setTo(new Scalar(0));
            }
            if (tempHuionImage == null || tempHuionImage.rows() != src.rows() || tempHuionImage.cols() != src.cols()){
                tempHuionImage = new Mat(src.rows(), src.cols(), src.type());
                tempHuionImage.setTo(new Scalar(0));
            }
            Imgproc.threshold(src, tempHuionImage, 250, 255, Imgproc.THRESH_BINARY);

            int[] boundery = extractCenteredNonBlackRegion(tempHuionImage);
            if(boundery[0] < 0){
                boundery[0] = 0;
            }

            if (boundery[1] <= 0){
                boundery[1] = src.rows() - 1;
            }

            System.out.println("Source Image Size: " + src.cols() + "x" + src.rows());
            System.out.println("Boundery: " + boundery[0] + " | " + boundery[1]);

            int tempBoundery = 0;
            if (boundery[0] > boundery[1]){
                tempBoundery = boundery[1];
                boundery[1] = boundery[0];
                boundery[0] = tempBoundery;
            }

            if (src.rows() > boundery[1] - boundery[0]){
                src = src.submat(new Rect(0, boundery[0], src.cols(), boundery[1] - boundery[0])).clone();
            }
            src = removeNoise(src);
            Imgproc.threshold(src, src, 250, 255, Imgproc.THRESH_BINARY);
//            saveImage(src);
        }

//        Imgproc.rectangle(src, new Rect(0, 0, src.cols(), 100), new Scalar(255, 255, 255));
//        saveImage(src);

        if (Components.getNoteApplication() == Constants.IARVEL) {
            int imageWidth = src.cols();
            int leftStripeEnd = 0;

            System.out.println("Removing left blank area...");
            for (int x = 0; x < 200; x++){
                Mat col = src.col(x);
                MatOfDouble mean = new MatOfDouble();
                MatOfDouble std = new MatOfDouble();
                Core.meanStdDev(col, mean, std);
                if (std.toArray()[0] == 0){
                    leftStripeEnd = x;
                }
            }

            src.colRange(0, leftStripeEnd + 3).setTo(new Scalar(255, 255, 255));

            int x1 = 0;
            int x2;
            for (int x = imageWidth - 1; x > imageWidth - 80; x--){
                Mat col = src.col(x);
                MatOfDouble mean = new MatOfDouble();
                MatOfDouble std = new MatOfDouble();

                Core.meanStdDev(col, mean, std);
                System.out.println("Standard Deviation: " + std.toArray()[0]);
                if (std.toArray()[0] == 0){
                    x1 = x;
                }
            }

            x2 = imageWidth - 1;

            if (x1 < x2 && x1 > imageWidth - 150){
                src.colRange(x1, x2).setTo(new Scalar(255));
            }


            Rect center = new Rect(
                    0,
                    0,
                    src.cols(),
                    src.rows());

            Mat centerMat = new Mat(src, center);

            double meanVal = Core.mean(centerMat).val[0];
            if (meanVal > 127) {
                Core.bitwise_not(src, src);
            }

            src = src.submat(0, src.rows(), 2, src.cols() -2).clone();
//            saveImage(src);
        }

        dilatedOriginalMat.setTo(new Scalar(0));

        Imgproc.dilate(src, dilatedOriginalMat, writingAreaFilterKernel);
//        saveImage(src);
//        saveImage(dilatedOriginalMat);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.findContours(dilatedOriginalMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        if (contours.size() > 0) {
            // Remove unnecessary parts of the image to extract only the writing area
            System.out.println("####### Found more than 1 contour");

            contours.sort((o1, o2) -> {
                Rect rect1 = Imgproc.boundingRect(o1);
                Rect rect2 = Imgproc.boundingRect(o2);
                return Integer.compare(rect1.y, rect2.y);
            });

            org.opencv.core.Rect canvasArea = Imgproc.boundingRect(contours.get(0));
//            if (contours.size() > 1 && Components.getNoteApplication() == Constants.HUIONNOTE){
//                canvasArea = Imgproc.boundingRect(contours.get(1));
//                src = mat.submat(canvasArea);
//            }

            if (Components.getNoteApplication() == Constants.HUIONNOTE){
                canvasArea = Imgproc.boundingRect(contours.get(0));
                src = src.submat(canvasArea);
            }
            System.out.println("Extracted content from Huion");

            if (Components.getNoteApplication() == Constants.YUAN){
                Rect boundingBox;

                Rect startBBox = Imgproc.boundingRect(contours.get(0));
                Rect endBBox = Imgproc.boundingRect(contours.get(contours.size() - 1));

                int startY = startBBox.y;

                int endY = endBBox.y + endBBox.height;

                if (endBBox.y + endBBox.height > mat.cols() - 20){
                    endBBox = Imgproc.boundingRect(contours.get(contours.size() - 1));
                    endY = endBBox.y + endBBox.height;
                }


                boundingBox = new Rect(0, startY, src.cols() - 10, endY - startY);
                System.out.println("Start Y: " + startY);
                System.out.println("Height: " + (endY - startY));
                src = src.submat(boundingBox).clone();
//                saveImage(src);
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
//                saveImage(src);
                System.out.println("Extracted content when iarvel application is selected.");
            }

            if (Components.getNoteApplication() != Constants.YUAN) {
                int imageWidth = src.cols();
                int leftStripeEnd = 0;
//            saveImage(src);
                System.out.println("Removing left blank area...");
                for (int x = 0; x < imageWidth / 4; x++) {
                    Mat col = src.col(x);
                    MatOfDouble mean = new MatOfDouble();
                    MatOfDouble std = new MatOfDouble();

                    Core.meanStdDev(col, mean, std);
//                System.out.println("Standard Deviation: " + std.toArray()[0]);
                    if (std.toArray()[0] == 0) {
                        leftStripeEnd = x;
//                    src.colRange(x, x+1).setTo(new Scalar(255, 255, 255));
                    }
                }

                if (leftStripeEnd < 100 && Components.getNoteApplication() != Constants.YUAN) {
                    src.colRange(0, leftStripeEnd + 3).setTo(new Scalar(255, 255, 255));
                }
//            saveImage(src);

                System.out.println("Removing right blank area...");
                int x1 = 0;
                int x2;
                for (int x = imageWidth - 1; x > imageWidth - 80; x--) {
                    Mat col = src.col(x);
                    MatOfDouble mean = new MatOfDouble();
                    MatOfDouble std = new MatOfDouble();

                    Core.meanStdDev(col, mean, std);
                    System.out.println("Standard Deviation: " + std.toArray()[0]);
                    if (std.toArray()[0] == 0) {
                        x1 = x;
                    }
                }

                x2 = imageWidth - 1;

                if (x1 < x2 && x1 > imageWidth - 150) {
                    src.colRange(x1, x2).setTo(new Scalar(255));
                }
            }

//            saveImage(src);

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
        }

//        saveImage(src);
        Imgproc.threshold(src, src, 150, 255, Imgproc.THRESH_BINARY);
//        saveImage(src);
        src = removeNoise(src);
//        saveImage(src);

        if (Components.getNoteApplication() == Constants.HUIONNOTE) {
            src = src.submat(0, src.rows(), 5, src.cols() - 5).clone();
        }else if(Components.getNoteApplication() == Constants.YUAN){
            System.out.println("saving yuan screenshot......");
//            saveImage(src);
            src = src.submat(0, src.rows(), 5, src.cols() - 5).clone();
        }

        double meanVal = Core.mean(src).val[0];
        if (meanVal > 127){
            Core.bitwise_not(src, src);
        }
//        saveImage(src);
        return src;
    }

    private List<MatOfPoint> getContours(Mat blackAndWhiteMat, boolean isChar){
//        Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(isChar ? 4 : 80, 3));
        Mat dilated = Mat.zeros(blackAndWhiteMat.rows(), blackAndWhiteMat.cols(), blackAndWhiteMat.type());

        if (isChar) {
            Imgproc.dilate(blackAndWhiteMat, dilated, charExtractingKernel);
        }else{
            Imgproc.dilate(blackAndWhiteMat, dilated, lineExtractingKernel);
//            saveImage(dilated);
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
                new Date().getTime()));
    }

    private String extractChar(Mat input, org.opencv.core.Rect lineRect){
        Mat bw = input.clone();
//        saveImage(bw);
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
        boundingBoxes = boundingBoxes.stream().sorted((o1, o2) -> Math.min(o1.x, o2.x)).collect(Collectors.toList());

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
        List<MatOfPoint> contours = getContours(bw, false);
        List<org.opencv.core.Rect> boundingBoxes = getBoundingBoxes(contours);
        Collections.reverse(boundingBoxes);

        System.out.println("Lines found: " + boundingBoxes.size());

        // remove last element when calculations needs to be done with detected numbers
        if (boundingBoxes.size() == 5) {
            boundingBoxes.remove(boundingBoxes.size() - 1);
        }

        numberOfLines = boundingBoxes.size();
        System.out.println("Lines found: " + boundingBoxes.size());

//        saveImage(bw.submat(boundingBoxes.get(0)));

        numberList.clear();
        numbersRecorded.clear();

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
                if (!number.equals("") && number.length() == 4){
                    int n = Integer.parseInt(number);
                    switch (numberList.size()) {
                        case 0:
                            total = n;
                            break;
                        case 1:
                            total += n;
                            break;
                        case 2:
                            total *= n;
                            break;
                            /*
                        case 3:
                            total *= n;
                            break;*/
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
        double fontScale = 1.4;
        int thickness = 2;
        Size textSize = Imgproc.getTextSize(String.valueOf(total), font, fontScale, thickness, null);
        int x = (int)((bw.cols() - textSize.width) / 2);

        if (x < 0){
            x = 0;
        }

        imagePosX = x;

        int y = 30;
        Imgproc.putText(calculationResult, String.valueOf(total), new Point(x, y), font, fontScale, new Scalar(40), thickness);

        System.out.println("Numbers: " + Arrays.toString(numberList.toArray()));
        System.out.println("Total: " + total);
        this.total = total;
        return calculationResult;
    }


    public Mat removeNoise(Mat mat){
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();

        int numComponents = Imgproc.connectedComponentsWithStats(mat, labels, stats, centroids);

        int MINAREA = 4;

        Mat cleaned = Mat.zeros(mat.size(), mat.type());
        for (int i = 1; i < numComponents; i++){
            int area = (int)stats.get(i, Imgproc.CC_STAT_AREA)[0];
            if (area >= MINAREA){
                Mat mask = new Mat();
                Core.compare(labels, new Scalar(i), mask, Core.CMP_EQ);

                // Extract pixels of this component
                Mat grayMask = new Mat();
                Core.inRange(mat, new Scalar(90), new Scalar(250), grayMask);
                Core.bitwise_or(mat, grayMask, cleaned);
//                cleaned.setTo(new Scalar(255), mask);
            }
        }
//        saveImage(cleaned);
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
        System.out.println("New Size" + newWidth + "x" + newHeight);
        System.out.println("Cropped Image Size" + croppedImage.cols() + "x" + croppedImage.cols());
        if (newHeight < 1.0D || newWidth < 1.0D){
            return new TransformedImage(croppedImage, 0, 0, croppedImage.cols(), croppedImage.rows(), false);
        }
        Imgproc.resize(croppedImage, resized, size, 0, 0, Imgproc.INTER_LANCZOS4);
        return new TransformedImage(resized, newPosX, newPosY, newWidth, newHeight, false);
    }

    private void arrangeInXAxis(List<Rect> boundingBoxes){
        Map<Integer[], Rect> yAlignments = new HashMap<>();
    }

    private void prepareMainDilatedMat(Mat mat){
        if (mainDilatedMat == null){
            mainDilatedMat = Mat.zeros(mat.rows(), mat.cols(), mat.type());
        }else{
            if (mat.cols() != mainDilatedMat.cols() || mat.rows() != mainDilatedMat.rows()){
                mainDilatedMat = Mat.zeros(mat.rows(), mat.cols(), mat.type());
            }
            mainDilatedMat.setTo(new Scalar(0));
        }
    }

    private Mat alignLinesInXAxis(Mat mat){
        System.out.println("Aligning lines in X axis...");
        prepareMainDilatedMat(mat);

        List<MatOfPoint> contours = new ArrayList<>();
        Mat hierarchy = new Mat();
        Imgproc.dilate(mat, mainDilatedMat, lineAligningKernel, new Point(55, 5), 1, Core.BORDER_CONSTANT, new Scalar(0));
        Imgproc.findContours(mainDilatedMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

//        saveImage(mainDilatedMat);

        contours.sort((o1, o2) -> {
            Rect rect1 = Imgproc.boundingRect(o1);
            Rect rect2 = Imgproc.boundingRect(o2);
            return Integer.compare(rect1.y, rect2.y);
        });

        int centerX = mat.cols() / 2;
        int space = 2;
        Mat selectedArea;
        for (MatOfPoint contour : contours) {
            Rect boundingBox = Imgproc.boundingRect(contour);

            if (boundingBox.x + boundingBox.width >= mat.cols()){
                if (boundingBox.x > 0){
                    boundingBox.x += 55;
                }

                boundingBox.width = mat.cols() - boundingBox.x;
            }else{
                if (boundingBox.x > 0){
                    boundingBox.x += 55;
                    boundingBox.width -= 110;
                }else{
                    boundingBox.width -= 55;
                }
            }

            if (boundingBox.x > 2) {
                boundingBox.x -= space;
                if (boundingBox.x + boundingBox.width < mat.cols() - 4) {
                    boundingBox.width += (space * 2);
//                    boundingBox.width -= 50;
                }else{
                    boundingBox.width += space;
                }
            }else{
                if(boundingBox.width < mat.cols() - space) {
                    boundingBox.width += space;
                }
            }

            selectedArea = mat.submat(boundingBox).clone();
            mat.submat(boundingBox).setTo(new Scalar(0));
//            Imgproc.rectangle(mat, boundingBox, new Scalar(255));
            boundingBox.x = centerX - (boundingBox.width / 2);
//            Imgproc.rectangle(mat, boundingBox, new Scalar(255));
            selectedArea.copyTo(mat.submat(boundingBox));
        }

//        saveImage(mat);
//        saveImage(mainDilatedMat);
        return mat.clone();
    }

    private Mat getNewCoordinateForImageContent(Mat mat){
        System.out.println("Getting new coordinates for image content...");
        //saveImage(mat);
        List<MatOfPoint> contours = new ArrayList<>();
        prepareMainDilatedMat(mat);

        Mat hierarchy = new Mat();
        Imgproc.dilate(mat, mainDilatedMat, kernel, new Point(90, 2), 1, Core.BORDER_CONSTANT, new Scalar(0));
        Imgproc.findContours(mainDilatedMat, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE);

        contours.sort((o1, o2) -> {
            Rect rect1 = Imgproc.boundingRect(o1);
            Rect rect2 = Imgproc.boundingRect(o2);
            return Integer.compare(rect1.y, rect2.y);
        });

        Map<Rect, Rect> imageContentLocations = new LinkedHashMap<>();

        int gap = 2;
        int subtractAmountX = 180 / 2;
        int subtractAmountY = 4 / 2;
        int minX = mat.cols(), maxX = 0;
        int minY = mat.rows(), maxY = 0;

        Rect prevBoundingBox = null;
        Rect newUpdatedBoundingBox = null;

        for (MatOfPoint contour : contours){
            Rect boundingBox = Imgproc.boundingRect(contour);
            if (boundingBox.width > 180 && boundingBox.height > 4) {
                if (boundingBox.x > subtractAmountX) {
                    if (boundingBox.x + boundingBox.width < mat.cols() - 1) {
                        boundingBox.x += subtractAmountX - gap;
                        boundingBox.width -= subtractAmountX * 2;
                        boundingBox.width += gap;
                    } else {
                        boundingBox.x += subtractAmountX;
                        boundingBox.width -= subtractAmountX;
                    }
                } else {
                    boundingBox.width -= subtractAmountX;
                }

                boundingBox.y += subtractAmountY;
                boundingBox.y -= gap;
                if (boundingBox.y + boundingBox.height < mat.rows() - 2) {
                    if (boundingBox.height - (subtractAmountY * 2) > 0) {
                        boundingBox.height -= subtractAmountY * 2;
                    }
                    boundingBox.height += gap;
                }else{
                    boundingBox.height = mat.rows() - boundingBox.y;
                }

                int space = 15;
                int x, y;
                x = boundingBox.x;
                y = boundingBox.y;

                if (prevBoundingBox != null) {
                    if (boundingBox.y >= prevBoundingBox.y + prevBoundingBox.height) {
                        y = newUpdatedBoundingBox.y + newUpdatedBoundingBox.height + space;
                    } else {
                        int yDelta = prevBoundingBox.y - boundingBox.y;
                        y = newUpdatedBoundingBox.y - yDelta;
                    }


                    if (boundingBox.x + boundingBox.width <= prevBoundingBox.x) {
                        x = newUpdatedBoundingBox.x - boundingBox.width;
                        if (x >= 10) {
                            x -= space;
                        }
                    } else if (boundingBox.x >= prevBoundingBox.x + prevBoundingBox.width) {
                        x = prevBoundingBox.x + prevBoundingBox.width + space;
                    }
                }

                if (x + boundingBox.width >= mat.cols() && x > mat.cols() - (boundingBox.x + boundingBox.width)){
                    x -= mat.cols() - (boundingBox.x + boundingBox.width);
                }

                if (y + boundingBox.height >= mat.rows()){
                    int yDelta = (y + boundingBox.height) - mat.rows();
                    y -= yDelta;
                }

                if (x < 0){
                    x = 0;
                }

                if (y < 0){
                    y = 0;
                }

                if (x < minX) {
                    minX = x;
                }

                if (y < minY) {
                    minY = y;
                }

                if (maxX < x + boundingBox.width) {
                    maxX = x + boundingBox.width;
                }

                if (maxY < y + boundingBox.height) {
                    maxY = y + boundingBox.height;
                }

                if(boundingBox.height < 0){
                    System.out.println(boundingBox.height);
                    if (boundingBox.y + 2 < mat.rows()) {
                        boundingBox.height = 2;
                    }else{
                        boundingBox.height = mat.rows() - boundingBox.y;
                    }
                }

                if (boundingBox.width < 0){
                    boundingBox.width = mat.cols();
                }

                prevBoundingBox = boundingBox;
                /*
                bug should be fixed in here. x coordinate plus width exceeds the image boundary.
                same happens with y coordinate plus height*/

                System.out.println("Size before validation check: " + boundingBox.width + "x" + boundingBox.height);

                if (x + boundingBox.width >= mat.cols() - 1){
                    boundingBox.width = (mat.cols() - 1) - x;
                }

                if (y + boundingBox.height >= mat.rows() - 1){
                    boundingBox.height = (mat.rows() - 1) - y;
                }

                if (boundingBox.y + boundingBox.height >= mat.rows()){
                    if (boundingBox.y >= 2){
                        boundingBox.y -= 2;
                    }else{
                        if(boundingBox.height >= 2){
                            boundingBox.height -= 1;
                        }
                    }
                }

                if (boundingBox.x + boundingBox.width >= mat.cols()){
                    if (boundingBox.x >= 2){
                        boundingBox.x -= 2;
                    }else{
                        if(boundingBox.width >= 2){
                            boundingBox.width -= 1;
                        }
                    }
                }

                System.out.println("Size after validation check: " + boundingBox.width + "x" + boundingBox.height);

                newUpdatedBoundingBox = new Rect(x, y, boundingBox.width, boundingBox.height);
                imageContentLocations.put(boundingBox, newUpdatedBoundingBox);
            }
//            Imgproc.rectangle(mat, boundingBox, new Scalar(255.0));
        }
//        saveImage(mat);

        Mat tempMatForExtractContent = Mat.zeros(mat.rows(), mat.cols(), CvType.CV_8UC1);
        Rect boundingBox;
        Rect targetBoundingBox;
        System.out.println("Copying region...");
        System.out.println("Size: " + mat.cols() + "x" + mat.rows());
        for (Map.Entry<Rect, Rect> info : imageContentLocations.entrySet()){
            boundingBox = info.getKey();
            System.out.println("X: " + boundingBox.x);
            System.out.println("Y: " + boundingBox.y);
            System.out.println("WIDTH: " + boundingBox.width);
            System.out.println("HEIGHT: " + boundingBox.height);

            targetBoundingBox = info.getValue();
            System.out.println("X: " + targetBoundingBox.x);
            System.out.println("Y: " + targetBoundingBox.y);
            System.out.println("WIDTH: " + targetBoundingBox.width);
            System.out.println("HEIGHT: " + targetBoundingBox.height);

            mat.submat(boundingBox).copyTo(tempMatForExtractContent.submat(targetBoundingBox));
//            Imgproc.rectangle(tempMatForExtractContent, new Rect(targetBoundingBox.x, targetBoundingBox.y, targetBoundingBox.width, targetBoundingBox.height), new Scalar(255.0));
        }

//        Imgproc.rectangle(tempMatForExtractContent, new Rect(minX, minY, maxX - minX, maxY - minY), new Scalar(255.0));
//        saveImage(tempMatForExtractContent);
        System.out.println("Extracting content...");

        if (minX > maxX){
            minX = 0;
            maxX = tempMatForExtractContent.cols() - 1;
        }

        if (minY > maxY){
            minY = 0;
            maxY = tempMatForExtractContent.rows() - 1;
        }

        System.out.println("Min Y: " + minY);
        System.out.println("Max Y: " + maxY);

        if (maxY - minY < 10 || maxX - minX < 10){
            return tempMatForExtractContent.clone();
        }

        return tempMatForExtractContent.submat(new Rect(minX, minY, maxX - minX, maxY - minY)).clone();
    }

    private Mat prepareImageForDisplay(Mat original) {
        int targetWidth = 368;
        int targetHeight = 448;

        System.out.println("Image received!");

        if (Components.getOrientation() == 0) {
            targetWidth = 448;
            targetHeight = 368;
        }

        /*
         *
         * Inverse image size automatically if the orientation change
         * */
        if (detectedNumbers.cols() != targetWidth){
            detectedNumbers = Mat.zeros(targetHeight, targetWidth, CvType.CV_8UC1);
        }

        if (canvas.cols() != targetWidth) {
            canvas = Mat.zeros(targetHeight, targetWidth, CvType.CV_8UC1);
        }

//        saveImage(original);

//        Mat noiseRemoved = removeNoise(original);
//        saveImage(noiseRemoved);

        Mat bw = convertToBlackAndWhite(original);
//        saveImage(bw);
        bw = getNewCoordinateForImageContent(bw);
//        saveImage(bw);
        bw = alignLinesInXAxis(bw);
//        saveImage(bw);

        canvas.setTo(new Scalar(0));

        System.out.println("Transforming image...");
        TransformedImage transformedImage = transformImageForDisplay(bw, targetWidth, targetHeight);

        int newPosX = transformedImage.getNewPosX();
        int newPosY = transformedImage.getNewPosY();
        System.out.println("Transformed Image:");
        System.out.println(transformedImage);

        Mat resized = transformedImage.getImage();

        if (Components.getNoteApplication() == Constants.YUAN){
            Mat tempResizedImage = new Mat();
            double fx = 0.7D;
            double fy = 0.7D;

            Imgproc.resize(resized, tempResizedImage, new Size(), fx, fy, Imgproc.INTER_LINEAR);

            newPosX = (canvas.cols() / 2) - (tempResizedImage.cols() / 2);
            newPosY = (canvas.rows() / 2) - (tempResizedImage.rows() / 2);
            resized = tempResizedImage.clone();
        }

        if (newPosY > 30) {
            newPosY = 30;
        }

//        saveImage(resized);

        org.opencv.core.Rect roi = new org.opencv.core.Rect(newPosX, newPosY, resized.cols(), resized.rows());
        canvas.setTo(new Scalar(0));
        if (roi.height > 10){
            Mat targetArea = canvas.submat(roi);
            resized.copyTo(targetArea);

            System.out.println("Placed resized image on canvas.");


            if (Components.isDoCalculation()){

                Mat result = extractLines(canvas);

                boolean isCalculationTrick = numberList.size() > 1 && numberOfLines <= 4;
                System.out.println("Number of number lines: " + numberOfLines);

                // when no lines detected clear the previous number stored
                if (numberOfLines == 0){
                    currentDisplayingNumber = "";
                }

                if (numberList.size() > 0) {
                    if (latestDisplayedNumber != numberList.get(numberList.size() - 1)) {
                        latestDisplayedNumber = numberList.get(numberList.size() - 1);
                        currentDisplayingNumber = String.valueOf(latestDisplayedNumber);
                        //new Handler(Looper.getMainLooper()).postDelayed(() -> currentDisplayingNumber = "", Components.getDelay() * 1000L);
                    }
                }

                Size textSize = Imgproc.getTextSize(currentDisplayingNumber, Imgproc.FONT_HERSHEY_SIMPLEX, 1.4, 2, null);
                int textWidth = (int)textSize.width;
                int textHeight = (int)textSize.height;
                int centerX = (canvas.cols() - textWidth) / 2;

                detectedNumbers.setTo(new Scalar(0));

                    /*
                    calculate number displaying position along the y axis and 20 is the space between the image and numbers displaying
                     */
                int numbersDisplayingPosY = newPosY + resized.rows() + 45;

                if (numbersDisplayingPosY + textHeight + result.rows() >= targetHeight){
                    numbersDisplayingPosY = targetHeight - (textHeight + result.rows());
                }

                Rect answerPositionRect = new Rect(0, numbersDisplayingPosY, result.cols(), textHeight + result.rows());

                System.out.println("Detected Numbers Image Size: " + detectedNumbers.cols() + "x" + detectedNumbers.rows());
                Imgproc.putText(detectedNumbers, currentDisplayingNumber, new Point(centerX, answerPositionRect.y), Imgproc.FONT_HERSHEY_SIMPLEX, 1.4, new Scalar(255), 2);
                Core.bitwise_or(detectedNumbers, canvas, canvas);

                // add text height to get the Y position for the answer. set answer position rect height to result's height
                answerPositionRect.y += textHeight;
                answerPositionRect.height = result.rows();
                if (numberList.size() == 3) {
                    System.out.println("Show answer");
                    Imgproc.threshold(canvas, canvas, 50, 255, Imgproc.THRESH_TOZERO);
                    System.out.println("canvas size: " + canvas.cols() +  'x' + canvas.rows());
                    System.out.println("result size: " + result.cols() +  'x' + result.rows());
                    System.out.println("answer position data: " + answerPositionRect.width +  'x' + answerPositionRect.height + " | " + "X: " + answerPositionRect.x + " Y:" + answerPositionRect.y);
                    result.copyTo(canvas.submat(answerPositionRect));
                }
            }
        }


        if (Components.getNoteApplication() == Constants.YUAN && Components.getOrientation() == 1){
            for (int y = 0; y < 50; y++){
                Mat row = canvas.row(y);
                MatOfDouble mean = new MatOfDouble();
                MatOfDouble std = new MatOfDouble();

                Core.meanStdDev(row, mean, std);
                if (std.toArray()[0] > 127){
                    row.setTo(new Scalar(0, 0, 0));
                }
            }

            for (int y = canvas.rows() - 50; y < canvas.rows(); y++){
                Mat row = canvas.row(y);
                MatOfDouble mean = new MatOfDouble();
                MatOfDouble std = new MatOfDouble();

                Core.meanStdDev(row, mean, std);
                if (std.toArray()[0] > 127){
                    row.setTo(new Scalar(0, 0, 0));
                }
            }
        }

        if (Components.getOrientation() == 0){
            Core.rotate(canvas, canvas, Core.ROTATE_90_CLOCKWISE);
        }

//        saveImage(canvas);

        return canvas;
    }

    private void sendBytes(Mat mat){
        byte[] bytes = new byte[364 * 448];
        mat.get(0, 0, bytes);
        System.out.println(Arrays.toString(bytes));

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

        String calculationResult = "TOTAL: " + this.total + " " + imagePosX + " " + imagePosY + "\n";

        System.out.println("Sending header and data!");
        System.out.println("Calculation Result: " + calculationResult);
        System.out.println("Total Chunks sending " + totalChunks + " of size " + chunkSize);
        if (outputStream != null){
            try {
                outputStream.write(connectionHeader.getBytes(StandardCharsets.US_ASCII));
                outputStream.flush();

                Components.setConnectionStatus(1);

                outputStream.write(header.getBytes(StandardCharsets.US_ASCII));
                outputStream.flush();

                outputStream.write(calculationResult.getBytes(StandardCharsets.US_ASCII));
                outputStream.flush();

                //outputStream.write(bytes);
                //outputStream.flush();

//                outputStream.write("\n".getBytes(StandardCharsets.US_ASCII))
//                outputStream.flush();

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

                    outputStream = null;
                    connectedToServer = false;
                    connectToServer();

                }catch(IOException e){
                    e.printStackTrace();
                }
            }
        }
    }

    private void prepareImageAndSend(Mat bitmap){
        Mat image = prepareImageForDisplay(bitmap);
        System.out.println("Image received.");
        sendBytes(image);
    }

    public ScreenRecorderService() {
//        textRecognition = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    }

    private void createVirtualDisplay() throws IOException {
        imageThread = new HandlerThread("ImageReaderThread");
        imageThread.start();
        handler = new Handler(imageThread.getLooper());

        captureTexture = new SurfaceTexture(10);
        captureTexture.setDefaultBufferSize(WIDTH, HEIGHT);

//        captureSurface = new Surface(captureTexture);
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

        testImageMat = new Mat(testBitmap.getHeight(), testBitmap.getWidth(), CvType.CV_8UC4);

        imageReader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2);
        Mat imageMat = Mat.zeros(imageReader.getHeight(), imageReader.getWidth(), CvType.CV_8UC4);
        final Mat[] gray = {new Mat()};
        final Mat[] gray1 = {new Mat()};

        Bitmap bitmap = Bitmap.createBitmap(imageReader.getWidth(), imageReader.getHeight(), Bitmap.Config.ARGB_8888);

        captureRunnable = new Runnable() {
            @Override
            public void run() {

                if(!isProcessing.compareAndSet(false, true)){
                    return;
                }

                Image latestImage = imageReader.acquireLatestImage();
                if (latestImage != null) {
                    PixelCopy.request(imageReader.getSurface(), bitmap, copyResult -> {
                        if (copyResult == PixelCopy.SUCCESS) {
                            boolean debug = true;

                            if (!debug) {
                                Utils.bitmapToMat(bitmap, imageMat);
                                Imgproc.cvtColor(imageMat, gray[0], Imgproc.COLOR_RGBA2GRAY);
                                if (gray[0].cols() > 250) {
                                    if (Components.getNoteApplication() == Constants.YUAN) {
                                        System.out.println("Passing image for processing...");
                                        System.out.println(gray[0].cols());
                                        System.out.println(gray[0].rows());
                                        gray[0] = gray[0].submat(150, gray[0].rows() - 280, 35, gray[0].cols() - 35).clone();
                                    } else {
                                        gray[0] = gray[0].submat(0, gray[0].rows(), 2, gray[0].cols() - 2).clone();
                                    }
                                }
                            }else {
                                Utils.bitmapToMat(testBitmap, testImageMat);
                                Imgproc.cvtColor(testImageMat, gray1[0], Imgproc.COLOR_RGBA2GRAY);
                                if (gray1[0].cols() > 150) {
                                    if (Components.getNoteApplication() == Constants.YUAN) {
                                        gray1[0] = gray1[0].submat(150, gray1[0].rows() - 280, 60, gray1[0].cols() - 35).clone();
                                    } else {
                                        gray1[0] = gray1[0].submat(0, gray1[0].rows(), 5, gray1[0].cols() - 5).clone();
                                    }
                                }
                            }

                            if (!debug) {
                                if (connectedToServer) {
                                    prepareImageAndSend(gray[0]);
                                }
                            }else{
                                if (!connectedToServer) {
                                    prepareImageAndSend(gray1[0]);
                                }
                            }
                        }
                        latestImage.close();
                        isProcessing.set(false);
                        //handler.post(captureRunnable);
                    }, handler);
                }else{
                    isProcessing.set(false);
                    //handler.post(this);
                }
                handler.postDelayed(this, 2500);
            }
        };

        handler.post(captureRunnable);

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

    public static void saveImageToPublicDirectory(Context context, Bitmap bitmap, String fileName) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MyAppImages"); // Your folder name

        Uri uri;
        OutputStream outputStream = null;

        try {
            // Insert into MediaStore
            uri = context.getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

            if (uri != null) {

                // Open output stream
                outputStream = context.getContentResolver().openOutputStream(uri);

                // Write the bitmap into the stream
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream);
            }

        } catch (Exception e) {
            e.printStackTrace();

        } finally {
            if (outputStream != null) {
                try { outputStream.close(); } catch (Exception ignored) {}
            }
        }
    }

    private void createNotification(){
        String CHANNEL_ID = "Screen Record Channel";
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

    public void startReceiverThread(){
        new Thread(() -> {
            try{
                DataInputStream dis = new DataInputStream(inputStream);
                while(!socket.isClosed()){
                    String response = dis.readUTF();
                    System.out.println("SERVER RESPONSE: " + response);
                    System.out.println(response.equals("NUMBERS"));
                    Components.setDoCalculation(response.equals("NUMBERS"));
                }
            }catch(IOException e){
                e.printStackTrace();
            }
        }).start();
    }

    public void connectToServer(){
        if (!connectedToServer) {
            networkConnectionHandlerThread = new HandlerThread("Network Connection Handler Thread");
            networkConnectionHandlerThread.start();
            networkConnectionHandler = new Handler(networkConnectionHandlerThread.getLooper());

            networkConnectionHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (startedScreenRecording) {
                        try {
                            System.out.println("Trying to connect to the server...");
                            socket = new Socket();
                            socket.connect(new InetSocketAddress("192.168.4.1", 5000), 5000);
//                            socket.connect(new InetSocketAddress("192.168.43.133", 5000), 5000);
                            outputStream = socket.getOutputStream();
                            inputStream = socket.getInputStream();
                            Components.setConnectionStatus(1);
                            connectedToServer = true;
                            startReceiverThread();
                            System.out.println("Connected to the server");
                        } catch (IOException exception) {
                            exception.printStackTrace();
                            Components.setConnectionStatus(0);
                            System.out.println("Trying to connect to the server in 1 sec...");
                            networkConnectionHandler.postDelayed(this, 1000);
                        }
                    }
                }
            });
        }
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

        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        System.out.println("Setting up everything...");
        try {
            digitClassifier = new DigitClassifier(getApplicationContext());
        } catch (IOException e) {
            e.printStackTrace();
        }

        originalImageCleaningKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(10, 10));

        if (Components.getNoteApplication() == Constants.YUAN){
            writingAreaFilterKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(290, 70));
        }else {
            writingAreaFilterKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(180, 70));
        }

        charExtractingKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(4, 2));
        lineExtractingKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(150, 1));

        kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(180, 4));
        lineAligningKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(110, 10));

        erosionKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(5, 15));

        huionBlackAndWhiteMask = new Mat();

        canvas = Mat.zeros(448, 368, CvType.CV_8UC1);
        rgba = Mat.zeros(canvas.rows(), canvas.cols(), CvType.CV_8UC4);
        outputBitmap = Bitmap.createBitmap(canvas.cols(), canvas.rows(), Bitmap.Config.ARGB_8888);
        digitMat = Mat.zeros(32, 368, CvType.CV_8UC1);
        calculationResult = Mat.zeros(32, 368, CvType.CV_8UC1);
        detectedNumbers = Mat.zeros(448, 368, CvType.CV_8UC1);

        yuanMorphKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(2, 2));

        rotated = Mat.zeros(368, 448, CvType.CV_8UC1);

        InputStream is = getApplicationContext().getResources().openRawResource(R.raw.yuan10);

        testBitmap = BitmapFactory.decodeStream(is);

        numbersRecorded = new ArrayList<>();

//        Components.setOrientation(0);

        startedScreenRecording = true;

        if (!threadStarted){
            connectToServer();
            threadStarted = true;
        }

        mediaProjection = projectionManager.getMediaProjection(resultCode, data);

        try {
            createVirtualDisplay();
        }catch (IOException exception){
            exception.printStackTrace();
        }
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
        startedScreenRecording = false;

        if (captureTexture != null){
            captureTexture.release();
        }

        if (captureVirtualDisplay != null){
            captureVirtualDisplay.release();
        }

        if (networkConnectionHandlerThread != null){
            networkConnectionHandlerThread.quitSafely();
        }

        if (imageThread != null){
            imageThread.quitSafely();
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

        if (imageReader != null){
            imageReader.close();
        }

        stopForeground(true);
    }
}