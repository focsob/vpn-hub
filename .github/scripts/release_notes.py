"""Build the GitHub release body: newest section of RELEASE_NOTES.md + commits since the last APK release."""
import subprocess
import sys

text = open("RELEASE_NOTES.md", encoding="utf-8").read()
sections = text.split("\n## ")
notes = ("## " + sections[1].strip()) if len(sections) > 1 else "## 更新"

try:
    prev = subprocess.run(["git", "describe", "--tags", "--abbrev=0", "--match", "apk-*", "HEAD^"],
                          capture_output=True, text=True, check=True).stdout.strip()
    rng = f"{prev}..HEAD"
except subprocess.CalledProcessError:
    prev, rng = "", "HEAD~20..HEAD"
log = subprocess.run(["git", "log", "--no-merges", "--format=- %s", rng], capture_output=True, text=True).stdout.strip()

body = notes + "\n\n下載下面嘅 `VPNHub.apk` 直接覆蓋安裝（Android 16 或以上）。\n"
if log:
    body += f"\n<details><summary>技術改動{('（自 ' + prev + '）') if prev else ''}</summary>\n\n{log}\n\n</details>\n"
sys.stdout.write(body)
