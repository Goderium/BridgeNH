"""Recognizable historical incidents, not proof of a current machine-specific cause."""

import re


def recognize(lines):
    signatures = [index for index, line in enumerate(lines) if "Payload may not be larger than 32k" in line]
    if not signatures:
        return []
    # Correlate direction with the latest exception's own stack, never unrelated
    # packet names elsewhere in the tail or a previous occurrence.
    stack = []
    for line in lines[signatures[-1] + 1:]:
        if line.lstrip().startswith("["):
            break
        stack.append(line)
    constructors = set(re.findall(r"\b(C17PacketCustomPayload|S3FPacketCustomPayload)\.<init>", "\n".join(stack)))
    packet = ("client_to_server_C17" if constructors == {"C17PacketCustomPayload"} else
              "server_to_client_S3F" if constructors == {"S3FPacketCustomPayload"} else "unknown")
    return [{
        "id": "minecraft_1710_custom_payload_32k", "signature": "Payload may not be larger than 32k",
        "recognitionScope": "sanitized_bounded_log_tail", "observedPacketPath": packet,
        "directionScope": "latest_matching_exception_stack", "occurrencesInTail": len(signatures),
        "mechanism": "The custom-payload constructor rejects data beyond its 32k protocol bound. An exception/disconnection is not a client memory shortage.",
        "knownReproduction": {
            "date": "2026-10-12", "machine": "Miracle Door", "cycleConfiguration": 100,
            "inputs": "debug unlimited", "recipe": "black titanium dust + circuit 1 -> molten black titanium 144mB",
            "outputPorts": 0, "voiding": "VOID_ALL", "trigger": "Keep controller GUI open and use its normal power button.",
            "recordedPacketPath": "client_to_server_C17", "occurrences": 1,
            "evidenceLimit": "Cycle configuration is not a measured per-cycle count. Actual packet size/channel/responsible widget and the full data-growth cause were not established.",
        },
        "nextAction": "Record the current trace and conditions. This historical incident is not a repair target; do not modify mods or reproduce again just to recognize it.",
    }]
