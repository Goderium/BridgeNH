# Sanitized historical regression capture

[English](r20-capture.md) | [简体中文](r20-capture_zh.md)

`round-20-810-timing-compact-v1.json.gz` is a sanitized derivative of a historical native-game capture. It is used only for offline consumer regression; it is not current-world evidence or an instruction to replay actions.

Uncompressed SHA256: `e3dfe4f792f266ab129ec98e505bb8f8b014bcecd572e214f4c074bed177a0fd`.

Player names, UUID/session correlations, world labels and machine-specific filesystem roots are replaced by synthetic identities/placeholders. The same original identity maps consistently throughout the fixture. Geometry, inventory relationships, NBT structure, operation order and numerical evidence remain regression inputs. The derivative is deliberately not described as the original byte-for-byte capture. No credential fields are included.

The exact-reference pool is decoded by `test_owned_replacement_consumer.py`. `MCB_TEST_R20_CAPTURE` may point to an absolute JSON/gzip path containing this same sanitized fixture; its uncompressed hash must match. No client installation or external build directory is required.
