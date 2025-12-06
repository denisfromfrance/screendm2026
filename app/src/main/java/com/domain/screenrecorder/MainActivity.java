package com.domain.screenrecorder;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
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
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;

import org.opencv.*;
import org.opencv.android.OpenCVLoader;

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
        OpenCVLoader.initLocal();

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

        MaterialButton portraitToggleButton = findViewById(R.id.portrait);
        MaterialButton landscapeToggleButton = findViewById(R.id.landscape);

        portraitToggleButton.setBackgroundColor(getResources().getColor(R.color.light_gray));
        landscapeToggleButton.setBackgroundColor(getResources().getColor(R.color.dark_gray));


        portraitToggleButton.addOnCheckedChangeListener(new MaterialButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(MaterialButton button, boolean isChecked) {
                Drawable icon = ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_portrait);
                if (isChecked){
                    icon.setTint(ContextCompat.getColor(getApplicationContext(), R.color.white));
                    button.setBackgroundColor(getResources().getColor(R.color.light_gray));
                    landscapeToggleButton.setBackgroundColor(getResources().getColor(R.color.dark_gray));
                    landscapeToggleButton.setChecked(false);
                    Components.setOrientation(1); // portrait orientation
                }else{
                    icon.setTint(ContextCompat.getColor(getApplicationContext(), R.color.black));
                    button.setBackgroundColor(getResources().getColor(R.color.dark_gray));
                    landscapeToggleButton.setBackgroundColor(getResources().getColor(R.color.light_gray));
                    landscapeToggleButton.setChecked(true);
                    Components.setOrientation(0); // landscape orientation
                }
                button.setIcon(icon);
            }
        });

        landscapeToggleButton.addOnCheckedChangeListener(new MaterialButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(MaterialButton button, boolean isChecked) {
                Drawable icon = ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_landscape);
                if (isChecked){
                    icon.setTint(ContextCompat.getColor(getApplicationContext(), R.color.white));
                    button.setBackgroundColor(getResources().getColor(R.color.light_gray));
                    portraitToggleButton.setBackgroundColor(getResources().getColor(R.color.dark_gray));
                    portraitToggleButton.setChecked(false);
                    Components.setOrientation(0); // landscape orientation
                }else{
                    icon.setTint(ContextCompat.getColor(getApplicationContext(), R.color.black));
                    button.setBackgroundColor(getResources().getColor(R.color.dark_gray));
                    portraitToggleButton.setBackgroundColor(getResources().getColor(R.color.light_gray));
                    portraitToggleButton.setChecked(true);
                    Components.setOrientation(1); // portrait orientation
                }
                button.setIcon(icon);
                System.out.println("Button icon changed!");
            }
        });

        MaterialButtonToggleGroup materialButtonToggleGroup = findViewById(R.id.toggle_button_group);
        materialButtonToggleGroup.addOnButtonCheckedListener(new MaterialButtonToggleGroup.OnButtonCheckedListener() {
            @Override
            public void onButtonChecked(MaterialButtonToggleGroup group, int checkedId, boolean isChecked) {
                if (isChecked){
                    if (checkedId == R.id.portrait){

                    }else if(checkedId == R.id.landscape){

                    }
                }
            }
        });

        Components.setOrientation(1);
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