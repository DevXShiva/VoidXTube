import unittest

import discord_release as dr

BODY = """Unofficial YouTube client.

### What's new

- **Light theme** ([#8](https://example.com/8)): Settings.
- **Comments in a panel** under the video.

### Download

| Phone | File |

<!-- virustotal-report -->
### VirusTotal scan
"""

RELEASE = {"tagName": "v1.12.0", "name": "NewTube 1.12.0", "url": "https://example.com/r",
           "body": BODY, "publishedAt": "2026-09-30T14:34:16Z"}


class DiscordReleaseTest(unittest.TestCase):
    def test_takes_only_whats_new(self):
        news = dr.whats_new(BODY)
        self.assertTrue(news.startswith("- **Light theme**"))
        self.assertTrue(news.endswith("under the video."))
        self.assertNotIn("Download", news)

    def test_stops_at_html_comment(self):
        news = dr.whats_new("### What's new\n- a\n<!-- virustotal-report -->\n- b\n")
        self.assertEqual(news, "- a")

    def test_missing_section_still_links_the_release(self):
        text = dr.description(dict(RELEASE, body="no sections"))
        self.assertIn("https://example.com/r", text)
        self.assertIn("NewTube_1.12.0_arm64-v8a.apk", text)

    def test_long_notes_are_cut_at_a_bullet(self):
        body = "### What's new\n" + "\n".join(f"- **Item {i}** " + "x" * 300 for i in range(40))
        text = dr.description(dict(RELEASE, body=body))
        self.assertLessEqual(len(text), dr.MAX_DESCRIPTION)
        self.assertIn("\n- …\n", text)
        self.assertEqual(text.count("**Item"), text.count("x" * 300))

    def test_payload_never_pings(self):
        data = dr.payload(RELEASE)
        self.assertEqual(data["allowed_mentions"], {"parse": []})
        self.assertEqual(data["embeds"][0]["title"], "NewTube 1.12.0")


if __name__ == "__main__":
    unittest.main()
