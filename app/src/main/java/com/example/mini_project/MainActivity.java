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
    private Button btnReceiveNFC;
    private Button btnReceiveWiFi;
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

        // NFC 接收按钮
        btnReceiveNFC.setOnClickListener(v -> {
            Intent intent = new Intent(this, NFCReceiverActivity.class);
            startActivity(intent);
        });

        // WiFi Direct 接收按钮
        btnReceiveWiFi.setOnClickListener(v -> {
            Intent intent = new Intent(this, WifiDirectReceiverActivity.class);
            startActivity(intent);
        });

        loadPhotos();
    }

    private void initViews() {
        btnTakePhoto = findViewById(R.id.btn_take_photo);
        btnReceiveNFC = findViewById(R.id.btn_receive_nfc);
        btnReceiveWiFi = findViewById(R.id.btn_receive_wifi);
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
                // 点击照片可以预览，这里留空或可扩展
            }
        });
        recyclerPhotos.setLayoutManager(new GridLayoutManager(this, 2));
        recyclerPhotos.setAdapter(photoAdapter);
    }

    private void sharePhotoViaNFC(PhotoHelper.PhotoItem photo) {
        Intent intent = new Intent(this, NFCSenderActivity.class);
        intent.putExtra("photo_uri", photo.uri.toString());
        intent.putExtra("photo_name", photo.name);
        startActivity(intent);
    }

    private void sharePhotoViaWiFi(PhotoHelper.PhotoItem photo) {
        Intent intent = new Intent(this, WifiDirectSenderActivity.class);
        intent.putExtra("photo_uri", photo.uri.toString());
        intent.putExtra("photo_name", photo.name);
        startActivity(intent);
    }

    private void loadPhotos() {
        new Thread(() -> {
            List<PhotoHelper.PhotoItem> photos = PhotoHelper.getAllPhotos(getContentResolver());
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
            Toast.makeText(this, "照片已保存", Toast.LENGTH_SHORT).show();
            loadPhotos();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPhotos();
    }

    private void requestPermissions() {
        List<String> permissions = new ArrayList<>();

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }

        // WiFi Direct 需要的权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
                permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
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

        // NFC 权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.NFC)
                != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.NFC);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
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