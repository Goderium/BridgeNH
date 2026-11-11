import base64
import struct
import zlib

import pytest

from minecraft_codex_bridge.bridge import BridgeError
from minecraft_codex_bridge.server import screenshot_result


def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


RAW = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(b"\x00\xff\x00\x00\xff")) + chunk(b"IEND", b"")


@pytest.mark.parametrize("changes", [
    {"base64": "not base64!"}, {"mimeType": "image/jpeg"}, {"width": 2}, {"height": True},
    {"base64": base64.b64encode(RAW[:-1]).decode()},
    {"base64": base64.b64encode(RAW[:-12]).decode()},
    {"base64": base64.b64encode(RAW[:45] + b"\x00" + RAW[46:]).decode()},
])
def test_malformed_images_are_not_returned(changes):
    data = {"base64": base64.b64encode(RAW).decode(), "width": 1, "height": 1, "mimeType": "image/png", **changes}
    with pytest.raises(BridgeError) as error:
        screenshot_result(data)
    assert error.value.code == "MALFORMED_IMAGE"


def test_capture_provenance_survives_mcp_without_raw_or_unbounded_fields():
    data = {"base64": base64.b64encode(RAW).decode(), "width": 1, "height": 1,
        "originalWidth": 3200, "originalHeight": 1710, "scaled": True,
        "source": "client_framebuffer_texture", "sourceTextureWidth": 4096, "sourceTextureHeight": 2048,
        "capturedAtMillis": 1792800000000, "renderPhase": "RenderTick.END", "cached": False,
        "displayState": {"clientWidth": 3200, "clientHeight": 1710, "clientFullscreen": True,
            "nativeWidth": 3200, "nativeHeight": 1710, "nativeFullscreen": True,
            "fullscreenPreference": True, "guiScalePreference": 2, "privateExtra": "not metadata"}}
    result = screenshot_result(data)
    metadata = result.structuredContent["data"]
    assert not result.isError and metadata["originalWidth"] == 3200 and metadata["scaled"]
    assert metadata["sourceTextureWidth"] == 4096 and metadata["displayState"]["nativeWidth"] == 3200
    assert metadata["cached"] is False and metadata["capturedAtMillis"] == data["capturedAtMillis"]
    assert "privateExtra" not in metadata["displayState"] and "base64" not in metadata
    assert len(result.content) == 2 and result.content[1].data == data["base64"]


def test_optional_capture_metadata_cannot_smuggle_objects_or_images():
    data = {"base64": base64.b64encode(RAW).decode(), "width": 1, "height": 1,
        "source": {"unexpected": "object"}, "originalWidth": True, "originalHeight": -1,
        "displayState": {"clientWidth": "854", "nativeFullscreen": 1, "scope": "private data"}}
    result = screenshot_result(data)
    assert result.structuredContent["data"] == {"mimeType": "image/png", "width": 1, "height": 1,
        "bytes": len(RAW), "displayState": {}}
