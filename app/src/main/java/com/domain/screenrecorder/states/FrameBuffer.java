package com.domain.screenrecorder.states;

import org.opencv.core.CvType;
import org.opencv.core.Mat;

public class FrameBuffer {
//    public final byte[] data;
    public final Mat mat;
    public volatile boolean ready = false;
    public FrameBuffer(int width, int height){
//        data = new byte[size];
        mat = Mat.zeros(height, width, CvType.CV_8UC1);
    }
}
