package com.example.privatevault;

import java.util.ArrayList;
import java.util.List;

public class CustomCategory {
    public long id;
    public String name = "";
    /** Empty for a top-level custom category; otherwise the unique parent category name. */
    public String parentName = "";
    public List<String> fields = new ArrayList<>();
    /** Labels of custom fields that should be treated as sensitive for plaintext export. */
    public List<String> sensitiveFields = new ArrayList<>();
    public long createdAt;
    public long updatedAt;
}
