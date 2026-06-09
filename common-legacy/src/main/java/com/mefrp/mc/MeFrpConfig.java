package com.mefrp.mc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class MeFrpConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path gameDirectory;
    
    private String token = "";
    private String tunnelName = MeFrpConstants.TUNNEL_NAME;
    
    private static MeFrpConfig instance;
    
    public static void init(Path gameDirectory) {
        MeFrpConfig.gameDirectory = gameDirectory;
        instance = null;
    }
    
    public static MeFrpConfig getInstance() {
        if (instance == null) {
            instance = new MeFrpConfig();
            instance.load();
        }
        return instance;
    }
    
    public void load() {
        File configFile = getConfigFile();
        if (!configFile.exists()) {
            save();
            return;
        }
        
        try (FileReader reader = new FileReader(configFile)) {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            if (json.has("token")) {
                token = json.get("token").getAsString();
            }
            if (json.has("tunnelName")) {
                tunnelName = json.get("tunnelName").getAsString();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public void save() {
        File configFile = getConfigFile();
        configFile.getParentFile().mkdirs();
        
        JsonObject json = new JsonObject();
        json.addProperty("token", token);
        json.addProperty("tunnelName", tunnelName);
        
        try (FileWriter writer = new FileWriter(configFile)) {
            GSON.toJson(json, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public String getToken() {
        return token;
    }
    
    public void setToken(String token) {
        this.token = token;
        save();
    }
    
    public String getTunnelName() {
        return tunnelName;
    }
    
    public boolean hasToken() {
        return token != null && !token.trim().isEmpty();
    }

    private File getConfigFile() {
        Path base = gameDirectory == null ? Paths.get(".") : gameDirectory;
        return base.resolve("config").resolve("mefrp.json").toFile();
    }
}
