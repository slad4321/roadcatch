# 로드캐치 (RoadCatch)

**운전 중 놓친 도로 위험, 차가 대신 포착합니다. 주차 후 확인하고 간편하게 신고하세요.**

팀 스웨이 · 현대자동차그룹 차량용 앱 개발 공모전 출품작

주행 중에는 화면 없이 차량 신호(조향, 제동, 방향지시등, 기어, 경고등, 위치)로 도로 위험과 차량 손상이 의심되는 순간을 자동으로 기록합니다. 주차하면 오늘 경로 위에 그 지점들이 표시되고, 운전자는 지점마다 차가 감지한 내용을 보고 발생 상황(도로 파임, 낙하물, 노면 미끄러움, 기타, 이상 없음)을 한 번 골라 저장합니다. 저장된 신고는 여러 차량 교차 확인을 거쳐 도로관리기관에 전달되고, 차량 손상 의심 지점은 배상 청구용 기록으로 남습니다.

## APK

- [`apk/스웨이_로드캐치_release.apk`](apk/) (release, 서명됨, 약 16 MB)
- 패키지 이름: `kr.gilzikim.app` (개발 당시 가칭 '길지킴'에서 유래)

## 실행 방법

### APK만으로 실행 (심사용, 서버 불필요)

1. Pleos Connect 에뮬레이터를 켭니다(아래 테스트 환경 참고).
2. APK를 설치합니다.
   ```bash
   adb install -r "apk/스웨이_로드캐치_release.apk"
   ```
3. 앱 목록에서 **로드캐치**를 실행하고, 위치 권한 요청을 허용합니다.
4. 처음 실행하면 모의 출근 주행(압구정 → 신논현, 2.9 km)이 실제 감지 로직을 거쳐 생성되고 주행 후 확인 화면이 열립니다.
   - 차량 손상 의심 지점(2번)이 먼저 선택되어 있습니다.
   - 발생 상황을 고르면 아래 버튼이 **저장**으로 바뀝니다. 고른 답을 다시 누르면 취소됩니다.
   - 이전·다음 화살표, 지도 번호, **다음 지점**으로 이동합니다. 모든 지점에 답하면 **완료**로 신고별 진행 상태를 봅니다.
   - 지도 왼쪽 아래 **모의 주행**을 누르면 처음부터 다시 시작합니다. **개발자 로그**는 실시간 차량 신호 화면입니다.

release APK에는 데모 경로 주변의 도로망(OpenStreetMap)과 세 지점의 주소가 들어 있어, 네트워크가 없는 에뮬레이터에서도 지도와 주소가 그대로 보입니다.

### 소스에서 빌드

```bash
cd android
./gradlew assembleDebug        # 디버그: 개발 서버의 지도 타일·주소 사용
./gradlew testDebugUnitTest    # 감지 규칙 단위 테스트 14개
```

- JDK 17 이상(Android Studio 내장 JBR 사용 가능), Android SDK 37.
- release 빌드 서명은 `android/keystore.properties`(저장소에 포함하지 않음)로 합니다. 없으면 서명되지 않은 release가 만들어집니다.

### 개발 서버 (선택, debug 빌드용)

```bash
cd server && python3 server.py          # 포트 8790, 표준 라이브러리만 사용
adb reverse tcp:8790 tcp:8790            # Pleos 에뮬레이터는 네트워크가 없어 adb로 연결
```

주소·관할 기관 변환(`POST /v1/places`)과 지도 타일 중계(`GET /v1/tiles/{z}/{x}/{y}.png`)를 합니다. 자세한 내용은 [server/README.md](server/README.md).

### E2E 테스트

에뮬레이터에 설치된 앱을 adb로 조작하며 화면 상태를 19단계로 확인합니다.

```bash
E2E_SERVER=0 python3 android/e2e/postdrive_e2e.py   # release APK, 서버 없이
python3 android/e2e/postdrive_e2e.py                # debug APK + 개발 서버
```

## 테스트 환경 및 필요한 설정

| 항목 | 값 |
|---|---|
| 에뮬레이터 | **Pleos Connect v2.1.0** 시스템 이미지 (`system-images/android-34/pleos_car_emulator/arm64-v8a`), Android 14 (API 34) |
| 화면 규격 | **1408 × 792**, 160 dpi (AVD `Automotive_1408p_landscape`) |
| 호스트 | macOS (Apple silicon), Android Studio |
| 앱 | minSdk 28, targetSdk 34, compileSdk 37, Kotlin 2.2, Jetpack Compose |

필요한 설정:

- **권한:** 위치 권한(`pleos.car.permission.FUSED_LOCATION`은 런타임 권한)을 허용해야 위치 신호가 들어옵니다. 앱은 Android 사용자 10으로 실행되므로, 수동으로 줄 때는 `adb shell pm grant --user 10 kr.gilzikim.app <권한>`을 씁니다.
- **차량 신호:** 핸들 각도, 브레이크, 방향지시등, 기어, 위치는 에뮬레이터에서 수신을 확인했습니다. 값은 에뮬레이터 Virtual Console로 바꿀 수 있습니다.
- **에뮬레이터 한계:** 타이어 압력 속성을 지원하지 않고(`ERROR_NOT_SUPPORTED_PROPERTY`), Virtual Console로는 주행 경로와 시간 흐름을 재현할 수 없습니다. 그래서 주행은 모의 데이터(`ScriptedDrive`)를 실제 감지 로직에 흘려 만듭니다.

## 사용한 Pleos SDK

| SDK | 용도 | 권한 |
|---|---|---|
| Vehicle 2.3.2 — SteeringWheel, Brake, TurnSignal, Gear | 급조향(방향지시등 미작동), 급제동 후 재가속, 주행 시작·종료 | `CAR_DRIVE`, `CAR_LIGHTS` |
| Vehicle — Safety(경고등), Tire | ABS·ESC·결빙·TPMS 경고, 타이어 공기압 | `PRIVATE_CAR_SAFETY` (실차는 Pleos 승인 필요로 추정), 저압 여부는 `CAR_SAFETY` |
| FusedLocation | 경로와 지점 위치 | `FUSED_LOCATION` (런타임) |
| TextToSpeech, SpeechToText | 음성 안내, 기타 상황 음성 입력 (예정) | |

## 구조

```
android/                 차량 앱 (Kotlin, Compose)
  app/src/main/java/kr/gilzikim/app/
    signal/SignalHub.kt        Pleos SDK 신호 구독
    detect/HazardDetector.kt   감지 규칙 (급조향·급제동·충격·공기압 저하·경고등)
    detect/TripRecorder.kt     기어 D~P를 한 주행으로 묶기
    detect/ScriptedDrive.kt    모의 출근 주행
    ui/postdrive/              주행 후 확인 화면
  app/src/main/assets/         내장 도로망·주소 (데모용)
  app/src/test/                감지 규칙 단위 테스트
  e2e/                         에뮬레이터 E2E 테스트
server/                  개발 서버 (Python 표준 라이브러리)
apk/                     release APK
```

## 데이터 출처

- 지도 도로망과 주소: © OpenStreetMap contributors (ODbL). 개발용으로만 사용하며, 운영에서는 계약 제공자(브이월드·카카오 등)로 바꿉니다.
