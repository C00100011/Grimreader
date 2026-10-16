# ONNX Runtime looks up its Java classes, fields and methods by name from native code (JNI).
-keep class ai.onnxruntime.** { *; }

# ML Kit (Gemini Nano, the book chat) starts itself by creating the classes named in the manifest's ComponentRegistrar entries by reflection.
# Without this R8 removes their constructors: ML Kit never initialises and Generation.getClient() throws a NullPointerException.
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
