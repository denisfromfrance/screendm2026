package com.domain.screenrecorder.utils;

import com.domain.screenrecorder.states.FrameBuffer;

public class ConsumerThread extends Thread {
    Consumer consumer;
    boolean running = false;
    public ConsumerThread(Consumer consumer){
        this.consumer = consumer;
    }

    public void stopThread(){
        this.running = false;
    }

    @Override
    public void run() {
        running = true;
        while (running){
            FrameBuffer frameBuffer = consumer.getLatestFrame();

        }
    }
}
