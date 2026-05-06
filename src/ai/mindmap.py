"""Generates mind map JSON structure and Mermaid/Markdown from transcript."""
import json

from src.ai.claude_client import complete

_MINDMAP_SYSTEM = (
    "You are a mind map generator. Analyze this transcript and create a hierarchical mind map. "
    'Return valid JSON with this exact structure: {"title": str, "nodes": [{"id": str, '
    '"label": str, "parent": str | null, "children": [str]}]}. '
    "Use short labels (max 6 words). The root node has parent=null."
)


def generate_mindmap(transcript: str) -> dict:
    response_text = complete(
        system=_MINDMAP_SYSTEM,
        user_content=f"Create a mind map for:\n\n{transcript}",
        cache_system=True,
    )
    try:
        return json.loads(response_text)
    except json.JSONDecodeError:
        # Return a minimal valid mindmap structure so callers don't crash.
        return {"title": "Mind Map", "nodes": [], "error": "JSON parse failed", "raw": response_text}


def mindmap_to_mermaid(mindmap: dict) -> str:
    title = mindmap.get("title", "Root")
    nodes: list[dict] = mindmap.get("nodes", [])

    node_map = {n["id"]: n for n in nodes}
    root = next((n for n in nodes if n.get("parent") is None), None)

    lines = ["mindmap"]

    def _render(node_id: str, depth: int) -> None:
        node = node_map.get(node_id)
        if node is None:
            return
        indent = "  " * depth
        label = node["label"]
        if depth == 1:
            lines.append(f"{indent}root(({label}))")
        else:
            lines.append(f"{indent}{label}")
        for child_id in node.get("children", []):
            _render(child_id, depth + 1)

    if root is not None:
        _render(root["id"], 1)
    else:
        lines.append(f"  root(({title}))")

    return "\n".join(lines)


def mindmap_to_markdown(mindmap: dict) -> str:
    nodes: list[dict] = mindmap.get("nodes", [])
    node_map = {n["id"]: n for n in nodes}
    root = next((n for n in nodes if n.get("parent") is None), None)

    lines: list[str] = []

    def _render(node_id: str, depth: int) -> None:
        node = node_map.get(node_id)
        if node is None:
            return
        indent = "  " * (depth - 1)
        lines.append(f"{indent}- {node['label']}")
        for child_id in node.get("children", []):
            _render(child_id, depth + 1)

    if root is not None:
        _render(root["id"], 1)

    return "\n".join(lines)
