OpenFst core, Apache-2.0. Source files are unmodified.

Source: https://github.com/csukuangfj/openfst/tree/v1.8.5-2026-06-15
Archive SHA-256: 5f9323ded5c9cf4d4e23325dd92652b18b553556ad92b59996e687ebd9688490

This is the pinned OpenFst dependency of wenet-e2e/WeTextProcessing's Android
runtime. Only core headers and library sources are included; the local CMake
file builds the static core without command-line tools or optional extensions.
The packaged FST graphs come from WeTextProcessing 1.2.0 with the pinned
OpenMOSS normalization configuration. Their hashes are in
`assets/tts/normalizer/manifest.json`.
