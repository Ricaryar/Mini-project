package com.example.mini_project;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 轻量离线意图分类器（多项式朴素贝叶斯模型）。
 * 模型文件来自 assets/intent_model.json，可由 ml/train_intent_model.py 训练生成。
 */
public final class IntentClassifier {

    public static final class Prediction {
        public final String intent;
        public final double confidence;

        public Prediction(String intent, double confidence) {
            this.intent = intent;
            this.confidence = confidence;
        }
    }

    private final List<String> labels = new ArrayList<>();
    private final Map<String, Double> logPrior = new HashMap<>();
    private final Map<String, Map<String, Double>> logLikelihood = new HashMap<>();
    private final Map<String, Double> unknownLogProb = new HashMap<>();

    public static IntentClassifier fromAssets(Context context, String assetName) throws Exception {
        try (InputStream in = context.getAssets().open(assetName);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            String json = out.toString(StandardCharsets.UTF_8.name());
            return fromJson(json);
        }
    }

    public static IntentClassifier fromJson(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        IntentClassifier classifier = new IntentClassifier();

        JSONArray labelsArray = root.getJSONArray("labels");
        for (int i = 0; i < labelsArray.length(); i++) {
            classifier.labels.add(labelsArray.getString(i));
        }

        JSONObject priorObj = root.getJSONObject("logPrior");
        JSONObject unkObj = root.getJSONObject("unknownLogProb");
        JSONObject llObj = root.getJSONObject("logLikelihood");

        for (String label : classifier.labels) {
            classifier.logPrior.put(label, priorObj.getDouble(label));
            classifier.unknownLogProb.put(label, unkObj.getDouble(label));

            JSONObject tokenObj = llObj.getJSONObject(label);
            Map<String, Double> tokenMap = new HashMap<>();
            JSONArray names = tokenObj.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String token = names.getString(i);
                    tokenMap.put(token, tokenObj.getDouble(token));
                }
            }
            classifier.logLikelihood.put(label, tokenMap);
        }

        return classifier;
    }

    public Prediction predict(String text) {
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty() || labels.isEmpty()) {
            return new Prediction("unknown", 0.0);
        }

        Map<String, Double> scores = new HashMap<>();
        double maxScore = Double.NEGATIVE_INFINITY;
        String bestLabel = "unknown";

        for (String label : labels) {
            double score = logPrior.getOrDefault(label, -100.0);
            Map<String, Double> tokenMap = logLikelihood.get(label);
            double unk = unknownLogProb.getOrDefault(label, -20.0);
            for (String token : tokens) {
                score += tokenMap.getOrDefault(token, unk);
            }
            scores.put(label, score);
            if (score > maxScore) {
                maxScore = score;
                bestLabel = label;
            }
        }

        // softmax 近似置信度
        double denom = 0.0;
        for (double s : scores.values()) {
            denom += Math.exp(s - maxScore);
        }
        double bestProb = 1.0 / Math.max(denom, 1e-9);
        return new Prediction(bestLabel, bestProb);
    }

    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null) {
            return tokens;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder latin = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (isCjk(c)) {
                if (latin.length() > 0) {
                    tokens.add(latin.toString());
                    latin.setLength(0);
                }
                tokens.add(String.valueOf(c));
                continue;
            }
            if (isAsciiAlphaNum(c)) {
                latin.append(c);
                continue;
            }
            if (latin.length() > 0) {
                tokens.add(latin.toString());
                latin.setLength(0);
            }
        }
        if (latin.length() > 0) {
            tokens.add(latin.toString());
        }
        return tokens;
    }

    private static boolean isCjk(char c) {
        return c >= '\u4E00' && c <= '\u9FFF';
    }

    private static boolean isAsciiAlphaNum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
