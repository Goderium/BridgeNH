"""Compare native SNBT compound order without changing receipts or decoding values.

Only a bounded, unambiguous subset is recognized. Unsupported text stays opaque;
this is not an NBT writer, a general parser, or permission to omit tag contents.
"""

import json
import re


def compound_order_key(text: str):
    opaque = ("opaque", text)
    if len(text) > 65536 or not text.startswith("{"):
        return opaque
    position, nodes = 0, 0

    def whitespace():
        nonlocal position
        while position < len(text) and text[position].isspace():
            position += 1

    def token():
        nonlocal position
        whitespace()
        start = position
        if position < len(text) and text[position] == '"':
            position += 1
            while position < len(text):
                char = text[position]
                position += 1
                if char == "\\":
                    if position >= len(text) or text[position] not in ('"', "\\"):
                        raise ValueError("unsupported escape")
                    position += 1
                elif char == '"':
                    return text[start:position]
            raise ValueError("unterminated string")
        while position < len(text) and text[position] not in ',:{}[]"\' \t\r\n':
            position += 1
        if position == start:
            raise ValueError("empty token")
        return text[start:position]

    def value(depth):
        nonlocal position, nodes
        nodes += 1
        if depth > 32 or nodes > 4096:
            raise ValueError("budget")
        whitespace()
        if position >= len(text):
            raise ValueError("missing value")
        opening = text[position]
        if opening not in "{[":
            scalar = token()
            if not scalar.startswith('"') and not re.fullmatch(
                r"[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?[bBsSlLfFdD]?", scalar
            ):
                raise ValueError("unsupported scalar")
            return ("scalar", scalar)
        position += 1
        closing = "}" if opening == "{" else "]"
        entries, keys = [], set()
        whitespace()
        while position < len(text) and text[position] != closing:
            if opening == "{":
                key = token()
                # Native unquoted keys containing punctuation are ambiguous.
                decoded = json.loads(key) if key.startswith('"') else key
                # AE cells use literal compound keys #0, #1, ...; these are
                # not list indices and contain no SNBT token delimiter.
                if not key.startswith('"') and not re.fullmatch(r"[A-Za-z0-9_.+#-]+", key):
                    raise ValueError("unsupported key")
                if decoded in keys:
                    raise ValueError("duplicate key")
                keys.add(decoded)
                whitespace()
                if position >= len(text) or text[position] != ":":
                    raise ValueError("missing colon")
                position += 1
                entries.append((key, value(depth + 1)))
            else:
                # 1.7.10 lists may print explicit index prefixes. Retain them
                # and list order; never equate list entries with compound keys.
                start = position
                prefix = re.match(r"[0-9]+:", text[position:])
                index = prefix.group() if prefix else ""
                position = start + len(index)
                entries.append((index, value(depth + 1)))
            whitespace()
            if position < len(text) and text[position] == ",":
                position += 1
                whitespace()
            elif position >= len(text) or text[position] != closing:
                raise ValueError("missing separator")
        if position >= len(text):
            raise ValueError("unclosed container")
        position += 1
        return (opening, tuple(sorted(entries) if opening == "{" else entries))

    try:
        result = value(0)
        whitespace()
        return result if position == len(text) else opaque
    except (ValueError, RecursionError):
        return opaque


def context_equal(left, right, field=None):
    """Keep raw evidence intact; only NBT compound member order is immaterial."""
    if type(left) is not type(right):
        return False
    if isinstance(left, dict):
        return left.keys() == right.keys() and all(context_equal(left[k], right[k], k) for k in left)
    if isinstance(left, list):
        return len(left) == len(right) and all(context_equal(a, b) for a, b in zip(left, right))
    if isinstance(left, str) and field == "nbtIdentity" and left != right:
        return compound_order_key(left) == compound_order_key(right)
    return left == right
