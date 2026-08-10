#include <jni.h>

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_unitymodloader_app_NativeBridge_coreVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("umlcore/0.1.0");
}
