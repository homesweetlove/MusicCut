<div align="center">

# 🎚️ MusicCut

**안드로이드 오픈소스 멀티트랙 오디오 워크스테이션**

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
[![CI](https://github.com/homesweetlove/MusicCut/actions/workflows/android.yml/badge.svg)](https://github.com/homesweetlove/MusicCut/actions/workflows/android.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

[**English**](README.md) · [**한국어**](README.ko.md) · [**Releases**](https://github.com/homesweetlove/MusicCut/releases) · [**빌드 현황**](https://github.com/homesweetlove/MusicCut/actions)

</div>

MusicCut은 멀티트랙 편집, DSP 오디오 효과, 녹음, 선택적으로 실행하는 온디바이스 AI 음원 분리를 하나의 Android 앱에 모은 프로젝트입니다.

> **상태:** 오픈소스 개발 프리뷰. 코어 단위 테스트와 Android 빌드를 자동 실행하지만 실제 기기 동작, 모델 다운로드, Google Drive 인증은 별도 검증·설정이 필요할 수 있습니다. 비공개 릴리스 서명키를 구성하기 전까지 정식 서명 APK는 제공되지 않습니다.
>
> MusicCut은 **Apple·Logic Pro 및 외부 AI 모델 제작사와 무관한 독립 프로젝트**입니다. 제품 명칭은 호환성·디자인 참고를 설명하기 위해 사용합니다.

## 설치

- [**정식 서명 APK 다운로드 (게시되면 Releases에서 제공)**](https://github.com/homesweetlove/MusicCut/releases)
- 개발용 APK: [Android CI](https://github.com/homesweetlove/MusicCut/actions/workflows/android.yml)의 성공한 실행에서 `musiccut-debug-apk` 파일을 다운로드합니다(로그인 필요할 수 있음).
- 개발용 APK는 빌드마다 디버그 서명키가 달라질 수 있어 이전 버전 위에 업데이트되지 않을 수 있습니다. 설치 전 프로젝트를 백업하세요.

## 주요 구성

| 구분 | 내용 |
| --- | --- |
| 멀티트랙 편집 | 파형, 분할·자르기·합치기, 리플 편집, 실행 취소, 오토메이션 |
| 오디오 엔진 | Kotlin DSP 효과, 믹서, Aux 버스, 오프라인 렌더링 |
| AI 음원 분리 | UVR MDX-Net / Kim Vocal 2, HT-Demucs 온디바이스 ONNX Runtime; 선택적 모델 다운로드 |
| 내보내기 | WAV, MP3, AAC, 기기 저장·공유 |
| Google Drive | 선택적 `drive.file` OAuth 연동, 별도 Google Cloud 설정 필요 |

## 주요 기능

### 편집 (자르기 / 붙이기)
| 기능 | 설명 |
|---|---|
| 멀티트랙 타임라인 | 파형 표시, 마디/박자 눈금자, 핀치 줌, 플레이헤드 자동 스크롤 |
| 편집 도구 3종 | **포인터**(선택·이동·길이 조절·페이드), **범위**(시간 구간 선택), **가위**(탭한 곳에서 자르기) |
| 자르기 / 잘라내기 / 복사 / 붙여넣기 | 붙여넣기는 덮어쓰기, **끼워넣기**는 뒤 내용을 밀어내며 삽입 |
| 구간 삭제 + 당기기 (Ripple) | 곡 중간 부분을 빼면 뒷부분이 자동으로 앞에 붙습니다 |
| 구간만 남기기 / 무음 삽입 / 경계에서 자르기 | 범위 도구로 선택한 구간에 적용 |
| 합치기 (Join) | 이어진 조각은 무손실로, 나머지는 새 오디오로 렌더링해서 하나의 리전으로 |
| 복제, 리버스, 노멀라이즈, 리전 게인, 페이드 인/아웃, 리전 뮤트 | |
| 스냅 | Off / 마디 / 1/4 / 1/8 / 1/16 (템포 기준) |
| 실행 취소 / 다시 실행 | 모든 편집은 비파괴(non-destructive)라 원본 오디오는 그대로 |
| 아레인지먼트 섹션 | Intro / Verse / Chorus 단위로 **섹션 삭제 · 복제(반복) · 앞뒤 순서 바꾸기** — 모든 트랙·마커가 함께 이동 |
| 마커 | 플레이헤드 위치에 마커 추가, 탭해서 이동, 이전/다음 마커로 |
| 넛지 | 선택 리전을 그리드 한 칸(스냅 끄면 10ms)씩 이동 |
| 사이클(루프) 재생, 녹음(오버더빙 + 1마디 카운트인), 메트로놈, 트랙 M/S/R, 트랙 색상 | |

### 플러그인 (로직 기본 구성, 41종)
트랙마다, 그리고 마스터(Stereo Out)에 **무제한 인서트 체인**을 걸 수 있고, 순서 변경 / 바이패스 / 프리셋을 지원합니다.

| 카테고리 | 플러그인 |
|---|---|
| EQ | **Channel EQ** (밴드 드래그 + 분석기), Silver EQ, **Graphic EQ** (31밴드), **Match EQ** (레퍼런스 학습) |
| Dynamics | **Compressor** (Platinum/VCA/FET/Opto/Classic 회로, 사이드체인), **Limiter**, **Adaptive Limiter**, **Noise Gate**(사이드체인), **DeEsser 2**, **Multipressor**, **Enveloper** |
| Reverb | **ChromaVerb**, **Space Designer** (컨볼루션: Room/Hall/Plate/Chamber/Cathedral/Spring) |
| Delay | **Tape Delay**, **Stereo Delay** (템포 싱크), **Delay Designer** (4탭), **Sample Delay** |
| Modulation | **Chorus**, **Ensemble**, **Flanger**, **Phaser**, **Tremolo**, **Ringshifter** |
| Distortion | **Overdrive**, **Distortion**, **Bitcrusher**, **Exciter**, **ChromaGlow**, **Amp Designer** |
| Filter | **AutoFilter**(LFO/엔벨로프), High/Low Cut |
| Pitch | **Pitch Shifter**, **Pitch Correction** (오토튠), **Vocal Transformer** (피치+포먼트) |
| Imaging / Utility | **Direction Mixer**, **Stereo Spread**, **Gain**, **Loudness Meter**, **MultiMeter**, **Tuner**, **Test Oscillator** |

Aux 버스(센드 리턴)도 지원합니다. 믹서의 Send 슬롯에서 "새 Aux + ChromaVerb" 를 고르면 로직처럼 리버브 버스가 만들어집니다.

새 플러그인은 `AudioPlugin`을 상속하고 `PluginRegistry.register(...)`로 등록하면 바로 플러그인 목록에 나타납니다.

### 보컬 / 반주 분리 (AI)
리전을 선택하고 **보컬 분리**를 누르면 `Vocals`, `Inst` 트랙이 새로 생깁니다. 엔진은 3가지 중에서 고릅니다.

| 엔진 | 설명 |
|---|---|
| **AI · UVR MDX-Net Voc FT** (기본) | Ultimate Vocal Remover의 MDX-Net ONNX 모델을 ONNX Runtime으로 폰에서 직접 실행. 처음 한 번만 모델(약 64MB) 다운로드 후 오프라인 |
| AI · Kim Vocal 2 | 같은 구조의 다른 학습 모델 |
| 빠른 분리 | STFT 스테레오 센터 추출 (가볍고 빠름, 품질은 낮음) |

AI 파이프라인(STFT 7680 / hop 1024 / 3072×256 텐서, 청크 오버랩)은 파이썬 기준 구현과 결과가 일치하는지 테스트합니다(오차 2×10⁻⁷).

### Flex · 템포 · 음정
- **타임 스트레치**(음정 유지, 페이즈 보코더) / **Varispeed** / **음정 이동**(길이 유지)
- **템포 감지** → 프로젝트 템포로 설정, 리전을 프로젝트 템포에 맞추기, 루프를 넣을 때 자동 템포 맞춤
- **Flex Pitch**: 보컬의 음표를 감지해 피아노 롤에 표시 → 드래그로 음정 수정, "키에 맞추기"
- **Pitch Correction** 플러그인: 실시간 오토튠 (키/스케일/반응 속도)

### 믹싱
- **오토메이션**: 볼륨·팬·센드·모든 플러그인 파라미터. `A` 버튼으로 오토메이션 보기 → 탭해서 점 추가, 드래그 이동, 길게 눌러 삭제. **Latch 쓰기**로 재생 중 페이더/노브 움직임을 기록
- **사이드체인**: 컴프레서/노이즈 게이트의 키를 다른 트랙으로 (킥 덕킹 등)
- **서밍 스택 / 출력 라우팅**, **그룹**(볼륨·뮤트·솔로 연동), Aux 센드(Pre/Post)
- **LUFS 라우드니스**: Loudness Meter 플러그인, 내보낼 때 -14 / -16 / -9 LUFS 자동 맞춤 + 결과 LUFS·True Peak 표시

### 녹음 · 테이크
- 1마디 카운트인, 메트로놈
- 같은 구간을 다시 녹음하면 **테이크 폴더**가 생기고, 테이크 전체 또는 선택 구간만 골라 컴핑

### 파일
- 내보내기: **MP3 (320/192k)**, WAV 16/24/32f, M4A(AAC)
- **자동 저장**(1분마다) + 앱을 열면 마지막 프로젝트 자동으로 열기
- **루프 · 미디어 브라우저**: 폴더를 골라 미리듣기, 템포 맞춰서 바로 넣기

### Google Drive 연동
- **내보내기 → Google Drive 업로드**: 믹스를 WAV(16/24/32f) 또는 M4A(AAC)로 바운스해서 Drive의 `MusicCut AI` 폴더에 업로드
- **프로젝트를 Drive에 백업**: 프로젝트 JSON + 오디오 파일 전체 업로드
- 기기 저장(SAF) / 다른 앱으로 공유도 지원

## 구조

```
core/   순수 Kotlin (안드로이드 의존성 없음, JVM에서 테스트)
  audio/       AudioBuffer, WAV 코덱, 고품질 리샘플러
  dsp/         Biquad, SVF, 딜레이 라인, FFT, 엔벨로프
  plugin/      플러그인 API, 레지스트리, 체인, 주파수 응답 분석
  plugin/effects/  26개 이펙트 구현
  project/     Project / Track / Region 모델 (JSON 직렬화), 소스 풀, 파형 피크
  edit/        비파괴 편집 연산 (split, ripple cut/insert, paste, trim, join…), 섹션/마커 편집, Undo
  engine/      믹서 (리전 → 인서트 → 센드 → 볼륨/팬 → Aux → 마스터), 메트로놈, 오프라인 바운스
  separation/  보컬/반주 분리
app/    Android (Jetpack Compose)
  audio/       MediaCodec 디코더, AudioTrack 재생 엔진, AudioRecord 녹음, AAC 인코더
  drive/       Google 인증 + Drive v3 재개 가능(resumable) 업로드
  data/        프로젝트 저장/불러오기
  ui/          타임라인, 믹서, 플러그인 에디터, 대화상자
```

재생 엔진은 전용 고우선순위 스레드에서 32-bit float로 믹싱하며, 편집·페이더·플러그인 값 변경이 재생 중에도 바로 반영됩니다.
프로젝트 오디오는 32-bit float WAV로 앱 내부 저장소에 저장되어 편집을 반복해도 음질 손실이 없습니다.
## 빌드 및 테스트

JDK 17과 Android SDK 35가 필요합니다. 로컬 디버그 빌드는 Google 인증 정보나 릴리스 서명키가 없어도 됩니다.

```bash
./gradlew :core:test :app:assembleDebug
```

GitHub Actions는 코어 단위 테스트와 Android 빌드를 확인합니다. 빌드 아티팩트는 **개발용**이며 정식 릴리스가 아닙니다. 정식 서명 배포 방법은 [릴리스 안내](docs/RELEASE.md)를 참고하세요.

### Google Drive 설정

Google Drive API를 활성화하고 패키지 `com.musiccut.app` 및 **직접 사용하는 서명 인증서의 SHA-1 지문**으로 Android OAuth 클라이언트를 만들어야 합니다. 기존 비공개 프로젝트의 서명 지문은 사용하지 않습니다. 앱에서는 `drive.file` 범위 권한만 요청합니다.

### 외부 모델·상표

AI 모델은 원본 제공처에서 별도로 다운로드하며 저장소·APK에 포함하지 않습니다. [모델 출처 및 라이선스](THIRD_PARTY_NOTICES.md)를 확인하세요. 오디오 저작권과 각 모델 라이선스는 별도로 적용됩니다.

