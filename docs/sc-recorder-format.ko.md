# SCRECBIN 버전 0.1 와이어 형식

[English](sc-recorder-format.md) | **한국어**

SCRECBIN은 프레임 기록기 `sc-recorder`의 파일 형식입니다. 게임의 관찰된 상태를 프레임마다 타입이 있는 샘플로 담습니다.

- [범위](#범위)
- [파일 구조](#파일-구조)
- [고정 헤더: 32바이트](#고정-헤더-32바이트)
- [타입 메타데이터](#타입-메타데이터)
- [청크 헤더: 24바이트](#청크-헤더-24바이트)
- [FRAME 페이로드](#frame-페이로드)
- [캡처](#캡처)
- [END 페이로드](#end-페이로드)
- [한계](#한계)

## 범위

버전 0.1만 읽습니다. 숫자는 리틀엔디언입니다. 파일 형식 버전이 모든 샘플 페이로드를 정하며, 파일에는 필드 목록, 클래스 이름, 스키마, 샘플별 버전이 없습니다.

## 파일 구조

```text
Fixed header
Selected sample identifiers
Typed provenance metadata
FRAME chunk, sequence 0
FRAME chunk, sequence 1
...
END chunk, next sequence
Physical EOF
```

선택한 식별자는 양수이고, 중복이 없고, 증가합니다. 모든 프레임에는 선택한 식별자마다 관찰 하나가 그 순서로 들어 있습니다.

## 고정 헤더: 32바이트

| 오프셋 | 폭 | 의미                |
|--------|----|---------------------|
| 0      | 8  | ASCII SCRECBIN      |
| 8      | 2  | major = 0           |
| 10     | 2  | minor = 1           |
| 12     | 4  | totalHeaderBytes    |
| 16     | 4  | featureFlags = 0    |
| 20     | 4  | selectedSampleCount |
| 24     | 4  | metadataBytes       |
| 28     | 4  | header CRC32C       |

그다음 u32 식별자 selectedSampleCount개와 메타데이터 metadataBytes바이트가 패딩 없이 옵니다.

```text
totalHeaderBytes = 32 + 4 * selectedSampleCount + metadataBytes
```

헤더 CRC32C는 바이트 28..31을 0으로 둔 헤더 전체를 대상으로 합니다.

## 타입 메타데이터

항목: tag u16, flags u16(비트 0 REQUIRED), payloadBytes u32, 페이로드. 알려진 태그의 중복과 모르는 필수 태그는 실패하고, 모르는 선택 태그는 건너뜁니다.

| 태그 | 페이로드                                                   |
|------|------------------------------------------------------------|
| 1    | 생성기 버전, strict UTF-8                                  |
| 2    | 실행 파일 SHA-256, 32바이트                                |
| 3    | 요청한 리플레이 SHA-256, 32바이트                          |
| 4    | 불러온 리플레이 근거, u8                                   |
| 5    | 샘플링 방식, u16                                           |
| 6    | 폴링 간격(나노초), u64                                     |
| 7    | 커버리지 요구 사항, u32                                    |
| 8    | 허용하는 다이제스트 알고리즘, u16: 1 = SHA-256             |
| 9    | 예상 첫/마지막 프레임, u64 값 둘. 모든 비트 1은 알 수 없음 |

불러온 리플레이 근거: 0 사용할 수 없음, 1 불러온 헤더 일치, 2 불일치. 샘플링 방식: 0 호출자가 준 값, 1 읽기 전용 외부 마커 폴링.

## 청크 헤더: 24바이트

| 오프셋 | 폭 | 의미                        |
|--------|----|-----------------------------|
| 0      | 2  | kind: 1 FRAME, 2 END        |
| 2      | 2  | chunkFlags = 0              |
| 4      | 4  | payloadBytes                |
| 8      | 8  | sequence, 0부터 시작        |
| 16     | 4  | chunk CRC32C                |
| 20     | 4  | reserved = 0                |

CRC32C는 바이트 0..15, 바이트 20..23, 페이로드를 이 순서로 대상으로 합니다.

## FRAME 페이로드

앞부분(24바이트): observedFrame u64, elapsedNanoseconds u64(스테이징 시작 시각), sampleCount u32, consistency u32(0 UNVERIFIED, 1 MARKER_STABLE, 2 TORN). 프레임 번호는 엄격하게 증가하며 빈틈이 있을 수 있습니다.

각 샘플에는 18바이트 헤더가 있습니다.

| 폭 | 의미                                                        |
|----|-------------------------------------------------------------|
| 4  | sampleIdentifier                                            |
| 1  | storageMode: 0 VALUES, 1 DIGEST, 2 NONE                     |
| 1  | status: 0 AVAILABLE, 1 READ_FAILED, 2 TORN, 3 NOT_ATTEMPTED |
| 8  | sampledFrame. 사용할 수 없으면 모든 비트 1                  |
| 4  | payloadBytes                                                |

사용할 수 없는 관찰은 NONE이고 페이로드가 없습니다. 사용할 수 있는 관찰은 VALUES나 DIGEST이고 sampledFrame이 observedFrame보다 늦지 않습니다. 패딩은 없습니다.

### VALUES 페이로드

`SampleDefinition`마다 한계가 있는 `SampleReader`와 정규형 `SampleWriter`가 있습니다. 이들이 필드 순서, 정수 폭, 선택 값 플래그, 컬렉션 한계를 정하고 페이로드를 정확히 다 소비합니다.

| 식별자 | 이름            | 모델                 |
|--------|-----------------|----------------------|
| 1      | players         | PlayersSample        |
| 2      | units           | UnitsSample          |
| 3      | random          | RandomSample         |
| 4      | frame           | FrameStateSample     |
| 5      | random-counters | RandomCountersSample |
| 6      | locations       | LocationsSample      |
| 7      | lists           | UnitListsSample      |
| 8      | context         | ContextSample        |
| 9      | triggers        | TriggersSample       |
| 10     | eud             | EudSample            |
| 11     | eud-hosts       | EudHostsSample       |
| 12     | ai              | AiSample             |
| 13     | camera          | CameraSample         |
| 14     | sync            | SyncSample           |
| 15     | tiles           | TilesSample          |
| 16     | runtime-random  | RuntimeRandomSample  |

플레이어, 유닛, 로케이션은 식별자 순서이고(유닛 식별자는 원본 풀 슬롯이며, 대상 포인터는 슬롯이 됨), EUD 영역은 가상 키 순서입니다. 보이는/숨은 유닛 목록은 원본 순회 순서를 유지합니다.

프로세스 주소는 정규화되므로 같은 게임 상태는 같은 페이로드를 줍니다.

- units: 유닛 +0x128의 공용체는 종류 바이트와 u64가 됩니다. 일꾼(SCV, Drone, Probe), Nuclear Silo, Nydus Canal: 유닛 포인터를 풀 슬롯으로(종류 1, 풀 밖이면 모든 비트 1). Ghost와 그 영웅, Pylon: 포인터 존재 여부 0 또는 1(종류 2). 그 밖의 타입: 원래 워드 넷(종류 0).
- context: 맵이나 리플레이 경로(+0xC4C, 260바이트)와 화면 타일(+0xE0, 4바이트)은 0으로 둡니다.
- ai: 영역 테이블 포인터는 존재 여부 0 또는 1이 되고, +32, +40, +56의 영역 행 포인터는 유닛 풀 기준 오프셋, +64와 +72의 것과 유닛 AI 레코드 주소는 모듈 베이스 기준 오프셋이 됩니다.
- sync: 0x140E76B70의 그리기 순서 버킷 테이블(256바이트)은 빠집니다. 블록은 0x140E76C70에서 시작하며 첫 QWORD는 유닛 풀 기준 오프셋이 됩니다.
- tiles: 맵 너비와 높이(각 u16, 게임 컨텍스트 +0xE4와 +0xE6), 그다음 0x140E7E478이 가리키는 타일 배열에서 행 우선 순서로 너비 * 높이 개의 타일 WORD.
- runtime-random: u32 행 수, 그다음 행마다 스레드 순서 u16과 C 런타임 `rand` 상태 u32(런타임 스레드 데이터 +0x28). 순서는 최근 스레드 목록에서 생성 시각에 따른 순위이며, 처음 스레드는 0입니다. 스레드 데이터는 인덱스가 0x140E09508에 있는 fiber local storage 슬롯으로 찾고(TEB +0x17C8, 그다음 슬롯 청크 테이블), 첫 포인터가 예외 동작 테이블 0x140C38280일 때만 씁니다. 블록을 읽을 수 없거나 슬롯이 더 이상 블록을 가리키지 않는 스레드는 빠지고, 다음 샘플을 위해 스레드 목록을 다시 만듭니다. 목록의 어느 스레드에도 읽을 수 있는 데이터가 없거나 가리켜진 블록이 테이블 확인에 실패하면 샘플이 실패합니다.
- eud와 eud-hosts: 에뮬레이터, 가상 테이블, 호스트 포인터, 그리고 객체, 노드, 호스트 헤드 바이트의 포인터 값 워드는 2^62 더하기 이미지 오프셋(실행 파일 이미지 안) 또는 2^61(그 밖의 포인터)이 됩니다. 0은 0으로 남습니다.

비교 샘플: players, units, random, frame, random-counters, locations, lists, triggers, eud, eud-hosts(`SampleDefinitions.getComparisonDefinitions`). 나머지는 진단용입니다. context, ai, camera, sync는 클라이언트, 렌더, 프로세스 상태를, tiles와 runtime-random은 컴퓨터마다 C 런타임 난수 생성기가 정하는 상태(크립 타일 변형)를 담습니다.

EUD 에뮬레이터가 없으면 관찰이 비어 있습니다. 0이 아닌데 읽을 수 없는 포인터는 캡처를 실패시킵니다. 제공된 값과 타입 2 헤드는 그것을 정의하는 영역 종류에 대해서만 기록합니다.

### DIGEST 페이로드: 32바이트

다음의 SHA-256 원본 바이트입니다.

```text
ASCII SCREC-STATE (11 bytes)
formatMajor u16 = 0
formatMinor u16 = 1
sampleIdentifier u32
canonical VALUES payload
```

다이제스트만 있는 관찰에서 `Frame.getSample`은 실패하고, 그 `SampleObservation`이 다이제스트를 줍니다. `SampleDigester`는 샘플 값으로 다이제스트를 계산합니다.

## 캡처

기록기는 프레임 카운터(게임 컨텍스트 +0x14C)를 스핀 루프로, 또는 `--poll-us N`이면 N마이크로초마다 폴링하고, 준비 패스(`--warm-up N`, 기본값 3)를 실행한 뒤 카운터가 바뀌는 즉시 선택한 프레임을 샘플링합니다. 게임은 0x140319F38에서 카운터를 올리고, 그다음 프레임 단계 0x14031C610이 RNG를 켜고 시뮬레이션을 실행합니다. 그 단계가 실행되는 동안 RNG 게이트가 켜져 있습니다. 한 패스는 앞뒤의 카운터가 같고 RNG 블록(상태, 합계, 게이트)이 바뀌지 않았으며 게이트가 꺼져 있을 때 받아들입니다. 카운터가 바뀐 패스는 그 프레임을 잃고, RNG 확인이 계속 불안정한 패스는 다시 시도한 뒤 TORN으로 쓰거나(strict) 버립니다(tolerant).

| 옵션 | 효과 |
|---|---|
| `--coverage strict`(기본값) | 빈틈 없음, 마커가 안정된 프레임, 예상한 경계를 요구 |
| `--coverage tolerant` | 사용할 수 있는 샘플과 마커가 안정된 프레임을 요구. 놓친 프레임은 없음 |
| `--dense-frames N --stride K` | 처음 N개의 모든 프레임, 그다음 K 프레임마다 첫 프레임, 마지막 예상 프레임부터 모든 프레임 |
| `--frames A..B` | 프레임 구간 |
| `--preset test` | 비교 샘플, DIGEST, tolerant, `--dense-frames 2400 --stride 24`, `--playback-speed X4` |
| `--playback-speed X1`, `X2`, `X4`, `X8`, `MAX` | 게임에 `--playbackSpeed=...` 전달 |
| `--sound on` | 게임을 `--launch --noSound` 대신 `--launch`로 시작 |

기록하지 않은 프레임은 END의 빠진 범위에 나타납니다.

`validate FILE`은 0(올바름, 요구 사항 만족, 정상 종료), 3(올바르지만 부분적), 1(올바르지 않음)로 종료합니다. `inspect FILE`은 메타데이터, 샘플, 프레임 수, 빠진 범위를 출력합니다.

## END 페이로드

앞부분(64바이트):

| 폭 | 의미                                                    |
|----|---------------------------------------------------------|
| 8  | 확정된 frameCount                                       |
| 8  | 처음 관찰된 프레임. 없으면 모든 비트 1                  |
| 8  | 마지막 관찰된 프레임. 없으면 모든 비트 1                |
| 4  | termination: 0 NORMAL, 1 CANCELLED, 2 TIMEOUT, 3 FAILED |
| 4  | 품질 플래그: 비트 0 요구 사항 만족                      |
| 8  | 빠진 프레임 수                                          |
| 8  | 사용할 수 없는 샘플 수                                  |
| 8  | 찢어진 프레임 수                                        |
| 4  | 빠진 범위 수                                            |
| 4  | reserved = 0                                            |

그다음 빠진 범위가 첫/마지막 u64 쌍으로 옵니다. 포함 범위이고, 정렬되어 있고, 겹치지 않고, 합쳐져 있으며, 선언한 개수와 맞습니다. 개수, 시간, 프레임 번호는 `0..Long.MAX_VALUE` 안에 있습니다.

END는 마지막 청크입니다. `readNextFrame`은 올바른 END와 EOF 뒤에만 null을 반환합니다. END가 없는 파일은 잘린 것이며, 끝나지 않은 쓰기 객체를 닫아도 END는 쓰지 않습니다.

## 한계

- 헤더 최대 1 MiB, 청크 페이로드 최대 64 MiB(할당 전에 확인), 선택한 샘플 1..4096.
- 샘플마다 정해진 최대 페이로드 크기가 있습니다.
- 개수, 포인터 범위, 산술은 할당이나 순회 전에 확인합니다.
- CRC, 예약 값, 시퀀스, 식별자, 페이로드 소비를 검증하며, 모르는 식별자와 버전은 실패합니다.
- 입출력 실패는 결함 상태의 쓰기 객체와 부분 출력을 남깁니다.
