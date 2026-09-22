from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from maas_usage_sync.writer import write_output_tables


class WriterTests(unittest.TestCase):
    def test_write_output_tables_uses_new_suffix_filenames(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            output_dir = Path(temp_dir)

            write_output_tables(
                output_dir,
                fact_rows=[],
                overview_rows=[],
                project_fact_rows=[],
            )

            self.assertTrue((output_dir / "daily_token_fact_new.csv").exists())
            self.assertTrue((output_dir / "daily_token_overview_new.csv").exists())
            self.assertTrue((output_dir / "daily_project_token_fact_new.csv").exists())
            self.assertFalse((output_dir / "daily_token_fact.csv").exists())
            self.assertFalse((output_dir / "daily_token_overview.csv").exists())
            self.assertFalse((output_dir / "daily_project_token_fact.csv").exists())


if __name__ == "__main__":
    unittest.main()
