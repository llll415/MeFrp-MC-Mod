package com.mefrp.mc.security;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class EncryptedToken {
    private final String algorithm;
    private final String salt;
    private final String iv;
    private final String ciphertext;

    public EncryptedToken(String algorithm, String salt, String iv, String ciphertext) {
        this.algorithm = algorithm;
        this.salt = salt;
        this.iv = iv;
        this.ciphertext = ciphertext;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public String getSalt() {
        return salt;
    }

    public String getIv() {
        return iv;
    }

    public String getCiphertext() {
        return ciphertext;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("algorithm", algorithm);
        json.addProperty("salt", salt);
        json.addProperty("iv", iv);
        json.addProperty("ciphertext", ciphertext);
        return json;
    }

    public static EncryptedToken fromJson(JsonObject json) throws ConfigCryptoException {
        return new EncryptedToken(
                readString(json, "algorithm"),
                readString(json, "salt"),
                readString(json, "iv"),
                readString(json, "ciphertext"));
    }

    private static String readString(JsonObject json, String key) throws ConfigCryptoException {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull() || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new ConfigCryptoException("invalid encrypted token field: " + key);
        }
        return element.getAsString();
    }
}
