# JNA's native dispatch resolves Java fields and Vosk entry points by their original names.
-keep class com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
# JNA also contains desktop AWT helpers; Android never executes those paths.
-dontwarn java.awt.Component
-dontwarn java.awt.GraphicsEnvironment
-dontwarn java.awt.HeadlessException
-dontwarn java.awt.Window
