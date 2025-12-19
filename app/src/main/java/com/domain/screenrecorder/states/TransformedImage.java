package com.domain.screenrecorder.states;

import androidx.annotation.NonNull;

import org.opencv.core.Mat;

public class TransformedImage {
    private int newPosX, newPosY;
    double width, height;
    private Mat image;
    private boolean isRotated = false;
    public TransformedImage(Mat image, int newPosX, int newPosY, double width, double height, boolean isRotated){
        this.image = image;
        this.newPosX = newPosX;
        this.newPosY = newPosY;
        this.width = width;
        this.height = height;
        this.isRotated = isRotated;
    }

    public int getNewPosX() {
        return newPosX;
    }

    public int getNewPosY() {
        return newPosY;
    }

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }

    public Mat getImage() {
        return image;
    }

    public boolean isRotated(){
        return this.isRotated;
    }

    @NonNull
    @Override
    public String toString() {
        return String.format("x: %d, y: %d, WIDTH: %.2f, HEIGHT: %.2f", newPosX, newPosY, width, height);
    }
}
