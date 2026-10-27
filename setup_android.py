# GitHub build me chalta hai (npx cap add android ke baad).
# Ye Android project me phone-AI wala hissa jod deta hai: Kotlin plugin, Google ki LiteRT-LM library, manifest.
# Tumhe isko kuch karna nahi.
import glob
import os
import re
import shutil
import sys

APP_ID = "com.anshu.labs.offline"
KOTLIN = os.environ.get("KOTLIN_VERSION", "2.3.20")
ANDROID = "android"
APP = ANDROID + "/app"


def fail(msg):
    print("SETUP FAIL: " + msg)
    sys.exit(1)


def read(p):
    with open(p, encoding="utf-8") as fh:
        return fh.read()


def write(p, s):
    with open(p, "w", encoding="utf-8") as fh:
        fh.write(s)


if not os.path.isdir(APP):
    fail("android/app folder nahi mila. Pehle 'npx cap add android' chalna chahiye.")

# 1) Kotlin plugin file + MainActivity
pkg_dir = os.path.join(APP, "src", "main", "java", *APP_ID.split("."))
os.makedirs(pkg_dir, exist_ok=True)
if not os.path.isfile("LabsLlmPlugin.kt"):
    fail("LabsLlmPlugin.kt repo me nahi mili.")
shutil.copy("LabsLlmPlugin.kt", os.path.join(pkg_dir, "LabsLlmPlugin.kt"))

olds = glob.glob(os.path.join(APP, "src", "main", "java", "**", "MainActivity.*"), recursive=True)
for o in olds:
    print("purani MainActivity hata rahe hain:", o)
    os.remove(o)
write(os.path.join(pkg_dir, "MainActivity.java"), """package %s;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(LabsLlmPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
""" % APP_ID)
print("OK plugin + MainActivity ->", pkg_dir)

# 2) top-level build.gradle: Kotlin plugin
top_path = ANDROID + "/build.gradle"
top = read(top_path)
m = re.search(r"com\.android\.tools\.build:gradle:(\d+)\.(\d+)", top)
agp_major = int(m.group(1)) if m else 8
print("AGP version:", m.group(0) if m else "pata nahi (8 maan rahe hain)")
builtin_kotlin = agp_major >= 9
if not builtin_kotlin:
    if "kotlin-gradle-plugin" not in top:
        pat = re.compile(r"([ \t]*)(classpath\s+['\"]com\.android\.tools\.build:gradle:[^'\"]+['\"])")
        mm = pat.search(top)
        if not mm:
            fail("build.gradle me AGP classpath line nahi mili:\n" + top)
        line = mm.group(1) + "classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:" + KOTLIN + "'"
        top = top[: mm.end()] + "\n" + line + top[mm.end():]
        write(top_path, top)
    print("OK Kotlin Gradle plugin", KOTLIN)
else:
    print("AGP 9+: Kotlin plugin alag se nahi lagaya (built-in)")

# 3) app/build.gradle
app_path = APP + "/build.gradle"
app = read(app_path)
if not builtin_kotlin and "kotlin-android" not in app:
    mm = re.search(r"apply plugin:\s*['\"]com\.android\.application['\"]", app)
    if not mm:
        fail("app/build.gradle me 'apply plugin: com.android.application' nahi mila:\n" + app[:600])
    app = app[: mm.end()] + "\napply plugin: 'kotlin-android'" + app[mm.end():]

DEPS = (
    '    implementation "com.google.ai.edge.litertlm:litertlm-android:latest.release"\n'
    '    implementation "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0"\n'
    '    implementation "com.google.code.gson:gson:2.11.0"\n'
)
if "litertlm-android" not in app:
    mm = re.search(r"^dependencies\s*\{[ \t]*\n", app, re.M)
    if not mm:
        fail("app/build.gradle me 'dependencies {' nahi mila:\n" + app[:1200])
    app = app[: mm.end()] + DEPS + app[mm.end():]

EXTRA = """

// ---- Labs Offline: setup_android.py ne jode ----
android {
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_21
        targetCompatibility JavaVersion.VERSION_21
    }
    defaultConfig {
        ndk {
            abiFilters 'arm64-v8a'
        }
    }
}
"""
if not builtin_kotlin:
    EXTRA += """kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}
"""
if "setup_android.py ne jode" not in app:
    app = app.rstrip("\n") + EXTRA
write(app_path, app)
print("OK app/build.gradle")

# 4) Manifest: GPU native libs + internet
man_path = APP + "/src/main/AndroidManifest.xml"
man = read(man_path)
if "libOpenCL.so" not in man:
    if "</application>" not in man:
        fail("Manifest me </application> nahi mila")
    man = man.replace(
        "</application>",
        '    <uses-native-library android:name="libvndksupport.so" android:required="false" />\n'
        '        <uses-native-library android:name="libOpenCL.so" android:required="false" />\n'
        "    </application>",
        1,
    )
if "android.permission.INTERNET" not in man:
    man = re.sub(r"(<manifest[^>]*>)", r'\1\n    <uses-permission android:name="android.permission.INTERNET" />', man, count=1)
write(man_path, man)
print("OK manifest")

# 5) variables.gradle: minSdk kam se kam 24 (Google ki library ki shart)
var_path = ANDROID + "/variables.gradle"
if os.path.isfile(var_path):
    v = read(var_path)
    mm = re.search(r"minSdkVersion\s*=\s*(\d+)", v)
    if mm and int(mm.group(1)) < 24:
        v = v[: mm.start(1)] + "24" + v[mm.end(1):]
        write(var_path, v)
        print("minSdk 24 kiya")
    else:
        print("minSdk:", mm.group(1) if mm else "pata nahi")

# 6) Gradle ko thodi zyada memory
gp = ANDROID + "/gradle.properties"
if os.path.isfile(gp):
    g = read(gp)
    if re.search(r"^org\.gradle\.jvmargs=.*$", g, re.M):
        g = re.sub(r"^org\.gradle\.jvmargs=.*$", "org.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8", g, flags=re.M)
    else:
        g = g.rstrip("\n") + "\norg.gradle.jvmargs=-Xmx3072m -Dfile.encoding=UTF-8\n"
    write(gp, g)
    print("OK gradle.properties")

print("SETUP DONE")
