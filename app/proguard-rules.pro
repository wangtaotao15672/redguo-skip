# Keep accessibility service entry
-keep class com.redguo.skip.service.** { *; }
# Keep config keys accessed via reflection-free SharedPreferences (just for safety)
-keepclassmembers class com.redguo.skip.config.** { *; }
