package com.example.mini_project;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class EncryptedPhotoListActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private EncryptedPhotoAdapter adapter;
    private PasswordProtectionHelper protectionHelper;
    private List<PasswordProtectionHelper.EncryptedPhotoInfo> photoList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_encrypted_list);

        recyclerView = findViewById(R.id.recycler_encrypted_photos);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        protectionHelper = new PasswordProtectionHelper(this);

        loadPhotos();
    }

    private void loadPhotos() {
        new Thread(() -> {
            photoList = protectionHelper.getAllEncryptedPhotos();
            runOnUiThread(() -> {
                adapter = new EncryptedPhotoAdapter(photoList);
                recyclerView.setAdapter(adapter);
            });
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadPhotos();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        protectionHelper.close();
    }

    private class EncryptedPhotoAdapter extends RecyclerView.Adapter<EncryptedPhotoAdapter.ViewHolder> {

        private List<PasswordProtectionHelper.EncryptedPhotoInfo> photos;

        EncryptedPhotoAdapter(List<PasswordProtectionHelper.EncryptedPhotoInfo> photos) {
            this.photos = photos;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_encrypted_photo, parent, false);
            return new ViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            PasswordProtectionHelper.EncryptedPhotoInfo photo = photos.get(position);
            holder.txtName.setText(photo.name);
            holder.txtDate.setText(photo.getFormattedDate());
            holder.txtStatus.setText("🔒 已加密");

            // 显示锁定图标作为预览（因为加密照片无法直接显示缩略图）
            holder.imgPreview.setImageResource(android.R.drawable.ic_menu_gallery);
            holder.imgPreview.setAlpha(0.5f);

            holder.itemView.setOnClickListener(v -> {
                Intent intent = new Intent(EncryptedPhotoListActivity.this, EncryptedPhotoViewer.class);
                intent.putExtra("photo_id", photo.id);
                intent.putExtra("photo_name", photo.name);
                startActivity(intent);
            });
        }

        @Override
        public int getItemCount() {
            return photos.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            ImageView imgPreview;
            TextView txtName;
            TextView txtDate;
            TextView txtStatus;

            ViewHolder(View itemView) {
                super(itemView);
                imgPreview = itemView.findViewById(R.id.img_preview);
                txtName = itemView.findViewById(R.id.txt_name);
                txtDate = itemView.findViewById(R.id.txt_date);
                txtStatus = itemView.findViewById(R.id.txt_status);
            }
        }
    }
}