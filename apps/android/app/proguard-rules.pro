# kotlinx.serialization keeps what it needs through its own rules; nothing extra yet.

# Glance finds a widget's ids by its GlanceAppWidget's class name. R8 merged the two widgets
# into one class, so both receivers named it and each update drew its card in both widgets.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget

# Shizuku constructs the shell service by its manifest-independent class name.
-keep class cc.hyoban.moments.sidekey.KeyBridge { *; }
