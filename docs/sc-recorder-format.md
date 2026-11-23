# SCRECBIN version 0.1 wire format

**English** | [한국어](sc-recorder-format.ko.md)

SCRECBIN is the file format of the frame recorder `sc-recorder`: the observed state of a game, frame by frame, as typed samples.

- [Scope](#scope)
- [File structure](#file-structure)
- [Fixed header: 32 bytes](#fixed-header-32-bytes)
- [Typed metadata](#typed-metadata)
- [Chunk header: 24 bytes](#chunk-header-24-bytes)
- [FRAME payload](#frame-payload)
- [Capture](#capture)
- [END payload](#end-payload)
- [Bounds](#bounds)

## Scope

Only version 0.1 is read. Numbers are little-endian. The format version defines every sample payload; the file holds no field manifest, class names, schema or per-sample version.

## File structure

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

Selected identifiers are positive, unique and increasing. Every frame holds one observation per selected identifier, in that order.

## Fixed header: 32 bytes

| Offset | Width | Meaning             |
|--------|-------|---------------------|
| 0      | 8     | ASCII SCRECBIN      |
| 8      | 2     | major = 0           |
| 10     | 2     | minor = 1           |
| 12     | 4     | totalHeaderBytes    |
| 16     | 4     | featureFlags = 0    |
| 20     | 4     | selectedSampleCount |
| 24     | 4     | metadataBytes       |
| 28     | 4     | header CRC32C       |

Then selectedSampleCount u32 identifiers and metadataBytes of metadata, without padding:

```text
totalHeaderBytes = 32 + 4 * selectedSampleCount + metadataBytes
```

The header CRC32C covers the whole header with bytes 28..31 zeroed.

## Typed metadata

Entry: tag u16, flags u16 (bit 0 REQUIRED), payloadBytes u32, payload. Duplicate known tags and unknown required tags fail; unknown optional tags are skipped.

| Tag | Payload                                                           |
|-----|-------------------------------------------------------------------|
| 1   | producer version, strict UTF-8                                    |
| 2   | executable SHA-256, 32 bytes                                      |
| 3   | requested replay SHA-256, 32 bytes                                |
| 4   | loaded replay evidence, u8                                        |
| 5   | sampling method, u16                                              |
| 6   | polling interval in nanoseconds, u64                              |
| 7   | coverage requirements, u32                                        |
| 8   | permitted digest algorithm, u16: 1 = SHA-256                      |
| 9   | expected first/last frame, two u64 values; all ones means unknown |

Loaded replay evidence: 0 unavailable, 1 loaded header matched, 2 mismatched. Sampling method: 0 caller-provided values, 1 read-only external marker polling.

## Chunk header: 24 bytes

| Offset | Width | Meaning                    |
|--------|-------|----------------------------|
| 0      | 2     | kind: 1 FRAME, 2 END       |
| 2      | 2     | chunkFlags = 0             |
| 4      | 4     | payloadBytes               |
| 8      | 8     | sequence, starting at zero |
| 16     | 4     | chunk CRC32C               |
| 20     | 4     | reserved = 0               |

The CRC32C covers bytes 0..15, bytes 20..23 and the payload, in that order.

## FRAME payload

Prefix (24 bytes): observedFrame u64, elapsedNanoseconds u64 (taken when staging begins), sampleCount u32, consistency u32 (0 UNVERIFIED, 1 MARKER_STABLE, 2 TORN). Frame numbers increase strictly and may have gaps.

Each sample has an 18-byte header:

| Width | Meaning                                                     |
|-------|-------------------------------------------------------------|
| 4     | sampleIdentifier                                            |
| 1     | storageMode: 0 VALUES, 1 DIGEST, 2 NONE                     |
| 1     | status: 0 AVAILABLE, 1 READ_FAILED, 2 TORN, 3 NOT_ATTEMPTED |
| 8     | sampledFrame; all ones when unavailable                     |
| 4     | payloadBytes                                                |

An unavailable observation has NONE and no payload. An available one has VALUES or DIGEST and a sampledFrame no later than observedFrame. No padding.

### VALUES payload

Each `SampleDefinition` has a bounded `SampleReader` and a canonical `SampleWriter`; they define field order, integer widths, optional-value flags and collection bounds, and consume exactly the payload.

| Identifier | Name            | Model                |
|------------|-----------------|----------------------|
| 1          | players         | PlayersSample        |
| 2          | units           | UnitsSample          |
| 3          | random          | RandomSample         |
| 4          | frame           | FrameStateSample     |
| 5          | random-counters | RandomCountersSample |
| 6          | locations       | LocationsSample      |
| 7          | lists           | UnitListsSample      |
| 8          | context         | ContextSample        |
| 9          | triggers        | TriggersSample       |
| 10         | eud             | EudSample            |
| 11         | eud-hosts       | EudHostsSample       |
| 12         | ai              | AiSample             |
| 13         | camera          | CameraSample         |
| 14         | sync            | SyncSample           |
| 15         | tiles           | TilesSample          |
| 16         | runtime-random  | RuntimeRandomSample  |

Players, units and locations are ordered by identifier (a unit identifier is its native pool slot; target pointers become slots), EUD regions by virtual key. Visible and hidden unit lists keep the native traversal order.

Process addresses are normalized, so equal game states give equal payloads:

- units: the union at unit +0x128 becomes a kind byte and a u64. Workers (SCV, Drone, Probe), Nuclear Silo and Nydus Canal: unit pointer as pool slot (kind 1; all ones outside the pool). Ghost and its heroes, Pylon: pointer presence 0 or 1 (kind 2). Other types: the four raw words (kind 0).
- context: the map or replay path (+0xC4C, 260 bytes) and the screen tile (+0xE0, 4 bytes) are zeroed.
- ai: the region table pointer becomes presence 0 or 1; region row pointers at +32, +40 and +56 become offsets from the unit pool, those at +64 and +72 and the unit AI record address offsets from the module base.
- sync: the draw-order bucket table at 0x140E76B70 (256 bytes) is left out; the block starts at 0x140E76C70 and its first QWORD becomes an offset from the unit pool.
- tiles: map width and height (u16 each, game context +0xE4 and +0xE6), then width * height tile WORDs in row-major order from the tile array addressed at 0x140E7E478.
- runtime-random: a u32 row count, then per row the thread order u16 and the C runtime `rand` state u32 (runtime thread data +0x28). The order is the thread's rank by creation time in the latest thread listing; the initial thread is 0. Thread data is found through the fiber local storage slot whose index is at 0x140E09508 (TEB +0x17C8, then the slot chunk table), and is used only while its first pointer is the exception action table 0x140C38280. A thread whose block is unreadable or no longer addressed by its slot is left out, and the threads are listed again for the next sample. A sample fails when no listed thread has readable data or an addressed block fails the table check.
- eud and eud-hosts: emulator, virtual table and host pointers and pointer-valued words of object, node and host-head bytes become 2^62 plus their image offset (inside the executable image) or 2^61 (any other pointer). Zero stays zero.

Comparison samples: players, units, random, frame, random-counters, locations, lists, triggers, eud, eud-hosts (`SampleDefinitions.getComparisonDefinitions`). The others are for diagnosis: context, ai, camera and sync hold client, render or process state; tiles and runtime-random hold state that each machine's C runtime random generator decides (creep tile variants).

Without an EUD emulator the observation is empty. A nonzero unreadable pointer fails the capture. Served values and type-2 heads are recorded only for the region kinds that define them.

### DIGEST payload: 32 bytes

The raw SHA-256 of:

```text
ASCII SCREC-STATE (11 bytes)
formatMajor u16 = 0
formatMinor u16 = 1
sampleIdentifier u32
canonical VALUES payload
```

`Frame.getSample` fails on a digest-only observation; its `SampleObservation` gives the digest. `SampleDigester` computes the digest from sample values.

## Capture

The recorder polls the frame counter (game context +0x14C) in a spin loop, or every N microseconds with `--poll-us N`, and samples a selected frame as soon as the counter changes, after the warm-up passes (`--warm-up N`, default 3). The game increments the counter at 0x140319F38, before the frame phase 0x14031C610 enables the RNG and runs the simulation; the RNG gate is set while that phase runs. A pass is accepted when the counter is equal before and after it and the RNG block (state, total, gate) is unchanged with the gate clear. A pass whose counter changed loses the frame; one whose RNG check stays unstable is retried, then written as TORN (strict) or dropped (tolerant).

| Option | Effect |
|---|---|
| `--coverage strict` (default) | requires no gaps, marker-stable frames and the expected boundaries |
| `--coverage tolerant` | requires available samples and marker-stable frames; missed frames are absent |
| `--dense-frames N --stride K` | every frame of the first N, then the first of each K frames, and every frame from the expected last frame |
| `--frames A..B` | a frame window |
| `--preset test` | comparison samples, DIGEST, tolerant, `--dense-frames 2400 --stride 24`, `--playback-speed X4` |
| `--playback-speed X1`, `X2`, `X4`, `X8`, `MAX` | passes `--playbackSpeed=...` to the game |
| `--sound on` | launches the game with `--launch` instead of `--launch --noSound` |

Frames not recorded appear in the END missing ranges.

`validate FILE` exits 0 (valid, requirements met, normal end), 3 (valid, partial) or 1 (invalid). `inspect FILE` prints the metadata, samples, frame counts and missing ranges.

## END payload

Prefix (64 bytes):

| Width | Meaning                                                 |
|-------|---------------------------------------------------------|
| 8     | committed frameCount                                    |
| 8     | first observed frame; all ones when none                |
| 8     | last observed frame; all ones when none                 |
| 4     | termination: 0 NORMAL, 1 CANCELLED, 2 TIMEOUT, 3 FAILED |
| 4     | quality flags: bit 0 requirements satisfied             |
| 8     | missing frame count                                     |
| 8     | unavailable sample count                                |
| 8     | torn frame count                                        |
| 4     | missing range count                                     |
| 4     | reserved = 0                                            |

Then the missing ranges as first/last u64 pairs: inclusive, ordered, disjoint, coalesced, and matching the declared counts. Counts, times and frame numbers lie in `0..Long.MAX_VALUE`.

END is the last chunk; `readNextFrame` returns null only after a valid END and EOF. A file without END is truncated; closing an unfinished writer writes no END.

## Bounds

- Header at most 1 MiB; chunk payload at most 64 MiB, checked before allocation; 1..4096 selected samples.
- Each sample has a fixed maximum payload size.
- Counts, pointer ranges and arithmetic are checked before allocating or traversing.
- CRCs, reserved values, sequence, identifiers and exact payload consumption are validated; unknown identifiers and versions fail.
- An I/O failure leaves a faulted writer and partial output.
