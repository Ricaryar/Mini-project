package com.example.mini_project;

import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class PhotoAdapter extends RecyclerView.Adapter<PhotoAdapter.ViewHolder> {

    private Context context;
    private List<PhotoHelper.PhotoItem> photos;
    private OnPhotoActionListener listener;
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface OnPhotoActionListener {
        void onShareNFC(PhotoHelper.PhotoItem photo);
        void onShareWiFi(PhotoHelper.PhotoItem photo);
        void onPhotoClick(PhotoHelper.PhotoItem photo);
        void onPhotoDeleted();  // 新增：照片删除后的回调
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
            mainHandler.post(() -> {
                if (bitmap != null) {
                    holder.imgPhoto.setImageBitmap(bitmap);
                }
            });
        }).start();

        holder.btnShareNFC.setOnClickListener(v -> listener.onShareNFC(photo));
        holder.btnShareWiFi.setOnClickListener(v -> listener.onShareWiFi(photo));

        // 点击图片显示选项菜单（包含删除）
        holder.imgPhoto.setOnClickListener(v -> showPhotoOptionsDialog(photo, holder.getAdapterPosition()));
        holder.txtDate.setOnClickListener(v -> showPhotoOptionsDialog(photo, holder.getAdapterPosition()));
    }

    private void showPhotoOptionsDialog(PhotoHelper.PhotoItem photo, int position) {
        String[] options;
        if (photo.isSensitive) {
            options = new String[]{"📷 查看图片", "🔒 已是加密状态", "ℹ️ 图片信息", "🗑️ 删除图片"};
        } else {
            options = new String[]{"📷 查看图片", "🔐 加密此图片", "ℹ️ 图片信息", "🗑️ 删除图片"};
        }

        new AlertDialog.Builder(context)
                .setTitle(photo.name)
                .setItems(options, (dialog, which) -> {
                    switch (which) {
                        case 0: // 查看图片
                            viewPhoto(photo);
                            break;
                        case 1: // 加密图片
                            if (!photo.isSensitive) {
                                showEncryptDialog(photo);
                            }
                            break;
                        case 2: // 图片信息
                            showPhotoInfo(photo);
                            break;
                        case 3: // 删除图片
                            showDeleteConfirmDialog(photo, position);
                            break;
                    }
                })
                .show();
    }

    private void viewPhoto(PhotoHelper.PhotoItem photo) {
        // 如果是加密照片，需要输入密码才能查看
        if (photo.isSensitive && photo.encryptedRefId != -1) {
            // 弹窗输入密码
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("加密照片");
            builder.setMessage("此照片已加密，请输入密码查看");

            final EditText input = new EditText(context);
            input.setHint("密码");
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT |
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
            builder.setView(input);

            builder.setPositiveButton("查看", (dialog, which) -> {
                String password = input.getText().toString().trim();
                if (!password.isEmpty()) {
                    decryptAndViewPhoto(photo, password);
                } else {
                    Toast.makeText(context, "请输入密码", Toast.LENGTH_SHORT).show();
                }
            });
            builder.setNegativeButton("取消", null);
            builder.show();
        } else {
            // 普通照片，用系统图片查看器
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(photo.uri, "image/jpeg");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            context.startActivity(intent);
        }
    }

    private void decryptAndViewPhoto(PhotoHelper.PhotoItem photo, String password) {
        new Thread(() -> {
            try {
                PasswordProtectionHelper helper = new PasswordProtectionHelper(context);
                byte[] decryptedData = helper.loadEncryptedPhoto(photo.encryptedRefId, password);
                helper.close();

                if (decryptedData != null) {
                    // 保存临时文件并查看
                    Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(decryptedData, 0, decryptedData.length);
                    if (bitmap != null) {
                        mainHandler.post(() -> {
                            // 显示临时查看对话框
                            showTempImageViewer(bitmap);
                        });
                    } else {
                        mainHandler.post(() -> Toast.makeText(context, "解码失败", Toast.LENGTH_SHORT).show());
                    }
                } else {
                    mainHandler.post(() -> Toast.makeText(context, "密码错误", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> Toast.makeText(context, "解密失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void showTempImageViewer(Bitmap bitmap) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("查看加密照片");

        android.widget.ImageView imageView = new android.widget.ImageView(context);
        imageView.setImageBitmap(bitmap);
        imageView.setAdjustViewBounds(true);
        imageView.setMaxHeight(800);

        builder.setView(imageView);
        builder.setPositiveButton("关闭", null);
        builder.show();
    }

    private void showEncryptDialog(PhotoHelper.PhotoItem photo) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("加密图片");
        builder.setMessage("设置密码来加密这张图片");

        final EditText input = new EditText(context);
        input.setHint("请输入密码（至少4位）");
        builder.setView(input);

        builder.setPositiveButton("加密", (dialog, which) -> {
            String password = input.getText().toString().trim();
            if (password.length() >= 4) {
                encryptExistingPhoto(photo, password);
            } else {
                Toast.makeText(context, "密码至少需要4位", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("取消", null);
        builder.show();
    }

    private void encryptExistingPhoto(PhotoHelper.PhotoItem photo, String password) {
        Toast.makeText(context, "正在加密...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                // 加载原图
                Bitmap bitmap = PhotoHelper.loadBitmapFromUri(
                        context.getContentResolver(), photo.uri, 1200);

                if (bitmap == null) {
                    mainHandler.post(() -> Toast.makeText(context, "加载图片失败", Toast.LENGTH_SHORT).show());
                    return;
                }

                // 转换为字节数组
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos);
                byte[] photoBytes = baos.toByteArray();

                // 加密保存到数据库
                PasswordProtectionHelper helper = new PasswordProtectionHelper(context);
                long id = helper.saveEncryptedPhoto(photoBytes, photo.name, password);
                helper.close();

                // 重要：不删除原图，只标记为加密状态
                // 更新列表中的标记
                photo.isSensitive = true;
                photo.encryptedRefId = id;

                bitmap.recycle();

                mainHandler.post(() -> {
                    if (id != -1) {
                        Toast.makeText(context, "图片已加密（仍可正常分享）", Toast.LENGTH_LONG).show();
                        // 刷新列表显示加密标记
                        if (context instanceof MainActivity) {
                            ((MainActivity) context).refreshPhotos();
                        }
                    } else {
                        Toast.makeText(context, "加密失败", Toast.LENGTH_LONG).show();
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> Toast.makeText(context, "加密失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void showPhotoInfo(PhotoHelper.PhotoItem photo) {
        String info = "文件名: " + photo.name + "\n" +
                "拍摄时间: " + photo.getFormattedDate() + "\n" +
                "路径: " + photo.uri.toString() + "\n" +
                "状态: " + (photo.isSensitive ? "🔒 已加密" : "📁 普通照片");

        new AlertDialog.Builder(context)
                .setTitle("图片信息")
                .setMessage(info)
                .setPositiveButton("确定", null)
                .show();
    }

    private void showDeleteConfirmDialog(PhotoHelper.PhotoItem photo, int position) {
        new AlertDialog.Builder(context)
                .setTitle("删除图片")
                .setMessage("确定要删除 \"" + photo.name + "\" 吗？此操作不可撤销。")
                .setPositiveButton("删除", (dialog, which) -> {
                    deletePhoto(photo, position);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void deletePhoto(PhotoHelper.PhotoItem photo, int position) {
        Toast.makeText(context, "正在删除...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            boolean success = false;

            try {
                // 如果是加密照片，同时从加密数据库中删除
                if (photo.isSensitive && photo.encryptedRefId != -1) {
                    PasswordProtectionHelper helper = new PasswordProtectionHelper(context);
                    success = helper.deleteEncryptedPhoto(photo.encryptedRefId);
                    helper.close();
                }

                // 删除原始图片文件
                ContentResolver resolver = context.getContentResolver();
                int deleted = resolver.delete(photo.uri, null, null);
                success = success || (deleted > 0);

                final boolean finalSuccess = success;
                mainHandler.post(() -> {
                    if (finalSuccess) {
                        Toast.makeText(context, "图片已删除", Toast.LENGTH_SHORT).show();
                        // 从列表中移除
                        photos.remove(position);
                        notifyItemRemoved(position);
                        notifyItemRangeChanged(position, photos.size());
                        // 通知Activity刷新
                        if (listener != null) {
                            listener.onPhotoDeleted();
                        }
                    } else {
                        Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                mainHandler.post(() -> Toast.makeText(context, "删除失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
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