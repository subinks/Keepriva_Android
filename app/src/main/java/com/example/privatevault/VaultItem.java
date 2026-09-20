package com.example.privatevault;

import java.util.LinkedHashMap;
import java.util.Map;

public class VaultItem {
    public long id;
    public String title;
    public String category;
    public String username;
    public String password;
    public String website;
    public String websiteUrl;
    public String phone1;
    public String phone2;
    public String phone3;
    public String notes;
    public Map<String, String> customFields = new LinkedHashMap<>();
    public long createdAt;
    public long updatedAt;

    public VaultItem() {}
}
