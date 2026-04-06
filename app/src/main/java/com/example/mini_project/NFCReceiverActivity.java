package com.example.mini_project;

import android.app.AlertDialog;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class NFCReceiverActivity extends AppCompatActivity {

    private NfcAdapter nfcAdapter;
    private TextView txtStatus;
    private Button btnCancel;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isReceiving = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nfc_receiver);

        txtStatus = findViewById(R.id.txt_status);
        btnCancel = findViewById(R.id.btn_cancel);

        // 调试：检查控件是否找到
        if (btnCancel == null) {
            android.util.Log.e("NFCReceiver", "btnCancel is null! Check layout file.");
        }

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            txtStatus.setText("❌ 设备不支持NFC");
            Toast.makeText(this, "设备不支持NFC", Toast.LENGTH_LONG).show();
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

    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter != null && nfcAdapter.isEnabled()) {
            setupForegroundDispatch();
            txtStatus.setText("📡 等待发送方连接...\n请将两部手机背对背靠近");
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
                    new String[]{Ndef.class.getName()}
            };
            nfcAdapter.enableForegroundDispatch(this, pendingIntent, intentFilters, techLists);
        } catch (Exception e) {
            txtStatus.setText("NFC初始化失败: " + e.getMessage());
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!isReceiving && nfcAdapter != null) {
            receivePhotoFromNFC(intent);
        }
    }

    private void receivePhotoFromNFC(Intent intent) {
        Tag tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG);
        if (tag == null) {
            txtStatus.setText("未检测到NFC标签");
            return;
        }

        isReceiving = true;
        txtStatus.setText("📥 正在接收照片...");

        new Thread(() -> {
            try {
                Ndef ndef = Ndef.get(tag);
                if (ndef != null) {
                    ndef.connect();
                    NdefMessage ndefMessage = ndef.getCachedNdefMessage();
                    ndef.close();

                    if (ndefMessage != null) {
                        NdefRecord[] records = ndefMessage.getRecords();
                        for (NdefRecord record : records) {
                            if (record.getTnf() == NdefRecord.TNF_MIME_MEDIA &&
                                    new String(record.getType()).equals("application/com.example.mini_project.photo")) {

                                byte[] payload = record.getPayload();
                                PhotoData photoData = parsePhotoData(payload);

                                if (photoData != null) {
                                    runOnUiThread(() -> showReceiveDialog(photoData));
                                    return;
                                }
                            }
                        }
                        runOnUiThread(() -> {
                            txtStatus.setText("❌ 未检测到有效照片数据");
                            isReceiving = false;
                        });
                    } else {
                        runOnUiThread(() -> {
                            txtStatus.setText("❌ 未检测到NDEF消息");
                            isReceiving = false;
                        });
                    }
                } else {
                    runOnUiThread(() -> {
                        txtStatus.setText("❌ 不支持的NFC标签类型");
                        isReceiving = false;
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    txtStatus.setText("❌ 接收失败: " + e.getMessage());
                    isReceiving = false;
                });
            }
        }).start();
    }

    private PhotoData parsePhotoData(byte[] payload) {
        try {
            if (payload.length < 4) return null;

            int fileNameLength = ((payload[0] & 0xFF) << 24) |
                    ((payload[1] & 0xFF) << 16) |
                    ((payload[2] & 0xFF) << 8) |
                    (payload[3] & 0xFF);

            if (payload.length < 4 + fileNameLength) return null;

            String fileName = new String(payload, 4, fileNameLength, "UTF-8");

            byte[] imageData = new byte[payload.length - 4 - fileNameLength];
            System.arraycopy(payload, 4 + fileNameLength, imageData, 0, imageData.length);

            PhotoData data = new PhotoData();
            data.fileName = fileName;
            data.imageData = imageData;
            data.bitmap = PhotoHelper.bytesToBitmap(imageData);

            return data;
        } catch (Exception e) {
            return null;
        }
    }

    private void showReceiveDialog(PhotoData photoData) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_receive_confirm, null);

        ImageView imgPreview = dialogView.findViewById(R.id.img_receive_preview);
        Button btnAccept = dialogView.findViewById(R.id.btn_accept);
        Button btnReject = dialogView.findViewById(R.id.btn_reject);

        if (photoData.bitmap != null) {
            imgPreview.setImageBitmap(photoData.bitmap);
        }

        AlertDialog dialog = builder.setView(dialogView)
                .setCancelable(false)
                .create();

        btnAccept.setOnClickListener(v -> {
            dialog.dismiss();
            saveReceivedPhoto(photoData);
        });

        btnReject.setOnClickListener(v -> {
            dialog.dismiss();
            txtStatus.setText("已拒绝接收");
            isReceiving = false;
            handler.postDelayed(() -> {
                txtStatus.setText("📡 等待发送方连接...");
            }, 2000);
        });

        dialog.show();
    }

    private void saveReceivedPhoto(PhotoData photoData) {
        txtStatus.setText("💾 正在保存照片...");

        new Thread(() -> {
            if (photoData.bitmap != null) {
                String fileName = "Received_" + System.currentTimeMillis() + ".jpg";
                Uri savedUri = PhotoHelper.saveBitmapToGallery(getContentResolver(), photoData.bitmap, fileName);

                runOnUiThread(() -> {
                    if (savedUri != null) {
                        txtStatus.setText("✅ 照片已保存到相册！");
                        Toast.makeText(this, "照片已保存", Toast.LENGTH_LONG).show();
                        handler.postDelayed(this::finish, 2000);
                    } else {
                        txtStatus.setText("❌ 保存失败");
                        isReceiving = false;
                        handler.postDelayed(() -> {
                            txtStatus.setText("📡 等待发送方连接...");
                        }, 2000);
                    }
                });
            }
        }).start();
    }

    private static class PhotoData {
        String fileName;
        byte[] imageData;
        Bitmap bitmap;
    }
}