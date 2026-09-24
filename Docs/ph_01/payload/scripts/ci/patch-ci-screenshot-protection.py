#!/usr/bin/env python3
"""Patch screenshot protection only inside the disposable CI build workspace."""

from pathlib import Path
import re


SOURCE = Path("app/src/main/java/com/example/privatevault/ScreenSecurityManager.java")


def main() -> None:
    text = SOURCE.read_text(encoding="utf-8")

    activity_pattern = re.compile(
        r"(public static void protect\(Activity activity\) \{\s*"
        r"if \(activity == null\) return;\s*)"
        r"(protectWindow\(activity\.getWindow\(\)\);)",
        re.MULTILINE,
    )
    dialog_pattern = re.compile(
        r"(public static void protect\(Dialog dialog\) \{\s*"
        r"if \(dialog == null\) return;\s*)"
        r"(protectWindow\(dialog\.getWindow\(\)\);)",
        re.MULTILINE,
    )

    text, activity_count = activity_pattern.subn(
        r"\1"
        "// CI visual verification only. This edit exists only in the disposable CI workspace.\n"
        "        if (BuildConfig.DEBUG) return;\n"
        r"        \2",
        text,
        count=1,
    )
    text, dialog_count = dialog_pattern.subn(
        r"\1"
        "// CI visual verification only.\n"
        "        if (BuildConfig.DEBUG) return;\n"
        r"        \2",
        text,
        count=1,
    )

    if activity_count != 1 or dialog_count != 1:
        raise SystemExit(
            "Expected exactly one Activity and one Dialog screenshot-protection patch; "
            f"patched Activity={activity_count}, Dialog={dialog_count}."
        )

    SOURCE.write_text(text, encoding="utf-8")
    print("Temporary CI-only screenshot patch applied successfully.")


if __name__ == "__main__":
    main()
