#!/usr/bin/env python3
"""
End-to-end test of the post-drive review on a running emulator.

Drives the installed app through adb (taps by on-screen text, read back from the UI hierarchy)
and checks the visible state after every step. Needs: the emulator running with the app
installed, gilzikim-server running, and `adb reverse tcp:8790 tcp:8790`.

    python3 e2e/postdrive_e2e.py

Screenshots of failed steps go to e2e/out/.
"""

import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "out")
PACKAGE = "kr.gilzikim.app/.MainActivity"
# Map markers are drawn on a canvas, so they have no text in the UI tree: fixed screen positions
# for the scripted commute on the 1408x792 Pleos emulator.
MARKER = {1: (591, 241), 2: (636, 383), 3: (805, 522)}

results = []


def adb(*args):
    return subprocess.run([ADB, *args], capture_output=True, text=True, check=False).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/gz_ui.xml")
    root = ET.fromstring(adb("shell", "cat", "/sdcard/gz_ui.xml"))
    out = []
    for n in root.iter("node"):
        text = n.get("text") or ""
        if not text:
            continue
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        out.append((text, (x1, y1, x2, y2)))
    return out


def texts():
    return [t for t, _ in nodes()]


def has(text):
    return text in texts()


def bounds(text):
    for t, b in nodes():
        if t == text:
            return b
    return None


def tap_xy(x, y, settle=0.8):
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(settle)


def tap(text, settle=0.8):
    b = bounds(text)
    if b is None:
        raise AssertionError(f"no element with text {text!r}")
    tap_xy((b[0] + b[2]) // 2, (b[1] + b[3]) // 2, settle)


def arrow(direction):
    """The ‹ / › arrows sit on either side of the "n / m" counter."""
    counter = next(((t, b) for t, b in nodes() if re.fullmatch(r"\d+ / \d+", t)), None)
    if counter is None:
        raise AssertionError("no spot counter on screen")
    _, (x1, y1, x2, y2) = counter
    y = (y1 + y2) // 2
    tap_xy(x1 - 8 - 18 if direction == "prev" else x2 + 8 + 18, y)


def wait_for(text, timeout=25):
    end = time.time() + timeout
    while time.time() < end:
        if has(text):
            return True
        time.sleep(1)
    return False


def check(name, *expect_present, absent=()):
    t = texts()
    missing = [e for e in expect_present if e not in t]
    unexpected = [a for a in absent if a in t]
    ok = not missing and not unexpected
    results.append((name, ok, missing, unexpected))
    mark = "PASS" if ok else "FAIL"
    detail = "" if ok else f"  missing={missing} unexpected={unexpected}"
    print(f"[{mark}] {name}{detail}", flush=True)
    if not ok:
        os.makedirs(OUT, exist_ok=True)
        path = os.path.join(OUT, re.sub(r"\W+", "_", name) + ".png")
        with open(path, "wb") as f:
            f.write(subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True).stdout)


def main():
    if os.environ.get("E2E_SERVER", "1") == "1":
        adb("reverse", "tcp:8790", "tcp:8790")
    adb("shell", "am", "force-stop", PACKAGE.split("/")[0])
    adb("shell", "am", "start", "-n", PACKAGE)
    if not wait_for("모의 주행"):
        sys.exit("app did not open")
    tap("모의 주행", settle=1.5)  # fresh review of the scripted commute
    if not wait_for("관할: 강남구청"):
        print("[WARN] place names did not load (server or adb reverse?)")

    # 1. Opens on the damage spot, nothing answered.
    check("열면 차량 손상 의심 지점(2/3)", "차량 손상 의심", "2 / 3", "07:41", "발생 상황", "이상 없음",
          "급제동 후 재가속", "노면 충격", "타이어 공기압 저하", absent=("저장",))

    # 2. Picking only selects; the bottom button turns into 저장. Nothing moves.
    tap("도로 파임")
    check("선택하면 아래 버튼이 저장으로", "2 / 3", "저장", absent=("이상 없음",))

    # 3. Tapping the same answer again clears it.
    tap("도로 파임")
    check("같은 답 다시 누르면 선택 취소", "이상 없음", absent=("저장",))

    # 4. Pick and save.
    tap("낙하물")
    tap("저장")
    check("저장하면 저장됨 표시, 다음 지점 버튼", "발생 상황 · 저장됨", "다음 지점", "2 / 3", absent=("저장",))

    # 5. Tapping the saved answer undoes the save.
    tap("낙하물")
    check("저장된 답 다시 누르면 저장 취소", "발생 상황", "이상 없음", absent=("발생 상황 · 저장됨", "저장"))

    # 6. Change the answer after the fact and save.
    tap("도로 파임")
    tap("저장")
    check("다른 답으로 저장", "발생 상황 · 저장됨", "다음 지점")

    # 7. 다음 지점 goes forward to the next unanswered spot.
    tap("다음 지점")
    check("다음 지점은 앞으로(3/3)", "3 / 3", "도로 위험 의심", "ABS 작동")

    # 8. 이상 없음 saves "nothing there".
    tap("이상 없음")
    check("이상 없음 저장", "발생 상황 · 이상 없음 저장됨", "다음 지점", "3 / 3")

    # 9. Next arrow is disabled on the last spot.
    arrow("next")
    check("마지막 지점에서 다음 화살표는 동작 안 함", "3 / 3")

    # 10. 다음 지점 wraps to the remaining spot.
    tap("다음 지점")
    check("남은 지점으로 이동(1/3)", "1 / 3", "급조향", "37° · 방향지시등 미작동")

    # 11. Prev arrow is disabled on the first spot.
    arrow("prev")
    check("첫 지점에서 이전 화살표는 동작 안 함", "1 / 3")

    # 12. Last answer: the bottom button becomes 완료.
    tap("노면 미끄러움")
    tap("저장")
    check("모두 답하면 완료 버튼", "완료", "발생 상황 · 저장됨", absent=("다음 지점",))

    # 13. Arrows still move between spots and keep each spot's saved answer.
    arrow("next")
    check("화살표 이동(2/3), 저장 상태 유지", "2 / 3", "발생 상황 · 저장됨", "완료")

    # 14. Results.
    tap("완료")
    check("확인 완료 화면", "확인 완료", "신고 진행", "신고", "이상 없음", "손상 기록",
          "도로 파임 · 논현동 강남대로136길", "노면 미끄러움 · 신사동 강남대로162길", "접수",
          absent=("ABS 작동",))

    # 15. A report row reopens its spot; change the answer and finish again.
    tap("07:41")
    check("신고 행 누르면 그 지점 열림", "2 / 3", "차량 손상 의심", "완료")
    tap("낙하물")
    check("결과 후 답 변경 시 저장 버튼", "저장")
    tap("저장")
    tap("완료")
    check("변경한 답이 결과에 반영", "확인 완료", "낙하물 · 논현동 강남대로136길",
          absent=("도로 파임 · 논현동 강남대로136길",))

    # 16. Map marker opens its spot.
    tap_xy(*MARKER[3])
    check("지도 마커 누르면 그 지점 열림", "3 / 3", "발생 상황 · 이상 없음 저장됨")

    # 17. Reset clears everything.
    tap("모의 주행", settle=2)
    check("모의 주행으로 초기화", "2 / 3", "발생 상황", "이상 없음",
          absent=("발생 상황 · 저장됨", "완료", "확인 완료"))

    passed = sum(1 for r in results if r[1])
    print(f"\n{passed}/{len(results)} passed")
    sys.exit(0 if passed == len(results) else 1)


if __name__ == "__main__":
    main()
