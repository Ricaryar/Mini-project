package com.example.mini_project;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Base64;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 密码保护助手
 * 用于加密存储敏感照片
 */
public class PasswordProtectionHelper {
    private static final String DATABASE_NAME = "secure_photos.db";
    private static final int DATABASE_VERSION = 1;
    private static final String TABLE_NAME = "encrypted_photos";

    private static final String ALGORITHM = "AES";
    private static final String CIPHER_MODE = "AES/CBC/PKCS5Padding";
    private static final int ITERATION_COUNT = 10000;
    private static final int KEY_LENGTH = 256;

    private Context context;
    private DatabaseHelper dbHelper;

    public PasswordProtectionHelper(Context context) {
        this.context = context;
        this.dbHelper = new DatabaseHelper(context);
    }

    private static class DatabaseHelper extends SQLiteOpenHelper {
        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            String createTable = "CREATE TABLE " + TABLE_NAME + " (" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT, " +
                    "encrypted_data TEXT, " +
                    "iv TEXT, " +
                    "salt TEXT, " +
                    "timestamp LONG" +
                    ")";
            db.execSQL(createTable);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
            onCreate(db);
        }
    }

    /**
     * 使用密码加密并保存照片
     * @param photoData 照片数据
     * @param name 照片名称
     * @param password 用户密码
     * @return 保存后的ID，-1表示失败
     */
    public long saveEncryptedPhoto(byte[] photoData, String name, String password) {
        try {
            // 生成随机盐值
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            String saltStr = Base64.encodeToString(salt, Base64.NO_WRAP);

            // 生成随机IV
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            String ivStr = Base64.encodeToString(iv, Base64.NO_WRAP);

            // 从密码派生密钥
            SecretKey key = deriveKeyFromPassword(password, salt);

            // 加密数据
            Cipher cipher = Cipher.getInstance(CIPHER_MODE);
            cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));
            byte[] encryptedData = cipher.doFinal(photoData);
            String encryptedStr = Base64.encodeToString(encryptedData, Base64.NO_WRAP);

            // 保存到数据库
            ContentValues values = new ContentValues();
            values.put("name", name);
            values.put("encrypted_data", encryptedStr);
            values.put("iv", ivStr);
            values.put("salt", saltStr);
            values.put("timestamp", System.currentTimeMillis());

            return dbHelper.getWritableDatabase().insert(TABLE_NAME, null, values);

        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

    /**
     * 使用密码解密照片
     * @param photoId 照片ID
     * @param password 用户密码
     * @return 解密后的照片数据，失败返回null
     */
    public byte[] loadEncryptedPhoto(long photoId, String password) {
        try {
            // 从数据库读取
            String query = "SELECT * FROM " + TABLE_NAME + " WHERE _id = ?";
            Cursor cursor = dbHelper.getReadableDatabase().rawQuery(query, new String[]{String.valueOf(photoId)});

            if (!cursor.moveToFirst()) {
                cursor.close();
                return null;
            }

            String encryptedStr = cursor.getString(cursor.getColumnIndexOrThrow("encrypted_data"));
            String ivStr = cursor.getString(cursor.getColumnIndexOrThrow("iv"));
            String saltStr = cursor.getString(cursor.getColumnIndexOrThrow("salt"));

            cursor.close();

            // 解码数据
            byte[] encryptedData = Base64.decode(encryptedStr, Base64.NO_WRAP);
            byte[] iv = Base64.decode(ivStr, Base64.NO_WRAP);
            byte[] salt = Base64.decode(saltStr, Base64.NO_WRAP);

            // 从密码派生密钥
            SecretKey key = deriveKeyFromPassword(password, salt);

            // 解密
            Cipher cipher = Cipher.getInstance(CIPHER_MODE);
            cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));

            return cipher.doFinal(encryptedData);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // 获取原始加密数据（不进行解密）
    public byte[] getRawEncryptedData(long photoId) {
        String query = "SELECT encrypted_data FROM " + TABLE_NAME + " WHERE _id = ?";
        Cursor cursor = dbHelper.getReadableDatabase().rawQuery(query, new String[]{String.valueOf(photoId)});

        if (cursor.moveToFirst()) {
            String encryptedStr = cursor.getString(0);
            cursor.close();
            return Base64.decode(encryptedStr, Base64.NO_WRAP);
        }
        cursor.close();
        return null;
    }

    // 获取IV
    public String getIvForPhoto(long photoId) {
        String query = "SELECT iv FROM " + TABLE_NAME + " WHERE _id = ?";
        Cursor cursor = dbHelper.getReadableDatabase().rawQuery(query, new String[]{String.valueOf(photoId)});

        if (cursor.moveToFirst()) {
            String iv = cursor.getString(0);
            cursor.close();
            return iv;
        }
        cursor.close();
        return null;
    }

    // 获取Salt
    public String getSaltForPhoto(long photoId) {
        String query = "SELECT salt FROM " + TABLE_NAME + " WHERE _id = ?";
        Cursor cursor = dbHelper.getReadableDatabase().rawQuery(query, new String[]{String.valueOf(photoId)});

        if (cursor.moveToFirst()) {
            String salt = cursor.getString(0);
            cursor.close();
            return salt;
        }
        cursor.close();
        return null;
    }

    /**
     * 获取所有加密照片的信息
     */
    public List<EncryptedPhotoInfo> getAllEncryptedPhotos() {
        List<EncryptedPhotoInfo> photos = new java.util.ArrayList<>();

        String query = "SELECT _id, name, timestamp FROM " + TABLE_NAME + " ORDER BY timestamp DESC";
        Cursor cursor = dbHelper.getReadableDatabase().rawQuery(query, null);

        while (cursor.moveToNext()) {
            EncryptedPhotoInfo info = new EncryptedPhotoInfo();
            info.id = cursor.getLong(cursor.getColumnIndexOrThrow("_id"));
            info.name = cursor.getString(cursor.getColumnIndexOrThrow("name"));
            info.timestamp = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp"));
            photos.add(info);
        }

        cursor.close();
        return photos;
    }

    /**
     * 删除加密照片
     */
    public boolean deleteEncryptedPhoto(long photoId) {
        return dbHelper.getWritableDatabase().delete(TABLE_NAME, "_id = ?", new String[]{String.valueOf(photoId)}) > 0;
    }

    /**
     * 使用PBKDF2从密码派生密钥
     */
    private SecretKey deriveKeyFromPassword(String password, byte[] salt) throws NoSuchAlgorithmException, java.security.spec.InvalidKeySpecException {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH);
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, ALGORITHM);
    }

    /**
     * 验证密码是否正确
     */
    public boolean verifyPassword(long photoId, String password) {
        byte[] decrypted = loadEncryptedPhoto(photoId, password);
        return decrypted != null;
    }

    /**
     * 加密照片信息类
     */
    public static class EncryptedPhotoInfo {
        public long id;
        public String name;
        public long timestamp;

        public String getFormattedDate() {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
            return sdf.format(new java.util.Date(timestamp));
        }
    }

    /**
     * 关闭数据库
     */
    public void close() {
        dbHelper.close();
    }
}