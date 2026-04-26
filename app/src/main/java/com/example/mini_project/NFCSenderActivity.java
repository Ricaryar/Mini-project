package com.example.mini_project;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.UUID;

public class NFCSenderActivity extends AppCompatActivity {
    private static final String TAG = "NFC_SENDER";
    private static final UUID BLUETOOTH_UUID = UUID.fromString("12345678-1234-1234-1234-123456789012");

    private TextView txtStatus;
    private ProgressBar progressBar;
    private byte[] photoData;
    private BluetoothServerSocket serverSocket;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_sender);
        txtStatus = findViewById(R.id.txt_status);
        progressBar = findViewById(R.id.progress_bar);

        handleIntentAndStartServer();
    }

    private void handleIntentAndStartServer() {
        String uriString = getIntent().getStringExtra("photo_uri");
        if (uriString == null) {
            txtStatus.setText("❌ 未找到照片");
            return;
        }
        txtStatus.setText("正在准备照片...");
        new Thread(() -> {
            Bitmap bitmap = PhotoHelper.loadBitmapFromUri(getContentResolver(), Uri.parse(uriString), 1024);
            if (bitmap != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
                photoData = baos.toByteArray();
                runOnUiThread(this::startBluetoothServer);
            } else {
                runOnUiThread(() -> txtStatus.setText("❌ 照片读取失败"));
            }
        }).start();
    }

    private void startBluetoothServer() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            txtStatus.setText("❌ 设备不支持蓝牙");
            return;
        }
        if (!adapter.isEnabled()) {
            txtStatus.setText("❌ 请先开启蓝牙");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            txtStatus.setText("❌ 缺少蓝牙权限");
            return;
        }

        new Thread(() -> {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("PhotoShare", BLUETOOTH_UUID);
                runOnUiThread(() -> txtStatus.setText("📡 已就緒，請靠近接收方"));

                BluetoothSocket socket = serverSocket.accept();
                if (socket != null) {
                    sendData(socket);
                }
            } catch (Exception e) {
                Log.e(TAG, "Server error", e);
                runOnUiThread(() -> txtStatus.setText("❌ 蓝牙服务启动失败"));
            }
        }).start();
    }

    private void sendData(BluetoothSocket socket) {
        if (photoData == null) return;
        new Thread(() -> {
            try {
                runOnUiThread(() -> {
                    txtStatus.setText("📤 發送中...");
                    progressBar.setVisibility(View.VISIBLE);
                });

                OutputStream out = socket.getOutputStream();
                byte[] size = ByteBuffer.allocate(4).putInt(photoData.length).array();
                out.write(size); // 發送長度
                out.write(photoData); // 發送數據
                out.flush();

                Thread.sleep(500); // 等待數據發出
                socket.close(); // 關閉連線
                if (serverSocket != null) serverSocket.close(); // 關閉伺服器

                runOnUiThread(() -> {
                    txtStatus.setText("✅ 發送成功！");
                    Toast.makeText(this, "發送成功", Toast.LENGTH_SHORT).show();
                    new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1500);
                });
            } catch (Exception e) {
                runOnUiThread(() -> txtStatus.setText("❌ 發送失敗"));
            }
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException e) {}
    }
}