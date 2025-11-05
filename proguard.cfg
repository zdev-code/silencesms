# https://code.google.com/p/android/issues/detail?id=78377
-keepnames class !androidx.appcompat.view.menu.**, ** { *; }

-keep public class androidx.appcompat.widget.** { *; }
-keep public class androidx.appcompat.view.menu.** { *; }

-keep public class * extends androidx.core.view.ActionProvider {
    public <init>(android.content.Context);
}
-keepattributes Exceptions
-dontskipnonpubliclibraryclassmembers

-dontwarn android.test.**
-dontwarn androidx.test.**
-dontwarn sun.reflect.**
-dontwarn sun.misc.**
-dontwarn org.assertj.**
-dontwarn org.hamcrest.**
-dontwarn org.mockito.**
-dontwarn com.squareup.**

-dontoptimize

-keepattributes SourceFile,LineNumberTable
-keep class org.whispersystems.** { *; }
-keep class org.smssecure.smssecure.** { *; }
-keepclassmembers class ** {
    public void onEvent*(**);
}
-keepattributes *Annotation*,EnclosingMethod
-keep public enum com.bumptech.glide.load.resource.bitmap.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}
-keep class * extends java.util.ListResourceBundle {
    protected Object[][] getContents();
}

-keepnames class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
# Proguard configuration for Jackson 2.x (fasterxml package instead of codehaus package)

-keepattributes *Annotation*,EnclosingMethod,Signature
-keepnames class com.fasterxml.jackson.** {
*;
}
-keepnames interface com.fasterxml.jackson.** {
    *;
}
-dontwarn com.fasterxml.jackson.databind.**
-keep class org.codehaus.** { *; }

-dontwarn android.net.ConnectivityManager
-dontwarn android.net.ConnectivityManager$NetworkCallback
-dontwarn okio.**
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.ParametersAreNonnullByDefault

-dontwarn retrofit.**
-keep class retrofit.** { *; }
-keepattributes Signature
-keepattributes Exceptions
-dontwarn com.squareup.picasso.**
-dontwarn com.google.zxing.**
-keep class com.journeyapps.barcodescanner.** { *; }
-keep class org.spongycastle.crypto.* {*;}
-keep class org.spongycastle.crypto.agreement.** {*;}
-keep class org.spongycastle.crypto.digests.* {*;}
-keep class org.spongycastle.crypto.ec.* {*;}
-keep class org.spongycastle.crypto.encodings.* {*;}
-keep class org.spongycastle.crypto.engines.* {*;}
-keep class org.spongycastle.crypto.macs.* {*;}
-keep class org.spongycastle.crypto.modes.* {*;}
-keep class org.spongycastle.crypto.paddings.* {*;}
-keep class org.spongycastle.crypto.params.* {*;}
-keep class org.spongycastle.crypto.prng.* {*;}
-keep class org.spongycastle.crypto.signers.* {*;}

-keep class org.spongycastle.jcajce.provider.asymmetric.* {*;}
-keep class org.spongycastle.jcajce.provider.asymmetric.util.* {*;}
-keep class org.spongycastle.jcajce.provider.asymmetric.dh.* {*;}
-keep class org.spongycastle.jcajce.provider.asymmetric.ec.* {*;}



-dontwarn javax.naming.**
-keep class org.sqlite.** { *; }
-keep class org.sqlite.database.** { *; }
# OkHttp
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.squareup.okhttp.** { *; }
-keep interface com.squareup.okhttp.** { *; }
-dontwarn com.squareup.okhttp.**
# Okio
-keep class sun.misc.Unsafe { *; }
-dontwarn java.nio.file.*
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn okio.**

-keep class org.apache.http.** { *; }
-dontwarn uk.co.senab.photoview.**

