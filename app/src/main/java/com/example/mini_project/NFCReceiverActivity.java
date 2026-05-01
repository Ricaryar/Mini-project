package com.example.mini_project;

import android.Manifest;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.SecretKeyFactory;

public class NFCReceiverActivity extends AppCompatActivity implements NfcAdapter.ReaderCallback {
    private static final UUID BLUETOOTH_UUID = UUID.fromString("12345678-1234-1234-1234-123456789012");
    private static final int REQUEST_BLUETOOTH_ENABLE = 1001;
    private static final int REQUEST_BLUETOOTH_PERMISSIONS = 1002;

    // 接收到的加密数据（用于双重验证模式）
    private byte[] receivedSalt;
    private byte[] receivedIv;
    private byte[] receivedCipherText;
    private String receivedFileName;
    private byte[] kEcdh;

    private NfcAdapter nfcAdapter;
    private TextView txtStatus;
    private Button btnCancel;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isReceiving = false;
    private BluetoothSocket currentSocket;
    private BluetoothAdapter bluetoothAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_receiver);
        txtStatus = findViewById(R.id.txt_status);
        btnCancel = findViewById(R.id.btn_cancel);

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        btnCancel.setOnClickListener(v -> {
            try { if (currentSocket != null) currentSocket.close(); } catch (Exception e) {}
            finish();
        });

        checkAndRequestBluetoothPermissions();
    }

    private void checkAndRequestBluetoothPermissions() {
        if (bluetoothAdapter == null) {
            txtStatus.setText("❌ 不支持蓝牙");
            return;
        }
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), REQUEST_BLUETOOTH_PERMISSIONS);
        } else {
            checkAndEnableBluetooth();
        }
    }

    private boolean hasBluetoothPermissions() {
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
        if (requestCode == REQUEST_BLUETOOTH_PERMISSIONS) {
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) {
                    txtStatus.setText("❌ 需要蓝牙权限");
                    return;
                }
            }
            checkAndEnableBluetooth();
        }
    }

    private void checkAndEnableBluetooth() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_BLUETOOTH_ENABLE);
        } else {
            txtStatus.setText("蓝牙已就绪，触碰NFC");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BLUETOOTH_ENABLE && resultCode != RESULT_OK) {
            txtStatus.setText("❌ 需要开启蓝牙");
        }
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
        if (isReceiving) return;
        runOnUiThread(() -> txtStatus.setText("🔗 发现设备，连接中..."));
        connectToSender();
    }

    private void connectToSender() {
        if (isReceiving) return;
        isReceiving = true;

        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled() || !hasBluetoothPermissions()) {
            runOnUiThread(() -> txtStatus.setText("❌ 蓝牙未就绪"));
            isReceiving = false;
            return;
        }

        new Thread(() -> {
            BluetoothSocket socket = null;
            try {
                Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
                if (pairedDevices == null || pairedDevices.isEmpty()) {
                    runOnUiThread(() -> txtStatus.setText("❌ 请先配对蓝牙"));
                    isReceiving = false;
                    return;
                }
                for (BluetoothDevice device : pairedDevices) {
                    try {
                        socket = device.createRfcommSocketToServiceRecord(BLUETOOTH_UUID);
                        socket.connect();
                        break;
                    } catch (Exception e) {
                        try { if (socket != null) socket.close(); } catch (Exception ex) {}
                        socket = null;
                    }
                }
                if (socket != null && socket.isConnected()) {
                    currentSocket = socket;
                    runOnUiThread(() -> txtStatus.setText("已连接，接收中..."));
                    receiveData(socket);
                } else {
                    runOnUiThread(() -> txtStatus.setText("❌ 连接失败"));
                    isReceiving = false;
                }
            } catch (Exception e) {
                runOnUiThread(() -> txtStatus.setText("❌ 连接失败"));
                isReceiving = false;
            }
        }).start();
    }

    private void receiveData(BluetoothSocket socket) {
        try {
            InputStream in = socket.getInputStream();

            // 先读取第一个字节判断模式
            int firstByte = in.read();
            if (firstByte == -1) {
                throw new Exception("连接已断开");
            }

            // 读取剩余的3个字节
            byte[] remaining = new byte[3];
            int read = in.read(remaining);
            if (read != 3) {
                throw new Exception("读取模式标识失败");
            }

            byte[] modeBytes = new byte[4];
            modeBytes[0] = (byte) firstByte;
            System.arraycopy(remaining, 0, modeBytes, 1, 3);
            String mode = new String(modeBytes);

            Log.d("NFC_RECEIVE", "收到模式: " + mode);

            if ("DUAL".equals(mode)) {
                // 双重验证加密接收 - 需要交换ECDH密钥
                receiveHybridEncrypted(in, socket);
            } else if ("NORM".equals(mode)) {
                // 普通照片接收
                receiveNormal(in, socket);
            } else {
                throw new Exception("未知模式: " + mode);
            }
        } catch (Exception e) {
            Log.e("NFC_RECEIVE", "Error", e);
            runOnUiThread(() -> {
                txtStatus.setText("❌ 接收失败: " + e.getMessage());
                Toast.makeText(this, "接收失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
            try { socket.close(); } catch (Exception ex) {}
        } finally {
            isReceiving = false;
            try { if (currentSocket != null) currentSocket.close(); } catch (Exception e) {}
        }
    }

    // 普通照片接收 - 保持原有逻辑
    private void receiveNormal(InputStream in, BluetoothSocket socket) throws Exception {
        // 读取文件名长度
        byte[] lenBuf = new byte[4];
        int read = in.read(lenBuf);
        if (read != 4) throw new Exception("读取文件名长度失败");
        int nameLen = ByteBuffer.wrap(lenBuf).getInt();

        // 读取文件名
        byte[] nameBytes = new byte[nameLen];
        read = in.read(nameBytes);
        if (read != nameLen) throw new Exception("读取文件名失败");

        // 读取数据大小
        read = in.read(lenBuf);
        if (read != 4) throw new Exception("读取数据大小失败");
        int fileSize = ByteBuffer.wrap(lenBuf).getInt();

        // 读取照片数据
        byte[] photoData = new byte[fileSize];
        int total = 0;
        while (total < fileSize) {
            int r = in.read(photoData, total, fileSize - total);
            if (r == -1) break;
            total += r;
        }

        if (total != fileSize) throw new Exception("数据接收不完整: " + total + "/" + fileSize);

        socket.close();

        // 保存照片
        Bitmap bitmap = BitmapFactory.decodeByteArray(photoData, 0, photoData.length);
        if (bitmap != null) {
            String name = "NFC_IMG_" + System.currentTimeMillis() + ".jpg";
            PhotoHelper.saveBitmapToGallery(getContentResolver(), bitmap, name);
            runOnUiThread(() -> {
                txtStatus.setText("✅ 接收成功！");
                Toast.makeText(this, "照片已保存", Toast.LENGTH_SHORT).show();
                mainHandler.postDelayed(this::finish, 2000);
            });
        } else {
            throw new Exception("解码照片失败");
        }
    }

    // 双重验证加密接收 - 需要 ECDH 密钥交换
    private void receiveHybridEncrypted(InputStream in, BluetoothSocket socket) throws Exception {
        OutputStream out = socket.getOutputStream();

        runOnUiThread(() -> txtStatus.setText("正在建立安全通道..."));

        // ========== ECDH 密钥交换 ==========
        // 读取发送方的 ECDH 公钥
        byte[] lenBuf = new byte[4];
        int read = in.read(lenBuf);
        if (read != 4) throw new Exception("读取公钥失败");
        int keyLen = ByteBuffer.wrap(lenBuf).getInt();
        byte[] keyBytes = new byte[keyLen];
        read = in.read(keyBytes);
        if (read != keyLen) throw new Exception("读取公钥不完整");
        String senderPublicKey = new String(keyBytes);

        // 生成自己的 ECDH 密钥对并发送公钥
        ECCEncryptionHelper eccHelper = new ECCEncryptionHelper();
        eccHelper.generateKeyPair();
        String myPublicKey = eccHelper.getPublicKeyBase64();
        byte[] myKeyBytes = myPublicKey.getBytes();
        out.write(ByteBuffer.allocate(4).putInt(myKeyBytes.length).array());
        out.write(myKeyBytes);
        out.flush();

        // 派生 ECDH 共享密钥
        eccHelper.loadOtherPublicKey(senderPublicKey);
        KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
        keyAgreement.init(eccHelper.getPrivateKey());
        keyAgreement.doPhase(eccHelper.getOtherPublicKey(), true);
        kEcdh = keyAgreement.generateSecret();

        runOnUiThread(() -> txtStatus.setText("ECDH 密钥交换完成"));

        // ========== 读取加密数据 ==========
        // 读取 salt
        byte[] salt = new byte[16];
        read = in.read(salt);
        if (read != 16) throw new Exception("读取盐值失败");
        receivedSalt = salt;

        // 读取 IV
        byte[] iv = new byte[12];
        read = in.read(iv);
        if (read != 12) throw new Exception("读取IV失败");
        receivedIv = iv;

        // 读取文件名
        read = in.read(lenBuf);
        if (read != 4) throw new Exception("读取文件名失败");
        int nameLen = ByteBuffer.wrap(lenBuf).getInt();
        byte[] nameBytes = new byte[nameLen];
        read = in.read(nameBytes);
        if (read != nameLen) throw new Exception("读取文件名不完整");
        receivedFileName = new String(nameBytes);

        // 读取密文
        read = in.read(lenBuf);
        if (read != 4) throw new Exception("读取数据大小失败");
        int dataSize = ByteBuffer.wrap(lenBuf).getInt();
        byte[] cipherText = new byte[dataSize];
        int total = 0;
        while (total < dataSize) {
            int r = in.read(cipherText, total, dataSize - total);
            if (r == -1) break;
            total += r;
        }
        if (total != dataSize) throw new Exception("数据不完整");
        receivedCipherText = cipherText;

        socket.close();

        // ========== 请求用户输入密码 ==========
        runOnUiThread(() -> {
            txtStatus.setText("📸 收到加密照片\n请输入密码查看");
            showPasswordDialogToDecrypt();
        });
    }

    private void showPasswordDialogToDecrypt() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("加密照片");
        builder.setMessage("此照片已加密，请输入密码查看\n\n密码由发送方设置，请向发送方获取");

        final EditText input = new EditText(this);
        input.setHint("密码");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.setView(input);

        builder.setPositiveButton("解密并保存", (dialog, which) -> {
            String password = input.getText().toString().trim();
            if (!password.isEmpty()) {
                decryptAndSave(password);
            } else {
                Toast.makeText(this, "请输入密码", Toast.LENGTH_SHORT).show();
                showPasswordDialogToDecrypt();
            }
        });
        builder.setNegativeButton("取消", (dialog, which) -> finish());
        builder.show();
    }

    private void decryptAndSave(String password) {
        runOnUiThread(() -> txtStatus.setText("正在验证密码并解密..."));

        new Thread(() -> {
            try {
                if (kEcdh == null) {
                    runOnUiThread(() -> {
                        txtStatus.setText("❌ 密钥交换失败");
                        Toast.makeText(this, "密钥交换失败", Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                // 从密码派生密钥
                SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
                PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), receivedSalt, 10000, 256);
                SecretKey tmp = factory.generateSecret(spec);
                byte[] kPassword = tmp.getEncoded();

                // 合并双重密钥
                MessageDigest digest = MessageDigest.getInstance("SHA-512");
                digest.update(kEcdh);
                digest.update(kPassword);
                byte[] combinedKey = digest.digest();

                byte[] finalKey = new byte[32];
                System.arraycopy(combinedKey, 0, finalKey, 0, 32);
                SecretKeySpec finalSecretKey = new SecretKeySpec(finalKey, "AES");

                // AES 解密
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, finalSecretKey, new IvParameterSpec(receivedIv));
                byte[] decryptedData = cipher.doFinal(receivedCipherText);

                // 保存照片
                Bitmap bitmap = BitmapFactory.decodeByteArray(decryptedData, 0, decryptedData.length);
                if (bitmap != null) {
                    String name = "Decrypted_" + System.currentTimeMillis() + ".jpg";
                    PhotoHelper.saveBitmapToGallery(getContentResolver(), bitmap, name);

                    runOnUiThread(() -> {
                        txtStatus.setText("✅ 解密成功！照片已保存");
                        Toast.makeText(this, "照片已保存到相册", Toast.LENGTH_LONG).show();
                        mainHandler.postDelayed(this::finish, 2000);
                    });
                } else {
                    throw new Exception("解码失败");
                }

            } catch (javax.crypto.AEADBadTagException e) {
                runOnUiThread(() -> {
                    txtStatus.setText("❌ 密码错误");
                    Toast.makeText(this, "密码错误，请重试", Toast.LENGTH_LONG).show();
                    showPasswordDialogToDecrypt();
                });
            } catch (Exception e) {
                Log.e("NFC_RECEIVE", "Decrypt error", e);
                runOnUiThread(() -> {
                    txtStatus.setText("❌ 解密失败: " + e.getMessage());
                    Toast.makeText(this, "解密失败", Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) nfcAdapter.disableReaderMode(this);
        try { if (currentSocket != null) currentSocket.close(); } catch (Exception e) {}
    }
}