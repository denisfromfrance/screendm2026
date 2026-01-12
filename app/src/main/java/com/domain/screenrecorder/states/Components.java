package com.domain.screenrecorder.states;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.domain.screenrecorder.R;
import com.domain.screenrecorder.threads.ImagePullThread;

public class Components {
    private static TextView connectionStatus;
    private static View connectionStatusIcon;

    private static int connectionStatusCode = 0;

    private static int noteApplication = Constants.HUIONNOTE;

    private static int orientation;

    private static ImagePullThread imagePullThread;

    private static Context applicationContext;

    private static boolean doCalculation = false;

    private static int delay = 5;

    public static void setApplicationContext(Context context){
        if (applicationContext == null){
            applicationContext = context;
        }
    }

    public static int getNoteApplication() {
        return noteApplication;
    }

    public static void setNoteApplication(int noteApplication) {
        Components.noteApplication = noteApplication;
    }

    public static boolean isDoCalculation() {
        return doCalculation;
    }

    public static void setDoCalculation(boolean doCalculation) {
        Components.doCalculation = doCalculation;
    }

    public static void setConnectionStatus(TextView textView){
        if (connectionStatus == null) {
            connectionStatus = textView;
        }
    }

    public static void setThread(ImagePullThread thread){
        imagePullThread = thread;
    }

    public static void setConnectionStatusIcon(View iconView){
        if (connectionStatusIcon == null) {
            connectionStatusIcon = iconView;
        }
    }

    public static ImagePullThread getThread(){
        return imagePullThread;
    }


    public static void setConnectionStatus(int isConnected){
        connectionStatusCode = isConnected;

        Intent connectionUpdateIntent = new Intent("connection_status_update");
        connectionUpdateIntent.putExtra("status", isConnected);
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(connectionUpdateIntent);

        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {

            }
        });
    }

    public static int getConnectionStatus(){
        return connectionStatusCode;
    }

    public static void setOrientation(int screenOrientation){
        orientation = screenOrientation;
    }

    public static int getOrientation(){
        return orientation;
    }

    public static int getDelay() {
        return delay;
    }

    public static void setDelay(int delay) {
        Components.delay = delay;
    }
}
