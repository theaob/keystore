# Bouncy Castle looks up its algorithm implementations reflectively.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
