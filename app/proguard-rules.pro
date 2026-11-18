# Anthropic SDK uses Jackson reflection.
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-dontwarn org.slf4j.**
