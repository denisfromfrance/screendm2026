package com.domain.screenrecorder;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.domain.screenrecorder.services.ScreenRecorderService;
import com.domain.screenrecorder.states.Components;
import com.domain.screenrecorder.threads.ImagePullThread;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE = 1000;
    private MediaProjectionManager projectionManager;

    TextView connectionStatus;
    View connectionStatusIcon;

    ImagePullThread imagePullThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        connectionStatus = findViewById(R.id.connectionstatus);
        connectionStatusIcon = findViewById(R.id.connectionstatusicon);

        imagePullThread = new ImagePullThread();

        Components.setApplicationContext(getApplicationContext());
        Components.setConnectionStatus(connectionStatus);
        Components.setConnectionStatusIcon(connectionStatusIcon);

        Components.setThread(imagePullThread);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        Button startButton = findViewById(R.id.startButton);
        startButton.setOnClickListener(v -> {
            Intent intent = projectionManager.createScreenCaptureIntent();
            startActivityForResult(intent, REQUEST_CODE);
        });

        Button stopButton = findViewById(R.id.stopButton);
        stopButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, ScreenRecorderService.class);
            stopService(intent);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK){
            Intent service = new Intent(this, ScreenRecorderService.class);
            service.putExtra("resultCode", resultCode);
            service.putExtra("data", data);
            System.out.println("Starting service...");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.startForegroundService(this, service);
            }else {
                startService(service);
            }
        }
    }
}