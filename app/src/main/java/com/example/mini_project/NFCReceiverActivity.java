package com.example.mini_project;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.UUID;

public class NFCReceiverActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {
    private static final UUID BLUETOOTH_UUID = UUID.fromString("12345678-1234-1234-1234-123456789012");
    private NfcAdapter nfcAdapter;
    private TextView txtStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_receiver);
        txtStatus = findViewById(R.id.txt_status);
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null) {
            nfcAdapter.enableReaderMode(this, this,
                    NfcAdapter.FLAG_READER_NFC_A | NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null);
        }
    }

    @Override
    public void onTagDiscovered(Tag tag) {
        runOnUiThread(() -> txtStatus.setText("🔗 發現設備，準備接收..."));
        connectToSender();
    }

    private void connectToSender() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null) {
            runOnUiThread(() -> txtStatus.setText("❌ 设备不支持蓝牙"));
            return;
        }
        if (!adapter.isEnabled()) {
            runOnUiThread(() -> txtStatus.setText("❌ 请先开启蓝牙"));
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread(() -> txtStatus.setText("❌ 缺少蓝牙权限"));
            return;
        }
        Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();
        if (pairedDevices == null || pairedDevices.isEmpty()) {
            runOnUiThread(() -> txtStatus.setText("❌ 未找到已配对蓝牙设备"));
            return;
        }
        for (BluetoothDevice device : pairedDevices) {
            new Thread(() -> {
                try {
                    BluetoothSocket socket = device.createRfcommSocketToServiceRecord(BLUETOOTH_UUID);
                    socket.connect();
                    receiveData(socket);
                } catch (Exception e) {
                    // 忽略無效的設備
                }
            }).start();
        }
    }

    private void receiveData(BluetoothSocket socket) {
        try {
            InputStream in = socket.getInputStream();

            // 1. 讀取長度 (這步很重要，避免 read 永遠阻塞)
            byte[] sizeBuf = new byte[4];
            int read = in.read(sizeBuf);
            if (read != 4) {
                socket.close();
                return;
            }
            int fileSize = ByteBuffer.wrap(sizeBuf).getInt();

            // 2. 根據長度讀取
            byte[] photoData = new byte[fileSize];
            int totalRead = 0;
            while (totalRead < fileSize) {
                int r = in.read(photoData, totalRead, fileSize - totalRead);
                if (r == -1) break;
                totalRead += r;
            }

            // 3. 處理數據與關閉
            Bitmap bitmap = BitmapFactory.decodeByteArray(photoData, 0, photoData.length);
            if (bitmap != null) {
                PhotoHelper.saveBitmapToGallery(getContentResolver(), bitmap, "NFC_IMG_" + System.currentTimeMillis() + ".jpg");
                runOnUiThread(() -> {
                    txtStatus.setText("✅ 接收成功並已存入相簿");
                    Toast.makeText(this, "照片已存入相簿", Toast.LENGTH_SHORT).show();
                    // 關鍵：延遲關閉 Activity，確保 UI 更新
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::finish, 2000);
                });
            }
            socket.close();
        } catch (Exception e) {
            Log.e("NFC_RECEIVE", "Receive Error", e);
            runOnUiThread(() -> txtStatus.setText("❌ 接收失敗"));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
    }
} 