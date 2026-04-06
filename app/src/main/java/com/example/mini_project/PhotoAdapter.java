package com.example.mini_project;

import android.content.Context;
import android.graphics.Bitmap;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.ViewHolder> {

    private Context context;
    private List<PhotoHelper.PhotoItem> photos;
    private OnPhotoActionListener listener;

    public interface OnPhotoActionListener {
        void onShareNFC(PhotoHelper.PhotoItem photo);
        void onShareWiFi(PhotoHelper.PhotoItem photo);
        void onPhotoClick(PhotoHelper.PhotoItem photo);
    }

    public PhotoAdapter(Context context, List<PhotoHelper.PhotoItem> photos, OnPhotoActionListener listener) {
        this.context = context;
        this.photos = photos;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_photo, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        PhotoHelper.PhotoItem photo = photos.get(position);

        holder.txtDate.setText(photo.getFormattedDate());

        // 加载缩略图
        new Thread(() -> {
            Bitmap bitmap = PhotoHelper.loadBitmapFromUri(
                    context.getContentResolver(), photo.uri, 300);
            holder.itemView.post(() -> {
                if (bitmap != null) {
                    holder.imgPhoto.setImageBitmap(bitmap);
                }
            });
        }).start();

        holder.btnShareNFC.setOnClickListener(v -> listener.onShareNFC(photo));
        holder.btnShareWiFi.setOnClickListener(v -> listener.onShareWiFi(photo));
        holder.itemView.setOnClickListener(v -> listener.onPhotoClick(photo));
    }

    @Override
    public int getItemCount() {
        return photos.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView imgPhoto;
        TextView txtDate;
        Button btnShareNFC;
        Button btnShareWiFi;

        ViewHolder(View itemView) {
            super(itemView);
            imgPhoto = itemView.findViewById(R.id.img_photo);
            txtDate = itemView.findViewById(R.id.txt_date);
            btnShareNFC = itemView.findViewById(R.id.btn_share_nfc);
            btnShareWiFi = itemView.findViewById(R.id.btn_share_wifi);
        }
    }
}