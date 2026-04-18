-keep class com.emiliotorrens.talk2claw.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# gRPC + Protobuf-lite
-keep class io.grpc.** { *; }
-keep class com.google.protobuf.** { *; }
-keep class com.google.cloud.texttospeech.v1.** { *; }
-dontwarn com.google.protobuf.**
-dontwarn io.grpc.**
-dontwarn javax.annotation.**
-dontwarn com.google.common.**
-dontwarn com.google.appengine.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.**
-dontwarn sun.misc.Unsafe
-dontwarn com.google.j2objc.annotations.**

# Keep gRPC generated service stubs
-keepclassmembers class * extends io.grpc.stub.AbstractBlockingStub { *; }
-keepclassmembers class * extends io.grpc.stub.AbstractAsyncStub { *; }
-keepclassmembers class * extends io.grpc.stub.AbstractFutureStub { *; }

# Keep protobuf-lite generated classes
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite$Builder { *; }
