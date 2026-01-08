package com.domain.screenrecorder.utils;

import com.domain.screenrecorder.states.FrameBuffer;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

import java.util.concurrent.atomic.AtomicInteger;

public class Consumer {
    int bufferCount = 3;
    FrameBuffer[] buffers = new FrameBuffer[bufferCount];

    final AtomicInteger writeIndex = new AtomicInteger(0);
    final AtomicInteger latestReadyIndex = new AtomicInteger(-1);
    int frameSize;

    public Consumer(int frameWidth, int frameHeight, int channels){
        frameSize = frameWidth * frameHeight * channels;

        for (int i = 0; i < bufferCount; i++){
            buffers[i] = new FrameBuffer(frameWidth, frameHeight);
        }
    }

    public void captureFrame(Mat incoming){
        int index = writeIndex.getAndIncrement() % bufferCount;

        FrameBuffer buffer = buffers[index];

        incoming.copyTo(buffer.mat);
        buffer.ready = true;

        latestReadyIndex.set(index);
    }

    public FrameBuffer getLatestFrame(){
        int index = latestReadyIndex.getAndSet(-1);
        if (index == -1){
            return null;
        }
        FrameBuffer frameBuffer = buffers[index];
        frameBuffer.ready = false;
        return frameBuffer;
    }
}
