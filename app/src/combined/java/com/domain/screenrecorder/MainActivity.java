package com.domain.screenrecorder;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.media.Image;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.domain.screenrecorder.services.ScreenRecorderService;
import com.domain.screenrecorder.states.Components;
import com.domain.screenrecorder.states.Constants;
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

    Spinner spinner;

    ImagePullThread imagePullThread;

    Switch detectNumbers;

    private Drawable resize(int drawableRes, double scaleFactor){
        Bitmap original = BitmapFactory.decodeResource(getResources(), drawableRes);
        int newWidth = (int)(original.getWidth() * scaleFactor);
        int newHeight = (int)(original.getHeight() * scaleFactor);
        Bitmap scaled = Bitmap.createScaledBitmap(original, newWidth, newHeight, true);
        return new BitmapDrawable(getResources(), scaled);
    }

    private BroadcastReceiver connectionStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra("status", -1);
            System.out.println("Connection status changed...");
            if (status == 1){
                System.out.println("Connected!");
                connectionStatus.setText("Connected!");
                connectionStatus.setTextColor(Color.GREEN);

                connectionStatusIcon.setBackground(ResourcesCompat.getDrawable(getApplicationContext().getResources(), R.drawable.connectionstatusdrawableconnected, null));
            }else if(status == 0){
                System.out.println("Disconnected!");
                connectionStatus.setText("Disconnected!");
                connectionStatus.setTextColor(Color.RED);

                connectionStatusIcon.setBackground(ResourcesCompat.getDrawable(getApplicationContext().getResources(), R.drawable.connectionstatusdrawable, null));
            }else{
                connectionStatus.setText("Connection Failed!");
                System.out.println("Connection Failed!");
                connectionStatus.setTextColor(Color.parseColor("#FFAA00"));

                connectionStatusIcon.setBackground(ResourcesCompat.getDrawable(getApplicationContext().getResources(), R.drawable.connectionstatusfaileddrawable, null));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        connectionStatus = findViewById(R.id.connectionstatus);
        connectionStatusIcon = findViewById(R.id.connectionstatusicon);

//        Toolbar toolbar = findViewById(R.id.toolbar);
//        setSupportActionBar(toolbar);

        imagePullThread = new ImagePullThread();
        OpenCVLoader.initLocal();

        Components.setApplicationContext(getApplicationContext());
        Components.setConnectionStatus(connectionStatus);
        Components.setConnectionStatusIcon(connectionStatusIcon);
        Components.setThread(imagePullThread);
        Components.setOrientation(1);
        Components.setNoteApplication(Constants.HUIONNOTE);

        projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        detectNumbers = findViewById(R.id.detectNumbers);
        detectNumbers.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                Components.setDoCalculation(b);
            }
        });

        Button startButton = findViewById(R.id.startButton);
        startButton.setBackgroundColor(Color.parseColor("#0088FF"));
        startButton.setTextColor(Color.WHITE);
        Button stopButton = findViewById(R.id.stopButton);
        stopButton.setBackgroundColor(Color.parseColor("#AA0000"));
        stopButton.setTextColor(Color.WHITE);
        stopButton.setEnabled(false);

        startButton.setOnClickListener(v -> {
            Intent intent = projectionManager.createScreenCaptureIntent();
            startActivityForResult(intent, REQUEST_CODE);
            startButton.setBackgroundColor(Color.parseColor("#0044AA"));
            stopButton.setBackgroundColor(Color.parseColor("#FF0000"));
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
        });

        stopButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, ScreenRecorderService.class);
            stopService(intent);
            startButton.setBackgroundColor(Color.parseColor("#0088FF"));
            stopButton.setBackgroundColor(Color.parseColor("#AA0000"));
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
        });

        ImageButton huionNoteBtn = findViewById(R.id.huionnotebtn);
        ImageButton iarvelBtn = findViewById(R.id.iarvelbtn);

        huionNoteBtn.setBackgroundColor(getResources().getColor(R.color.light_green));
        iarvelBtn.setBackgroundColor(getResources().getColor(R.color.dark_gray));


        huionNoteBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
//                Drawable icon = ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_portrait);
//                icon.setTint(ContextCompat.getColor(getApplicationContext(), R.color.white));
                Components.setNoteApplication(Constants.HUIONNOTE);
                huionNoteBtn.setBackgroundColor(getResources().getColor(R.color.light_green));
                iarvelBtn.setBackgroundColor(getResources().getColor(R.color.dark_gray));
                //Components.setOrientation(1); // portrait orientation
            }
        });

        iarvelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
//                Drawable icon = ContextCompat.getDrawable(getApplicationContext(), R.drawable.ic_landscape);
//                icon.setTint(ContextCompat.getColor(getApplicationContext(), R.color.white));
                Components.setNoteApplication(Constants.IARVEL);
                iarvelBtn.setBackgroundColor(getResources().getColor(R.color.light_green));
                huionNoteBtn.setBackgroundColor(getResources().getColor(R.color.dark_gray));
                //Components.setOrientation(0); // landscape orientation
//                System.out.println("Button icon changed!");
            }
        });

        //Drawable largeIcon = resize(R.mipmap.app_logo_round, 3);
//        toolbar.setNavigationIcon(R.mipmap.app_logo_round);

//        spinner = findViewById(R.id.spinner);
//        Integer[] seconds = new Integer[]{3, 4, 5};
//        ArrayAdapter<Integer> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, seconds);
//        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
//        spinner.setAdapter(adapter);
//
//        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
//            @Override
//            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
//                Components.setDelay(seconds[position]);
//            }
//
//            @Override
//            public void onNothingSelected(AdapterView<?> parent) {
//
//            }
//        });
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

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(getApplicationContext()).unregisterReceiver(connectionStatusReceiver);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LocalBroadcastManager.getInstance(getApplicationContext()).registerReceiver(connectionStatusReceiver, new IntentFilter("connection_status_update"));
    }
}