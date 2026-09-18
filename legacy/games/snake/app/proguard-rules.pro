# R8 full mode. This app is pure Kotlin with no reflection, no JNI and no
# serialization, so almost nothing needs an explicit keep rule.
#
# The launcher Activity is referenced from AndroidManifest.xml and is kept
# automatically by the AAPT-generated rules, so we do not keep it by hand.

# Squeeze a little more out of the shrinker:
-allowaccessmodification
-repackageclasses ''

# Strip Android log calls from the release build.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
