package com.example.mini_project;

import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.nfc.Tag;
import android.nfc.tech.Ndef;
import android.nfc.tech.NdefFormatable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.io.ByteArrayOutputStream;

public class NFCSenderActivity extends AppCompatActivity {

    private NfcAdapter nfcAdapter;
    private TextView txtStatus;
    private ImageView imgPreview;
    private ProgressBar progressBar;
    private Button btnCancel;

    private Uri photoUri;
    private String photoName;
    private byte[] photoData;
    private boolean isSending = false;
    private Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_sender);

        initViews();

        // 获取传递的照片信息
        if (getIntent() != null) {
            String uriString = getIntent().getStringExtra("photo_uri");
            photoName = getIntent().getStringExtra("photo_name");
            if (uriString != null) {
                photoUri = Uri.parse(uriString);
                loadPhoto();
            }
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            txtStatus.setText("❌ 设备不支持NFC");
            Toast.makeText(this, "设备不支持NFC", Toast.LENGTH_LONG).show();
            // 仍然让取消按钮可以工作
        } else if (!nfcAdapter.isEnabled()) {
            txtStatus.setText("❌ 请开启NFC功能");
            Toast.makeText(this, "请开启NFC功能", Toast.LENGTH_LONG).show();
        }

        // 修复：确保取消按钮有正确的点击监听器
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> {
                finish();  // 直接关闭当前Activity
            });
        }
    }

    private void initViews() {
        txtStatus = findViewById(R.id.txt_status);
        imgPreview = findViewById(R.id.img_preview);
        progressBar = findViewById(R.id.progress_bar);
        btnCancel = findViewById(R.id.btn_cancel);

        // 调试：检查控件是否找到
        if (btnCancel == null) {
            android.util.Log.e("NFCSender", "btnCancel is null! Check layout file.");
        }
    }

    private void loadPhoto() {
        new Thread(() -> {
            Bitmap bitmap = PhotoHelper.loadBitmapFromUri(getContentResolver(), photoUri, 500);
            if (bitmap != null) {
                photoData = PhotoHelper.bitmapToBytes(bitmap, 500);
                runOnUiThread(() -> {
                    imgPreview.setImageBitmap(bitmap);
                    txtStatus.setText("✅ 照片已加载\n请将两部手机背对背靠近...");
                });
            } else {
                runOnUiThread(() -> {
                    txtStatus.setText("❌ 加载照片失败");
                });
            }
        }).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null && nfcAdapter.isEnabled()) {
            setupForegroundDispatch();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (nfcAdapter != null) {
            try {
                nfcAdapter.disableForegroundDispatch(this);
            } catch (Exception e) {
                // 忽略异常
            }
        }
    }

    private void setupForegroundDispatch() {
        try {
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_MUTABLE
            );
            IntentFilter[] intentFilters = new IntentFilter[]{
                    new IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED),
                    new IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
            };
            String[][] techLists = new String[][]{
                    new String[]{Ndef.class.getName()},
                    new String[]{NdefFormatable.class.getName()}
            };
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists);
        } catch (Exception e) {
            txtStatus.setText("NFC初始化失败: " + e.getMessage());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (photoData != null && !isSending && nfcAdapter != null) {
            sendPhotoViaNFC(intent);
        }
    }

    private void sendPhotoViaNFC(Intent intent) {
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) {
            txtStatus.setText("未检测到NFC标签");
            return;
        }

        isSending = true;
        progressBar.setVisibility(View.VISIBLE);
        txtStatus.setText("📤 正在发送照片...");

        new Thread(() -> {
            try {
                NdefMessage ndefMessage = createNdefMessage(photoData, photoName);

                Ndef ndef = Ndef.get(tag);
                if (ndef != null) {
                    ndef.connect();
                    if (ndef.isWritable()) {
                        ndef.writeNdefMessage(ndefMessage);
                        runOnUiThread(() -> {
                            txtStatus.setText("✅ 照片发送成功！");
                            Toast.makeText(this, "照片已发送", Toast.LENGTH_LONG).show();
                            handler.postDelayed(this::finish, 2000);
                        });
                    } else {
                        runOnUiThread(() -> {
                            txtStatus.setText("❌ 标签不可写");
                            isSending = false;
                            progressBar.setVisibility(View.GONE);
                        });
                    }
                    ndef.close();
                } else {
                    NdefFormatable formatable = NdefFormatable.get(tag);
                    if (formatable != null) {
                        formatable.connect();
                        formatable.format(ndefMessage);
                        runOnUiThread(() -> {
                            txtStatus.setText("✅ 照片发送成功！");
                            Toast.makeText(this, "照片已发送", Toast.LENGTH_LONG).show();
                            handler.postDelayed(this::finish, 2000);
                        });
                        formatable.close();
                    } else {
                        runOnUiThread(() -> {
                            txtStatus.setText("❌ 不支持的NFC标签类型");
                            isSending = false;
                            progressBar.setVisibility(View.GONE);
                        });
                    }
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    txtStatus.setText("❌ 发送失败: " + e.getMessage());
                    isSending = false;
                    progressBar.setVisibility(View.GONE);
                });
            }
        }).start();
    }

    private NdefMessage createNdefMessage(byte[] photoData, String fileName) {
        try {
            byte[] fileNameBytes = fileName.getBytes("UTF-8");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();

            baos.write((fileNameBytes.length >> 24) & 0xFF);
            baos.write((fileNameBytes.length >> 16) & 0xFF);
            baos.write((fileNameBytes.length >> 8) & 0xFF);
            baos.write(fileNameBytes.length & 0xFF);
            baos.write(fileNameBytes);
            baos.write(photoData);

            byte[] payload = baos.toByteArray();

            NdefRecord record = new NdefRecord(
                    NdefRecord.TNF_MIME_MEDIA,
                    "application/com.example.mini_project.photo".getBytes(),
                    new byte[0],
                    payload
            );

            return new NdefMessage(new NdefRecord[]{record});
        } catch (Exception e) {
            return null;
        }
    }
}