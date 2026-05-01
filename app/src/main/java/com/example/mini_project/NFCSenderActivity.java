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
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class NFCSenderActivity extends AppCompatActivity {
    private static final String TAG = "NFC_SENDER";
    private static final UUID BLUETOOTH_UUID = UUID.fromString("12345678-1234-1234-1234-123456789012");
    private static final int REQUEST_BLUETOOTH_CONNECT_PERMISSION = 1001;
    private static final int REQUEST_BLUETOOTH_ENABLE = 1002;

    private TextView txtStatus;
    private ProgressBar progressBar;
    private Button btnCancel;
    private BluetoothServerSocket serverSocket;
    private boolean isSending = false;
    private Thread serverThread;

    private byte[] photoData;
    private String photoName;
    private long encryptedPhotoId = -1;
    private boolean isEncrypted;
    private String userPassword;  // 用户输入的密码

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_sender);
        txtStatus = findViewById(R.id.txt_status);
        progressBar = findViewById(R.id.progress_bar);
        btnCancel = findViewById(R.id.btn_cancel);

        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> cancelSending());
        }

        checkAndRequestBluetoothPermissions();
        handleIntentAndStartServer();
    }

    private void checkAndRequestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.BLUETOOTH_CONNECT},
                        REQUEST_BLUETOOTH_CONNECT_PERMISSION);
            }
        }
    }

    private boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BLUETOOTH_CONNECT_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ensureBluetoothEnabled();
            } else {
                txtStatus.setText("❌ 需要蓝牙权限");
            }
        }
    }

    private void ensureBluetoothEnabled() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && !adapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_BLUETOOTH_ENABLE);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BLUETOOTH_ENABLE && resultCode != RESULT_OK) {
            txtStatus.setText("❌ 需要开启蓝牙");
        }
    }

    private void cancelSending() {
        isSending = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException e) {}
        if (serverThread != null) serverThread.interrupt();
        Toast.makeText(this, "已取消", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void handleIntentAndStartServer() {
        String photoUriString = getIntent().getStringExtra("photo_uri");
        photoName = getIntent().getStringExtra("photo_name");
        isEncrypted = getIntent().getBooleanExtra("is_encrypted", false);

        if (photoUriString == null && !isEncrypted) {
            txtStatus.setText("❌ 未找到照片");
            return;
        }

        if (isEncrypted) {
            encryptedPhotoId = getIntent().getLongExtra("encrypted_id", -1);
            txtStatus.setText("加密照片，请输入密码");
            showPasswordDialogForShare();
        } else {
            txtStatus.setText("正在准备照片...");
            loadNormalPhoto(photoUriString);
        }
    }

    private void showPasswordDialogForShare() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("设置保护密码");
        builder.setMessage("请设置一个密码，接收方需要这个密码才能查看照片");

        final EditText input = new EditText(this);
        input.setHint("密码（至少4位）");
        builder.setView(input);

        builder.setPositiveButton("确定", (dialog, which) -> {
            String password = input.getText().toString().trim();
            if (password.length() >= 4) {
                userPassword = password;
                loadEncryptedPhoto(password);
            } else {
                Toast.makeText(this, "密码至少4位", Toast.LENGTH_SHORT).show();
                showPasswordDialogForShare();
            }
        });
        builder.setNegativeButton("取消", (dialog, which) -> finish());
        builder.show();
    }

    private void loadNormalPhoto(String uriString) {
        new Thread(() -> {
            try {
                Uri uri = Uri.parse(uriString);
                Bitmap bitmap = PhotoHelper.loadBitmapFromUri(getContentResolver(), uri, 1024);
                if (bitmap != null) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);
                    photoData = baos.toByteArray();
                    bitmap.recycle();
                    runOnUiThread(() -> startBluetoothServer("NORM"));
                } else {
                    runOnUiThread(() -> txtStatus.setText("❌ 照片读取失败"));
                }
            } catch (Exception e) {
                runOnUiThread(() -> txtStatus.setText("❌ 加载失败"));
            }
        }).start();
    }

    private void loadEncryptedPhoto(String password) {
        txtStatus.setText("正在解密...");
        new Thread(() -> {
            try {
                PasswordProtectionHelper helper = new PasswordProtectionHelper(this);
                byte[] decryptedData = helper.loadEncryptedPhoto(encryptedPhotoId, password);
                helper.close();
                if (decryptedData != null) {
                    photoData = decryptedData;
                    runOnUiThread(() -> startBluetoothServer("DUAL"));
                } else {
                    runOnUiThread(() -> txtStatus.setText("❌ 密码错误"));
                }
            } catch (Exception e) {
                runOnUiThread(() -> txtStatus.setText("❌ 处理失败"));
            }
        }).start();
    }

    private void startBluetoothServer(String mode) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled() || !hasBluetoothConnectPermission()) {
            txtStatus.setText("❌ 蓝牙未就绪");
            return;
        }

        isSending = true;
        final String finalMode = mode;
        serverThread = new Thread(() -> {
            try {
                serverSocket = adapter.listenUsingRfcommWithServiceRecord("PhotoShare", BLUETOOTH_UUID);
                runOnUiThread(() -> txtStatus.setText("📡 等待连接..."));
                BluetoothSocket socket = serverSocket.accept();
                if (socket != null && isSending) {
                    if ("DUAL".equals(finalMode)) {
                        sendWithHybridEncryption(socket);
                    } else {
                        sendNormal(socket);
                    }
                }
            } catch (Exception e) {
                if (isSending) runOnUiThread(() -> txtStatus.setText("❌ 发送失败"));
            } finally {
                isSending = false;
            }
        });
        serverThread.start();
    }

    // 普通照片发送
    private void sendNormal(BluetoothSocket socket) {
        try {
            OutputStream out = socket.getOutputStream();
            out.write("NORM".getBytes());

            byte[] nameBytes = (photoName != null ? photoName : "photo.jpg").getBytes();
            out.write(ByteBuffer.allocate(4).putInt(nameBytes.length).array());
            out.write(nameBytes);
            out.write(ByteBuffer.allocate(4).putInt(photoData.length).array());
            out.write(photoData);
            out.flush();

            Thread.sleep(500);
            socket.close();
            if (serverSocket != null) serverSocket.close();

            runOnUiThread(() -> {
                txtStatus.setText("✅ 发送成功！");
                new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1500);
            });
        } catch (Exception e) {
            runOnUiThread(() -> txtStatus.setText("❌ 发送失败"));
        }
    }

    /**
     * 双重验证加密架构 (Hybrid Encryption)
     *
     * 最终密钥 = HKDF(K_ECDH || K_Password)
     * 接收方需要同时拥有：
     *   1. ECDH 自动协商的共享密钥 K_ECDH
     *   2. 用户输入的密码 P (派生 K_Password)
     * 才能解密照片
     */
    private void sendWithHybridEncryption(BluetoothSocket socket) {
        try {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            runOnUiThread(() -> txtStatus.setText("正在建立安全通道..."));

            // ========== 第1层：ECDH 密钥交换（自动） ==========
            ECCEncryptionHelper eccHelper = new ECCEncryptionHelper();
            eccHelper.generateKeyPair();
            String myPublicKey = eccHelper.getPublicKeyBase64();

            // 发送模式标识
            out.write("DUAL".getBytes());

            // 发送 ECDH 公钥
            byte[] pubKeyBytes = myPublicKey.getBytes();
            out.write(ByteBuffer.allocate(4).putInt(pubKeyBytes.length).array());
            out.write(pubKeyBytes);
            out.flush();

            // 接收对方 ECDH 公钥
            byte[] lenBuf = new byte[4];
            int read = in.read(lenBuf);
            if (read != 4) throw new Exception("接收公钥失败");
            int otherKeyLen = ByteBuffer.wrap(lenBuf).getInt();
            byte[] otherKeyBytes = new byte[otherKeyLen];
            read = in.read(otherKeyBytes);
            if (read != otherKeyLen) throw new Exception("接收公钥不完整");
            String otherPublicKey = new String(otherKeyBytes);
            eccHelper.loadOtherPublicKey(otherPublicKey);

            // 派生 ECDH 共享密钥 K1
            KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
            keyAgreement.init(eccHelper.getPrivateKey());
            keyAgreement.doPhase(eccHelper.getOtherPublicKey(), true);
            byte[] kEcdh = keyAgreement.generateSecret();

            runOnUiThread(() -> txtStatus.setText("ECDH 密钥交换完成"));

            // ========== 第2层：从用户密码派生密钥 K2 ==========
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);

            javax.crypto.SecretKeyFactory factory =
                    javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            java.security.spec.KeySpec spec =
                    new javax.crypto.spec.PBEKeySpec(userPassword.toCharArray(), salt, 10000, 256);
            SecretKey tmp = factory.generateSecret(spec);
            byte[] kPassword = tmp.getEncoded();

            runOnUiThread(() -> txtStatus.setText("密码密钥派生完成"));

            // ========== 组合：双重密钥 ==========
            // 使用 HKDF 合并两个密钥
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            digest.update(kEcdh);
            digest.update(kPassword);
            byte[] combinedKey = digest.digest();

            // 取前 32 字节作为最终 AES 密钥
            byte[] finalKey = new byte[32];
            System.arraycopy(combinedKey, 0, finalKey, 0, 32);
            SecretKeySpec finalSecretKey = new SecretKeySpec(finalKey, "AES");

            runOnUiThread(() -> txtStatus.setText("双重密钥生成完成"));

            // ========== AES 加密照片 ==========
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, finalSecretKey, new IvParameterSpec(iv));
            byte[] cipherText = cipher.doFinal(photoData);

            // ========== 发送数据 ==========
            // 发送 salt（接收方需要它来派生 K2）
            out.write(salt);

            // 发送 IV
            out.write(iv);

            // 发送文件名
            byte[] nameBytes = (photoName != null ? photoName : "encrypted.jpg").getBytes();
            out.write(ByteBuffer.allocate(4).putInt(nameBytes.length).array());
            out.write(nameBytes);

            // 发送密文
            out.write(ByteBuffer.allocate(4).putInt(cipherText.length).array());
            out.write(cipherText);
            out.flush();

            Thread.sleep(500);
            socket.close();
            if (serverSocket != null) serverSocket.close();

            runOnUiThread(() -> {
                txtStatus.setText("✅ 双重加密照片发送成功！");
                Toast.makeText(this, "加密照片已发送\n接收方需要密码才能查看", Toast.LENGTH_LONG).show();
                new Handler(Looper.getMainLooper()).postDelayed(this::finish, 2000);
            });

        } catch (Exception e) {
            Log.e(TAG, "Hybrid error", e);
            runOnUiThread(() -> txtStatus.setText("❌ 发送失败: " + e.getMessage()));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        isSending = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException e) {}
    }
}