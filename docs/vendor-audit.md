# Vendored Code Audit

Receiver includes native code because the AirPlay/RAOP stack depends on protocol, crypto, AAC, and DNS-SD components that are not provided by Android as a single ready-made receiver library.

This audit documents what is retained and what was removed while cleaning the repository for the Android TV receiver build.

## Retained Components

### AirPlay/RAOP Core

Path: `app/src/main/cpp/lib`

Retained because it implements the receiver protocol stack, HTTP handling, pairing, RAOP session handling, mirroring packet handling, RTP buffering, and JNI-facing playback data.

Important subcomponents:

- `crypto`, for AES, SHA1, HMAC, RC4, and related helpers.
- `curve25519` and `ed25519`, for pairing and key exchange.
- `playfair`, for FairPlay support.
- `plist`, for binary/XML property list parsing and serialization.
- Top-level `raop_*`, `http_*`, `sockets`, `threads`, and logger sources.

License note: `playfair` is GPLv3, so the top-level project license is GPLv3.

### FDK AAC Decoder Support

Path: `app/src/main/cpp/lib/fdk-aac`

Retained because `raop_buffer.c` calls the FDK AAC decoder API:

- `aacDecoder_Open`
- `aacDecoder_ConfigRaw`
- `aacDecoder_Fill`
- `aacDecoder_DecodeFrame`
- `aacDecoder_Close`

Retained decoder/support directories:

- `libAACdec`
- `libArithCoding`
- `libDRCdec`
- `libFDK`
- `libMpegTPDec`
- `libPCMutils`
- `libSACdec`
- `libSBRdec`
- `libSYS`

Retained notices:

- `MODULE_LICENSE_FRAUNHOFER`
- `NOTICE`

Removed from the FDK AAC tree:

- Encoder libraries: `libAACenc`, `libMpegTPEnc`, `libSACenc`, `libSBRenc`
- Command-line encoder/sample files: `aac-enc.c`, `wavreader.c`, `wavreader.h`
- Non-CMake upstream build files: `Android.bp`, `Makefile.am`, `Makefile.vc`, `autogen.sh`, `configure.ac`, `fdk-aac.pc.in`
- Upstream-only metadata and platform folders: `OWNERS`, `.clang-format`, `.gitignore`, `m4`, `win32`, `documentation`, `ChangeLog`, `fdk-aac.sym`

### mDNSResponder DNS-SD Client Bridge

Path: `app/src/main/cpp/mDNSResponder`

Retained as a compatibility bridge while runtime Bonjour service registration is handled by Android NSD.

Retained files:

- `CMakeLists.txt`
- `DNSSD.java.h`
- `JNISupport.c`
- `LICENSE`
- `mDNSShared/dns_sd.h`
- `mDNSShared/dns_sd_internal.h`
- `mDNSShared/dns_sd_private.h`
- `mDNSShared/dnssd_clientlib.c`
- `mDNSShared/dnssd_clientstub.c`
- `mDNSShared/dnssd_ipc.c`
- `mDNSShared/dnssd_ipc.h`

Removed from the mDNSResponder tree:

- Sample clients and GUI projects
- macOS, Windows, proxy, and unit-test implementations
- Upstream Makefiles, solution files, private DNS notes, and documentation
- POSIX daemon/core responder sources that are not compiled by the Android JNI client bridge

## Build Graph After Cleanup

The Android native build now compiles only the retained CMake targets:

- `play-lib`
- `raop_server`
- `fdk-aac`
- `jdns_sd`
- static support libraries under `crypto`, `curve25519`, `ed25519`, `playfair`, and `plist`

The Gradle wrapper JAR is intentionally retained. GitHub Actions uses it so the project does not require a separately installed Gradle version on the runner. The wrapper uses Gradle's `bin` distribution to avoid downloading local documentation and source bundles that CI does not need.
