import re
import unittest
from pathlib import Path


class AndroidButtonStyleTest(unittest.TestCase):
    def test_only_two_compact_session_controls_can_use_text_buttons(self):
        source_root = Path(__file__).resolve().parents[2] / "app/src/main"
        ui_root = source_root / "java/io/github/ts3mobile/app/ui"
        compact_actions = {ui_root / "ChannelPanel.kt", ui_root / "SessionAudioActions.kt"}
        bare_control = re.compile(r"\b(?:TextButton|ClickableText)\b")
        violations = []
        for source in sorted(source_root.rglob("*.kt")):
            path = source.relative_to(source_root).as_posix()
            content = source.read_text(encoding="utf-8")
            if source in compact_actions:
                content = content.replace("import androidx.compose.material3.TextButton\n", "", 1).replace("TextButton(", "", 1)
            if bare_control.search(content):
                violations.append(path)
        self.assertEqual([], violations, "Use bordered, filled, icon, or full-row controls instead.")
