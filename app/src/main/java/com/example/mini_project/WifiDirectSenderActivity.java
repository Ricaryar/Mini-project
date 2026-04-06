package com.example.mini_project;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wifi_sender);

        // 检查权限
        if (!checkWiFiPermissions()) {
            requestWiFiPermissions();
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

        // 开始发现设备
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
                    txtStatus.setText("✅ 照片已加载\n正在搜索附近设备...");
                });
            } else {
                runOnUiThread(() -> txtStatus.setText("❌ 加载照片失败"));
            }
        }).start();
    }

    private void discoverDevices() {
        if (manager == null || channel == null) return;

        try {
            manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    txtStatus.setText("正在搜索设备...");
                }

                @Override
                public void onFailure(int reason) {
                    txtStatus.setText("设备发现失败: " + reason);
                }
            });
        } catch (SecurityException e) {
            txtStatus.setText("权限不足，请检查权限设置");
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
                    txtStatus.setText("连接成功，准备传输...");
                }

                @Override
                public void onFailure(int reason) {
                    runOnUiThread(() -> {
                        txtStatus.setText("连接失败: " + reason);
                        discoverDevices();
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
            holder.txtDeviceName.setText(device.deviceName);
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