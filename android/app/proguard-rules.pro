
# Pleos SDKs talk to the vehicle service over AIDL/reflection: keep them whole.
-keep class ai.pleos.** { *; }
-keep interface ai.pleos.** { *; }
-dontwarn ai.pleos.**
# The Pleos SDK parses vehicle data with Gson TypeTokens: keep generic signatures and Gson.
-keepattributes Signature, InnerClasses, EnclosingMethod, *Annotation*
-keep class com.google.gson.** { *; }
-keep class * extends com.google.gson.reflect.TypeToken
-dontwarn com.google.gson.**
