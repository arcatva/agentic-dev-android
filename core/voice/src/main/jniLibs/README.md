# Vendored native libraries (arm64-v8a)

On-device dictation runtime (sherpa-onnx over onnxruntime). Committed as binaries; verify
integrity against the checksums below before trusting a modified copy.

| Library | License | SHA-256 |
|---|---|---|
| libonnxruntime.so | MIT (microsoft/onnxruntime) | `4d2318b3849abb8862133d3068fc7e807ed8b2671cc6d83657fff2fcb9e1caad` |
| libsherpa-onnx-jni.so | Apache-2.0 (k2-fsa/sherpa-onnx) | `97bf245b98b37ff69042100573d5e6c1c7bc4e72b5f178f6dcf5a04c61b8aece` |

Provenance: prebuilt arm64-v8a artifacts from the sherpa-onnx Android release bundle (the JNI
wrapper classes in `com.k2fsa.sherpa.onnx` match its API). To upgrade: take both .so files from
the same sherpa-onnx release, update the wrapper classes if the API changed, refresh these
checksums, and update THIRD-PARTY-NOTICES.md at the repo root if license texts changed.
