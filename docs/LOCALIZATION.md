# Audited display-literal-only Chinese edition

This pass replaces the previous localization implementation. Its baseline is commit `a134b8e5e0424024870b53252eb0d1b9f4d3195e`.

Only 139 reviewed display string literals in the original `MainActivity`, `GatewayDialerActivity`, `GatewayService` and `GatewayDialerService` are translated. No runtime translation adapter, resource lookup or new enum-label mapping is introduced. This edition has fixed Chinese labels; the original product identity, shared statuses, validation errors, protocol IDs and technical diagnostic values remain verbatim. Some technical values therefore remain English.

The first-pass `UiStatusText` adapter and its tests are removed, original resources restored, and the original port-error/save-button data path, enum label functions, interpolation expressions and notification summary chain reinstated.

See [the page-by-page audit](LOCALIZATION_AUDIT.zh.md) for every screen section, its original business binding, preserved values and translation decision. `localization-literals.json` records every approved source literal.

## Source proof

```sh
python3 tools/verify_display_only_localization.py --self-test
```

The checker reverses the reviewed literal replacements and requires the resulting source bytes to match upstream exactly, independently verifies interpolation code, compares 134 protected files byte for byte, and rejects added module source files. Mutation checks demonstrate rejection of changed callbacks, validation conditions, interpolation computations and unreviewed text.

## Build and device validation

The release workflow runs the unchanged core tests, builds the original native libraries, builds both the upstream control APK and Chinese APK using the same toolchain/debug certificate, runs all Android module unit tests, and verifies signatures before publishing a clearly marked prerelease.

The paired APKs make an original-versus-Chinese device comparison possible without changing the package identity. The signing certificate is a CI debug certificate, not an official release key, so it may differ from APKs produced by earlier runs or by upstream.

Source equivalence and passing tests constrain the translation change; they do not prove physical audio routing or resolve a device-specific report without reproduction steps. Verify takeover on/off, dialer role restoration, invalid/valid ports, unsaved/pending settings, both call directions, audio methods, muting, loopback and notifications on the same device and configuration. Never treat a test pass alone as proof of equivalent hardware behavior.
