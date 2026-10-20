"""Fast source-contract checks; not a substitute for Android lint, compilation or device tests."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
android = "{http://schemas.android.com/apk/res/android}"
checks = 0
def check(condition, message):
    global checks
    assert condition, message
    checks += 1
    print("PASS", message)

manifest = ET.parse(root/"app/src/main/AndroidManifest.xml").getroot()
app = manifest.find("application")
for activity in app.findall("activity"):
    name = activity.get(android+"name")
    check(activity.get(android+"exported") == ("true" if name.endswith("ListenActivity") else "false"),
          "activity visibility: "+name)
services = {e.get(android+"name"):e for e in app.findall("service")}
projection = services[".service.PlayNotifyService"]
check(projection.get(android+"foregroundServiceType") == "mediaProjection", "projection FGS type")
check(projection.get(android+"exported") == "false", "projection FGS private")
check(services["org.yameida.worktool.service.WeworkService"].get(android+"permission") ==
      "android.permission.BIND_ACCESSIBILITY_SERVICE", "accessibility system binding only")
check(services["org.yameida.worktool.service.WeworkService"].get(android+"foregroundServiceType") is None,
      "accessibility is not a dataSync FGS")
permissions = {e.get(android+"name") for e in manifest.findall("uses-permission")}
check("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" in permissions, "projection permission")
check("android.permission.QUERY_ALL_PACKAGES" not in permissions, "package visibility scoped")
paths = ET.parse(root/"app/src/main/res/xml/provider_paths.xml").getroot()
check(all(p.tag in {"external-files-path", "cache-path"} and p.get("path") == "share/" for p in paths),
      "FileProvider limited to shared subdirectories")
source = "\n".join(p.read_text(encoding="utf-8") for p in root.glob("*/src/main/**/*.kt"))
check("kotlinx.android.synthetic" not in source, "removed synthetic bindings")
check(len(re.findall(r"\.createVirtualDisplay\(", source)) == 1, "one projection session creation site")
capture = (root/"app/src/main/java/org/yameida/worktool/activity/GetScreenShotActivity.kt").read_text(encoding="utf-8")
check("startActivity" not in capture.split("fun startCapture()")[1].split("override fun onCreate")[0],
      "frame reads cannot request fresh consent")
service = (root/"app/src/main/java/org/yameida/worktool/service/WeworkService.kt").read_text(encoding="utf-8")
check("imagemsg2" not in service and "initObserver" not in service, "no cross-app private-cache watcher")
check("RECEIVER_NOT_EXPORTED" in service, "service receiver private")
check("RECEIVER_NOT_EXPORTED" in (root/"app/src/main/java/org/yameida/worktool/activity/ListenActivity.kt").read_text(encoding="utf-8"),
      "activity receiver private")
application = (root/"app/src/main/java/org/yameida/worktool/MyApplication.kt").read_text(encoding="utf-8")
check("PlayNotifyManager.show()" not in application, "application does not start unauthorized projection")
for module in ("app", "baselibrary", "floatwindow"):
    text = (root/module/"build.gradle").read_text(encoding="utf-8")
    check("compileSdk 36" in text and "targetSdkVersion 36" in text and "minSdkVersion 24" in text,
          "SDK bounds: "+module)
for xml in root.glob("*/src/main/**/*.xml"):
    ET.parse(xml)
check(True, "all source XML parses")
print(f"RESULT: {checks} source contract checks passed; Android lint/device validation is separate.")
