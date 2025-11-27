//package com.domain.screenrecorder.utils;
//
//import android.content.Context;
//import android.graphics.Bitmap;
//import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions;
//import com.google.mediapipe.tasks.vision.text.TextRecognizer;
//import com.google.mediapipe.tasks.vision.text.TextRecognizerResult;
//import com.google.mediapipe.tasks.core.BaseOptions;
//import com.google.mediapipe.tasks.vision.core.BitmapImageBuilder;
//
//public class OCRHelper {
//
//    private TextRecognizer recognizer;
//
//    public OCRHelper(Context context) {
//        try {
//            BaseOptions baseOptions = BaseOptions.builder()
//                    .setModelAssetPath("text_recognizer.task") // model in assets
//                    .build();
//
//            TextRecognizer.TextRecognizerOptions options =
//                    TextRecognizer.TextRecognizerOptions.builder()
//                            .setBaseOptions(baseOptions)
//                            .build();
//
//            recognizer = TextRecognizer.createFromOptions(context, options);
//
//        } catch (Exception ex) {
//            ex.printStackTrace();
//        }
//    }
//
//    public String extractText(Bitmap bitmap) {
//        BitmapImageBuilder imageBuilder = new BitmapImageBuilder(bitmap);
//
//        TextRecognizerResult result =
//                recognizer.recognize(imageBuilder.build(), ImageProcessingOptions.builder().build());
//
//        if (result == null || result.text().isEmpty()) {
//            return "";
//        }
//
//        return result.text(); // Full recognized text
//    }
//}