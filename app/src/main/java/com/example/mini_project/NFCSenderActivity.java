package com.example.mini_project;

import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public class NFCSenderActivity extends AppCompatActivity {

    private static final UUID BLUETOOTH_UUID = UUID.fromString("12345678-1234-1234-1234-123456789012");
    private static final String SERVICE_NAME = "PhotoShare";

    private NfcAdapter nfcAdapter;
    private TextView txtStatus;
    private ImageView imgPreview;
    private ProgressBar progressBar;
    private Button btnCancel;

    private Uri photoUri;
    private String photoName;
    private byte[] photoData;
    private boolean isSending = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothServerSocket serverSocket;
    private boolean isServerRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_sender);

        initViews();

        // 获取传递的照片信息
        if (getIntent() != null) {
            String uriString = getIntent().getStringExtra("photo_uri");
            photoName = getIntent().getStringExtra("photo_name");
            if (uriString != null) {
                photoUri = Uri.parse(uriString);
                loadPhoto();
            }
        }

        // 初始化蓝牙
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) {
            txtStatus.setText("❌ 设备不支持蓝牙");
            Toast.makeText(this, "设备不支持蓝牙", Toast.LENGTH_LONG).show();
        }

        // 初始化 NFC
        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            txtStatus.setText("❌ 设备不支持NFC");
            Toast.makeText(this, "设备不支持NFC", Toast.LENGTH_LONG).show();
        } else if (!nfcAdapter.isEnabled()) {
            txtStatus.setText("❌ 请开启NFC功能");
            Toast.makeText(this, "请开启NFC功能", Toast.LENGTH_LONG).show();
        }

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> {
                stopBluetoothServer();
                finish();
            });
        }

        // 请求蓝牙权限
        requestBluetoothPermissions();
    }

    private void initViews() {
        txtStatus = findViewById(R.id.txt_status);
        imgPreview = findViewById(R.id.img_preview);
        progressBar = findViewById(R.id.progress_bar);
        btnCancel = findViewById(R.id.btn_cancel);
    }

    private void requestBluetoothPermissions() {
        // Android 12+ 需要新的蓝牙权限
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            String[] permissions = {
                    android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    android.Manifest.permission.BLUETOOTH_ADVERTISE,
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

    private void loadPhoto() {
        new Thread(() -> {
            // 不压缩太多，保持较好质量
            Bitmap bitmap = PhotoHelper.loadBitmapFromUri(getContentResolver(), photoUri, 1000);
            if (bitmap != null) {
                // 转换为 JPEG 字节数组，质量 80%
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos);
                photoData = baos.toByteArray();

                runOnUiThread(() -> {
                    imgPreview.setImageBitmap(bitmap);
                    txtStatus.setText("✅ 照片已加载 (" + (photoData.length / 1024) + " KB)\n请将两部手机背对背靠近...");
                    // 启动蓝牙服务器
                    startBluetoothServer();
                });
            } else {
                runOnUiThread(() -> {
                    txtStatus.setText("❌ 加载照片失败");
                });
            }
        }).start();
    }

    private void startBluetoothServer() {
        if (bluetoothAdapter == null) return;

        new Thread(() -> {
            try {
                serverSocket = bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, BLUETOOTH_UUID);
                isServerRunning = true;
                runOnUiThread(() -> txtStatus.append("\n📡 蓝牙服务器已启动，等待连接..."));
            } catch (IOException e) {
                runOnUiThread(() -> txtStatus.append("\n❌ 蓝牙服务器启动失败: " + e.getMessage()));
            }
        }).start();
    }

    private void stopBluetoothServer() {
        isServerRunning = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            serverSocket = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null && nfcAdapter.isEnabled()) {
            setupForegroundDispatch();
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
                    new String[]{Ndef.class.getName()},
                    new String[]{NdefFormatable.class.getName()}
            };
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists);
        } catch (Exception e) {
            txtStatus.setText("NFC初始化失败: " + e.getMessage());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (photoData != null && !isSending && nfcAdapter != null) {
            sendDeviceInfoViaNFC(intent);
        }
    }

    private void sendDeviceInfoViaNFC(Intent intent) {
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) {
            txtStatus.setText("未检测到NFC标签");
            return;
        }

        isSending = true;
        progressBar.setVisibility(View.VISIBLE);
        txtStatus.setText("📤 正在通过NFC建立连接...");

        new Thread(() -> {
            try {
                // 获取本机蓝牙地址和照片大小信息
                String bluetoothAddress = bluetoothAdapter.getAddress();
                String deviceInfo = bluetoothAddress + "|" + photoData.length + "|" + photoName;

                NdefMessage ndefMessage = createConnectionInfoMessage(deviceInfo);

                Ndef ndef = Ndef.get(tag);
                if (ndef != null) {
                    ndef.connect();
                    if (ndef.isWritable()) {
                        ndef.writeNdefMessage(ndefMessage);
                        runOnUiThread(() -> {
                            txtStatus.setText("✅ 连接信息已发送\n📡 等待接收方连接蓝牙...");
                            // 等待蓝牙连接并发送数据
                            waitForBluetoothConnectionAndSend();
                        });
                    } else {
                        runOnUiThread(() -> {
                            txtStatus.setText("❌ 标签不可写");
                            isSending = false;
                            progressBar.setVisibility(View.GONE);
                        });
                    }
                    ndef.close();
                } else {
                    NdefFormatable formatable = NdefFormatable.get(tag);
                    if (formatable != null) {
                        formatable.connect();
                        formatable.format(ndefMessage);
                        runOnUiThread(() -> {
                            txtStatus.setText("✅ 连接信息已发送\n📡 等待接收方连接蓝牙...");
                            waitForBluetoothConnectionAndSend();
                        });
                        formatable.close();
                    } else {
                        runOnUiThread(() -> {
                            txtStatus.setText("❌ 不支持的NFC标签类型");
                            isSending = false;
                            progressBar.setVisibility(View.GONE);
                        });
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    txtStatus.setText("❌ NFC发送失败: " + e.getMessage());
                    isSending = false;
                    progressBar.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    private void waitForBluetoothConnectionAndSend() {
        new Thread(() -> {
            try {
                // 等待接收方连接
                if (serverSocket == null) {
                    runOnUiThread(() -> {
                        txtStatus.setText("❌ 蓝牙服务器未启动");
                        isSending = false;
                        progressBar.setVisibility(View.GONE);
                    });
                    return;
                }

                BluetoothSocket socket = serverSocket.accept();

                runOnUiThread(() -> {
                    txtStatus.setText("📤 蓝牙已连接，正在发送照片...");
                    progressBar.setIndeterminate(false);
                    progressBar.setMax(100);
                });

                OutputStream outputStream = socket.getOutputStream();

                // 先发送文件名长度和文件名
                byte[] nameBytes = photoName.getBytes();
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

                // 分块发送照片数据，并更新进度
                int chunkSize = 8192;
                int offset = 0;
                while (offset < photoData.length) {
                    int len = Math.min(chunkSize, photoData.length - offset);
                    outputStream.write(photoData, offset, len);
                    offset += len;

                    final int progress = (offset * 100) / photoData.length;
                    runOnUiThread(() -> progressBar.setProgress(progress));
                }

                outputStream.flush();
                socket.close();
                stopBluetoothServer();

                runOnUiThread(() -> {
                    txtStatus.setText("✅ 照片发送成功！");
                    Toast.makeText(this, "照片已发送", Toast.LENGTH_LONG).show();
                    handler.postDelayed(this::finish, 2000);
                });

            } catch (IOException e) {
                runOnUiThread(() -> {
                    txtStatus.setText("❌ 蓝牙发送失败: " + e.getMessage());
                    isSending = false;
                    progressBar.setVisibility(View.GONE);
                });
                e.printStackTrace();
            }
        }).start();
    }

    private NdefMessage createConnectionInfoMessage(String info) {
        try {
            byte[] infoBytes = info.getBytes("UTF-8");

            NdefRecord record = new NdefRecord(
                    NdefRecord.TNF_MIME_MEDIA,
                    "application/com.example.mini_project.connection".getBytes(),
                    new byte[0],
                    infoBytes
            );

            return new NdefMessage(new NdefRecord[]{record});
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopBluetoothServer();
    }
}