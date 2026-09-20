# Private Vault release R8 rules.
# The application intentionally uses no reflection-based serialization libraries,
# so broad keep rules are not required.

# Do not preserve source file names in optimized release output.
-renamesourcefileattribute SourceFile

# Remove common platform logging calls if any are introduced later.
# Never use these rules as a substitute for avoiding sensitive log arguments in source.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep framework entry point constructors/names Android resolves from the manifest.
-keep public class com.example.privatevault.MainActivity { public <init>(); }

# Keep generic/signature annotations that are useful to platform tooling without
# retaining source debug metadata.
-keepattributes RuntimeVisibleAnnotations,RuntimeInvisibleAnnotations,AnnotationDefault,Signature
