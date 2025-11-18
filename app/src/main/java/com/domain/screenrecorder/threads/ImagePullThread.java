package com.domain.screenrecorder.threads;

import android.graphics.Bitmap;
import android.media.MediaCodec;

import com.domain.screenrecorder.states.Components;
import com.google.mlkit.vision.common.InputImage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ImagePullThread extends Thread{
    Socket socket;
    OutputStream outputStream;
    MediaCodec encoder;

    public String textData;

    private boolean started = false;

    private ConcurrentLinkedQueue<Bitmap> imageQueue;

    public ImagePullThread(MediaCodec encoder){
        this.encoder = encoder;
    }

    public ImagePullThread(){
        textData = "";
        imageQueue = new ConcurrentLinkedQueue<>();
    }

    @Override
    public void run() {
        try {
            started = true;
            connectToServer();
            Components.setConnectionStatus(1);
//            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            while (started) {
                if (!textData.equals("")) {
                    sendDataToServer();
                }

                if (imageQueue.size() != 0){
                    prepareImageAndSend(imageQueue.poll(), 160, 128);
                }

            }
        }catch (IOException exception){
            Components.setConnectionStatus(-1);
            exception.printStackTrace();
        }
    }

    public void terminateConnection(){
        started = false;
        try {
            if (socket != null) socket.close();
        }catch(IOException exception){
            exception.printStackTrace();
        }
        Components.setConnectionStatus(0);
    }

    public void connectToServer() throws IOException{
        socket = new Socket();
        socket.connect(new InetSocketAddress("192.168.4.1", 5000), 5000);
        outputStream = socket.getOutputStream();
    }

    public void sendDataToServer(){
        try{
            if (outputStream != null){
                System.out.println("Sending text data...");
                outputStream.write(this.textData.getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                this.textData = "";
                System.out.println("Text data sent!");
            }
        }catch(IOException e){
            e.printStackTrace();
        }
    }

    public void sendFrameToServer(byte[] data){
        try{
            if (outputStream != null){
                outputStream.write(data);
                outputStream.flush();
            }
        }catch(IOException e){
            e.printStackTrace();
        }
    }

    public void setTextData(String text){
        this.textData = text + "\n";
        System.out.println("Received text: " + text);
    }

    private byte[] intToByteArray(int value) {
        return new byte[] {
                (byte)(value >> 24),
                (byte)(value >> 16),
                (byte)(value >> 8),
                (byte)value
        };
    }

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
                int bw = (gray < 128) ? 0xFF000000 : 0xFFFFFFFF;

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
        int chunkSize = 1024;
        int totalChunks = (int)Math.ceil(bytes.length / (double)chunkSize);
        String header = "IMG " + totalChunks + " " + bytes.length;
        try {
            outputStream.write(header.getBytes(StandardCharsets.UTF_8));

            for (int i = 0; i < totalChunks; i++){
                int start = i * chunkSize;
                int length = Math.min(chunkSize, bytes.length - start);
                outputStream.write(bytes, start, length);
                outputStream.flush();
            }
        }catch(IOException exception){
            exception.printStackTrace();
        }
    }

    private void prepareImageAndSend(Bitmap bitmap, int width, int height){
        Bitmap image = prepareImageForDisplay(bitmap, width, height);
        sendBytes(bitmapTo1BitArray(image));
    }

    public void addImageToQueue(Bitmap bitmap){
        imageQueue.add(bitmap);
    }


//    private void sendImage(Bitmap bitmap){
//        // 2. Resize (optional – match your OLED resolution)
//        Bitmap resized = Bitmap.createScaledBitmap(bitmap, 128, 160, true);
//
//        // 3. Convert to JPEG
//        ByteArrayOutputStream baos = new ByteArrayOutputStream();
//        resized.compress(Bitmap.CompressFormat.JPEG, 90, baos);
//        byte[] jpgBytes = baos.toByteArray();
//
//        int chunkSize = 1024;
//        int offset = 0;
//        try {
//            while (offset < jpgBytes.length) {
//                int end = Math.min(offset + chunkSize, jpgBytes.length);
//                byte[] chunk = new byte[end - offset];
//                System.arraycopy(jpgBytes, offset, chunk, 0, chunk.length);
//
//                // ---- SEND CHUNK LENGTH (4 bytes) ----
//                outputStream.write(intToByteArray(chunk.length));
//
//                // ---- SEND CHUNK DATA ----
//                outputStream.write(chunk);
//
//                offset = end;
//            }
//
//            // optional end marker (chunk length = 0)
//            outputStream.write(intToByteArray(0));
//
//            outputStream.flush();
//        }catch(IOException exception){
//            exception.printStackTrace();
//        }
//    }
    
    
}
