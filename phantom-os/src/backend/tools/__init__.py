"""
Tools-as-skills package — Phase-5 R2.

Each module under this package implements one tool family
(files / audit / backup). The corresponding wrappers in
``ai/tool_executor.py`` adapt the raw service result into a typed
``ToolResult`` (``ai/scenes.py``) that the chat loop forwards to FE.
"""
