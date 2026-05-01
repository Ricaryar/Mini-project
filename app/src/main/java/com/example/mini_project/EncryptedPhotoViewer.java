package com.example.mini_project;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 加密照片查看器
 * 需要输入密码才能查看
 */
public class EncryptedPhotoViewer extends AppCompatActivity {

    private ImageView imgPhoto;
    private TextView txtInfo;
    private EditText editPassword;
    private Button btnUnlock;
    private Button btnDelete;

    private long photoId;
    private String photoName;
    private PasswordProtectionHelper protectionHelper;
    private byte[] decryptedData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_encrypted_viewer);

        imgPhoto = findViewById(R.id.img_photo);
        txtInfo = findViewById(R.id.txt_info);
        editPassword = findViewById(R.id.edit_password);
        btnUnlock = findViewById(R.id.btn_unlock);
        btnDelete = findViewById(R.id.btn_delete);

        protectionHelper = new PasswordProtectionHelper(this);

        if (getIntent() != null) {
            photoId = getIntent().getLongExtra("photo_id", -1);
            photoName = getIntent().getStringExtra("photo_name");
        }

        if (photoId == -1) {
            Toast.makeText(this, "无效的照片", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        txtInfo.setText("加密照片: " + photoName + "\n请输入密码查看");

        btnUnlock.setOnClickListener(v -> unlockPhoto());
        btnDelete.setOnClickListener(v -> deletePhoto());
    }

    private void unlockPhoto() {
        String password = editPassword.getText().toString().trim();
        if (password.isEmpty()) {
            Toast.makeText(this, "请输入密码", Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(() -> {
            byte[] data = protectionHelper.loadEncryptedPhoto(photoId, password);
            runOnUiThread(() -> {
                if (data != null) {
                    decryptedData = data;
                    Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
                    if (bitmap != null) {
                        imgPhoto.setImageBitmap(bitmap);
                        txtInfo.setText("照片: " + photoName);
                        editPassword.setVisibility(View.GONE);
                        btnUnlock.setVisibility(View.GONE);
                        Toast.makeText(this, "解密成功", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "解码照片失败", Toast.LENGTH_LONG).show();
                    }
                } else {
                    Toast.makeText(this, "密码错误", Toast.LENGTH_LONG).show();
                }
            });
        }).start();
    }

    private void deletePhoto() {
        new AlertDialog.Builder(this)
                .setTitle("删除照片")
                .setMessage("确定要删除这张加密照片吗？")
                .setPositiveButton("确定", (dialog, which) -> {
                    protectionHelper.deleteEncryptedPhoto(photoId);
                    Toast.makeText(this, "照片已删除", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        protectionHelper.close();
    }
}