package com.domain.screenrecorder.states;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

import com.domain.screenrecorder.R;
import com.domain.screenrecorder.threads.ImagePullThread;

public class Components {
    private static TextView connectionStatus;
    private static View connectionStatusIcon;

    private static int orientation;

    private static ImagePullThread imagePullThread;

    private static Context applicationContext;

    public static void setApplicationContext(Context context){
        if (applicationContext == null){
            applicationContext = context;
        }
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
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (isConnected == 1){
                    connectionStatus.setText("Connected!");
                    connectionStatus.setTextColor(Color.GREEN);

                    connectionStatusIcon.setBackground(ResourcesCompat.getDrawable(applicationContext.getResources(), R.drawable.connectionstatusdrawableconnected, null));
                }else if(isConnected == 0){
                    connectionStatus.setText("Disconnected!");
                    connectionStatus.setTextColor(Color.RED);

                    connectionStatusIcon.setBackground(ResourcesCompat.getDrawable(applicationContext.getResources(), R.drawable.connectionstatusdrawable, null));
                }else{
                    connectionStatus.setText("Connection Failed!");
                    connectionStatus.setTextColor(Color.parseColor("#FFAA00"));

                    connectionStatusIcon.setBackground(ResourcesCompat.getDrawable(applicationContext.getResources(), R.drawable.connectionstatusfaileddrawable, null));
                }
            }
        });
    }

    public static void setOrientation(int screenOrientation){
        orientation = screenOrientation;
    }

    public static int getOrientation(){
        return orientation;
    }
}
