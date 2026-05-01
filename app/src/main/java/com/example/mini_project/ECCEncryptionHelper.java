package com.example.mini_project;

import android.util.Base64;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ECC加密助手
 * 使用椭圆曲线加密替代RSA
 */
public class ECCEncryptionHelper {
    private static final String EC_ALGORITHM = "EC";
    private static final String EC_CURVE = "secp256r1";  // NIST P-256
    private static final String AES_ALGORITHM = "AES";
    private static final String AES_MODE = "AES/GCM/NoPadding";

    private PrivateKey privateKey;
    private PublicKey publicKey;
    private PublicKey otherPublicKey;

    /**
     * 生成ECC密钥对
     */
    public void generateKeyPair() throws Exception {
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance(EC_ALGORITHM);
        ECGenParameterSpec ecSpec = new ECGenParameterSpec(EC_CURVE);
        keyGen.initialize(ecSpec, new SecureRandom());

        KeyPair keyPair = keyGen.generateKeyPair();
        privateKey = keyPair.getPrivate();
        publicKey = keyPair.getPublic();
    }

    /**
     * 获取公钥的Base64编码
     */
    public String getPublicKeyBase64() {
        return Base64.encodeToString(publicKey.getEncoded(), Base64.NO_WRAP);
    }

    /**
     * 加载对方的公钥
     */
    public void loadOtherPublicKey(String base64Key) throws Exception {
        byte[] keyBytes = Base64.decode(base64Key, Base64.NO_WRAP);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(EC_ALGORITHM);
        otherPublicKey = keyFactory.generatePublic(spec);
    }

    /**
     * 获取私钥的Base64编码（用于存储）
     */
    public String getPrivateKeyBase64() {
        return Base64.encodeToString(privateKey.getEncoded(), Base64.NO_WRAP);
    }

    /**
     * 从Base64加载私钥
     */
    public void loadPrivateKey(String base64Key) throws Exception {
        byte[] keyBytes = Base64.decode(base64Key, Base64.NO_WRAP);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory keyFactory = KeyFactory.getInstance(EC_ALGORITHM);
        privateKey = keyFactory.generatePrivate(spec);
    }

    /**
     * 使用ECDH派生共享密钥
     */
    private SecretKey deriveSharedSecret() throws Exception {
        KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
        keyAgreement.init(privateKey);
        keyAgreement.doPhase(otherPublicKey, true);

        byte[] sharedSecret = keyAgreement.generateSecret();

        // 使用SHA-256派生AES-256密钥
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] aesKey = digest.digest(sharedSecret);

        return new SecretKeySpec(aesKey, AES_ALGORITHM);
    }

    /**
     * 使用ECC加密数据（混合加密）
     * @param data 要加密的数据
     * @return 加密后的数据（包含IV和密文）
     */
    public byte[] encryptWithECC(byte[] data) throws Exception {
        if (otherPublicKey == null) {
            throw new IllegalStateException("未加载对方的公钥");
        }

        // 使用ECDH派生共享密钥
        KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
        keyAgreement.init(privateKey);
        keyAgreement.doPhase(otherPublicKey, true);
        byte[] sharedSecret = keyAgreement.generateSecret();

        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] aesKey = digest.digest(sharedSecret);
        SecretKeySpec secretKey = new SecretKeySpec(aesKey, "AES");

        // 生成随机IV
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        IvParameterSpec ivSpec = new IvParameterSpec(iv);

        // AES-GCM加密
        Cipher cipher = Cipher.getInstance(AES_MODE);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);

        byte[] cipherText = cipher.doFinal(data);

        // 组合: IV (12字节) + 密文
        byte[] result = new byte[iv.length + cipherText.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(cipherText, 0, result, iv.length, cipherText.length);

        return result;
    }

    /**
     * 使用ECC解密数据
     * @param encryptedData 加密的数据（包含IV和密文）
     * @return 解密后的数据
     */
    public byte[] decryptWithECC(byte[] encryptedData) throws Exception {
        if (otherPublicKey == null) {
            throw new IllegalStateException("未加载对方的公钥");
        }

        // 提取IV
        byte[] iv = new byte[12];
        System.arraycopy(encryptedData, 0, iv, 0, iv.length);
        byte[] cipherText = new byte[encryptedData.length - iv.length];
        System.arraycopy(encryptedData, iv.length, cipherText, 0, cipherText.length);

        // 使用ECDH派生共享密钥
        KeyAgreement keyAgreement = KeyAgreement.getInstance("ECDH");
        keyAgreement.init(privateKey);
        keyAgreement.doPhase(otherPublicKey, true);
        byte[] sharedSecret = keyAgreement.generateSecret();

        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] aesKey = digest.digest(sharedSecret);
        SecretKeySpec secretKey = new SecretKeySpec(aesKey, "AES");

        // AES-GCM解密
        Cipher cipher = Cipher.getInstance(AES_MODE);
        cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));

        return cipher.doFinal(cipherText);
    }

    /**
     * 使用自己的私钥加密（用于签名/身份验证）
     */
    public byte[] sign(byte[] data) throws Exception {
        java.security.Signature signature = java.security.Signature.getInstance("SHA256withECDSA");
        signature.initSign(privateKey);
        signature.update(data);
        return signature.sign();
    }

    /**
     * 使用对方公钥验证签名
     */
    public boolean verify(byte[] data, byte[] signatureBytes) throws Exception {
        java.security.Signature signature = java.security.Signature.getInstance("SHA256withECDSA");
        signature.initVerify(otherPublicKey);
        signature.update(data);
        return signature.verify(signatureBytes);
    }

    public PrivateKey getPrivateKey() { return privateKey; }
    public PublicKey getPublicKey() { return publicKey; }
    public PublicKey getOtherPublicKey() { return otherPublicKey; }
}