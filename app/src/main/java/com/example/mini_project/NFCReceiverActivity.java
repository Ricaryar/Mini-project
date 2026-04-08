package com.example.mini_project;

import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class NFCReceiverActivity extends AppCompatActivity {

    private static final UUID BLUETOOTH_UUID = UUID.fromString("12345678-1234-1234-1234-123456789012");

    private NfcAdapter nfcAdapter;
    private TextView txtStatus;
    private Button btnCancel;
    private ProgressBar progressBar;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isReceiving = false;

    private BluetoothAdapter bluetoothAdapter;
    private String targetBluetoothAddress;
    private int expectedFileSize;
    private String fileName;
    private byte[] receivedPhotoData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_receiver);

        txtStatus = findViewById(R.id.txt_status);
        btnCancel = findViewById(R.id.btn_cancel);

        // 添加进度条
        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setVisibility(View.GONE);

        // 初始化蓝牙
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            txtStatus.setText("❌ 设备不支持蓝牙");
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_LONG).show();
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            txtStatus.setText("❌ 设备不支持NFC");
            Toast.makeText(this, "设备不支持NFC", Toast.LENGTH_LONG).show();
        } else if (!nfcAdapter.isEnabled()) {
            txtStatus.setText("❌ 请开启NFC功能");
            Toast.makeText(this, "请开启NFC功能", Toast.LENGTH_LONG).show();
        }

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> finish());
        }

        // 请求蓝牙权限
        requestBluetoothPermissions();
    }

    private void requestBluetoothPermissions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            String[] permissions = {
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
            };
            for (String perm : permissions) {
                if (ContextCompat.checkSelfPermission(this, perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, permissions, 200);
                    break;
                }
            }
        } else {
            String[] permissions = {
                    android.Manifest.permission.BLUETOOTH,
                    android.Manifest.permission.BLUETOOTH_ADMIN,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
            };
            for (String perm : permissions) {
                if (ContextCompat.checkSelfPermission(this, perm) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, permissions, 200);
                    break;
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null && nfcAdapter.isEnabled()) {
            setupForegroundDispatch();
            txtStatus.setText("📡 等待发送方连接...\n请将两部手机背对背靠近");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            try {
                nfcAdapter.disableForegroundDispatch(this);
            } catch (Exception e) {
                // 忽略异常
            }
        }
    }

    private void setupForegroundDispatch() {
        try {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_MUTABLE
            );
            IntentFilter[] intentFilters = new IntentFilter[]{
                    new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
                    new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
            };
            String[][] techLists = new String[][]{
                    new String[]{Ndef.class.getName()}
            };
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists);
        } catch (Exception e) {
            txtStatus.setText("NFC初始化失败: " + e.getMessage());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!isReceiving && nfcAdapter != null) {
            receiveConnectionInfo(intent);
        }
    }

    private void receiveConnectionInfo(Intent intent) {
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) {
            txtStatus.setText("未检测到NFC标签");
            return;
        }

        isReceiving = true;
        txtStatus.setText("📥 正在接收连接信息...");

        new Thread(() -> {
            try {
                Ndef ndef = Ndef.get(tag);
                if (ndef != null) {
                    ndef.connect();
                    NdefMessage ndefMessage = ndef.getCachedNdefMessage();
                    ndef.close();

                    if (ndefMessage != null) {
                        NdefRecord[] records = ndefMessage.getRecords();
                        for (NdefRecord record : records) {
                            if (record.getTnf() == NdefRecord.TNF_MIME_MEDIA &&
                                    new String(record.getType()).equals("application/com.example.mini_project.connection")) {

                                byte[] payload = record.getPayload();
                                String deviceInfo = new String(payload, "UTF-8");
                                String[] parts = deviceInfo.split("\\|");

                                if (parts.length >= 3) {
                                    targetBluetoothAddress = parts[0];
                                    expectedFileSize = Integer.parseInt(parts[1]);
                                    fileName = parts[2];

                                    runOnUiThread(() -> {
                                        txtStatus.setText("✅ 已获取发送方信息\n📡 正在连接蓝牙...");
                                        connectBluetoothAndReceive();
                                    });
                                    return;
                                }
                            }
                        }
                        runOnUiThread(() -> {
                            txtStatus.setText("❌ 未检测到有效连接信息");
                            isReceiving = false;
                        });
                    } else {
                        runOnUiThread(() -> {
                            txtStatus.setText("❌ 未检测到NDEF消息");
                            isReceiving = false;
                        });
                    }
                } else {
                    runOnUiThread(() -> {
                        txtStatus.setText("❌ 不支持的NFC标签类型");
                        isReceiving = false;
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    txtStatus.setText("❌ 接收失败: " + e.getMessage());
                    isReceiving = false;
                });
            }
        }).start();
    }

    private void connectBluetoothAndReceive() {
        new Thread(() -> {
            try {
                BluetoothDevice device = bluetoothAdapter.getRemoteDevice(targetBluetoothAddress);
                BluetoothSocket socket = device.createRfcommSocketToServiceRecord(BLUETOOTH_UUID);

                // 取消发现，提高连接速度
                bluetoothAdapter.cancelDiscovery();

                socket.connect();

                runOnUiThread(() -> {
                    txtStatus.setText("📥 蓝牙已连接，正在接收照片...");
                    showProgressBar();
                });

                InputStream inputStream = socket.getInputStream();

                // 读取文件名长度
                int nameLen = ((inputStream.read() & 0xFF) << 24) |
                        ((inputStream.read() & 0xFF) << 16) |
                        ((inputStream.read() & 0xFF) << 8) |
                        (inputStream.read() & 0xFF);

                byte[] nameBytes = new byte[nameLen];
                inputStream.read(nameBytes);
                String receivedFileName = new String(nameBytes);

                // 读取文件大小
                int fileSize = ((inputStream.read() & 0xFF) << 24) |
                        ((inputStream.read() & 0xFF) << 16) |
                        ((inputStream.read() & 0xFF) << 8) |
                        (inputStream.read() & 0xFF);

                // 接收照片数据
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int totalRead = 0;
                int bytesRead;

                while (totalRead < fileSize && (bytesRead = inputStream.read(buffer)) != -1) {
                    baos.write(buffer, 0, bytesRead);
                    totalRead += bytesRead;

                    final int progress = (totalRead * 100) / fileSize;
                    runOnUiThread(() -> updateProgress(progress));
                }

                receivedPhotoData = baos.toByteArray();
                socket.close();

                runOnUiThread(() -> {
                    hideProgressBar();
                    showReceiveDialog(receivedPhotoData, receivedFileName);
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    txtStatus.setText("❌ 蓝牙接收失败: " + e.getMessage());
                    isReceiving = false;
                    hideProgressBar();
                });
                e.printStackTrace();
            }
        }).start();
    }

    private void showProgressBar() {
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setMax(100);
            progressBar.setProgress(0);
        }
    }

    private void updateProgress(int progress) {
        if (progressBar != null) {
            progressBar.setProgress(progress);
            txtStatus.setText("📥 接收中: " + progress + "%");
        }
    }

    private void hideProgressBar() {
        if (progressBar != null) {
            progressBar.setVisibility(View.GONE);
        }
    }

    private void showReceiveDialog(byte[] imageData, String fileName) {
        Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_receive_confirm, null);

        ImageView imgPreview = dialogView.findViewById(R.id.img_receive_preview);
        Button btnAccept = dialogView.findViewById(R.id.btn_accept);
        Button btnReject = dialogView.findViewById(R.id.btn_reject);

        if (bitmap != null) {
            imgPreview.setImageBitmap(bitmap);
        }

        AlertDialog dialog = builder.setView(dialogView)
                .setCancelable(false)
                .create();

        btnAccept.setOnClickListener(v -> {
            dialog.dismiss();
            saveReceivedPhoto(imageData, fileName);
        });

        btnReject.setOnClickListener(v -> {
            dialog.dismiss();
            txtStatus.setText("已拒绝接收");
            isReceiving = false;
            handler.postDelayed(() -> {
                txtStatus.setText("📡 等待发送方连接...");
            }, 2000);
        });

        dialog.show();
    }

    private void saveReceivedPhoto(byte[] imageData, String fileName) {
        txtStatus.setText("💾 正在保存照片...");

        new Thread(() -> {
            Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
            if (bitmap != null) {
                String saveFileName = "Received_" + System.currentTimeMillis() + ".jpg";
                Uri savedUri = PhotoHelper.saveBitmapToGallery(getContentResolver(), bitmap, saveFileName);

                runOnUiThread(() -> {
                    if (savedUri != null) {
                        txtStatus.setText("✅ 照片已保存到相册！");
                        Toast.makeText(this, "照片已保存", Toast.LENGTH_LONG).show();
                        handler.postDelayed(this::finish, 2000);
                    } else {
                        txtStatus.setText("❌ 保存失败");
                        isReceiving = false;
                        handler.postDelayed(() -> {
                            txtStatus.setText("📡 等待发送方连接...");
                        }, 2000);
                    }
                });
            }
        }).start();
    }
}