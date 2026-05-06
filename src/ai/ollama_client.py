"""Ollama local inference client — drop-in replacement for claude_client.py.

Talks to the Ollama REST API (default: http://localhost:11434).
Used for summarization, mind maps, translation, and the Functionality
analyses. Transcription still uses openai-whisper (no LLM needed for that).
"""
import json
import os
import urllib.error
import urllib.request

MODEL = os.environ.get("OLLAMA_MODEL", "gemma4:e4b")
BASE_URL = os.environ.get("OLLAMA_BASE_URL", "http://localhost:11434")


def _post(endpoint: str, payload: dict) -> dict:
    url = f"{BASE_URL}{endpoint}"
    data = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            return json.loads(resp.read())
    except urllib.error.URLError as exc:
        raise ConnectionError(
            f"Cannot reach Ollama at {BASE_URL}. "
            "Make sure Ollama is running: `ollama serve`"
        ) from exc


def complete(
    system: str,
    user_content: list | str,
    *,
    cache_system: bool = True,  # no-op for Ollama; kept for API compat
    json_mode: bool = False,
) -> str:
    """Send a chat completion request to the local Ollama model."""
    if isinstance(user_content, list):
        # Extract text from content blocks (same shape as Anthropic SDK)
        text_parts = [
            block["text"] for block in user_content if block.get("type") == "text"
        ]
        user_text = "\n".join(text_parts)
    else:
        user_text = user_content

    payload: dict = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user_text},
        ],
        "stream": False,
    }
    if json_mode:
        payload["format"] = "json"

    result = _post("/api/chat", payload)
    return result["message"]["content"]
