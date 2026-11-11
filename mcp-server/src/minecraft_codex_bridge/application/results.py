"""MCP result rendering with original error and image evidence preserved."""
from __future__ import annotations

import base64
import binascii
import json
import math
import struct
import zlib
from typing import Any
from mcp import types
from ..bridge import BridgeError, MAX_IMAGE_BYTES


def _result(data: dict[str, Any], *, error: bool = False, image: types.ImageContent | None = None) -> types.CallToolResult:
    content: list = [types.TextContent(type="text", text=json.dumps(data, ensure_ascii=False, allow_nan=False, separators=(",", ":")))]
    if image is not None:
        content.append(image)
    return types.CallToolResult(content=content, structuredContent=data, isError=error)


def screenshot_result(data: dict[str, Any]) -> types.CallToolResult:
    try:
        encoded = data["base64"]
        width, height = data["width"], data["height"]
        if data.get("mimeType", "image/png") != "image/png":
            raise ValueError("Not PNG")
        if not isinstance(encoded, str) or len(encoded) > 4 * math.ceil(MAX_IMAGE_BYTES / 3):
            raise ValueError("Oversized PNG")
        if type(width) is not int or type(height) is not int or not 1 <= width <= 8192 or not 1 <= height <= 8192:
            raise ValueError("Invalid image dimensions")
        raw = base64.b64decode(encoded, validate=True)
        if not 33 <= len(raw) <= MAX_IMAGE_BYTES or raw[:8] != b"\x89PNG\r\n\x1a\n" or raw[12:16] != b"IHDR":
            raise ValueError("Invalid PNG header")
        if struct.unpack(">II", raw[16:24]) != (width, height):
            raise ValueError("Image dimensions mismatch")
        # Verify bounded PNG framing and checksums without decompressing image data.
        offset = 8
        saw_data = False
        saw_end = False
        while offset < len(raw):
            if len(raw) - offset < 12:
                raise ValueError("Truncated PNG chunk")
            length = struct.unpack(">I", raw[offset:offset + 4])[0]
            end = offset + length + 12
            if end > len(raw):
                raise ValueError("Truncated PNG data")
            kind = raw[offset + 4:offset + 8]
            checksum = struct.unpack(">I", raw[end - 4:end])[0]
            if zlib.crc32(raw[offset + 4:end - 4]) & 0xFFFFFFFF != checksum:
                raise ValueError("Invalid PNG checksum")
            if offset == 8 and (kind != b"IHDR" or length != 13):
                raise ValueError("Invalid PNG IHDR")
            if kind == b"IDAT":
                saw_data = True
            if kind == b"IEND":
                if length != 0 or end != len(raw):
                    raise ValueError("Invalid PNG IEND")
                saw_end = True
            offset = end
        if not saw_data or not saw_end:
            raise ValueError("Incomplete PNG")
    except (KeyError, ValueError, TypeError, binascii.Error, struct.error):
        raise BridgeError("MALFORMED_IMAGE", "The bridge screenshot is not a valid bounded base64 PNG with matching dimensions.") from None
    metadata = {"mimeType": "image/png", "width": width, "height": height, "bytes": len(raw)}
    # Preserve bounded capture provenance; scaled pixels must not be mistaken for an OS window resize.
    for key in ("originalWidth", "originalHeight", "sourceTextureWidth", "sourceTextureHeight"):
        value = data.get(key)
        if type(value) is int and 1 <= value <= 8192:
            metadata[key] = value
    for key in ("scaled", "cached"):
        if type(data.get(key)) is bool:
            metadata[key] = data[key]
    if type(data.get("capturedAtMillis")) is int and 0 < data["capturedAtMillis"] < 2**53:
        metadata["capturedAtMillis"] = data["capturedAtMillis"]
    for key, allowed in (("source", {"client_framebuffer_texture", "current_gl_read_buffer"}), ("renderPhase", {"RenderTick.END"})):
        if isinstance(data.get(key), str) and data[key] in allowed:
            metadata[key] = data[key]
    state = data.get("displayState")
    if isinstance(state, dict):
        metadata["displayState"] = {key: value for key, value in state.items() if
            (key in {"clientWidth", "clientHeight", "guiWidth", "guiHeight", "framebufferWidth", "framebufferHeight", "textureWidth", "textureHeight", "nativeWidth", "nativeHeight", "modeWidth", "modeHeight"}
                and type(value) is int and 0 <= value <= 16384)
            or (key in {"clientFullscreen", "fullscreenPreference", "framebufferEnabled", "nativeDisplayCreated", "nativeFullscreen", "nativeActive", "nativeVisible"} and type(value) is bool)
            or (key == "guiScalePreference" and type(value) is int and 0 <= value <= 16)
            or (key == "observedAtMillis" and type(value) is int and 0 < value < 2**53)}
    return _result(
        {"ok": True, "data": metadata},
        image=types.ImageContent(type="image", data=encoded, mimeType="image/png"),
    )


def waila_result(data: dict[str, Any]) -> types.CallToolResult:
    # Also recognize explicit provider/coverage failures from older bridge JARs.
    providers, sections = data.get("providers"), data.get("sections")
    incomplete = data.get("complete") is False or data.get("partial") is True
    incomplete |= isinstance(providers, list) and any(isinstance(provider, dict) and
        (provider.get("available") is False or provider.get("truncated") is True) for provider in providers)
    incomplete |= isinstance(sections, dict) and any(isinstance(section, dict) and
        (section.get("providerBudgetPartial") is True or section.get("tooltipTruncated") is True) for section in sections.values())
    if incomplete:
        return _result({"ok": False, "data": {**data, "complete": False, "partial": True}, "error": {
            "code": "WAILA_PARTIAL", "message": "Waila provider text is incomplete; inspect providerNbt, providers and completeness. Partial text is retained; this does not diagnose the in-game overlay.",
            "replayAllowed": False}}, error=True)
    return _result({"ok": True, "data": data})


def health_result(result, client, diagnostics):
    conversation = getattr(client.configuration, "conversation_binding", None)
    if conversation is None and (result.isError or not isinstance((result.structuredContent or {}).get("data"), dict)):
        return result
    try:
        connection = client.configuration.connection_info()
    except BridgeError as error:
        connection = {"mode": "unresolved", "error": error.code}
    if conversation is not None:
        from ..dynamic_catalog import catalog_hint
        diagnostics = {**diagnostics, "dynamicTools": catalog_hint(client.configuration)}
        if result.isError:
            return _result({**result.structuredContent, "mcpConnection": {**connection, "liveStatus": "unavailable",
                "error": result.structuredContent.get("error", {}).get("code")}}, error=True)
        data = result.structuredContent.get("data", {})
        connection = {**connection, "liveStatus": "online", "currentSessionId": data.get("sessionId"),
                      "gameAccessProfile": data.get("profile")}
    return _result({**result.structuredContent, "data": {
        **result.structuredContent["data"], "mcpAdapter": diagnostics, "mcpConnection": connection,
    }})


def completed_result(data):
    """One status-to-MCP result policy for operations with completed/failed receipts."""
    completed = data["status"] == "completed"
    return _result({"ok": completed, "data": data}, error=not completed)
