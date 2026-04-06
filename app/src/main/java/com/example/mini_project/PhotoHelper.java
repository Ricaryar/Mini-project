package com.example.mini_project;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class PhotoHelper {
    private static final String TAG = "PhotoHelper";
    private static final String APP_NAME = "NFCPhotoShare";

    /**
     * 保存Bitmap到相册
     * @param contentResolver ContentResolver
     * @param bitmap 要保存的图片
     * @param fileName 文件名
     * @return 保存后的Uri，失败返回null
     */
    public static Uri saveBitmapToGallery(ContentResolver contentResolver, Bitmap bitmap, String fileName) {
        try {
            // 压缩图片为JPEG格式
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, bytes);
            byte[] imageData = bytes.toByteArray();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10及以上使用MediaStore
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + APP_NAME);
                values.put(MediaStore.Images.Media.IS_PENDING, 1);

                Uri uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream outputStream = contentResolver.openOutputStream(uri);
                    if (outputStream != null) {
                        outputStream.write(imageData);
                        outputStream.close();
                    }
                    values.clear();
                    values.put(MediaStore.Images.Media.IS_PENDING, 0);
                    contentResolver.update(uri, values, null, null);
                    return uri;
                }
            } else {
                // Android 9及以下
                String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                String imageFileName = fileName != null ? fileName : "NFCPhoto_" + timeStamp + ".jpg";

                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, imageFileName);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");

                Uri uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri != null) {
                    OutputStream outputStream = contentResolver.openOutputStream(uri);
                    if (outputStream != null) {
                        outputStream.write(imageData);
                        outputStream.close();
                    }
                    return uri;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "保存图片失败", e);
        }
        return null;
    }

    /**
     * 从Uri加载Bitmap
     */
    public static Bitmap loadBitmapFromUri(ContentResolver contentResolver, Uri uri, int maxSize) {
        try {
            InputStream inputStream = contentResolver.openInputStream(uri);
            if (inputStream == null) return null;

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(inputStream, null, options);
            inputStream.close();

            int scale = 1;
            while (options.outWidth / scale > maxSize || options.outHeight / scale > maxSize) {
                scale *= 2;
            }

            options = new BitmapFactory.Options();
            options.inSampleSize = scale;

            inputStream = contentResolver.openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream, null, options);
            inputStream.close();

            return bitmap;
        } catch (Exception e) {
            Log.e(TAG, "加载图片失败", e);
            return null;
        }
    }

    /**
     * 将Bitmap转换为字节数组用于NFC传输
     */
    public static byte[] bitmapToBytes(Bitmap bitmap, int maxSize) {
        try {
            // 先压缩尺寸
            Bitmap scaledBitmap = bitmap;
            if (bitmap.getWidth() > maxSize || bitmap.getHeight() > maxSize) {
                float scale = Math.min((float) maxSize / bitmap.getWidth(), (float) maxSize / bitmap.getHeight());
                int newWidth = Math.round(bitmap.getWidth() * scale);
                int newHeight = Math.round(bitmap.getHeight() * scale);
                scaledBitmap = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos);

            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle();
            }

            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Bitmap转字节数组失败", e);
            return null;
        }
    }

    /**
     * 将字节数组转换为Bitmap
     */
    public static Bitmap bytesToBitmap(byte[] data) {
        try {
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (Exception e) {
            Log.e(TAG, "字节数组转Bitmap失败", e);
            return null;
        }
    }

    /**
     * 获取所有照片
     */
    public static List<PhotoItem> getAllPhotos(ContentResolver contentResolver) {
        List<PhotoItem> photos = new ArrayList<>();

        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATA
        };

        String sortOrder = MediaStore.Images.Media.DATE_ADDED + " DESC";

        try (Cursor cursor = contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder)) {

            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                int dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String name = cursor.getString(nameColumn);
                    long dateAdded = cursor.getLong(dateColumn);
                    Uri uri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));

                    PhotoItem photo = new PhotoItem();
                    photo.id = id;
                    photo.name = name;
                    photo.uri = uri;
                    photo.dateAdded = dateAdded;

                    photos.add(photo);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取照片列表失败", e);
        }

        return photos;
    }

    /**
     * 照片数据类
     */
    public static class PhotoItem {
        public long id;
        public String name;
        public Uri uri;
        public long dateAdded;

        public String getFormattedDate() {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            return sdf.format(new Date(dateAdded * 1000));
        }
    }
}