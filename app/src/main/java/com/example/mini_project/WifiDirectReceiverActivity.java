package com.example.mini_project;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
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
    private int discoverRetryCount = 0;
    private static final int MAX_DISCOVER_RETRY = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_receiver);

        // 检查权限
        if (!checkWiFiPermissions()) {
            requestWiFiPermissions();
            return;
        }

        // 检查WiFi是否开启
        if (!isWifiEnabled()) {
            Toast.makeText(this, "请先开启WiFi", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            handler.postDelayed(this::finish, 2000);
            return;
        }

        initViews();

        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        if (manager == null) {
            txtStatus.setText("设备不支持 WiFi Direct");
            Toast.makeText(this, "设备不支持 WiFi Direct", Toast.LENGTH_LONG).show();
            handler.postDelayed(this::finish, 2000);
            return;
        }

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

        // 初始化并开始发现设备（兼容性处理）
        initWiFiDirect();
    }

    private boolean isWifiEnabled() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return wifiManager != null && wifiManager.isWifiEnabled();
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

    /**
     * 兼容性初始化 WiFi Direct
     */
    private void initWiFiDirect() {
        if (manager == null || channel == null) {
            txtStatus.setText("WiFi Direct 初始化失败");
            return;
        }

        txtStatus.setText("正在初始化 WiFi Direct...");

        try {
            manager.stopPeerDiscovery(channel, null);
        } catch (Exception e) {}

        try {
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.d("WiFiDirect", "清理成功");
                    startDiscoveryWithDelay();
                }

                @Override
                public void onFailure(int reason) {
                    Log.d("WiFiDirect", "清理失败: " + reason);
                    startDiscoveryWithDelay();
                }
            });
        } catch (SecurityException e) {
            startDiscoveryWithDelay();
        }
    }

    private void startDiscoveryWithDelay() {
        handler.postDelayed(() -> {
            discoverDevicesWithRetry();
        }, 2000);
    }

    private void discoverDevicesWithRetry() {
        if (manager == null || channel == null) return;

        try {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    runOnUiThread(() -> {
                        txtStatus.setText("等待发送方连接...");
                        discoverRetryCount = 0;
                    });
                }

                @Override
                public void onFailure(int reason) {
                    runOnUiThread(() -> {
                        String reasonMsg = getFailureReason(reason);
                        if (discoverRetryCount < MAX_DISCOVER_RETRY) {
                            discoverRetryCount++;
                            txtStatus.setText("初始化中 (" + reasonMsg + ")，重试 " + discoverRetryCount + "/" + MAX_DISCOVER_RETRY + "...");
                            handler.postDelayed(() -> {
                                discoverDevicesWithRetry();
                            }, 1500);
                        } else {
                            txtStatus.setText("WiFi Direct 初始化失败 (" + reasonMsg + ")\n请尝试：\n1. 关闭再打开WiFi\n2. 确保未连接其他设备\n3. 重启应用后重试");
                            Toast.makeText(WifiDirectReceiverActivity.this,
                                    "请关闭再打开WiFi后重试", Toast.LENGTH_LONG).show();
                        }
                    });
                }
            });
        } catch (SecurityException e) {
            runOnUiThread(() -> txtStatus.setText("权限不足，请检查权限设置"));
        }
    }

    private String getFailureReason(int reason) {
        switch (reason) {
            case WifiP2pManager.BUSY:
                return "设备忙";
            case WifiP2pManager.ERROR:
                return "内部错误";
            case WifiP2pManager.P2P_UNSUPPORTED:
                return "不支持P2P";
            default:
                return "错误码:" + reason;
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
                manager.stopPeerDiscovery(channel, null);
            } catch (Exception e) {}
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
            try {
                registerReceiver(receiver, intentFilter);
            } catch (IllegalArgumentException e) {
                // ignore
            }
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