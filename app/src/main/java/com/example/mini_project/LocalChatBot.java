package com.example.mini_project;

import android.content.Context;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * 本地规则型聊天逻辑：不访问网络、不调用外部 API，仅基于关键字与简单模式匹配。
 */
public final class LocalChatBot {
    private static final String TAG = "LocalChatBot";
    private static final Random RANDOM = new Random();
    private static String lastTopic = "";
    private static IntentClassifier intentClassifier;
    private static boolean modelInitTried = false;

    private LocalChatBot() {
    }

    public static String welcomeMessage() {
        return "你好！我是 PhotoShare 本地智慧助理。\n\n"
                + "我可以說明本應用的拍照、相簿、NFC 觸發藍牙傳圖，以及 Wi-Fi Direct 傳圖流程。"
                + "輸入「幫助」查看示例問題。";
    }

    public static synchronized void init(Context context) {
        if (modelInitTried) {
            return;
        }
        modelInitTried = true;
        try {
            intentClassifier = IntentClassifier.fromAssets(context.getApplicationContext(), "intent_model.json");
        } catch (Exception e) {
            Log.w(TAG, "Intent model load failed, fallback to rules.", e);
            intentClassifier = null;
        }
    }

    public static String reply(String userMessage) {
        if (userMessage == null) {
            return "請輸入內容。";
        }
        String raw = userMessage.trim();
        if (raw.isEmpty()) {
            return "請輸入內容後再發送。";
        }
        String normalized = normalize(raw);
        String lower = normalized.toLowerCase(Locale.ROOT);

        String predictedIntent = predictIntent(normalized);
        if (predictedIntent != null) {
            String modelReply = replyByIntent(predictedIntent);
            if (modelReply != null) {
                return modelReply;
            }
        }

        if (containsAny(normalized, lower, "继续", "繼續", "再讲", "再說", "然后", "然後", "更多", "细节", "詳細")) {
            return continueByTopic();
        }

        if (containsAny(normalized, lower, "帮助", "help", "功能", "教我", "怎么用", "點用", "点用")) {
            lastTopic = "help";
            return "你可以试着问我：\n"
                    + "• 如何拍照 / 照片保存在哪裡\n"
                    + "• NFC 分享怎麼用（需要藍牙配對）\n"
                    + "• Wi-Fi Direct 怎麼用 / 連不上怎麼辦\n"
                    + "• 需要哪些權限\n"
                    + "• 你好 / 謝謝 / 你是誰 / 現在幾點\n"
                    + "也可以說「繼續」讓我針對上一個主題展開。";
        }

        if (containsAny(normalized, lower, "你好", "您好", "hello", "hi", "hey")) {
            lastTopic = "greet";
            return pick(
                    "你好呀！今天想優化哪個功能：NFC、Wi-Fi Direct，還是 Chatbot？",
                    "你好！要不要我先幫你快速過一遍整個 Demo 流程？",
                    "嗨～你可以直接問我「WiFi 連不上怎麼辦」或「NFC 怎麼演示」。"
            );
        }

        if (containsAny(normalized, lower, "谢谢", "感谢", "thanks", "thank you")) {
            lastTopic = "thanks";
            return "不客氣，祝你演示順利！";
        }

        if (containsAny(normalized, lower, "你是谁", "你是誰", "自我介绍", "自我介紹", "介绍你", "介紹你")) {
            lastTopic = "intro";
            return "我是一個離線本地規則 Chatbot，不聯網、不調用外部 API。\n"
                    + "我專門回答 PhotoShare 項目相關問題，也能做基礎閒聊。";
        }

        if (containsAny(normalized, lower, "几点", "幾點", "时间", "時間", "date", "time")) {
            lastTopic = "time";
            String now = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());
            return "現在時間是：" + now + "。如果你在準備答辯，我也可以幫你整理講稿。";
        }

        if (containsAny(normalized, lower, "心情", "无聊", "無聊", "紧张", "緊張", "压力", "壓力", "怕答辩", "怕答辯")) {
            lastTopic = "mood";
            return "這很正常，答辯前緊張很常見。你可以用這個順序講：\n"
                    + "1) 問題背景 2) 三層技術 3) 演示流程 4) 成果與限制。\n"
                    + "要不要我給你一版 1 分鐘口頭稿？";
        }

        if (containsAny(normalized, lower, "项目", "項目", "报告", "報告", "介绍", "介紹", "overview", "summary")) {
            lastTopic = "project";
            return "你的項目可總結為三層：\n"
                    + "• 通訊層：NFC + Bluetooth、Wi-Fi Direct(P2P)\n"
                    + "• 智慧層：本地規則 Chatbot（離線）\n"
                    + "• 新趨勢層：Jetpack Compose 聊天 UI\n"
                    + "這套結構很適合 EA 報告與答辯。";
        }

        if (containsAny(normalized, lower, "拍照", "camera", "take photo")) {
            lastTopic = "camera";
            return "在主畫面點「拍照」，相機會把照片寫入系統相簿（Android 10+ 會存到 Pictures/PhotoShare）。"
                    + "返回主畫面後列表會自動刷新顯示最近照片。";
        }

        if (containsAny(normalized, lower, "相册", "照片列表", "gallery", "最近照片")) {
            lastTopic = "gallery";
            return "主畫面下方的網格會讀取系統相簿中的圖片並顯示縮圖；"
                    + "每張圖旁有「NFC 分享」與「Wi-Fi 分享」按鈕。";
        }

        if (containsAny(normalized, lower, "权限", "permission")) {
            lastTopic = "permission";
            return "本應用會申請相機、相簿讀取、NFC、藍牙、Wi-Fi 狀態與附近裝置/定位（用於發現 Wi-Fi Direct）、"
                    + "以及通知權限（Android 13+）。若拒絕，相關功能可能無法使用。";
        }

        if (containsAny(normalized, lower, "nfc", "近场")) {
            lastTopic = "nfc";
            return "「NFC 分享」流程：發送端會先把照片壓縮為 JPEG 位元組；接收端用 NFC 偵測到標籤後，"
                    + "再透過已配對的藍牙裝置以 RFCOMM Socket 接收資料並保存到相簿。"
                    + "注意：兩台裝置需先完成藍牙配對，且接收端要在「NFC 接收」介面待命。";
        }

        if (containsAny(normalized, lower, "蓝牙", "bluetooth")) {
            lastTopic = "bt";
            return "目前實作裡，NFC 路徑實際承載的是藍牙傳輸：NFC 用於觸發連線時機，"
                    + "圖片資料走藍牙 Socket。請確保藍牙已開啟並完成配對。";
        }

        if (containsAny(normalized, lower, "wifi", "wi-fi", "无线", "直连")) {
            lastTopic = "wifi";
            return "「Wi-Fi 分享」使用 Wi-Fi Direct（P2P）：發送端搜尋裝置、點選連線；"
                    + "當發送端成為 Group Owner 後會開 TCP 連接埠 8888，接收端連上後按協議接收檔名與圖片位元組。"
                    + "接收端需先打開「Wi-Fi 接收」介面，並確保 Wi-Fi 已開啟。";
        }

        if (containsAny(normalized, lower, "连不上", "失败", "busy", "搜不到", "連不上", "失敗")) {
            lastTopic = "troubleshoot";
            return "若 Wi-Fi Direct 初始化失敗或一直 busy：可先關閉再開啟 Wi-Fi、結束其他 P2P 連線、"
                    + "雙方退出介面後重進；三星等機型有時需要稍等清理 group 後再發現裝置。";
        }

        if (containsAny(normalized, lower, "compose", "界面", "jetpack")) {
            lastTopic = "compose";
            return "本頁的聊天介面使用 Jetpack Compose 建構（宣告式 UI），"
                    + "對話邏輯仍在 Java 的 LocalChatBot 中，屬於「UI 新趨勢 + 本地智慧助理」的組合。";
        }

        lastTopic = "fallback";
        return "這個問題我暫時未完全聽懂 😅\n"
                + "你可以換一種問法，例如：\n"
                + "• 「NFC 怎麼演示」\n"
                + "• 「WiFi 連不上怎麼辦」\n"
                + "• 「幫我寫 1 分鐘答辯稿」";
    }

    private static String normalize(String input) {
        // 轻量繁体转简体与同义词归一化（离线、本地规则）
        return input
                .replace("幫助", "帮助")
                .replace("幫", "帮")
                .replace("您好", "你好")
                .replace("謝謝", "谢谢")
                .replace("相簿", "相册")
                .replace("權限", "权限")
                .replace("藍牙", "蓝牙")
                .replace("無線", "无线")
                .replace("傳圖", "传图")
                .replace("發送", "发送")
                .replace("接收方", "接收端")
                .replace("WiFi", "wifi")
                .replace("WI-FI", "wi-fi")
                .replace("幾點", "几点")
                .replace("介紹", "介绍")
                .replace("項目", "项目")
                .replace("報告", "报告")
                .replace("緊張", "紧张")
                .replace("壓力", "压力")
                .replace("答辯", "答辩")
                .replace("怎麼", "怎么")
                .replace("連不上", "连不上")
                .replace("幫我", "帮我")
                .replace("幾分鐘", "几分钟");
    }

    private static boolean containsAny(String normalized, String lower, String... keywords) {
        for (String keyword : keywords) {
            String k = keyword.toLowerCase(Locale.ROOT);
            if (normalized.contains(keyword) || lower.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static String continueByTopic() {
        switch (lastTopic) {
            case "nfc":
                return "NFC 演示建議：\n"
                        + "1) 兩機先藍牙配對\n"
                        + "2) 接收機先開「NFC 接收」介面\n"
                        + "3) 發送機選圖後點「NFC 分享」\n"
                        + "4) 貼近觸發後等待保存提示。";
            case "wifi":
            case "troubleshoot":
                return "Wi-Fi Direct 穩定演示技巧：\n"
                        + "1) 兩機都開 Wi-Fi\n"
                        + "2) 接收機先進入接收頁\n"
                        + "3) 發送機刷新並點裝置連線\n"
                        + "4) 若失敗先退回重進，再嘗試重新發現。";
            case "compose":
                return "Compose 在你項目的價值：UI 改動更快、狀態驅動更清晰，適合聊天場景（列表+輸入+載入態）。";
            case "project":
                return "如果你願意，我可以繼續給你：\n"
                        + "• 30 秒開場白\n"
                        + "• 1 分鐘技術講解\n"
                        + "• 評委常見追問與回答。";
            case "help":
                return "你也可以直接問：\n"
                        + "• 「我該怎麼 demo 最穩？」\n"
                        + "• 「這項目有什麼創新點？」\n"
                        + "• 「局限性怎麼講才不扣分？」";
            default:
                return "我可以繼續展開任何主題：NFC、Wi-Fi Direct、Compose、Chatbot、答辯講稿。";
        }
    }

    private static String predictIntent(String normalized) {
        if (intentClassifier == null) {
            return null;
        }
        IntentClassifier.Prediction p = intentClassifier.predict(normalized);
        if (p.confidence < 0.45) {
            return null;
        }
        return p.intent;
    }

    private static String replyByIntent(String intent) {
        switch (intent) {
            case "help":
                lastTopic = "help";
                return "你可以试着问我：\n"
                        + "• 如何拍照 / 照片保存在哪裡\n"
                        + "• NFC 分享怎麼用（需要藍牙配對）\n"
                        + "• Wi-Fi Direct 怎麼用 / 連不上怎麼辦\n"
                        + "• 需要哪些權限\n"
                        + "• 你好 / 謝謝 / 你是誰 / 現在幾點\n"
                        + "也可以說「繼續」讓我針對上一個主題展開。";
            case "greet":
                lastTopic = "greet";
                return pick(
                    "你好呀！今天想優化哪個功能：NFC、Wi-Fi Direct，還是 Chatbot？",
                        "你好！要不要我先幫你快速過一遍整個 Demo 流程？",
                        "嗨～你可以直接問我「WiFi 連不上怎麼辦」或「NFC 怎麼演示」。"
                );
            case "thanks":
                lastTopic = "thanks";
                return "不客氣，祝你演示順利！";
            case "intro":
                lastTopic = "intro";
                return "我是一個離線本地規則 Chatbot，不聯網、不調用外部 API。\n"
                        + "我專門回答 PhotoShare 項目相關問題，也能做基礎閒聊。";
            case "time":
                lastTopic = "time";
                String now = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());
                return "現在時間是：" + now + "。如果你在準備答辯，我也可以幫你整理講稿。";
            case "mood":
                lastTopic = "mood";
                return "這很正常，答辯前緊張很常見。你可以用這個順序講：\n"
                        + "1) 問題背景 2) 三層技術 3) 演示流程 4) 成果與限制。\n"
                        + "要不要我給你一版 1 分鐘口頭稿？";
            case "project":
                lastTopic = "project";
                return "你的項目可總結為三層：\n"
                        + "• 通訊層：NFC + Bluetooth、Wi-Fi Direct(P2P)\n"
                        + "• 智慧層：本地規則 Chatbot（離線）\n"
                        + "• 新趨勢層：Jetpack Compose 聊天 UI\n"
                        + "這套結構很適合 EA 報告與答辯。";
            case "camera":
                lastTopic = "camera";
                return "在主畫面點「拍照」，相機會把照片寫入系統相簿（Android 10+ 會存到 Pictures/PhotoShare）。"
                        + "返回主畫面後列表會自動刷新顯示最近照片。";
            case "gallery":
                lastTopic = "gallery";
                return "主畫面下方的網格會讀取系統相簿中的圖片並顯示縮圖；"
                        + "每張圖旁有「NFC 分享」與「Wi-Fi 分享」按鈕。";
            case "permission":
                lastTopic = "permission";
                return "本應用會申請相機、相簿讀取、NFC、藍牙、Wi-Fi 狀態與附近裝置/定位（用於發現 Wi-Fi Direct）、"
                        + "以及通知權限（Android 13+）。若拒絕，相關功能可能無法使用。";
            case "nfc":
                lastTopic = "nfc";
                return "「NFC 分享」流程：發送端會先把照片壓縮為 JPEG 位元組；接收端用 NFC 偵測到標籤後，"
                        + "再透過已配對的藍牙裝置以 RFCOMM Socket 接收資料並保存到相簿。"
                        + "注意：兩台裝置需先完成藍牙配對，且接收端要在「NFC 接收」介面待命。";
            case "bluetooth":
                lastTopic = "bt";
                return "目前實作裡，NFC 路徑實際承載的是藍牙傳輸：NFC 用於觸發連線時機，"
                        + "圖片資料走藍牙 Socket。請確保藍牙已開啟並完成配對。";
            case "wifi":
                lastTopic = "wifi";
                return "「Wi-Fi 分享」使用 Wi-Fi Direct（P2P）：發送端搜尋裝置、點選連線；"
                        + "當發送端成為 Group Owner 後會開 TCP 連接埠 8888，接收端連上後按協議接收檔名與圖片位元組。"
                        + "接收端需先打開「Wi-Fi 接收」介面，並確保 Wi-Fi 已開啟。";
            case "troubleshoot":
                lastTopic = "troubleshoot";
                return "若 Wi-Fi Direct 初始化失敗或一直 busy：可先關閉再開啟 Wi-Fi、結束其他 P2P 連線、"
                        + "雙方退出介面後重進；三星等機型有時需要稍等清理 group 後再發現裝置。";
            case "compose":
                lastTopic = "compose";
                return "本頁的聊天介面使用 Jetpack Compose 建構（宣告式 UI），"
                        + "對話邏輯仍在 Java 的 LocalChatBot 中，屬於「UI 新趨勢 + 本地智慧助理」的組合。";
            default:
                return null;
        }
    }

    private static String pick(String... choices) {
        if (choices == null || choices.length == 0) {
            return "";
        }
        return choices[RANDOM.nextInt(choices.length)];
    }
}
