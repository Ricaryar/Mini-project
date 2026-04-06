package com.example.mini_project;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.InputStream;
import java.net.Socket;

public class WifiDirectReceiverActivity extends AppCompatActivity {

    private static final int REQUEST_WIFI_PERMISSIONS = 200;

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private IntentFilter intentFilter;

    private TextView txtStatus;
    private ProgressBar progressBar;
    private TextView txtProgress;
    private Button btnCancel;

    private boolean isReceiving = false;
    private Handler handler = new Handler(Looper.getMainLooper());
    private Socket clientSocket;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_receiver);

        // 检查权限
        if (!checkWiFiPermissions()) {
            requestWiFiPermissions();
            return;
        }

        initViews();

        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        btnCancel.setOnClickListener(v -> {
            cleanup();
            finish();
        });

        discoverDevices();
    }

    private boolean checkWiFiPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestWiFiPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},
                    REQUEST_WIFI_PERMISSIONS);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_WIFI_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WIFI_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                recreate();
            } else {
                Toast.makeText(this, "需要 WiFi Direct 权限才能使用此功能", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    private void initViews() {
        txtStatus = findViewById(R.id.txt_status);
        progressBar = findViewById(R.id.progress_bar);
        txtProgress = findViewById(R.id.txt_progress);
        btnCancel = findViewById(R.id.btn_cancel);
    }

    private void discoverDevices() {
        if (manager == null || channel == null) return;

        try {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    txtStatus.setText("等待发送方连接...");
                }

                @Override
                public void onFailure(int reason) {
                    txtStatus.setText("初始化失败: " + reason);
                }
            });
        } catch (SecurityException e) {
            txtStatus.setText("权限不足，请检查权限设置");
        }
    }

    private void receiveFile(String host) {
        isReceiving = true;

        new Thread(() -> {
            try {
                clientSocket = new Socket(host, 8888);
                InputStream inputStream = clientSocket.getInputStream();

                runOnUiThread(() -> {
                    txtStatus.setText("已连接，正在接收照片...");
                    progressBar.setVisibility(ProgressBar.VISIBLE);
                    txtProgress.setVisibility(TextView.VISIBLE);
                    progressBar.setMax(100);
                });

                // 读取文件名长度
                int nameLen = (inputStream.read() << 24) | (inputStream.read() << 16) |
                        (inputStream.read() << 8) | inputStream.read();

                byte[] nameBytes = new byte[nameLen];
                inputStream.read(nameBytes);
                String fileName = new String(nameBytes, "UTF-8");

                // 读取数据大小
                int dataSize = (inputStream.read() << 24) | (inputStream.read() << 16) |
                        (inputStream.read() << 8) | inputStream.read();

                // 接收照片数据
                byte[] imageData = new byte[dataSize];
                int totalRead = 0;
                while (totalRead < dataSize) {
                    int read = inputStream.read(imageData, totalRead, dataSize - totalRead);
                    if (read < 0) break;
                    totalRead += read;

                    final int progress = (totalRead * 100) / dataSize;
                    runOnUiThread(() -> {
                        progressBar.setProgress(progress);
                        txtProgress.setText(progress + "%");
                    });
                }

                inputStream.close();
                clientSocket.close();

                // 保存照片
                Bitmap bitmap = PhotoHelper.bytesToBitmap(imageData);
                if (bitmap != null) {
                    String saveName = "Received_" + System.currentTimeMillis() + ".jpg";
                    Uri savedUri = PhotoHelper.saveBitmapToGallery(getContentResolver(), bitmap, saveName);

                    runOnUiThread(() -> {
                        if (savedUri != null) {
                            txtStatus.setText("✅ 照片接收成功！已保存到相册");
                            Toast.makeText(this, "照片已保存", Toast.LENGTH_LONG).show();
                            progressBar.setVisibility(ProgressBar.GONE);
                            txtProgress.setVisibility(TextView.GONE);
                            handler.postDelayed(this::finish, 2000);
                        } else {
                            txtStatus.setText("❌ 保存失败");
                        }
                    });
                } else {
                    runOnUiThread(() -> txtStatus.setText("❌ 解码照片失败"));
                }

            } catch (Exception e) {
                runOnUiThread(() -> {
                    txtStatus.setText("接收失败: " + e.getMessage());
                    progressBar.setVisibility(ProgressBar.GONE);
                });
            } finally {
                isReceiving = false;
            }
        }).start();
    }

    private void cleanup() {
        if (clientSocket != null) {
            try {
                clientSocket.close();
            } catch (Exception e) {
                // ignore
            }
        }
        if (manager != null && channel != null) {
            try {
                manager.removeGroup(channel, null);
            } catch (SecurityException e) {
                // ignore
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkWiFiPermissions() && manager != null) {
            receiver = new WiFiDirectBroadcastReceiver();
            registerReceiver(receiver, intentFilter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiver != null) {
            try {
                unregisterReceiver(receiver);
            } catch (IllegalArgumentException e) {
                // ignore
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanup();
    }

    private class WiFiDirectBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                if (manager != null && checkWiFiPermissions()) {
                    try {
                        manager.requestConnectionInfo(channel, info -> {
                            if (info.groupFormed) {
                                if (!info.isGroupOwner) {
                                    receiveFile(info.groupOwnerAddress.getHostAddress());
                                }
                            }
                        });
                    } catch (SecurityException e) {
                        // ignore
                    }
                }
            }
        }
    }
}