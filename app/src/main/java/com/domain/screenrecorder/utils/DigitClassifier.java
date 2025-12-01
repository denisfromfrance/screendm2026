package com.domain.screenrecorder.utils;
/*
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.tensorflow.lite.Interpreter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

public class DigitClassifier {

    private final Interpreter interpreter;

    public DigitClassifier(Context context) throws IOException {
        Interpreter.Options options = new Interpreter.Options();
        options.setNumThreads(4); // adjust based on device
        interpreter = new Interpreter(loadModelFile(context, "mnist_model_backup.tflite"), options);
    }

    private ByteBuffer loadModelFile(Context context, String modelName) throws IOException {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(modelName);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    // Preprocess image: resize to 28x28, grayscale, normalize to [0,1]
    private float[][][][] preprocess(Mat inputMat) {
//        Mat gray = new Mat();
//        Imgproc.cvtColor(inputMat, gray, Imgproc.COLOR_BGR2GRAY);

//        Mat resized = new Mat();
//        Imgproc.resize(inputMat, resized, new Size(28, 28));

        float[][][][] input = new float[1][28][28][1];
        for (int y = 0; y < 28; y++) {
            for (int x = 0; x < 28; x++) {
                double pixel = inputMat.get(y, x)[0];
                input[0][y][x][0] = (float) (pixel / 255.0); // normalize
            }
        }
        return input;
    }

    // Predict digit
    public int classify(Mat digitMat) {
        float[][] output = new float[1][10]; // 10 classes: 0-9
        float[][][][] input = preprocess(digitMat);

        interpreter.run(input, output);

        // Find max probability
        int best = 0;
        float max = output[0][0];
        for (int i = 1; i < 10; i++) {
            if (output[0][i] > max) {
                max = output[0][i];
                best = i;
            }
        }
        return best;
    }
}*/
