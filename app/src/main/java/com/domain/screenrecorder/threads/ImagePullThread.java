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

public class ImagePullThread extends Thread{
    Socket socket;
    OutputStream outputStream;
    MediaCodec encoder;

    InputImage inputImage;
    Bitmap bitmap;

    public String textData;

    private boolean started = false;

    public ImagePullThread(MediaCodec encoder){
        this.encoder = encoder;
    }

    public ImagePullThread(){
        textData = "";
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
//                int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000);
//                if (outputIndex >= 0) {
//                    ByteBuffer encodedData = encoder.getOutputBuffer(outputIndex);
//                    if (encodedData != null) {
//                        byte[] frameData = new byte[bufferInfo.size];
//                        encodedData.get(frameData);
////                        sendFrameToServer(frameData);
//                    }
//                    encoder.releaseOutputBuffer(outputIndex, false);
//                }
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

    public void sendImage(Bitmap bitmap){
        // 2. Resize (optional – match your OLED resolution)
        Bitmap resized = Bitmap.createScaledBitmap(bitmap, 128, 160, true);

        // 3. Convert to JPEG
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        resized.compress(Bitmap.CompressFormat.JPEG, 90, baos);
        byte[] jpgBytes = baos.toByteArray();

        int chunkSize = 1024;
        int offset = 0;
        try {
            while (offset < jpgBytes.length) {
                int end = Math.min(offset + chunkSize, jpgBytes.length);
                byte[] chunk = new byte[end - offset];
                System.arraycopy(jpgBytes, offset, chunk, 0, chunk.length);

                // ---- SEND CHUNK LENGTH (4 bytes) ----
                outputStream.write(intToByteArray(chunk.length));

                // ---- SEND CHUNK DATA ----
                outputStream.write(chunk);

                offset = end;
            }

            // optional end marker (chunk length = 0)
            outputStream.write(intToByteArray(0));

            outputStream.flush();
        }catch(IOException exception){
            exception.printStackTrace();
        }
    }
    
    
}
