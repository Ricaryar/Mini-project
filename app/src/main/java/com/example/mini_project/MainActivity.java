package com.example.mini_project;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CAMERA_PERMISSION = 100;
    private static final int REQUEST_TAKE_PHOTO = 102;
    private static final int REQUEST_WIFI_PERMISSIONS = 103;

    private Button btnTakePhoto;
    private Button btnChatbot;
    private Button btnReceiveNFC;
    private Button btnReceiveWiFi;
    private Button btnViewEncrypted;
    private RecyclerView recyclerPhotos;
    private PhotoAdapter photoAdapter;
    private List<PhotoHelper.PhotoItem> photoList;
    private Uri currentPhotoUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupRecyclerView();
        requestPermissions();

        btnTakePhoto.setOnClickListener(v -> takePhoto());

        btnChatbot.setOnClickListener(v ->
                startActivity(new Intent(this, ChatbotComposeActivity.class)));

        btnReceiveNFC.setOnClickListener(v -> {
            Intent intent = new Intent(this, NFCReceiverActivity.class);
            startActivity(intent);
        });

        btnReceiveWiFi.setOnClickListener(v -> {
            Intent intent = new Intent(this, WifiDirectReceiverActivity.class);
            startActivity(intent);
        });

        btnViewEncrypted.setOnClickListener(v -> {
            Intent intent = new Intent(this, EncryptedPhotoListActivity.class);
            startActivity(intent);
        });

        loadPhotos();
    }

    private void initViews() {
        btnTakePhoto = findViewById(R.id.btn_take_photo);
        btnChatbot = findViewById(R.id.btn_chatbot);
        btnReceiveNFC = findViewById(R.id.btn_receive_nfc);
        btnReceiveWiFi = findViewById(R.id.btn_receive_wifi);
        btnViewEncrypted = findViewById(R.id.btn_view_encrypted);
        recyclerPhotos = findViewById(R.id.recycler_photos);
    }

    private void setupRecyclerView() {
        photoList = new ArrayList<>();
        photoAdapter = new PhotoAdapter(this, photoList, new PhotoAdapter.OnPhotoActionListener() {
            @Override
            public void onShareNFC(PhotoHelper.PhotoItem photo) {
                sharePhotoViaNFC(photo);
            }

            @Override
            public void onShareWiFi(PhotoHelper.PhotoItem photo) {
                sharePhotoViaWiFi(photo);
            }

            @Override
            public void onPhotoClick(PhotoHelper.PhotoItem photo) {
                // 点击照片可以预览
            }

            @Override
            public void onPhotoDeleted() {
                // 照片删除后刷新列表
                loadPhotos();
            }
        });
        recyclerPhotos.setLayoutManager(new GridLayoutManager(this, 2));
        recyclerPhotos.setAdapter(photoAdapter);
    }

    private void sharePhotoViaNFC(PhotoHelper.PhotoItem photo) {
        Intent intent = new Intent(this, NFCSenderActivity.class);
        intent.putExtra("photo_uri", photo.uri.toString());
        intent.putExtra("photo_name", photo.name);
        if (photo.isSensitive && photo.encryptedRefId != -1) {
            intent.putExtra("is_encrypted", true);
            intent.putExtra("encrypted_id", photo.encryptedRefId);
        } else {
            intent.putExtra("is_encrypted", false);
        }
        startActivity(intent);
    }

    private void sharePhotoViaWiFi(PhotoHelper.PhotoItem photo) {
        Intent intent = new Intent(this, WifiDirectSenderActivity.class);
        intent.putExtra("photo_uri", photo.uri.toString());
        intent.putExtra("photo_name", photo.name);
        if (photo.isSensitive && photo.encryptedRefId != -1) {
            intent.putExtra("is_encrypted", true);
            intent.putExtra("encrypted_id", photo.encryptedRefId);
        } else {
            intent.putExtra("is_encrypted", false);
        }
        startActivity(intent);
    }

    public void loadPhotos() {
        new Thread(() -> {
            List<PhotoHelper.PhotoItem> photos = PhotoHelper.getAllPhotos(getContentResolver());

            PasswordProtectionHelper protectionHelper = new PasswordProtectionHelper(this);
            List<PasswordProtectionHelper.EncryptedPhotoInfo> encryptedPhotos = protectionHelper.getAllEncryptedPhotos();
            protectionHelper.close();

            // 标记加密照片（通过文件名匹配）
            for (PhotoHelper.PhotoItem photo : photos) {
                photo.isSensitive = false;
                photo.encryptedRefId = -1;
                for (PasswordProtectionHelper.EncryptedPhotoInfo encrypted : encryptedPhotos) {
                    if (photo.name.equals(encrypted.name)) {
                        photo.isSensitive = true;
                        photo.encryptedRefId = encrypted.id;
                        break;
                    }
                }
            }

            runOnUiThread(() -> {
                photoList.clear();
                photoList.addAll(photos);
                photoAdapter.notifyDataSetChanged();
            });
        }).start();
    }

    private void takePhoto() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            String fileName = "Photo_" + System.currentTimeMillis() + ".jpg";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PhotoShare");
            }

            currentPhotoUri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            intent.putExtra(MediaStore.EXTRA_OUTPUT, currentPhotoUri);
            startActivityForResult(intent, REQUEST_TAKE_PHOTO);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_TAKE_PHOTO && resultCode == RESULT_OK) {
            // 拍照完成后，让用户选择是否需要加密保护
            showEncryptChoiceDialog(currentPhotoUri);
        }
    }

    // 让用户选择是否需要加密保护
    private void showEncryptChoiceDialog(Uri photoUri) {
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("照片加密保护")
                .setMessage("是否需要对此照片进行加密保护？\n\n选择「加密保护」后，照片将被加密保存，需要密码才能查看。")
                .setPositiveButton("加密保护", (dialog, which) -> {
                    showPasswordDialog(photoUri);
                })
                .setNegativeButton("普通保存", (dialog, which) -> {
                    Toast.makeText(this, "照片已保存", Toast.LENGTH_SHORT).show();
                    loadPhotos();
                })
                .show();
    }

    private void showPasswordDialog(Uri photoUri) {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(this);
        builder.setTitle("设置保护密码");

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("请输入密码（至少4位）");
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.setView(input);

        builder.setPositiveButton("确定", (dialog, which) -> {
            String password = input.getText().toString().trim();
            if (password.length() >= 4) {
                protectAndSavePhoto(photoUri, password);
            } else {
                Toast.makeText(this, "密码至少需要4位", Toast.LENGTH_SHORT).show();
                showPasswordDialog(photoUri);
            }
        });
        builder.setNegativeButton("取消", (dialog, which) -> {
            Toast.makeText(this, "照片已保存", Toast.LENGTH_SHORT).show();
            loadPhotos();
        });
        builder.show();
    }

    private void protectAndSavePhoto(Uri originalUri, String password) {
        Toast.makeText(this, "正在加密保存...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                // 加载照片
                android.graphics.Bitmap bitmap = PhotoHelper.loadBitmapFromUri(
                        getContentResolver(), originalUri, 1200);

                if (bitmap == null) {
                    runOnUiThread(() -> Toast.makeText(this, "加载照片失败", Toast.LENGTH_SHORT).show());
                    return;
                }

                // 转换为字节数组
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, baos);
                byte[] photoBytes = baos.toByteArray();

                // 获取原始文件名
                String fileName = null;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    String[] projection = {MediaStore.Images.Media.DISPLAY_NAME};
                    try (android.database.Cursor cursor = getContentResolver().query(originalUri, projection, null, null, null)) {
                        if (cursor != null && cursor.moveToFirst()) {
                            fileName = cursor.getString(0);
                        }
                    }
                }
                if (fileName == null) {
                    fileName = "Encrypted_" + System.currentTimeMillis() + ".jpg";
                }

                // 加密保存到数据库（保留原文件不删除）
                PasswordProtectionHelper protectionHelper = new PasswordProtectionHelper(this);
                long id = protectionHelper.saveEncryptedPhoto(photoBytes, fileName, password);
                protectionHelper.close();

                // 重要：不删除原图，只是标记原图为加密状态
                // 这样图片仍然在最近照片列表中，可以正常分享
                bitmap.recycle();

                runOnUiThread(() -> {
                    if (id != -1) {
                        Toast.makeText(this, "照片已加密（仍可在最近照片中查看）", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "加密失败", Toast.LENGTH_LONG).show();
                    }
                    loadPhotos();  // 刷新列表，会显示加密标记
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(this, "处理失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPhotos();
    }

    public void refreshPhotos() {
        loadPhotos();
    }


    private void requestPermissions() {
        List<String> permissions = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }

        // WiFi Direct 需要的权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
                permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }
        }

        // Android 12+ 蓝牙权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.INTERNET)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.INTERNET);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_WIFI_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_WIFI_STATE);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CHANGE_WIFI_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CHANGE_WIFI_STATE);
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.NFC)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.NFC);
        }

        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), REQUEST_WIFI_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WIFI_PERMISSIONS) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "需要相关权限才能使用完整功能", Toast.LENGTH_LONG).show();
                    break;
                }
            }
        }
    }
}