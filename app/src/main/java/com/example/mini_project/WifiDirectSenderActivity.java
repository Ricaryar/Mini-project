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
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public class WifiDirectSenderActivity extends AppCompatActivity {

    private static final int REQUEST_WIFI_PERMISSIONS = 200;

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private IntentFilter intentFilter;

    private ImageView imgPreview;
    private TextView txtStatus;
    private ProgressBar progressBar;
    private TextView txtProgress;
    private RecyclerView recyclerDevices;
    private Button btnCancel;

    private List<WifiP2pDevice> deviceList;
    private DeviceAdapter deviceAdapter;
    private Uri photoUri;
    private String photoName;
    private byte[] photoData;
    private WifiP2pDevice selectedDevice;
    private Handler handler = new Handler(Looper.getMainLooper());
    private ServerSocket serverSocket;
    private boolean isSending = false;
    private int discoverRetryCount = 0;
    private static final int MAX_DISCOVER_RETRY = 3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_sender);

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

        if (!isLocationServiceEnabled()) {
            Toast.makeText(this, "请先开启定位服务（WiFi Direct 发现设备需要）", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
            handler.postDelayed(this::finish, 2000);
            return;
        }

        initViews();

        // 获取照片数据
        if (getIntent() != null) {
            String uriString = getIntent().getStringExtra("photo_uri");
            photoName = getIntent().getStringExtra("photo_name");
            if (uriString != null) {
                photoUri = Uri.parse(uriString);
                loadPhoto();
            }
        }

        // 初始化 WiFi Direct
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

        deviceList = new ArrayList<>();
        deviceAdapter = new DeviceAdapter(deviceList, device -> {
            selectedDevice = device;
            connectToDevice(device);
        });
        recyclerDevices.setLayoutManager(new LinearLayoutManager(this));
        recyclerDevices.setAdapter(deviceAdapter);

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
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestWiFiPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_WIFI_PERMISSIONS);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    REQUEST_WIFI_PERMISSIONS);
        }
    }

    private boolean isLocationServiceEnabled() {
        LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null) return false;
        return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                || locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
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
        imgPreview = findViewById(R.id.img_preview);
        txtStatus = findViewById(R.id.txt_status);
        progressBar = findViewById(R.id.progress_bar);
        txtProgress = findViewById(R.id.txt_progress);
        recyclerDevices = findViewById(R.id.recycler_devices);
        btnCancel = findViewById(R.id.btn_cancel);
    }

    private void loadPhoto() {
        new Thread(() -> {
            Bitmap bitmap = PhotoHelper.loadBitmapFromUri(getContentResolver(), photoUri, 800);
            if (bitmap != null) {
                photoData = PhotoHelper.bitmapToBytes(bitmap, 800);
                runOnUiThread(() -> {
                    imgPreview.setImageBitmap(bitmap);
                    txtStatus.setText("✅ 照片已加载\n正在初始化...");
                });
            } else {
                runOnUiThread(() -> txtStatus.setText("❌ 加载照片失败"));
            }
        }).start();
    }

    /**
     * 兼容性初始化 WiFi Direct
     * 解决三星等不同厂商手机的兼容性问题
     */
    private void initWiFiDirect() {
        if (manager == null || channel == null) {
            txtStatus.setText("WiFi Direct 初始化失败");
            return;
        }

        txtStatus.setText("正在初始化 WiFi Direct...");

        try {
            // 停止正在进行的发现
            manager.stopPeerDiscovery(channel, null);
        } catch (Exception e) {
            // ignore
        }

        try {
            // 移除现有组（重要：解决三星等设备的 BUSY 状态）
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
        // 给设备足够时间完成清理（三星设备需要更长时间）
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
                        txtStatus.setText("正在搜索设备...");
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
                            Toast.makeText(WifiDirectSenderActivity.this,
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

    private void connectToDevice(WifiP2pDevice device) {
        txtStatus.setText("正在连接 " + device.deviceName + "...");

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.groupOwnerIntent = 15;

        try {
            manager.connect(channel, config, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    runOnUiThread(() -> txtStatus.setText("连接成功，准备传输..."));
                }

                @Override
                public void onFailure(int reason) {
                    runOnUiThread(() -> {
                        txtStatus.setText("连接失败: " + getFailureReason(reason));
                        handler.postDelayed(() -> discoverDevicesWithRetry(), 2000);
                    });
                }
            });
        } catch (SecurityException e) {
            txtStatus.setText("权限不足，无法连接");
        }
    }

    private void startFileServer() {
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(8888);
                serverSocket.setSoTimeout(30000);

                runOnUiThread(() -> txtStatus.setText("等待接收方连接..."));

                Socket clientSocket = serverSocket.accept();

                runOnUiThread(() -> {
                    txtStatus.setText("已连接，正在发送照片...");
                    progressBar.setVisibility(View.VISIBLE);
                    txtProgress.setVisibility(View.VISIBLE);
                    progressBar.setMax(100);
                });

                OutputStream outputStream = clientSocket.getOutputStream();

                // 发送文件名长度和文件名
                byte[] nameBytes = photoName.getBytes("UTF-8");
                outputStream.write((nameBytes.length >> 24) & 0xFF);
                outputStream.write((nameBytes.length >> 16) & 0xFF);
                outputStream.write((nameBytes.length >> 8) & 0xFF);
                outputStream.write(nameBytes.length & 0xFF);
                outputStream.write(nameBytes);

                // 发送照片数据大小
                outputStream.write((photoData.length >> 24) & 0xFF);
                outputStream.write((photoData.length >> 16) & 0xFF);
                outputStream.write((photoData.length >> 8) & 0xFF);
                outputStream.write(photoData.length & 0xFF);

                // 分块发送照片数据并更新进度
                int chunkSize = 8192;
                int totalSent = 0;
                while (totalSent < photoData.length) {
                    int sendSize = Math.min(chunkSize, photoData.length - totalSent);
                    outputStream.write(photoData, totalSent, sendSize);
                    totalSent += sendSize;

                    final int progress = (totalSent * 100) / photoData.length;
                    runOnUiThread(() -> {
                        progressBar.setProgress(progress);
                        txtProgress.setText(progress + "%");
                    });
                }

                outputStream.flush();
                outputStream.close();
                clientSocket.close();
                serverSocket.close();

                runOnUiThread(() -> {
                    txtStatus.setText("✅ 照片发送成功！");
                    progressBar.setVisibility(View.GONE);
                    txtProgress.setVisibility(View.GONE);
                    Toast.makeText(this, "照片发送成功", Toast.LENGTH_LONG).show();
                    handler.postDelayed(this::finish, 2000);
                });

            } catch (IOException e) {
                runOnUiThread(() -> {
                    txtStatus.setText("发送失败: " + e.getMessage());
                    progressBar.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    private void cleanup() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
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

            if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                if (manager != null && checkWiFiPermissions()) {
                    try {
                        manager.requestPeers(channel, peers -> {
                            deviceList.clear();
                            for (WifiP2pDevice device : peers.getDeviceList()) {
                                if (device.status == WifiP2pDevice.AVAILABLE) {
                                    deviceList.add(device);
                                }
                            }
                            runOnUiThread(() -> {
                                deviceAdapter.notifyDataSetChanged();
                                if (deviceList.isEmpty()) {
                                    txtStatus.setText("未发现附近设备\n请确保接收方已打开接收界面");
                                } else {
                                    txtStatus.setText("发现 " + deviceList.size() + " 个设备，点击发送");
                                }
                            });
                        });
                    } catch (SecurityException e) {
                        txtStatus.setText("权限不足，无法获取设备列表");
                    }
                }
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                if (manager != null && checkWiFiPermissions()) {
                    try {
                        manager.requestConnectionInfo(channel, info -> {
                            if (info.groupFormed && info.isGroupOwner) {
                                startFileServer();
                            }
                        });
                    } catch (SecurityException e) {
                        // ignore
                    }
                }
            }
        }
    }

    private static class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {
        private List<WifiP2pDevice> devices;
        private OnDeviceClickListener listener;

        interface OnDeviceClickListener {
            void onConnect(WifiP2pDevice device);
        }

        DeviceAdapter(List<WifiP2pDevice> devices, OnDeviceClickListener listener) {
            this.devices = devices;
            this.listener = listener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_device, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            WifiP2pDevice device = devices.get(position);
            holder.txtDeviceName.setText(device.deviceName != null && !device.deviceName.isEmpty()
                    ? device.deviceName : "未知设备");
            holder.btnConnect.setOnClickListener(v -> listener.onConnect(device));
        }

        @Override
        public int getItemCount() {
            return devices.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView txtDeviceName, txtDeviceStatus;
            Button btnConnect;

            ViewHolder(View itemView) {
                super(itemView);
                txtDeviceName = itemView.findViewById(R.id.txt_device_name);
                txtDeviceStatus = itemView.findViewById(R.id.txt_device_status);
                btnConnect = itemView.findViewById(R.id.btn_connect);
            }
        }
    }
}