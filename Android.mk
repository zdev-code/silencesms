LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := Silence
LOCAL_MODULE_TAGS := optional
LOCAL_PACKAGE_NAME := Silence

silence_root  := $(LOCAL_PATH)
silence_build := $(silence_root)/app/build
silence_apk   := app/build/outputs/apk/release/app-release.apk

$(silence_root)/$(silence_apk):
	rm -Rf $(silence_build)
	cd $(silence_root) && ./gradlew :app:assembleRelease

LOCAL_CERTIFICATE := platform
LOCAL_SRC_FILES := $(silence_apk)
LOCAL_MODULE_CLASS := APPS
LOCAL_MODULE_SUFFIX := $(COMMON_ANDROID_PACKAGE_SUFFIX)

include $(BUILD_PREBUILT)
