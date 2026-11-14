#!/usr/bin/env python3
"""Records a ~2 minute demo video of the post-drive review on the emulator (adb screenrecord)."""
import os, subprocess, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import postdrive_e2e as e  # reuse tap-by-text helpers

OUT = sys.argv[1] if len(sys.argv) > 1 else "demo.mp4"
e.adb("shell", "am", "force-stop", "kr.gilzikim.app")
e.adb("shell", "am", "start", "-n", e.PACKAGE)
e.wait_for("모의 주행")
e.tap("모의 주행", settle=4)
rec = subprocess.Popen([e.ADB, "shell", "screenrecord", "--time-limit", "170", "--bit-rate", "8000000", "/sdcard/rc_demo.mp4"])
time.sleep(2)
pause = lambda s=2.0: time.sleep(s)
pause(3)                                  # opening: damage spot 2/3
e.tap("도로 파임"); pause()               # pick -> 저장
e.tap("도로 파임"); pause(1.5)            # undo pick
e.tap("도로 파임"); pause(1.5)
e.tap("저장"); pause(2.5)
e.tap("다음 지점"); pause(3)              # 3/3 ABS
e.tap("이상 없음"); pause(2.5)
e.tap("다음 지점"); pause(3)              # 1/3 swerve
e.tap("낙하물"); pause(1.5)
e.tap("저장"); pause(2.5)
e.arrow("next"); pause(2)                 # browse back to 2/3
e.tap_xy(*e.MARKER[3]); pause(2)          # open spot via map
e.tap("완료"); pause(5)                   # results
e.tap("07:41"); pause(3)                  # reopen a report
e.tap("완료"); pause(4)
rec.terminate(); rec.wait()
time.sleep(3)
e.adb("pull", "/sdcard/rc_demo.mp4", OUT)
print("saved", OUT)
