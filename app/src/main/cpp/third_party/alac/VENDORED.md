# Vendored: Apple ALAC codec

Source: https://github.com/macosforge/alac @ c38887c5c5e64a4b31108733bd79ca9b2496d987 (Apache-2.0, see LICENSE).

Vendored (was a git submodule) so ScreenDock's security patches ship with the repo:
- codec/ALACDecoder.cpp: reject partial-frame `numSamples > frameLength` (remote heap overflow).
- codec/ALACBitUtilities.c: BitBuffer reads/peeks return 0 past end of buffer.
- codec/ag_dec.c: bound reads by remaining bits, not total buffer size.
